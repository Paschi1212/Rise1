package de.myhornets.rise1.session

import java.security.MessageDigest

/**
 * T-105 — der Wiedereinstiegs-Handshake, TDD 9.3.
 *
 * ## Der Satz, aus dem die ganze Prüfung folgt
 *
 * TDD 9.1: *„**Wiedereinstieg ist Authentifizierung, nicht Wiedererkennung.**
 * Eine Spieler-ID reicht nicht. UUIDs stehen in Logs, gehen übers Netz und
 * tauchen in Fehlermeldungen auf; wer sich mit einer fremden `participant_uid`
 * anmelden könnte, bekäme das Schlüsselpaket dieses Spielers. Es braucht ein
 * echtes Geheimnis von mindestens 128 Bit, das nur das Gerät des Spielers
 * kennt."*
 *
 * Deshalb ist die `participant_uid` hier **kein Nachweis**, sondern nur die
 * Frage. Der Nachweis ist der `rejoin_token`.
 *
 * ## Was diese Klasse nicht tut
 *
 * **Sie stellt keine Verbindung her und beendet keine.** Der Transport ist
 * fertig, wenn sie anfängt — [Sitzungsverbindung] meldet `bereitFuerHandshake`,
 * und erst dann läuft hier etwas. Eine stehende Leitung ist keine Anmeldung.
 *
 * **Sie verändert keinen Spielzustand.** Sie liefert eine Antwort; was daraus
 * an Ereignissen entsteht, entscheidet der Aufrufer über [Verbindungsautomat].
 *
 * ## Reihenfolge der Prüfungen
 *
 * TDD 9.3 gibt sie vor, und sie wird eingehalten: Partie da und `active` oder
 * `paused` · Sitzplatz da · **Hash passt, Vergleich in konstanter Zeit** ·
 * Ratenbegrenzung.
 *
 * Dass eine beendete Partie einen **eigenen** Ablehnungsgrund bekommt, steht
 * ausdrücklich im TDD: *„damit die App ‚Partie ist vorbei' statt ‚Zugriff
 * verweigert' anzeigen kann"*.
 */

/** Was das zurückkehrende Gerät schickt (TDD 9.3). */
data class Wiedereinstiegsgesuch(
    val matchUid: String,
    val participantUid: String,
    /**
     * Das Geheimnis. Mindestens 128 Bit (TDD 9.1).
     *
     * Es steht **nur** hier und im Speicher des Spielergeräts. Der Host kennt
     * ausschließlich einen gesalzenen Hash davon.
     */
    val rejoinToken: String,
    val deviceUid: String,
    /** Der Stand des Clients — Grundlage für das Aufholen (TDD 9.5, siehe [Deltaauswahl]). */
    val lastSeqSeen: Long,
) {
    init {
        require(matchUid.isNotBlank()) { "Ein Gesuch ohne Partie ist keines." }
        require(participantUid.isNotBlank()) { "Ein Gesuch ohne Sitzplatz ist keines." }
        require(deviceUid.isNotBlank()) { "Ein Gesuch ohne Gerät ist keines." }
        require(lastSeqSeen >= -1) { "last_seq_seen ist -1 (nichts gesehen) oder größer." }
    }

    /** Bewusst ohne den Token: Ein Geheimnis gehört in kein Protokoll. */
    override fun toString(): String =
        "Wiedereinstiegsgesuch(match=$matchUid, sitzplatz=$participantUid, " +
            "geraet=$deviceUid, lastSeqSeen=$lastSeqSeen)"
}

/** Warum ein Wiedereinstieg abgelehnt wurde. */
enum class Ablehnungsgrund {
    /** Die Partie kennt dieses Gerät nicht — oder es gibt sie nicht. */
    PARTIE_UNBEKANNT,

    /**
     * Die Partie ist vorbei.
     *
     * Eigener Grund nach TDD 9.3, damit die App das auch so sagen kann. „Zugriff
     * verweigert" wäre hier schlicht falsch und würde nach einem Fehler des
     * Spielers aussehen.
     */
    PARTIE_BEENDET,

    /** Diesen Sitzplatz gibt es in dieser Partie nicht. */
    SITZPLATZ_UNBEKANNT,

    /** Der Nachweis passt nicht. Deckt falschen Token, fremden Sitzplatz und fremde Partie ab. */
    NACHWEIS_FALSCH,

    /** Ratenbegrenzung — zu viele Fehlversuche in kurzer Zeit. */
    ZU_VIELE_VERSUCHE,
}

sealed interface Wiedereinstiegsantwort {

    /**
     * Angenommen.
     *
     * @param sitzungsUid die Sitzung, die den Handshake bestanden hat. **An sie**
     *   geht das Aufholen, nicht an das Gerät und nicht an die Person (TDD 9.5).
     * @param abgeloesteSitzung eine noch offene frühere Sitzung, die dadurch mit
     *   `end_reason = superseded` endet (TDD 9.3). `null`, wenn es keine gab.
     * @param bisSeq der Stand, bis zu dem der Host bestätigt hat.
     */
    data class Angenommen(
        val sitzungsUid: String,
        val abgeloesteSitzung: String?,
        val bisSeq: Long,
    ) : Wiedereinstiegsantwort

    data class Abgelehnt(val grund: Ablehnungsgrund) : Wiedereinstiegsantwort
}

/** Was der Host über einen Sitzplatz weiß — ohne den Token selbst. */
data class Sitzplatznachweis(
    val participantUid: String,
    /** Gesalzener Hash. Der Klartext liegt nur auf dem Spielergerät (TDD 4.4). */
    val rejoinTokenHash: String,
    val salz: String,
    /** Die derzeit offene Sitzung dieses Sitzplatzes, oder `null`. */
    val offeneSitzung: String? = null,
    /** Das Gerät der offenen Sitzung. */
    val offenesGeraet: String? = null,
)

/** Partiestatus nach TDD 4.4. */
object Partiestatus {
    const val SETUP = "setup"
    const val DEALING = "dealing"
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val FINISHED = "finished"
    const val ABANDONED = "abandoned"

    /** TDD 9.3: Wiedereinstieg nur bei `active` oder `paused`. */
    val WIEDEREINSTIEG_MOEGLICH = setOf(ACTIVE, PAUSED)
}

/**
 * Woher der Prüfer sein Wissen nimmt.
 *
 * Eine Schnittstelle, damit `:session` **keine** Kante auf `:store` braucht —
 * die gibt es nicht und soll es nicht geben. `:ui` verdrahtet sie mit den DAOs.
 * Nebenbei ist der Prüfer damit ohne Android und ohne Datenbank testbar.
 */
interface Partienachschlag {
    /** Der Status der Partie, oder `null`, wenn dieses Gerät sie nicht kennt. */
    fun status(matchUid: String): String?

    /** Der Sitzplatz, oder `null`. */
    fun sitzplatz(matchUid: String, participantUid: String): Sitzplatznachweis?

    /** Legt eine neue Sitzung an und gibt ihre Kennung zurück. */
    fun eroeffneSitzung(matchUid: String, participantUid: String, deviceUid: String): String

    /** Die höchste bestätigte `seq` dieser Partie. */
    fun hoechsteSeq(matchUid: String): Long
}

/** Ratenbegrenzung nach TDD 9.3. */
data class Versuchsgrenze(
    val hoechstversucheJeFenster: Int = 5,
    val fensterMillis: Long = 60_000,
) {
    init {
        require(hoechstversucheJeFenster >= 1) { "Ohne einen erlaubten Versuch käme niemand zurück." }
        require(fensterMillis > 0) { "Ein Fenster von 0 wäre keines." }
    }
}

/**
 * Der Host-seitige Prüfer (TDD 9.3).
 *
 * Zeit kommt von außen — dieselbe Regel wie beim [Verbindungsautomat]: Ein Test
 * über die Ratenbegrenzung soll nicht eine Minute dauern.
 */
class RejoinPruefer(
    private val nachschlag: Partienachschlag,
    private val uhr: () -> Long,
    private val grenze: Versuchsgrenze = Versuchsgrenze(),
) {

    /** Fehlversuche je Sitzplatz, mit Zeitstempel. Nur Fehlversuche zählen. */
    private val fehlversuche = mutableMapOf<String, MutableList<Long>>()

    fun pruefe(gesuch: Wiedereinstiegsgesuch): Wiedereinstiegsantwort {
        val jetzt = uhr()

        // Die Ratenbegrenzung steht **vor** allen inhaltlichen Prüfungen. TDD 9.3
        // nennt sie zuletzt; das ist die Reihenfolge der Bedingungen, nicht die
        // der Ausführung. Wer erst prüft und dann begrenzt, hat schon geantwortet.
        if (zuVieleVersuche(gesuch.participantUid, jetzt)) {
            return Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.ZU_VIELE_VERSUCHE)
        }

        val status = nachschlag.status(gesuch.matchUid)
            ?: return abgelehnt(gesuch, jetzt, Ablehnungsgrund.PARTIE_UNBEKANNT)

        if (status == Partiestatus.FINISHED || status == Partiestatus.ABANDONED) {
            // Kein Fehlversuch: Hier hat niemand etwas falsch gemacht. Diesen
            // Fall mitzuzählen würde einen Spieler aussperren, dessen Partie
            // schlicht vorbei ist.
            return Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.PARTIE_BEENDET)
        }
        if (status !in Partiestatus.WIEDEREINSTIEG_MOEGLICH) {
            return abgelehnt(gesuch, jetzt, Ablehnungsgrund.PARTIE_UNBEKANNT)
        }

        val sitz = nachschlag.sitzplatz(gesuch.matchUid, gesuch.participantUid)
            ?: return abgelehnt(gesuch, jetzt, Ablehnungsgrund.SITZPLATZ_UNBEKANNT)

        if (!nachweisPasst(gesuch, sitz)) {
            return abgelehnt(gesuch, jetzt, Ablehnungsgrund.NACHWEIS_FALSCH)
        }

        // ── Erfolg ──────────────────────────────────────────────────────────
        fehlversuche.remove(gesuch.participantUid)

        // Idempotenz (TDD 9.3): „Ein zweiter identischer Versuch führt zum
        // selben Ergebnis." Dasselbe Gerät bekommt dieselbe Sitzung — es löst
        // sich nicht selbst ab. Nur ein **anderes** Gerät verdrängt.
        if (sitz.offeneSitzung != null && sitz.offenesGeraet == gesuch.deviceUid) {
            return Wiedereinstiegsantwort.Angenommen(
                sitzungsUid = sitz.offeneSitzung,
                abgeloesteSitzung = null,
                bisSeq = nachschlag.hoechsteSeq(gesuch.matchUid),
            )
        }

        val neue = nachschlag.eroeffneSitzung(gesuch.matchUid, gesuch.participantUid, gesuch.deviceUid)
        return Wiedereinstiegsantwort.Angenommen(
            sitzungsUid = neue,
            abgeloesteSitzung = sitz.offeneSitzung,
            bisSeq = nachschlag.hoechsteSeq(gesuch.matchUid),
        )
    }

    /** Wie viele Fehlversuche im laufenden Fenster stehen. Für Anzeige und Tests. */
    fun fehlversucheFuer(participantUid: String): Int =
        fehlversuche[participantUid].orEmpty().count { uhr() - it < grenze.fensterMillis }

    private fun abgelehnt(
        gesuch: Wiedereinstiegsgesuch,
        jetzt: Long,
        grund: Ablehnungsgrund,
    ): Wiedereinstiegsantwort {
        fehlversuche.getOrPut(gesuch.participantUid) { mutableListOf() } += jetzt
        return Wiedereinstiegsantwort.Abgelehnt(grund)
    }

    private fun zuVieleVersuche(participantUid: String, jetzt: Long): Boolean {
        val liste = fehlversuche[participantUid] ?: return false
        liste.removeAll { jetzt - it >= grenze.fensterMillis }
        return liste.size >= grenze.hoechstversucheJeFenster
    }

    /**
     * Vergleich **in konstanter Zeit** (TDD 9.3).
     *
     * Über [MessageDigest.isEqual]. Ein Vergleich, der beim ersten abweichenden
     * Zeichen abbricht, verrät bei genügend Versuchen, wie weit der Angreifer
     * gekommen ist — und Versuche kann er beliebig viele machen.
     *
     * Der Token wird **gesalzen** gehasht. Ohne Salz wäre derselbe Token in zwei
     * Partien derselbe Hash, und ein Hash aus einer alten Partie wäre ein
     * Nachschlagewerk für die nächste.
     */
    private fun nachweisPasst(gesuch: Wiedereinstiegsgesuch, sitz: Sitzplatznachweis): Boolean {
        // Ein zu kurzer Token ist eine **Ablehnung**, kein Absturz. [tokenHash]
        // wirft, weil es beim Erzeugen eines Tokens ein Programmierfehler wäre —
        // hier kommt der Wert aber von außen, und was von außen kommt, darf den
        // Host nicht zum Anhalten bringen.
        if (gesuch.rejoinToken.toByteArray(Charsets.UTF_8).size < MINDESTLAENGE_BYTES) return false

        val gerechnet = tokenHash(gesuch.rejoinToken, sitz.salz)
        return MessageDigest.isEqual(
            gerechnet.toByteArray(Charsets.US_ASCII),
            sitz.rejoinTokenHash.toByteArray(Charsets.US_ASCII),
        )
    }

    companion object {

        /** Mindestlänge eines Tokens in Bytes — TDD 9.1 verlangt 128 Bit. */
        const val MINDESTLAENGE_BYTES = 16

        /**
         * Der gesalzene Hash, den der Host speichert.
         *
         * Auch der Erzeuger des Tokens benutzt diese Funktion — es gibt genau
         * eine Ableitung, sonst passte nichts zusammen.
         */
        fun tokenHash(token: String, salz: String): String {
            require(token.toByteArray(Charsets.UTF_8).size >= MINDESTLAENGE_BYTES) {
                "Ein Wiedereinstiegs-Token braucht mindestens $MINDESTLAENGE_BYTES Bytes " +
                    "(TDD 9.1: 128 Bit). Eine UUID als Text erfüllt das; eine kurze PIN nicht."
            }
            require(salz.isNotBlank()) { "Ohne Salz wäre derselbe Token in zwei Partien derselbe Hash." }
            val verdauung = MessageDigest.getInstance("SHA-256")
                .digest(("rise1-rejoin-v1" + salz + token).toByteArray(Charsets.UTF_8))
            return verdauung.joinToString("") { "%02x".format(it) }
        }
    }
}
