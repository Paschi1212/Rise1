package de.myhornets.rise1.session

/**
 * T-103 — der Zustandsautomat der Verbindung, TDD 9.2.
 *
 * ## Der Satz, aus dem alles folgt
 *
 * TDD 9.1: **„Verbindungszustand ist kein Spielzustand."** Und 9.2, noch
 * schärfer: *„**Kein Übergang in diesem Automaten verändert den Spielzustand**
 * — ein Spieler im Zustand `offline` behält Sitzplatz, Identität, Lebenspunkte
 * und Zugreihenfolge."*
 *
 * Deshalb kennt diese Klasse weder das Event-Log noch die Projektion noch
 * `match_participant`. Sie kann den Spielstand nicht verändern, weil sie ihn
 * nicht erreicht. Was sie liefert, sind Übergänge und **Vorschläge** — und der
 * Aufrufer entscheidet, was daraus ein Ereignis wird.
 *
 * ## Der zweite Satz, der leicht untergeht
 *
 * TDD 9.1: **„Nur explizite Ereignisse beenden eine Partie."** Kein Timeout,
 * kein ausbleibender Herzschlag, keine Karenzzeit. Deshalb gibt es hier keinen
 * Übergang, der eine Partie beendet, und [Vorschlag.SitzplatzFreigeben] ist
 * ausdrücklich ein *Vorschlag* an den Host — mehr darf daraus nicht werden.
 *
 * ## Warum ein Herzschlag nicht aus `offline` zurückführt
 *
 * Das ist die sicherheitsrelevante Feinheit des Automaten. TDD 9.1:
 * *„Wiedereinstieg ist Authentifizierung, nicht Wiedererkennung."* Wer offline
 * war, kommt **nur über einen bestandenen Handshake** zurück — ein
 * eintreffendes Lebenszeichen genügt nicht. Sonst reichte es, Pakete mit einer
 * fremden `participant_uid` zu schicken, um deren Sitzplatz zu übernehmen.
 *
 * Genau dafür gibt es den Zwischenzustand [Verbindungszustand.WIEDEREINSTIEG]:
 * Er ist das Wartezimmer vor der Prüfung, nicht die Prüfung selbst.
 */

/** Die fünf Zustände aus TDD 9.2. */
enum class Verbindungszustand {
    VERBUNDEN,

    /**
     * *„`suspect` existiert nur für die Anzeige"* (TDD 9.2).
     *
     * Ein kurzer Aussetzer erscheint als „wackelig", **ohne** dass ein Ereignis
     * in den Verlauf geschrieben wird. Deshalb meldet der Automat beim Eintritt
     * hierher auch keines.
     */
    WACKELIG,

    OFFLINE,

    /** Handshake läuft. Zwischenzustand, kein Ergebnis. */
    WIEDEREINSTIEG,

    /** Endzustand. Nur durch eine bewusste Handlung erreichbar. */
    GEGANGEN,
    ;

    val istEndzustand: Boolean get() = this == GEGANGEN
}

/**
 * Die Schwellen aus TDD 9.2. Konfigurierbar, mit den dort vorgeschlagenen Werten.
 */
data class Verbindungsschwellen(
    val herzschlagIntervallSekunden: Int = 5,
    val verpassteBisWackelig: Int = 2,
    val verpassteBisOffline: Int = 6,

    /**
     * `0` = **unbegrenzt**, und das ist der Standard.
     *
     * TDD 9.2: *„Dass die Karenzzeit standardmäßig unbegrenzt ist, ist Absicht:
     * Ein Tischspiel kann pausieren, weil jemand die Pizza holt."*
     */
    val karenzSekunden: Int = 0,

    /** TDD 9.2, Standard aus. Der Automat wertet es nicht aus, er reicht es durch. */
    val zugAutomatischUeberspringen: Boolean = false,
) {
    init {
        require(herzschlagIntervallSekunden > 0) { "Ein Herzschlagintervall von 0 wäre kein Intervall." }
        require(verpassteBisWackelig in 1..verpassteBisOffline) {
            "Die Schwelle für `wackelig` ($verpassteBisWackelig) muss zwischen 1 und der für " +
                "`offline` ($verpassteBisOffline) liegen — sonst gäbe es den Anzeigezustand nie."
        }
        require(karenzSekunden >= 0) { "Eine negative Karenzzeit gibt es nicht; 0 heißt unbegrenzt." }
    }

    val herzschlagIntervallMillis: Long get() = herzschlagIntervallSekunden * 1_000L
    val karenzUnbegrenzt: Boolean get() = karenzSekunden == 0
}

/** Was der Automat meldet. */
sealed interface Verbindungsmeldung {

    /** Ein Zustandswechsel. */
    data class Uebergang(
        val von: Verbindungszustand,
        val nach: Verbindungszustand,
        val bei: Long,
    ) : Verbindungsmeldung

    /**
     * Ein Ereignis, das in den Verlauf gehört — `event_class = session` (TDD 5.4).
     *
     * Drei Typen, und **keiner** verändert den Spielzustand:
     * `participant_disconnected`, `participant_reconnected` und
     * `rejoin_rejected`. Der Wechsel nach [Verbindungszustand.WACKELIG] erzeugt
     * bewusst gar keines — er existiert nur für die Anzeige.
     */
    data class Sitzungsereignis(val typ: String, val participantUid: String, val bei: Long) :
        Verbindungsmeldung

    /** Ein Vorschlag an den Host. Nie eine Handlung. */
    data class Vorschlag(val art: Art, val participantUid: String, val bei: Long) :
        Verbindungsmeldung {

        enum class Art {
            /**
             * Die Karenzzeit ist abgelaufen.
             *
             * TDD 9.2: *„Läuft sie doch ab, beendet das **niemals** die Partie,
             * sondern schlägt dem Host höchstens vor, den Platz freizugeben."*
             */
            SitzplatzFreigeben,
        }
    }
}

/** Die Ereignistypen, die dieser Automat vorschlägt (TDD 5.4). */
object Sitzungsereignistypen {
    const val VERBINDUNG_VERLOREN = "participant_disconnected"
    const val VERBINDUNG_ZURUECK = "participant_reconnected"

    /**
     * Ein abgelehnter Wiedereinstieg.
     *
     * TDD 5.4 führt ihn als `event_class = annotation` mit Sichtbarkeit
     * `PUBLIC` und begründet das so: *„Fehlversuch — sicherheitsrelevant,
     * deshalb für alle sichtbar."* Wer am Tisch sitzt, soll sehen, wenn jemand
     * vergeblich versucht, sich auf einen Sitzplatz zu setzen.
     */
    const val WIEDEREINSTIEG_ABGELEHNT = "rejoin_rejected"
}

/**
 * Der Automat für **einen** Sitzplatz.
 *
 * Zeit kommt von außen — der Automat fragt keine Uhr. Damit ist er gegen das
 * [[T-067 Host-Attrappe|Attrappennetz]] und seine Uhr prüfbar, und ein Test über
 * eine Stunde Spielverlauf dauert keine Stunde.
 */
class Verbindungsautomat(
    val participantUid: String,
    val schwellen: Verbindungsschwellen = Verbindungsschwellen(),
    /** Wann die Verbindung zustande kam. Ab hier zählt der erste Herzschlag. */
    startzeit: Long,
) {

    var zustand: Verbindungszustand = Verbindungszustand.VERBUNDEN
        private set

    /** Wann zuletzt ein Lebenszeichen kam. */
    var letzterHerzschlag: Long = startzeit
        private set

    /** Seit wann [Verbindungszustand.OFFLINE] gilt — für die Karenzzeit. */
    private var offlineSeit: Long? = null
    private var freigabeVorgeschlagen = false

    // ── Ereignisse von außen ────────────────────────────────────────────────

    /**
     * Ein Lebenszeichen ist eingetroffen.
     *
     * Führt aus [Verbindungszustand.WACKELIG] zurück nach
     * [Verbindungszustand.VERBUNDEN] — aber **nicht** aus
     * [Verbindungszustand.OFFLINE]. Siehe Klassenkommentar: Wiedereinstieg ist
     * Authentifizierung.
     */
    fun herzschlag(jetzt: Long): List<Verbindungsmeldung> {
        if (zustand.istEndzustand) return emptyList()
        letzterHerzschlag = maxOf(letzterHerzschlag, jetzt)
        return when (zustand) {
            Verbindungszustand.WACKELIG -> wechsleZu(Verbindungszustand.VERBUNDEN, jetzt)
            // OFFLINE und WIEDEREINSTIEG bleiben unberührt. Ein Lebenszeichen
            // ist kein Nachweis; es hebt keinen Verlust auf.
            else -> emptyList()
        }
    }

    /**
     * Die Zeit ist fortgeschritten — was folgt daraus?
     *
     * Der Aufrufer ruft das regelmäßig auf. Der Automat rechnet aus, wie viele
     * Herzschläge ausgeblieben sind, und wechselt entsprechend. Mehrere
     * Übergänge in einem Aufruf sind möglich: Wer eine Minute weg war, geht in
     * einem Schritt über `wackelig` nach `offline` — beide werden gemeldet,
     * damit ein Mitschnitt vollständig bleibt.
     */
    fun zeitLaeuft(jetzt: Long): List<Verbindungsmeldung> {
        if (zustand.istEndzustand) return emptyList()
        if (zustand == Verbindungszustand.WIEDEREINSTIEG) return emptyList()

        val meldungen = mutableListOf<Verbindungsmeldung>()
        val verpasst = verpassteHerzschlaege(jetzt)

        if (zustand == Verbindungszustand.VERBUNDEN && verpasst >= schwellen.verpassteBisWackelig) {
            meldungen += wechsleZu(Verbindungszustand.WACKELIG, jetzt)
        }
        if (zustand == Verbindungszustand.WACKELIG && verpasst >= schwellen.verpassteBisOffline) {
            meldungen += wechsleZu(Verbindungszustand.OFFLINE, jetzt)
        }

        meldungen += pruefeKarenzzeit(jetzt)
        return meldungen
    }

    /** Das Gerät meldet sich zurück und der Handshake beginnt (TDD 9.3). */
    fun handshakeBegonnen(jetzt: Long): List<Verbindungsmeldung> {
        if (zustand.istEndzustand) return emptyList()
        // Auch aus `wackelig` heraus zulässig: Ein Gerät, das gerade den
        // Netzwechsel bemerkt hat, muss nicht erst offline gehen dürfen.
        if (zustand == Verbindungszustand.WIEDEREINSTIEG) return emptyList()
        return wechsleZu(Verbindungszustand.WIEDEREINSTIEG, jetzt)
    }

    /**
     * Der Handshake ist bestanden (TDD 9.3).
     *
     * Erst hier — und nur hier — wird aus einem abgemeldeten Sitzplatz wieder
     * ein verbundener.
     */
    fun handshakeErfolgreich(jetzt: Long): List<Verbindungsmeldung> {
        if (zustand != Verbindungszustand.WIEDEREINSTIEG) return emptyList()
        letzterHerzschlag = jetzt
        offlineSeit = null
        freigabeVorgeschlagen = false
        return wechsleZu(Verbindungszustand.VERBUNDEN, jetzt) +
            Verbindungsmeldung.Sitzungsereignis(
                Sitzungsereignistypen.VERBINDUNG_ZURUECK,
                participantUid,
                jetzt,
            )
    }

    /**
     * Der Handshake war bestanden, aber das **Aufholen** ist gescheitert (T-108).
     *
     * Zurück nach `offline`, und zwar **ohne jedes Ereignis**:
     *
     * - Kein `rejoin_rejected` — der Host hat nichts abgelehnt. Diesen Fehlversuch
     *   in den Verlauf zu schreiben, würde einem Spieler einen Angriffsversuch
     *   unterstellen, den es nicht gab.
     * - Kein `participant_disconnected` — der Spieler war die ganze Zeit weg. Das
     *   verhindert schon der Übergang aus [Verbindungszustand.WIEDEREINSTIEG].
     *
     * Es bleibt also nur der Übergang selbst. Das ist Absicht: Ein Client, der
     * ein unstimmiges Delta bekommen hat, ist nicht angemeldet — mehr sagt dieser
     * Vorgang nicht, und mehr darf er nicht sagen.
     */
    fun aufholenGescheitert(jetzt: Long): List<Verbindungsmeldung> {
        if (zustand != Verbindungszustand.WIEDEREINSTIEG) return emptyList()
        return wechsleZu(Verbindungszustand.OFFLINE, jetzt)
    }

    /** Der Handshake wurde abgelehnt — zurück nach `offline` (TDD 9.2). */
    fun handshakeAbgelehnt(jetzt: Long): List<Verbindungsmeldung> {
        if (zustand != Verbindungszustand.WIEDEREINSTIEG) return emptyList()
        return wechsleZu(Verbindungszustand.OFFLINE, jetzt) +
            Verbindungsmeldung.Sitzungsereignis(
                Sitzungsereignistypen.WIEDEREINSTIEG_ABGELEHNT,
                participantUid,
                jetzt,
            )
    }

    /**
     * Der Spieler verlässt den Tisch — `participant_left`.
     *
     * TDD 5.4: *„Endgültiges Ausscheiden — eine Entscheidung, kein Timeout."*
     * Deshalb ist das der einzige Weg nach [Verbindungszustand.GEGANGEN], und
     * es gibt keinen Weg zurück.
     */
    fun verlassen(jetzt: Long): List<Verbindungsmeldung> {
        if (zustand.istEndzustand) return emptyList()
        return wechsleZu(Verbindungszustand.GEGANGEN, jetzt)
    }

    // ── Auskünfte ───────────────────────────────────────────────────────────

    /** Wie viele Herzschläge ausgeblieben sind. */
    fun verpassteHerzschlaege(jetzt: Long): Int {
        if (jetzt <= letzterHerzschlag) return 0
        return ((jetzt - letzterHerzschlag) / schwellen.herzschlagIntervallMillis).toInt()
    }

    // ── Innen ───────────────────────────────────────────────────────────────

    private fun pruefeKarenzzeit(jetzt: Long): List<Verbindungsmeldung> {
        if (schwellen.karenzUnbegrenzt) return emptyList()
        if (zustand != Verbindungszustand.OFFLINE) return emptyList()
        if (freigabeVorgeschlagen) return emptyList()
        val seit = offlineSeit ?: return emptyList()
        if (jetzt - seit < schwellen.karenzSekunden * 1_000L) return emptyList()

        freigabeVorgeschlagen = true
        // Ein Vorschlag, kein Übergang. Der Sitzplatz bleibt besetzt, die
        // Partie läuft weiter — nur explizite Ereignisse beenden sie (TDD 9.1).
        return listOf(
            Verbindungsmeldung.Vorschlag(
                Verbindungsmeldung.Vorschlag.Art.SitzplatzFreigeben,
                participantUid,
                jetzt,
            ),
        )
    }

    private fun wechsleZu(neu: Verbindungszustand, jetzt: Long): List<Verbindungsmeldung> {
        val alt = zustand
        if (alt == neu) return emptyList()
        zustand = neu

        val meldungen = mutableListOf<Verbindungsmeldung>(
            Verbindungsmeldung.Uebergang(alt, neu, jetzt),
        )

        when (neu) {
            Verbindungszustand.OFFLINE -> {
                if (offlineSeit == null) offlineSeit = jetzt
                // Aus dem Wiedereinstieg heraus ist das **kein neuer Verlust**:
                // Der Spieler war die ganze Zeit weg, der Versuch ist nur
                // gescheitert. Ein zweites `participant_disconnected` würde im
                // Verlauf eine Trennung behaupten, die es nicht gab. Für diesen
                // Fall gibt es `rejoin_rejected` — es kommt aus
                // [handshakeAbgelehnt].
                if (alt != Verbindungszustand.WIEDEREINSTIEG) {
                    meldungen += Verbindungsmeldung.Sitzungsereignis(
                        Sitzungsereignistypen.VERBINDUNG_VERLOREN,
                        participantUid,
                        jetzt,
                    )
                }
            }

            Verbindungszustand.VERBUNDEN -> {
                offlineSeit = null
                freigabeVorgeschlagen = false
            }

            // `wackelig` erzeugt bewusst kein Ereignis — es existiert nur für
            // die Anzeige (TDD 9.2). Ein Verlauf, in dem jeder Funkschatten
            // steht, wäre unlesbar.
            Verbindungszustand.WACKELIG,
            Verbindungszustand.WIEDEREINSTIEG,
            Verbindungszustand.GEGANGEN,
            -> Unit
        }
        return meldungen
    }
}
