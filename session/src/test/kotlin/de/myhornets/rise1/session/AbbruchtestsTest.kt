package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.MatchEvent
import de.myhornets.rise1.core.event.Partiestand
import de.myhornets.rise1.core.event.Schnappschuss
import de.myhornets.rise1.core.event.Sitzplatzstand
import de.myhornets.rise1.core.event.Visibility
import de.myhornets.rise1.transport.Rahmen
import de.myhornets.rise1.transport.Rahmencodec
import de.myhornets.rise1.transport.Rahmenleser
import de.myhornets.rise1.transport.Rahmentyp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-114 — Abbruchtests, und der Schnappschuss auf der Leitung (T-109).
 *
 * ## Wozu diese Datei
 *
 * Die Einzelteile sind geprüft. Hier geht es um das, was **zwischen** ihnen
 * passiert, wenn etwas schiefgeht: eine Nachricht kommt zweimal, in der
 * falschen Reihenfolge, zu spät, gar nicht, oder von jemandem, der sie nicht
 * schicken dürfte.
 *
 * ## Die Tests, um die es hier geht
 *
 * [eineWiederholteAntwortMeldetNiemandenZweimalAn] — der Klassiker im
 * unzuverlässigen Netz. Wiederholungen sind kein Fehlerfall, sondern der
 * Normalbetrieb; ein Ablauf, der bei der zweiten Kopie etwas anderes tut, ist
 * kaputt.
 *
 * [einFremderSchnappschussWirdNichtUebernommen] — TDD 9.5 bindet den
 * Schnappschuss an die Sitzung, die den Handshake bestanden hat. Einer, der auf
 * einen fremden Sitzplatz lautet, ist entweder verwechselt oder untergeschoben.
 */
class AbbruchtestsTest {

    private val token = "0123456789abcdef-ein-langer-token"
    private var jetzt = 0L

    private fun automat() = Verbindungsautomat("p-1", Verbindungsschwellen(), startzeit = 0)

    private fun offlineAblauf(stand: Long = 2): Pair<Verbindungsautomat, Wiedereinstiegsablauf> {
        val a = automat()
        jetzt = 40_000
        a.zeitLaeuft(jetzt)
        check(a.zustand == Verbindungszustand.OFFLINE)
        return a to Wiedereinstiegsablauf(
            matchUid = "m-1",
            eigenerParticipantUid = "p-1",
            eigenesGeraeteUid = "d-a",
            token = { token },
            automat = a,
            uhr = { jetzt },
            startStand = stand,
        )
    }

    private fun schnappschuss(
        empfaenger: String = "p-1",
        bisSeq: Long = 5,
        matchUid: String = "m-1",
        eigene: List<MatchEvent> = emptyList(),
    ) = Schnappschuss(
        matchUid = matchUid,
        empfaenger = empfaenger,
        bisSeq = bisSeq,
        partie = Partiestand(matchUid, 3, "p-2", bisSeq),
        sitzplaetze = listOf(
            Sitzplatzstand("p-1", 40, false, false, null),
            Sitzplatzstand("p-2", 33, true, false, null),
        ),
        transkript = emptyList(),
        eigenePrivate = eigene,
    )

    // ── Wiederholungen und Reihenfolge ──────────────────────────────────────

    @Test
    fun eineWiederholteAntwortMeldetNiemandenZweimalAn() {
        val (a, ablauf) = offlineAblauf()
        ablauf.beginne()
        val antwort = Wiedereinstiegsantwort.Angenommen("s-1", null, bisSeq = 5)

        ablauf.antwort(antwort)
        ablauf.antwort(antwort) // die Kopie
        ablauf.delta(Aufholung.Delta("m-1", "p-1", 2, 5, listOf(ereignis(3), ereignis(5))))

        assertTrue(ablauf.angemeldet)
        assertEquals(
            listOf(Sitzungsereignistypen.VERBINDUNG_ZURUECK),
            ablauf.meldungen().filterIsInstance<Verbindungsmeldung.Sitzungsereignis>().map { it.typ },
            "Genau ein participant_reconnected, auch bei doppelter Antwort.",
        )
        assertEquals(Verbindungszustand.VERBUNDEN, a.zustand)
    }

    @Test
    fun einWiederholtesDeltaWirdNichtZweimalUebernommen() {
        val (_, ablauf) = offlineAblauf()
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", null, 5))
        val delta = Aufholung.Delta("m-1", "p-1", 2, 5, listOf(ereignis(3), ereignis(4)))

        ablauf.delta(delta)
        val zweites = ablauf.delta(delta)

        assertTrue(zweites is Deltaergebnis.Abgelehnt, "Nach der Anmeldung gibt es kein zweites Aufholen.")
        assertEquals(listOf(3L, 4L), ablauf.uebernommene().mapNotNull { it.seq })
        assertEquals(5L, ablauf.stand)
    }

    @Test
    fun einDeltaVorDerAntwortWirdVerworfen() {
        val (_, ablauf) = offlineAblauf()
        ablauf.beginne()
        val ergebnis = ablauf.delta(Aufholung.Delta("m-1", "p-1", 2, 5, listOf(ereignis(3))))

        assertTrue(ergebnis is Deltaergebnis.Abgelehnt)
        assertFalse(ablauf.angemeldet)
    }

    @Test
    fun eineAntwortNachDerAnmeldungAendertNichts() {
        val (_, ablauf) = offlineAblauf()
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", null, 5))
        ablauf.delta(Aufholung.Delta("m-1", "p-1", 2, 5, emptyList()))

        val spaet = ablauf.antwort(Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.NACHWEIS_FALSCH))

        assertEquals(emptyList<Verbindungsmeldung>(), spaet, "Eine verspätete Ablehnung wirft niemanden hinaus.")
        assertTrue(ablauf.angemeldet)
    }

    @Test
    fun nachEinemAbbruchBeginntDerAblaufVonVorn() {
        // Der ganze Zyklus zweimal: abgelehnt, dann angenommen. Der zweite
        // Versuch darf nicht am Zustand des ersten hängen.
        val (a, ablauf) = offlineAblauf()
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.ZU_VIELE_VERSUCHE))
        assertEquals(Verbindungszustand.OFFLINE, a.zustand)

        jetzt += 60_000
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-2", null, 4))
        ablauf.delta(Aufholung.Delta("m-1", "p-1", 2, 4, listOf(ereignis(4))))

        assertTrue(ablauf.angemeldet)
        assertEquals("s-2", ablauf.sitzungsUid)
        assertEquals(4L, ablauf.stand)
    }

    // ── Der Schnappschuss im Ablauf (T-109) ─────────────────────────────────

    @Test
    fun einSchnappschussSetztDenStandUndMeldetAn() {
        val (a, ablauf) = offlineAblauf(stand = -1)
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", null, bisSeq = 5))

        val ergebnis = ablauf.schnappschuss(schnappschuss())

        assertTrue(ergebnis is Deltaergebnis.Angenommen)
        assertTrue(ablauf.angemeldet)
        assertEquals(5L, ablauf.stand, "Ein Schnappschuss ersetzt den Stand, er ergänzt ihn nicht.")
        assertEquals(Verbindungszustand.VERBUNDEN, a.zustand)
        assertEquals(schnappschuss(), ablauf.letzterSchnappschuss)
    }

    @Test
    fun einFremderSchnappschussWirdNichtUebernommen() {
        val (a, ablauf) = offlineAblauf()
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", null, 5))

        val ergebnis = ablauf.schnappschuss(schnappschuss(empfaenger = "p-2"))

        assertEquals<Deltaergebnis>(Deltaergebnis.Abgelehnt(Deltafehler.FREMDER_EMPFAENGER, 5), ergebnis)
        assertFalse(ablauf.angemeldet)
        assertEquals(Verbindungszustand.OFFLINE, a.zustand)
        assertEquals(2L, ablauf.stand, "Der Stand bleibt unberührt.")
    }

    @Test
    fun einSchnappschussEinerFremdenPartieWirdNichtUebernommen() {
        val (_, ablauf) = offlineAblauf()
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", null, 5))

        val fremd = schnappschuss(matchUid = "m-2")
        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.FREMDE_PARTIE, 5),
            ablauf.schnappschuss(fremd),
        )
    }

    @Test
    fun einSchnappschussZumFalschenStandWirdNichtUebernommen() {
        val (_, ablauf) = offlineAblauf()
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", null, bisSeq = 5))

        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.PASST_NICHT_ZUR_ANTWORT, 9),
            ablauf.schnappschuss(schnappschuss(bisSeq = 9)),
        )
    }

    @Test
    fun einSchnappschussOhneHandshakeWirdNichtUebernommen() {
        val (_, ablauf) = offlineAblauf()
        val ergebnis = ablauf.schnappschuss(schnappschuss())
        assertTrue(ergebnis is Deltaergebnis.Abgelehnt)
        assertFalse(ablauf.angemeldet)
    }

    // ── Der Schnappschuss auf der Leitung ───────────────────────────────────

    @Test
    fun einSchnappschussUeberstehtDenRundlaufDurchDieRahmung() {
        val original = schnappschuss(
            eigene = listOf(
                ereignis(3, typ = "deal_key_packet", visibility = Visibility.PRIVATE, empfaenger = "p-1"),
            ),
        )
        val strom = Rahmencodec.kodiere(Sitzungsprotokoll.kodiere(original))
        val rahmen = Rahmenleser().fuettere(strom).filterIsInstance<Rahmen>().single()

        assertEquals(Rahmentyp.SCHNAPPSCHUSS, rahmen.typ)
        assertEquals(original, Sitzungsprotokoll.liesSchnappschuss(rahmen))
    }

    @Test
    fun einSchnappschussMitFremdemPrivatemKommtNichtDurchDieLeitung() {
        // Gebaut wird er über den Kodierer eines **anderen** Empfängers; der
        // Werttyp fängt ihn beim Lesen ab. Damit gilt die Regel aus TDD 9.5 auch
        // für Bytes, die von einem Host kommen, dem man nicht vertrauen muss.
        val fremdesPaket = ereignis(
            3,
            typ = "deal_key_packet",
            visibility = Visibility.PRIVATE,
            empfaenger = "p-2",
        )
        val fuerP2 = schnappschuss(empfaenger = "p-2", eigene = listOf(fremdesPaket))
        val bytes = Sitzungsprotokoll.kodiere(fuerP2).nutzlast

        // Den Empfängernamen auf p-1 umbiegen — genau das, was ein böswilliger
        // Host täte, um ein fremdes Paket unterzuschieben.
        val verbogen = String(bytes, Charsets.ISO_8859_1).replaceFirst("p-2", "p-1")
            .toByteArray(Charsets.ISO_8859_1)

        val fehler = assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesSchnappschuss(Rahmen(Rahmentyp.SCHNAPPSCHUSS, verbogen))
        }
        assertTrue(fehler.message!!.contains("unzulässig"))
    }

    @Test
    fun eineRiesigeSitzplatzanzahlWirdNichtGeglaubt() {
        val boese = mutableListOf<Byte>()
        boese += 1 // Fassung
        fun text(s: String) {
            val roh = s.toByteArray(Charsets.UTF_8)
            boese += ((roh.size ushr 8) and 0xFF).toByte()
            boese += (roh.size and 0xFF).toByte()
            roh.forEach { boese += it }
        }
        fun lang(v: Long) { for (i in 7 downTo 0) boese += ((v ushr (i * 8)) and 0xFF).toByte() }
        fun vier(v: Long) { for (i in 3 downTo 0) boese += ((v ushr (i * 8)) and 0xFF).toByte() }

        text("m-1"); text("p-1"); lang(5)
        vier(0); boese += 0; lang(5)
        vier(2_000_000_000) // so viele Sitzplätze gibt es an keinem Tisch

        val fehler = assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesSchnappschuss(Rahmen(Rahmentyp.SCHNAPPSCHUSS, boese.toByteArray()))
        }
        assertTrue(fehler.message!!.contains("seat_count"))
    }

    // ── Authentifizierung unter Wiederholung ────────────────────────────────

    @Test
    fun einWiederholtesGesuchZaehltNichtAlsFehlversuch() {
        // Ein Netz, das Pakete doppelt zustellt, darf niemanden aussperren.
        val salz = "salz-m1"
        val hash = RejoinPruefer.tokenHash(token, salz)
        var sitzungen = 0
        val pruefer = RejoinPruefer(
            object : Partienachschlag {
                override fun status(matchUid: String) = Partiestatus.ACTIVE
                override fun sitzplatz(matchUid: String, participantUid: String) =
                    Sitzplatznachweis("p-1", hash, salz, offeneSitzung = if (sitzungen > 0) "s-1" else null, offenesGeraet = if (sitzungen > 0) "d-a" else null)

                override fun eroeffneSitzung(m: String, p: String, d: String): String {
                    sitzungen++
                    return "s-1"
                }

                override fun hoechsteSeq(matchUid: String) = 7L
            },
            { 0L },
        )
        val gesuch = Wiedereinstiegsgesuch("m-1", "p-1", token, "d-a", 3)

        val erste = pruefer.pruefe(gesuch) as Wiedereinstiegsantwort.Angenommen
        val zweite = pruefer.pruefe(gesuch) as Wiedereinstiegsantwort.Angenommen

        assertEquals(erste.sitzungsUid, zweite.sitzungsUid, "TDD 9.3: derselbe Versuch, dasselbe Ergebnis.")
        assertEquals(null, zweite.abgeloesteSitzung)
        assertEquals(0, pruefer.fehlversucheFuer("p-1"))
        assertEquals(1, sitzungen, "Keine zweite Sitzung für dasselbe Gerät.")
    }

    @Test
    fun einAbgebrochenerHandshakeLaesstDenSitzplatzInRuhe() {
        // TDD 9.2: Kein Übergang verändert den Spielzustand. Nach einem
        // gescheiterten Aufholen ist der Stand derselbe wie vorher — der
        // Spieler hat seinen Platz, seine Identität und seine Punkte behalten.
        val (a, ablauf) = offlineAblauf(stand = 4)
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", null, 9))
        ablauf.delta(Aufholung.Delta("m-1", "p-1", 7, 9, listOf(ereignis(8))))

        assertEquals(4L, ablauf.stand)
        assertEquals(emptyList<MatchEvent>(), ablauf.uebernommene())
        assertEquals(Verbindungszustand.OFFLINE, a.zustand)
        assertEquals(
            emptyList<String>(),
            ablauf.meldungen().filterIsInstance<Verbindungsmeldung.Sitzungsereignis>().map { it.typ },
            "Kein Ereignis im Verlauf: Es hat weder jemand abgelehnt noch die Verbindung verloren.",
        )
    }
}
