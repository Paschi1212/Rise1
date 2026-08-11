package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.MatchEvent
import de.myhornets.rise1.core.event.Visibility

/**
 * T-108 — das Aufholen nach dem Wiedereinstieg, TDD 9.5.
 *
 * ## Die zwei Sätze, aus denen die ganze Datei folgt
 *
 * TDD 9.5: *„Delta, wenn die Lücke unter `resume_delta_threshold` liegt: alle
 * für diesen Empfänger sichtbaren Events mit `seq > last_seq_seen`."* — und:
 * *„Der Schnappschuss geht an die Sitzung, die den Handshake bestanden hat,
 * nicht an das Gerät und nicht an die Person."*
 *
 * Beides steht hier als Code: Die Auswahl kennt **einen Empfänger**, und sie
 * liest **nur** aus dem Event-Log. Es gibt in dieser Datei keinen Weg, aus einem
 * Anzeigezustand ein Delta zu bauen — [Ereignisquelle] ist die einzige Quelle,
 * und sie liefert `MatchEvent`.
 *
 * ## Warum die `seq` im Delta Lücken hat und das richtig ist
 *
 * Das Delta ist **gefiltert**. Ein Spieler bekommt die `deal_key_packet` der
 * anderen nicht, also fehlen deren `seq` in seinem Delta. Ein Client, der
 * Lückenlosigkeit der `seq` verlangte, würde jedes korrekte Delta ablehnen.
 *
 * Deshalb trägt [Aufholung.Delta] den **abgedeckten Bereich** mit sich
 * (`abSeqExklusiv`, `bisSeq`) und nicht nur die Events. Die Lückenprüfung des
 * Clients ist eine Prüfung dieses Bereichs — passt er an den eigenen Stand an? —
 * und **nicht** eine Prüfung der Abstände zwischen den Events. Das ist der
 * einzige Weg, beides zu haben: kein Loch im Verlauf und keine Kenntnis darüber,
 * was andere bekommen haben.
 *
 * ## Was hier nicht passiert
 *
 * **Kein Entschlüsseln.** Ein Chiffrat wird befördert, nicht gelesen.
 *
 * **Kein Falten.** Was der Client mit den Events macht, ist `:projection`.
 * Diese Datei entscheidet, *welche* Events er bekommt und ob das, was ankam,
 * annehmbar ist.
 */

/**
 * Woher die Auswahl liest.
 *
 * Eine Schnittstelle aus demselben Grund wie [Partienachschlag]: `:session` hat
 * keine Kante auf `:store` und soll keine bekommen. `:ui` verdrahtet sie mit dem
 * DAO; im Test steht eine Liste dahinter.
 */
interface Ereignisquelle {

    /**
     * Alle **bestätigten** Events der Partie mit `nachSeq < seq <= bisSeq`,
     * aufsteigend nach `seq`.
     *
     * Unbestätigte Events (`seq == null`) gehören nicht dazu: Sie haben noch
     * keine Position in der Reihenfolge (TDD 6.2), und ein Aufholen ist genau
     * eine Aussage über eine Reihenfolge.
     */
    fun ab(matchUid: String, nachSeq: Long, bisSeq: Long): List<MatchEvent>
}

/** `resume_delta_threshold` aus TDD 9.5. */
data class Aufholschwelle(
    /**
     * Ab wie vielen fehlenden `seq` ein Schnappschuss statt eines Deltas fällig
     * ist.
     *
     * Der Wert ist eine Einstellung, keine Naturkonstante — TDD 9.5 nennt keinen.
     * 200 ist die Größenordnung einer langen Partie: Wer eine Minute weg war,
     * bekommt ein Delta; wer die halbe Partie verpasst hat, einen Schnappschuss.
     */
    val resumeDeltaSchwelle: Long = 200,
) {
    init {
        require(resumeDeltaSchwelle > 0) { "Eine Schwelle von 0 hieße: immer Schnappschuss." }
    }
}

/** Das Ergebnis der Auswahl auf der Host-Seite. */
sealed interface Aufholung {

    /** Der Stand, bis zu dem aufgeholt wird. Fest, bevor irgendetwas gelesen wird (TDD 9.5). */
    val bisSeq: Long

    /**
     * Das Delta.
     *
     * @param abSeqExklusiv der Stand des Clients. Die Events beginnen **hinter**
     *   dieser `seq`.
     * @param events die für [empfaenger] sichtbaren Events des Bereichs,
     *   aufsteigend. Kann leer sein — auch das ist eine gültige Antwort und
     *   bedeutet: In diesem Bereich war nichts für dich dabei.
     */
    data class Delta(
        val matchUid: String,
        val empfaenger: String,
        val abSeqExklusiv: Long,
        override val bisSeq: Long,
        val events: List<MatchEvent>,
    ) : Aufholung {
        init {
            require(bisSeq >= abSeqExklusiv) {
                "Ein Delta läuft vorwärts: abSeqExklusiv=$abSeqExklusiv, bisSeq=$bisSeq."
            }
        }
    }

    /**
     * Die Lücke ist zu groß — es ist ein personalisierter Schnappschuss fällig
     * (TDD 9.5).
     *
     * **Hier steht die Entscheidung, nicht der Schnappschuss.** Das Bauen des
     * Schnappschusses gehört in die Projektionsschicht und ist ein eigener,
     * noch offener Punkt: TDD 9.5 verlangt *„für alle Empfänger dieselbe
     * Feldstruktur mit Nullwerten"*, und das ist eine Aussage über einen
     * Zustandsaufbau, nicht über eine Ereignisauswahl. Diese Datei erfindet ihn
     * nicht — sie sagt, dass er nötig ist, und nennt den festen [bisSeq], zu dem
     * er zu bauen ist.
     */
    data class SchnappschussNoetig(
        val matchUid: String,
        val empfaenger: String,
        override val bisSeq: Long,
        /** Wie viele `seq` fehlen. Für Anzeige und Protokoll. */
        val luecke: Long,
    ) : Aufholung
}

/**
 * Die Auswahl auf der Host-Seite (TDD 9.5).
 *
 * Sie ist der Ort, an dem entschieden wird, was ein Gerät zu sehen bekommt —
 * und damit der Ort, an dem ein Fehler ein Geheimnis kostet. Deshalb ist die
 * Sichtbarkeitsregel eine einzige, benannte Funktion ([sichtbarFuer]) und keine
 * Bedingung in einem Filterausdruck.
 */
class Deltaauswahl(
    private val quelle: Ereignisquelle,
    private val schwelle: Aufholschwelle = Aufholschwelle(),
) {

    /**
     * Was diese Sitzung zum Aufholen bekommt.
     *
     * @param empfaenger die `participant_uid` der Sitzung, die den Handshake
     *   bestanden hat. **Nicht** das Gerät: Ein Sitzplatz gehört einem Spieler,
     *   nicht einem Gerät (TDD 9.1).
     * @param lastSeqSeen der gemeldete Stand des Clients. `-1` heißt: nichts.
     * @param bisSeq der feste Endpunkt, üblicherweise
     *   [Wiedereinstiegsantwort.Angenommen.bisSeq].
     */
    fun fuer(
        matchUid: String,
        empfaenger: String,
        lastSeqSeen: Long,
        bisSeq: Long,
    ): Aufholung {
        require(matchUid.isNotBlank()) { "Ohne Partie gibt es nichts aufzuholen." }
        require(empfaenger.isNotBlank()) { "Ein Aufholen ohne Empfänger hätte keinen Adressaten." }
        require(lastSeqSeen >= -1) { "last_seq_seen ist -1 (nichts gesehen) oder größer." }

        // Ein Client, der weiter ist als der Host, hat nichts aufzuholen. Das
        // ist kein Fehler: Der Host kann seine Bestätigung verloren haben, oder
        // der Client hat sich vertan. Ein leeres Delta sagt beides richtig.
        val stand = minOf(lastSeqSeen, bisSeq)
        val luecke = bisSeq - stand

        if (luecke >= schwelle.resumeDeltaSchwelle) {
            return Aufholung.SchnappschussNoetig(matchUid, empfaenger, bisSeq, luecke)
        }

        val sichtbare = quelle.ab(matchUid, stand, bisSeq)
            .filter { it.matchUid == matchUid }
            .filter { sichtbarFuer(it, empfaenger) }
            .sortedBy { it.seq }

        return Aufholung.Delta(matchUid, empfaenger, stand, bisSeq, sichtbare)
    }

    companion object {

        /**
         * Die Sichtbarkeitsregel (TDD 5.2 / 7.3).
         *
         * - `PUBLIC` — für alle. Klartext, für den Host ohnehin lesbar.
         * - `PLAYER_ONLY` — für die Spieler der Partie. Der Empfänger ist einer,
         *   sonst hätte er den Handshake nicht bestanden.
         * - `PRIVATE` — **nur** für den eingetragenen Empfänger. Das ist die
         *   Zeile, die verhindert, dass ein Schlüsselpaket beim Falschen landet.
         *
         * Unbestätigte Events sind für niemanden sichtbar: ohne `seq` keine
         * Position, ohne Position kein Aufholen.
         */
        fun sichtbarFuer(event: MatchEvent, empfaenger: String): Boolean {
            if (event.seq == null) return false
            return when (event.visibility) {
                Visibility.PUBLIC -> true
                Visibility.PLAYER_ONLY -> true
                Visibility.PRIVATE -> event.recipientParticipantUid == empfaenger
            }
        }
    }
}

/** Warum ein Delta nicht angenommen wurde. */
enum class Deltafehler {

    /** Der Bereich schließt nicht an den eigenen Stand an — eine echte Lücke. */
    FALSCHER_ANSCHLUSS,

    /** Der Bereich passt nicht zu dem, was der Handshake zugesagt hat. */
    PASST_NICHT_ZUR_ANTWORT,

    /** Ein Event einer anderen Partie. */
    FREMDE_PARTIE,

    /** Ein Event ohne `seq`. Aufholen geht nur über Bestätigtes. */
    UNBESTAETIGT,

    /** Dieselbe `seq` zweimal. */
    DOPPELTE_SEQ,

    /** Absteigende `seq`. */
    FALSCHE_REIHENFOLGE,

    /** Eine `seq`, die der Client schon hatte. */
    BEREITS_BEKANNT,

    /** Eine `seq` jenseits des zugesagten Endes. */
    UEBER_DEM_ENDE,

    /**
     * Der Schnappschuss ist für einen anderen Sitzplatz gebaut.
     *
     * TDD 9.5: Er geht an die Sitzung, die den Handshake bestanden hat — nicht
     * an das Gerät und nicht an die Person. Einer, der auf einen fremden
     * Sitzplatz lautet, ist entweder verwechselt oder untergeschoben; in beiden
     * Fällen wird er nicht übernommen.
     */
    FREMDER_EMPFAENGER,

    /**
     * Ein `PRIVATE`-Event für einen anderen Sitzplatz.
     *
     * Der Client kann es nicht entschlüsseln — aber darum geht es nicht. Es hätte
     * ihn nie erreichen dürfen, und das ist ein Befund über den Host, kein
     * Schönheitsfehler. Deshalb wird das ganze Delta verworfen und nicht nur
     * dieses eine Event weggelassen.
     */
    FREMDES_PRIVATES,
}

/** Das Ergebnis der Prüfung auf der Client-Seite. */
sealed interface Deltaergebnis {

    /** Annehmbar. Erst jetzt darf gefaltet und erst jetzt darf angemeldet werden. */
    data class Angenommen(val events: List<MatchEvent>, val neuerStand: Long) : Deltaergebnis

    /**
     * Nicht annehmbar. **Nichts** davon wird übernommen — auch nicht der Teil vor
     * dem Fehler. Ein halb übernommenes Delta hinterließe einen Stand, den
     * niemand mehr erklären kann.
     */
    data class Abgelehnt(val fehler: Deltafehler, val beiSeq: Long? = null) : Deltaergebnis
}

/**
 * Die Prüfung auf der Client-Seite (T-108).
 *
 * ## Warum der Client prüft, obwohl der Host ausgewählt hat
 *
 * Weil der Host das Gerät eines Mitspielers ist. ADR-001 sagt es deutlich:
 * *„TLS schützt gegen Mitlesen im Netz, nicht gegen den Host."* Ein Delta ist
 * eine Behauptung eines anderen Geräts über den Verlauf einer Partie — und die
 * wird geprüft, bevor sie zum eigenen Zustand wird.
 *
 * ## Reihenfolge und Zustand
 *
 * Die Prüfung ist **zustandslos**: derselbe Stand und dasselbe Delta ergeben
 * immer dasselbe Ergebnis. Der Stand wird übergeben, nicht gehalten. Wer ihn
 * fortschreibt, ist [Wiedereinstiegsablauf] — und der tut es nur bei Annahme.
 */
class Deltapruefung(
    val matchUid: String,
    /** Der eigene Sitzplatz. Maßstab für `PRIVATE`. */
    val eigenerParticipantUid: String,
) {

    init {
        require(matchUid.isNotBlank()) { "Eine Prüfung ohne Partie prüft nichts." }
        require(eigenerParticipantUid.isNotBlank()) { "Ohne eigenen Sitzplatz gibt es keinen Maßstab." }
    }

    /**
     * @param stand die höchste `seq`, die dieser Client bereits übernommen hat;
     *   `-1`, wenn noch keine.
     * @param zugesagtesEnde das `bisSeq` aus [Wiedereinstiegsantwort.Angenommen].
     *   `null`, wenn ohne Handshake geprüft wird (laufender Betrieb).
     */
    fun pruefe(stand: Long, delta: Aufholung.Delta, zugesagtesEnde: Long? = null): Deltaergebnis {
        if (delta.matchUid != matchUid) {
            return Deltaergebnis.Abgelehnt(Deltafehler.FREMDE_PARTIE)
        }
        if (zugesagtesEnde != null && delta.bisSeq != zugesagtesEnde) {
            // Der Host hat im Handshake einen Endpunkt genannt und liefert einen
            // anderen. Vielleicht ein Fehler, vielleicht zwei verschiedene
            // Antworten auf dieselbe Anfrage — in beiden Fällen ist der Stand
            // danach nicht mehr der zugesagte.
            return Deltaergebnis.Abgelehnt(Deltafehler.PASST_NICHT_ZUR_ANTWORT, delta.bisSeq)
        }
        if (delta.abSeqExklusiv != stand) {
            // Beginnt das Delta später, fehlt ein Stück Verlauf. Beginnt es
            // früher, hat der Host einen Stand angenommen, den dieser Client
            // nicht gemeldet hat. Beides ist ein falscher Anschluss.
            return Deltaergebnis.Abgelehnt(Deltafehler.FALSCHER_ANSCHLUSS, delta.abSeqExklusiv)
        }

        var vorher = stand
        for (event in delta.events) {
            val seq = event.seq
                ?: return Deltaergebnis.Abgelehnt(Deltafehler.UNBESTAETIGT)

            if (event.matchUid != matchUid) {
                return Deltaergebnis.Abgelehnt(Deltafehler.FREMDE_PARTIE, seq)
            }
            if (seq == vorher) {
                return Deltaergebnis.Abgelehnt(Deltafehler.DOPPELTE_SEQ, seq)
            }
            if (seq < vorher) {
                // Kleiner als der Vorgänger: entweder absteigend geliefert oder
                // schon bekannt. Der Unterschied ist für die Fehlersuche wichtig.
                val fehler =
                    if (seq <= stand) Deltafehler.BEREITS_BEKANNT else Deltafehler.FALSCHE_REIHENFOLGE
                return Deltaergebnis.Abgelehnt(fehler, seq)
            }
            if (seq > delta.bisSeq) {
                return Deltaergebnis.Abgelehnt(Deltafehler.UEBER_DEM_ENDE, seq)
            }
            if (event.visibility == Visibility.PRIVATE &&
                event.recipientParticipantUid != eigenerParticipantUid
            ) {
                return Deltaergebnis.Abgelehnt(Deltafehler.FREMDES_PRIVATES, seq)
            }
            vorher = seq
        }

        // Der neue Stand ist das **zugesagte Ende**, nicht die höchste gelieferte
        // `seq`. Sonst würde ein Client, dessen Delta nur gefilterte Events
        // enthielt, denselben Bereich beim nächsten Mal erneut anfordern — und
        // bekäme erneut nichts. Der Bereich ist abgearbeitet, auch wenn er leer war.
        return Deltaergebnis.Angenommen(delta.events, delta.bisSeq)
    }
}
