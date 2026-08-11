package de.myhornets.rise1.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Nur zur Lesbarkeit der Erwartungen unten. */
private typealias Zustandspaar = Pair<Verbindungszustand, Verbindungszustand>

/**
 * T-103 — der Zustandsautomat aus TDD 9.2.
 *
 * Der wichtigste Test ist [einHerzschlagAlleinHoltNiemandenAusOffline]. Er
 * schützt keine Funktion, sondern TDD 9.1: *„Wiedereinstieg ist
 * Authentifizierung, nicht Wiedererkennung."* Käme man mit einem Lebenszeichen
 * zurück, reichte es, Pakete mit einer fremden `participant_uid` zu schicken,
 * um deren Sitzplatz zu übernehmen — und dann deren Schlüsselpaket zu bekommen.
 *
 * Der zweitwichtigste ist [dieKarenzzeitBeendetNiemalsEtwas]: *„Nur explizite
 * Ereignisse beenden eine Partie."*
 */
class VerbindungsautomatTest {

    private val sitz = "p-1"
    private val takt = 5_000L // herzschlagIntervallSekunden = 5

    private fun automat(schwellen: Verbindungsschwellen = Verbindungsschwellen()) =
        Verbindungsautomat(sitz, schwellen, startzeit = 0L)

    private fun uebergaenge(meldungen: List<Verbindungsmeldung>) =
        meldungen.filterIsInstance<Verbindungsmeldung.Uebergang>().map { it.von to it.nach }

    private fun ereignisse(meldungen: List<Verbindungsmeldung>) =
        meldungen.filterIsInstance<Verbindungsmeldung.Sitzungsereignis>().map { it.typ }

    // ── Der Weg nach unten ──────────────────────────────────────────────────

    @Test
    fun einNeuerAutomatIstVerbunden() {
        assertEquals(Verbindungszustand.VERBUNDEN, automat().zustand)
    }

    @Test
    fun zweiVerpassteHerzschlaegeMachenWackelig() {
        val a = automat()
        assertEquals(emptyList<Zustandspaar>(), uebergaenge(a.zeitLaeuft(takt)), "Einer reicht nicht.")
        val meldungen = a.zeitLaeuft(2 * takt)
        assertEquals(
            listOf(Verbindungszustand.VERBUNDEN to Verbindungszustand.WACKELIG),
            uebergaenge(meldungen),
        )
    }

    @Test
    fun wackeligErzeugtKeinEreignisImVerlauf() {
        // TDD 9.2: „`suspect` existiert nur für die Anzeige." Ein Verlauf, in
        // dem jeder Funkschatten steht, wäre unlesbar.
        val a = automat()
        assertEquals(emptyList<String>(), ereignisse(a.zeitLaeuft(2 * takt)))
    }

    @Test
    fun sechsVerpassteHerzschlaegeMachenOffline() {
        val a = automat()
        a.zeitLaeuft(2 * takt)
        val meldungen = a.zeitLaeuft(6 * takt)
        assertEquals(
            listOf(Verbindungszustand.WACKELIG to Verbindungszustand.OFFLINE),
            uebergaenge(meldungen),
        )
        assertEquals(listOf(Sitzungsereignistypen.VERBINDUNG_VERLOREN), ereignisse(meldungen))
    }

    @Test
    fun einLangerAusfallMeldetBeideUebergaenge() {
        // Wer eine Minute weg war, geht in einem Schritt durch beide Zustände.
        // Beide werden gemeldet, damit ein Mitschnitt vollständig bleibt.
        val a = automat()
        val meldungen = a.zeitLaeuft(60_000)
        assertEquals(
            listOf(
                Verbindungszustand.VERBUNDEN to Verbindungszustand.WACKELIG,
                Verbindungszustand.WACKELIG to Verbindungszustand.OFFLINE,
            ),
            uebergaenge(meldungen),
        )
        assertEquals(Verbindungszustand.OFFLINE, a.zustand)
    }

    // ── Der Weg nach oben ───────────────────────────────────────────────────

    @Test
    fun einHerzschlagHoltAusWackeligZurueck() {
        val a = automat()
        a.zeitLaeuft(2 * takt)
        val meldungen = a.herzschlag(2 * takt + 100)
        assertEquals(
            listOf(Verbindungszustand.WACKELIG to Verbindungszustand.VERBUNDEN),
            uebergaenge(meldungen),
        )
        assertEquals(emptyList<String>(), ereignisse(meldungen), "Ein Aussetzer gehört nicht in den Verlauf.")
    }

    @Test
    fun einHerzschlagAlleinHoltNiemandenAusOffline() {
        // TDD 9.1: „Wiedereinstieg ist Authentifizierung, nicht Wiedererkennung."
        // Der wichtigste Test dieser Datei.
        val a = automat()
        a.zeitLaeuft(60_000)
        assertEquals(Verbindungszustand.OFFLINE, a.zustand)

        val meldungen = a.herzschlag(61_000)
        assertEquals(emptyList<Zustandspaar>(), uebergaenge(meldungen))
        assertEquals(
            Verbindungszustand.OFFLINE,
            a.zustand,
            "Ein Lebenszeichen ist kein Nachweis. Sonst genügte eine fremde participant_uid.",
        )
    }

    @Test
    fun nurEinBestandenerHandshakeFuehrtZurueck() {
        val a = automat()
        a.zeitLaeuft(60_000)

        assertEquals(
            listOf(Verbindungszustand.OFFLINE to Verbindungszustand.WIEDEREINSTIEG),
            uebergaenge(a.handshakeBegonnen(61_000)),
        )
        val meldungen = a.handshakeErfolgreich(62_000)
        assertEquals(
            listOf(Verbindungszustand.WIEDEREINSTIEG to Verbindungszustand.VERBUNDEN),
            uebergaenge(meldungen),
        )
        assertEquals(listOf(Sitzungsereignistypen.VERBINDUNG_ZURUECK), ereignisse(meldungen))
    }

    @Test
    fun einAbgelehnterHandshakeFuehrtZurueckNachOffline() {
        val a = automat()
        a.zeitLaeuft(60_000)
        a.handshakeBegonnen(61_000)
        val meldungen = a.handshakeAbgelehnt(62_000)
        assertEquals(
            listOf(Verbindungszustand.WIEDEREINSTIEG to Verbindungszustand.OFFLINE),
            uebergaenge(meldungen),
        )
        assertEquals(Verbindungszustand.OFFLINE, a.zustand)
    }

    @Test
    fun einAbgelehnterHandshakeMeldetKeineZweiteTrennung() {
        // Der Spieler war die ganze Zeit weg; der Versuch ist nur gescheitert.
        // Ein zweites `participant_disconnected` würde im Verlauf eine
        // Trennung behaupten, die es nicht gab. TDD 5.4 hat dafür einen
        // eigenen Typ — und der ist sicherheitsrelevant, also PUBLIC.
        val a = automat()
        a.zeitLaeuft(60_000)
        a.handshakeBegonnen(61_000)
        val meldungen = a.handshakeAbgelehnt(62_000)

        assertEquals(listOf(Sitzungsereignistypen.WIEDEREINSTIEG_ABGELEHNT), ereignisse(meldungen))
        assertTrue(
            Sitzungsereignistypen.VERBINDUNG_VERLOREN !in ereignisse(meldungen),
            "Es gab keine neue Trennung, also darf keine gemeldet werden.",
        )
    }

    @Test
    fun waehrendDesHandshakesLaeuftKeineZeitAb() {
        // Sonst könnte der Automat mitten in der Prüfung den Zustand wechseln,
        // und das Ergebnis der Prüfung träfe auf etwas anderes als das, was
        // geprüft wurde.
        val a = automat()
        a.zeitLaeuft(60_000)
        a.handshakeBegonnen(61_000)
        assertEquals(emptyList<Verbindungsmeldung>(), a.zeitLaeuft(600_000))
        assertEquals(Verbindungszustand.WIEDEREINSTIEG, a.zustand)
    }

    @Test
    fun einZweiterHandshakeVersuchAendertNichts() {
        // TDD 9.3: „Der Handshake ist idempotent."
        val a = automat()
        a.zeitLaeuft(60_000)
        a.handshakeBegonnen(61_000)
        assertEquals(emptyList<Verbindungsmeldung>(), a.handshakeBegonnen(61_500))
    }

    @Test
    fun einHandshakeErfolgOhneBegonnenenHandshakeAendertNichts() {
        val a = automat()
        assertEquals(emptyList<Verbindungsmeldung>(), a.handshakeErfolgreich(1_000))
        assertEquals(Verbindungszustand.VERBUNDEN, a.zustand)
    }

    // ── Der einzige Weg nach draußen ────────────────────────────────────────

    @Test
    fun nurEineBewussteHandlungFuehrtNachGegangen() {
        // TDD 5.4: „Endgültiges Ausscheiden — eine Entscheidung, kein Timeout."
        val a = automat()
        a.zeitLaeuft(600_000)
        assertEquals(Verbindungszustand.OFFLINE, a.zustand, "Kein Timeout führt nach GEGANGEN.")

        assertEquals(
            listOf(Verbindungszustand.OFFLINE to Verbindungszustand.GEGANGEN),
            uebergaenge(a.verlassen(600_001)),
        )
    }

    @Test
    fun ausGegangenFuehrtKeinWegZurueck() {
        val a = automat()
        a.verlassen(1_000)
        assertEquals(emptyList<Verbindungsmeldung>(), a.herzschlag(2_000))
        assertEquals(emptyList<Verbindungsmeldung>(), a.zeitLaeuft(600_000))
        assertEquals(emptyList<Verbindungsmeldung>(), a.handshakeBegonnen(3_000))
        assertEquals(emptyList<Verbindungsmeldung>(), a.verlassen(4_000))
        assertEquals(Verbindungszustand.GEGANGEN, a.zustand)
    }

    @Test
    fun ausJedemZustandFuehrtVerlassenNachGegangen() {
        // TDD 9.2: „Jeder Zustand ── participant_left ──► left"
        listOf<(Verbindungsautomat) -> Unit>(
            { },
            { it.zeitLaeuft(2 * takt) },
            { it.zeitLaeuft(60_000) },
            { it.zeitLaeuft(60_000); it.handshakeBegonnen(61_000) },
        ).forEach { vorbereitung ->
            val a = automat()
            vorbereitung(a)
            a.verlassen(700_000)
            assertEquals(Verbindungszustand.GEGANGEN, a.zustand)
        }
    }

    // ── Karenzzeit ──────────────────────────────────────────────────────────

    @Test
    fun dieKarenzzeitIstStandardmaessigUnbegrenzt() {
        // TDD 9.2: „Ein Tischspiel kann pausieren, weil jemand die Pizza holt."
        val a = automat()
        a.zeitLaeuft(60_000)
        val nachEinerStunde = a.zeitLaeuft(3_600_000)
        assertTrue(nachEinerStunde.filterIsInstance<Verbindungsmeldung.Vorschlag>().isEmpty())
        assertEquals(Verbindungszustand.OFFLINE, a.zustand)
    }

    @Test
    fun dieKarenzzeitBeendetNiemalsEtwas() {
        // TDD 9.2: „Läuft sie doch ab, beendet das NIEMALS die Partie, sondern
        // schlägt dem Host höchstens vor, den Platz freizugeben."
        val a = automat(Verbindungsschwellen(karenzSekunden = 60))
        a.zeitLaeuft(60_000) // offline bei 60 s
        val meldungen = a.zeitLaeuft(180_000)

        val vorschlaege = meldungen.filterIsInstance<Verbindungsmeldung.Vorschlag>()
        assertEquals(1, vorschlaege.size)
        assertEquals(Verbindungsmeldung.Vorschlag.Art.SitzplatzFreigeben, vorschlaege.single().art)
        assertEquals(
            Verbindungszustand.OFFLINE,
            a.zustand,
            "Der Ablauf der Karenzzeit ist ein Vorschlag, kein Übergang.",
        )
    }

    @Test
    fun derFreigabevorschlagKommtNurEinmal() {
        val a = automat(Verbindungsschwellen(karenzSekunden = 60))
        a.zeitLaeuft(60_000)
        a.zeitLaeuft(180_000)
        val nochmal = a.zeitLaeuft(240_000)
        assertTrue(nochmal.filterIsInstance<Verbindungsmeldung.Vorschlag>().isEmpty())
    }

    @Test
    fun nachDemZurueckkommenBeginntDieKarenzzeitNeu() {
        val a = automat(Verbindungsschwellen(karenzSekunden = 60))
        a.zeitLaeuft(60_000)
        a.zeitLaeuft(180_000) // Vorschlag
        a.handshakeBegonnen(190_000)
        a.handshakeErfolgreich(191_000)

        a.zeitLaeuft(260_000) // wieder offline
        assertEquals(Verbindungszustand.OFFLINE, a.zustand)
        val meldungen = a.zeitLaeuft(300_000)
        assertTrue(
            meldungen.filterIsInstance<Verbindungsmeldung.Vorschlag>().isEmpty(),
            "Die Karenzzeit lief ab dem neuen Verlust noch nicht ab.",
        )
    }

    // ── Zählung und Schwellen ───────────────────────────────────────────────

    @Test
    fun verpassteHerzschlaegeWerdenGezaehlt() {
        val a = automat()
        assertEquals(0, a.verpassteHerzschlaege(0))
        assertEquals(0, a.verpassteHerzschlaege(takt - 1))
        assertEquals(1, a.verpassteHerzschlaege(takt))
        assertEquals(6, a.verpassteHerzschlaege(6 * takt))
    }

    @Test
    fun einHerzschlagSetztDieZaehlungZurueck() {
        val a = automat()
        a.zeitLaeuft(takt)
        a.herzschlag(takt)
        assertEquals(0, a.verpassteHerzschlaege(takt))
        assertEquals(1, a.verpassteHerzschlaege(2 * takt))
    }

    @Test
    fun einVerspaeteterHerzschlagZaehltNichtRueckwaerts() {
        // Pakete können sich überholen. Ein alter Zeitstempel darf den Stand
        // nicht zurückdrehen.
        val a = automat()
        a.herzschlag(10_000)
        a.herzschlag(5_000)
        assertEquals(10_000, a.letzterHerzschlag)
    }

    @Test
    fun unsinnigeSchwellenWerdenAbgewiesen() {
        assertFailsWith<IllegalArgumentException> {
            Verbindungsschwellen(verpassteBisWackelig = 8, verpassteBisOffline = 6)
        }
        assertFailsWith<IllegalArgumentException> { Verbindungsschwellen(herzschlagIntervallSekunden = 0) }
        assertFailsWith<IllegalArgumentException> { Verbindungsschwellen(karenzSekunden = -1) }
    }

    @Test
    fun dieVorgeschlagenenWerteAusDemTddSindDieStandardwerte() {
        val s = Verbindungsschwellen()
        assertEquals(5, s.herzschlagIntervallSekunden)
        assertEquals(2, s.verpassteBisWackelig)
        assertEquals(6, s.verpassteBisOffline)
        assertEquals(0, s.karenzSekunden)
        assertTrue(s.karenzUnbegrenzt)
        assertTrue(!s.zugAutomatischUeberspringen)
    }
}
