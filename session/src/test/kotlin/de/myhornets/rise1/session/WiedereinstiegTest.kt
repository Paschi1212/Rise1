package de.myhornets.rise1.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-105 — der Wiedereinstiegs-Handshake, TDD 9.3.
 *
 * ## Die Tests, um die es hier geht
 *
 * [einFremderTokenKommtNichtRein] ist der Zweck der ganzen Datei. Wäre er grün,
 * ohne dass er es sein dürfte, könnte sich ein beliebiges Gerät auf einen
 * fremden Sitzplatz setzen — und bekäme mit dem nächsten Schlüsselpaket dessen
 * Identität.
 *
 * [einZweitesGleichesGesuchAendertNichts] ist der zweitwichtigste: TDD 9.3
 * verlangt Idempotenz. Ohne sie löst ein Gerät bei jedem Wackler seine eigene
 * Sitzung ab, und der Verlauf füllt sich mit Ablösungen, die nie stattgefunden
 * haben.
 */
class WiedereinstiegTest {

    private val token = "0123456789abcdef-ein-langer-token"
    private val salz = "salz-m1-p1"

    /** Ein Nachschlag über festen Werten. Zählt mit, was er herausgegeben hat. */
    private class Nachschlag(
        var status: String? = Partiestatus.ACTIVE,
        var sitz: Sitzplatznachweis? = null,
        var hoechste: Long = 42,
    ) : Partienachschlag {

        val eroeffnete = mutableListOf<Triple<String, String, String>>()

        override fun status(matchUid: String): String? = status

        override fun sitzplatz(matchUid: String, participantUid: String): Sitzplatznachweis? =
            sitz?.takeIf { it.participantUid == participantUid }

        override fun eroeffneSitzung(matchUid: String, participantUid: String, deviceUid: String): String {
            eroeffnete += Triple(matchUid, participantUid, deviceUid)
            return "s-${eroeffnete.size}"
        }

        override fun hoechsteSeq(matchUid: String): Long = hoechste
    }

    private fun nachschlag(
        status: String? = Partiestatus.ACTIVE,
        offeneSitzung: String? = null,
        offenesGeraet: String? = null,
        hoechste: Long = 42,
    ) = Nachschlag(
        status = status,
        sitz = Sitzplatznachweis(
            participantUid = "p-1",
            rejoinTokenHash = RejoinPruefer.tokenHash(token, salz),
            salz = salz,
            offeneSitzung = offeneSitzung,
            offenesGeraet = offenesGeraet,
        ),
        hoechste = hoechste,
    )

    private fun gesuch(
        token: String = this.token,
        participantUid: String = "p-1",
        geraet: String = "d-a",
        lastSeqSeen: Long = 40,
        matchUid: String = "m-1",
    ) = Wiedereinstiegsgesuch(matchUid, participantUid, token, geraet, lastSeqSeen)

    private fun pruefer(n: Partienachschlag, uhr: () -> Long = { 0L }) = RejoinPruefer(n, uhr)

    // ── 1. Der gute Fall ────────────────────────────────────────────────────

    @Test
    fun einPassenderNachweisOeffnetEineSitzung() {
        val n = nachschlag()
        val antwort = pruefer(n).pruefe(gesuch())

        val angenommen = antwort as Wiedereinstiegsantwort.Angenommen
        assertEquals("s-1", angenommen.sitzungsUid)
        assertNull(angenommen.abgeloesteSitzung, "Es gab keine offene Sitzung.")
        assertEquals(42, angenommen.bisSeq)
        assertEquals(listOf(Triple("m-1", "p-1", "d-a")), n.eroeffnete)
    }

    // ── 2. Der Nachweis ─────────────────────────────────────────────────────

    @Test
    fun einFremderTokenKommtNichtRein() {
        val antwort = pruefer(nachschlag()).pruefe(gesuch(token = "ein-ganz-anderer-token-x"))
        assertEquals<Wiedereinstiegsantwort>(
            Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.NACHWEIS_FALSCH),
            antwort,
        )
    }

    @Test
    fun dieParticipantUidAlleinIstKeinNachweis() {
        // TDD 9.1: Wiedereinstieg ist Authentifizierung, nicht Wiedererkennung.
        // Wer nur den Sitzplatz kennt — und der steht in jedem Verlauf —, kommt
        // nicht hinein. Der Token ist hier eine plausible, aber falsche UUID.
        val antwort = pruefer(nachschlag())
            .pruefe(gesuch(token = "8f14e45f-ea8f-2b0b-9d7f-7b0000000000"))
        assertEquals<Wiedereinstiegsantwort>(
            Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.NACHWEIS_FALSCH),
            antwort,
        )
    }

    @Test
    fun einTokenUnterTdd91IstKeinerUndFaelltFrueherAuf() {
        // 128 Bit sind die Untergrenze aus TDD 9.1. Eine kurze PIN würde die
        // Ratenbegrenzung zur einzigen Verteidigung machen.
        val fehler = assertFailsWith<IllegalArgumentException> {
            RejoinPruefer.tokenHash("1234", salz)
        }
        assertTrue(fehler.message!!.contains("128 Bit"))
    }

    @Test
    fun einZuKurzerTokenLegtDenHostNichtLahm() {
        // Der Wert kommt von außen. Ein Gesuch mit vierstelliger PIN muss
        // abgelehnt werden — und darf nicht die Prüfung selbst abbrechen, sonst
        // wäre die Ablehnung ein Absturz und der Host anfällig für ein einziges
        // Paket.
        val pruefer = pruefer(nachschlag())
        assertEquals<Wiedereinstiegsantwort>(
            Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.NACHWEIS_FALSCH),
            pruefer.pruefe(gesuch(token = "1234")),
        )
        assertEquals(1, pruefer.fehlversucheFuer("p-1"))
    }

    @Test
    fun dasSalzMachtDenselbenTokenInZweiPartienVerschieden() {
        assertNotEquals(
            RejoinPruefer.tokenHash(token, "salz-a"),
            RejoinPruefer.tokenHash(token, "salz-b"),
        )
    }

    @Test
    fun derTokenStehtInKeinemProtokoll() {
        val text = gesuch().toString()
        assertFalse(text.contains(token), "Ein Geheimnis gehört in keine Fehlermeldung.")
        assertTrue(text.contains("p-1"))
    }

    // ── 3. Partie und Sitzplatz ─────────────────────────────────────────────

    @Test
    fun eineUnbekanntePartieWirdAbgelehnt() {
        val antwort = pruefer(nachschlag(status = null)).pruefe(gesuch())
        assertEquals<Wiedereinstiegsantwort>(
            Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.PARTIE_UNBEKANNT),
            antwort,
        )
    }

    @Test
    fun einebeendetePartieBekommtIhrenEigenenGrund() {
        // TDD 9.3 ausdrücklich: damit die App sagen kann, dass die Partie vorbei
        // ist, statt dem Spieler einen Zugriffsfehler zu zeigen.
        val n = nachschlag(status = Partiestatus.FINISHED)
        val pruefer = pruefer(n)
        assertEquals<Wiedereinstiegsantwort>(
            Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.PARTIE_BEENDET),
            pruefer.pruefe(gesuch()),
        )
        // Und er zählt nicht als Fehlversuch — hier hat niemand etwas falsch gemacht.
        assertEquals(0, pruefer.fehlversucheFuer("p-1"))
    }

    @Test
    fun einSitzplatzAusEinerAnderenPartieIstUnbekannt() {
        val antwort = pruefer(nachschlag()).pruefe(gesuch(participantUid = "p-2"))
        assertEquals<Wiedereinstiegsantwort>(
            Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.SITZPLATZ_UNBEKANNT),
            antwort,
        )
    }

    @Test
    fun waehrendDerVerteilungGibtEsKeinenWiedereinstieg() {
        // TDD 9.3 nennt `active` und `paused`. `dealing` ist bewusst nicht dabei:
        // Ein Gerät, das mitten in der Verteilung neu einsteigt, bekäme ein
        // halbes Schlüsselpaket.
        val antwort = pruefer(nachschlag(status = Partiestatus.DEALING)).pruefe(gesuch())
        assertEquals<Wiedereinstiegsantwort>(
            Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.PARTIE_UNBEKANNT),
            antwort,
        )
    }

    @Test
    fun einePausiertePartieLaesstZurueck() {
        val antwort = pruefer(nachschlag(status = Partiestatus.PAUSED)).pruefe(gesuch())
        assertTrue(antwort is Wiedereinstiegsantwort.Angenommen)
    }

    // ── 4. Ablösung und Idempotenz (TDD 9.3) ────────────────────────────────

    @Test
    fun einAnderesGeraetLoestDieOffeneSitzungAb() {
        val n = nachschlag(offeneSitzung = "s-alt", offenesGeraet = "d-alt")
        val antwort = pruefer(n).pruefe(gesuch(geraet = "d-neu")) as Wiedereinstiegsantwort.Angenommen

        assertEquals("s-alt", antwort.abgeloesteSitzung, "end_reason = superseded")
        assertNotEquals("s-alt", antwort.sitzungsUid)
    }

    @Test
    fun einZweitesGleichesGesuchAendertNichts() {
        // TDD 9.3: Ein zweiter identischer Versuch führt zum selben Ergebnis.
        // Dasselbe Gerät löst sich nicht selbst ab.
        val n = nachschlag(offeneSitzung = "s-alt", offenesGeraet = "d-a")
        val antwort = pruefer(n).pruefe(gesuch(geraet = "d-a")) as Wiedereinstiegsantwort.Angenommen

        assertEquals("s-alt", antwort.sitzungsUid, "dieselbe Sitzung")
        assertNull(antwort.abgeloesteSitzung, "keine Selbstablösung")
        assertEquals(emptyList<Triple<String, String, String>>(), n.eroeffnete, "und keine zweite Sitzung")
    }

    // ── 5. Ratenbegrenzung ──────────────────────────────────────────────────

    @Test
    fun nachFuenfFehlversuchenIstSchluss() {
        var jetzt = 0L
        val pruefer = RejoinPruefer(nachschlag(), { jetzt })

        repeat(5) {
            jetzt += 1_000
            assertEquals<Wiedereinstiegsantwort>(
                Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.NACHWEIS_FALSCH),
                pruefer.pruefe(gesuch(token = "falscher-token-mit-laenge")),
            )
        }
        jetzt += 1_000
        // Jetzt auch mit **richtigem** Token nicht mehr. Sonst wäre die Grenze
        // keine: Ein Angreifer probiert, bis er trifft.
        assertEquals<Wiedereinstiegsantwort>(
            Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.ZU_VIELE_VERSUCHE),
            pruefer.pruefe(gesuch()),
        )
    }

    @Test
    fun nachDemFensterGehtEsWieder() {
        var jetzt = 0L
        val pruefer = RejoinPruefer(nachschlag(), { jetzt })
        repeat(5) { pruefer.pruefe(gesuch(token = "falscher-token-mit-laenge")) }

        jetzt = 60_000
        assertTrue(pruefer.pruefe(gesuch()) is Wiedereinstiegsantwort.Angenommen)
    }

    @Test
    fun einErfolgLoeschtDieFehlversuche() {
        var jetzt = 0L
        val pruefer = RejoinPruefer(nachschlag(), { jetzt })
        repeat(3) {
            jetzt += 100
            pruefer.pruefe(gesuch(token = "falscher-token-mit-laenge"))
        }
        assertEquals(3, pruefer.fehlversucheFuer("p-1"))

        jetzt += 100
        assertTrue(pruefer.pruefe(gesuch()) is Wiedereinstiegsantwort.Angenommen)
        assertEquals(0, pruefer.fehlversucheFuer("p-1"))
    }

    @Test
    fun dieGrenzeGiltJeSitzplatzUndNichtFuerDenTisch() {
        // Sonst sperrte ein einzelnes stotterndes Gerät den ganzen Tisch aus.
        val n = Nachschlag(
            status = Partiestatus.ACTIVE,
            sitz = Sitzplatznachweis("p-2", RejoinPruefer.tokenHash(token, salz), salz),
        )
        var jetzt = 0L
        val pruefer = RejoinPruefer(n, { jetzt })
        repeat(5) {
            jetzt += 100
            pruefer.pruefe(gesuch(participantUid = "p-1"))
        }
        jetzt += 100
        assertTrue(pruefer.pruefe(gesuch(participantUid = "p-2")) is Wiedereinstiegsantwort.Angenommen)
    }

    // ── 6. Grenzen des Gesuchs ──────────────────────────────────────────────

    @Test
    fun einGesuchOhneSitzplatzGibtEsNicht() {
        assertFailsWith<IllegalArgumentException> {
            Wiedereinstiegsgesuch("m-1", "  ", token, "d-a", 0)
        }
    }

    @Test
    fun einStandUnterMinusEinsGibtEsNicht() {
        assertFailsWith<IllegalArgumentException> {
            Wiedereinstiegsgesuch("m-1", "p-1", token, "d-a", -2)
        }
    }
}
