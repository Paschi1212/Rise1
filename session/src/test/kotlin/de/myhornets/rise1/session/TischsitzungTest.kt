package de.myhornets.rise1.session

import de.myhornets.rise1.transport.Gegenstelle
import de.myhornets.rise1.transport.Rahmen
import de.myhornets.rise1.transport.Rahmencodec
import de.myhornets.rise1.transport.Rahmenleser
import de.myhornets.rise1.transport.Rahmentyp
import de.myhornets.rise1.transport.Transport
import de.myhornets.rise1.transport.TransportEreignis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `T-101` — der Beitritt über die Leitung.
 *
 * ## Warum gegen ein [Leitungspaar] und nicht gegen Sockets
 *
 * Weil hier das **Protokoll** geprüft wird und nicht der Scheduler. Das
 * Leitungspaar stellt zwei Transporte gegeneinander, synchron und ohne Thread —
 * dieselbe Haltung wie beim `AttrappenTransport` aus `T-066`: *„Ein Test, der
 * eine Reihenfolge prüft, prüft dann die Reihenfolge und nicht den Scheduler."*
 *
 * Dass derselbe Ablauf auch über echte Sockets läuft, steht in
 * [derGanzeWegLaeuftAuchUeberEchteSockets] — einmal, am Ende, als Nachweis.
 *
 * ## Der wichtigste
 *
 * [eineAblehnungUeberlebtDasEndeDerLeitung]. Ein Host, der ablehnt, legt danach
 * auf. Wer das Ende als Scheitern wertet, zeigt dem Nutzer „keine Verbindung"
 * statt „der Tisch ist voll" — und der versucht es dann wieder und wieder.
 */
class TischsitzungTest {

    private val partie = "m-1"

    private val hostAdresse = Gegenstelle("host-1", "Tisch am Fenster")
    private val gastAdresse = Gegenstelle("gast-1", "Bert")

    private fun gesuch(
        matchUid: String = partie,
        geraet: String = "d-gast",
        name: String = "Bert",
        wunschplatz: Int? = null,
    ) = Beitrittsgesuch(matchUid = matchUid, deviceUid = geraet, anzeigename = name, wunschplatz = wunschplatz)

    private fun angenommen(platz: Int = 1) =
        Beitrittsantwort.Angenommen(participantUid = "p-$platz", sitzplatz = platz, rejoinToken = "a".repeat(32))

    // ── Zwei Transporte, gegeneinander gestellt ─────────────────────────────

    /**
     * Zwei [Transport]-Enden, die einander bedienen — synchron, ohne Thread.
     *
     * Kein Ersatz für den `Sockettransport`, sondern das Gegenstück zum
     * `AttrappenTransport`: Es geht um die Reihenfolge der Nachrichten, nicht um
     * Nebenläufigkeit. Was der eine sendet, hat der andere schon empfangen,
     * wenn `sende` zurückkommt.
     */
    private class Leitungspaar(hostName: String = "host-1", gastName: String = "gast-1") {

        val hostSeite = Seite(Gegenstelle(gastName, gastName))
        val gastSeite = Seite(Gegenstelle(hostName, hostName))

        init {
            hostSeite.andere = gastSeite
            gastSeite.andere = hostSeite
        }

        class Seite(val gegenueber: Gegenstelle) : Transport {

            lateinit var andere: Seite

            private val hoerer = mutableListOf<(TransportEreignis) -> Unit>()
            private var steht = false

            override val verbundene: Set<Gegenstelle> get() = if (steht) setOf(gegenueber) else emptySet()

            override fun beobachte(hoerer: (TransportEreignis) -> Unit) {
                this.hoerer += hoerer
            }

            override fun verbinde(gegenstelle: Gegenstelle) {
                if (steht) return
                steht = true
                andere.steht = true
                // Beide Enden erfahren es — genau wie beim Sockettransport, wo
                // der Annahmethread die Gegenseite einrichtet.
                melde(TransportEreignis.Verbunden(gegenueber))
                andere.melde(TransportEreignis.Verbunden(andere.gegenueber))
            }

            override fun sende(an: Gegenstelle, rahmen: ByteArray): Boolean {
                if (!steht) return false
                andere.melde(TransportEreignis.Empfangen(andere.gegenueber, rahmen))
                return true
            }

            override fun trenne(gegenstelle: Gegenstelle, grund: String) {
                if (!steht) return
                steht = false
                andere.steht = false
                melde(TransportEreignis.Getrennt(gegenueber, grund))
                andere.melde(TransportEreignis.Getrennt(andere.gegenueber, grund))
            }

            override fun schliesse() {
                steht = false
                hoerer.clear()
            }

            fun melde(ereignis: TransportEreignis) {
                hoerer.toList().forEach { it(ereignis) }
            }

            /** Von Hand einspeisen — für Protokollfehler und fremde Rahmen. */
            fun speiseEin(bytes: ByteArray) {
                andere.melde(TransportEreignis.Empfangen(andere.gegenueber, bytes))
            }
        }
    }

    private fun tisch(
        paar: Leitungspaar,
        matchUid: String = partie,
        antwort: (Beitrittsgesuch) -> Beitrittsantwort = { angenommen() },
    ): Tischdienst = Tischdienst(paar.hostSeite, matchUid, antwort).also { it.starte() }

    private fun gast(paar: Leitungspaar, gesuch: Beitrittsgesuch = gesuch()): Beitrittsablauf =
        Beitrittsablauf(paar.gastSeite, Gegenstelle("host-1", "host-1"), gesuch)

    // ── Der Normalweg ───────────────────────────────────────────────────────

    @Test
    fun derGastBekommtEinenPlatzUndDerHostKenntIhn() {
        val paar = Leitungspaar()
        val dienst = tisch(paar) { angenommen(platz = 3) }
        val ablauf = gast(paar)

        ablauf.starte()

        val stand = assertIs<Beitrittsablauf.Stand.Angenommen>(ablauf.stand)
        assertEquals("p-3", stand.participantUid)
        assertEquals(3, stand.sitzplatz)
        assertEquals(32, stand.rejoinToken.length)
        assertTrue(ablauf.leitungSteht)

        assertEquals(1, dienst.gaeste.size)
        val platz = dienst.gaeste.single()
        assertEquals("p-3", platz.participantUid)
        assertEquals(3, platz.sitzplatz)
        assertEquals("Bert", platz.anzeigename)
        assertTrue(platz.steht)
    }

    @Test
    fun derWunschplatzGehtMitUeberDieLeitung() {
        val paar = Leitungspaar()
        var gesehen: Beitrittsgesuch? = null
        tisch(paar) { g -> gesehen = g; angenommen(platz = g.wunschplatz ?: 0) }

        gast(paar, gesuch(wunschplatz = 5)).starte()

        assertEquals(5, gesehen?.wunschplatz)
    }

    @Test
    fun derHostSpiegeltDenHerzschlag() {
        // Ohne Antwort hielte `Sitzungsverbindung` den Host für stumm — und ein
        // stummer Host ist nicht dasselbe wie ein abwesender (TDD 9.2).
        val paar = Leitungspaar()
        tisch(paar)
        val empfangen = mutableListOf<Rahmen>()
        val leser = Rahmenleser()
        paar.gastSeite.beobachte { ereignis ->
            if (ereignis is TransportEreignis.Empfangen) {
                empfangen += leser.fuettere(ereignis.rahmen).filterIsInstance<Rahmen>()
            }
        }

        paar.gastSeite.verbinde(hostAdresse)
        paar.gastSeite.sende(hostAdresse, Rahmencodec.kodiere(Rahmen(Rahmentyp.HERZSCHLAG, ByteArray(0))))

        assertEquals(listOf(Rahmentyp.HERZSCHLAG), empfangen.map { it.typ })
    }

    // ── Ablehnungen ─────────────────────────────────────────────────────────

    @Test
    fun einVollerTischWirdAlsSolcherGemeldet() {
        val paar = Leitungspaar()
        val dienst = tisch(paar) { Beitrittsantwort.Abgelehnt(Beitrittsablehnung.TISCH_VOLL) }
        val ablauf = gast(paar)

        ablauf.starte()

        assertEquals(Beitrittsablauf.Stand.Abgelehnt(Beitrittsablehnung.TISCH_VOLL), ablauf.stand)
        assertTrue(dienst.gaeste.isEmpty(), "Ein Abgelehnter sitzt nicht.")
    }

    @Test
    fun einGesuchFuerEineFremdePartieWirdAbgelehntOhneDieStelleZuFragen() {
        // Der Host darf ein Gesuch für eine andere Partie nicht an die
        // Platzvergabe durchreichen — dort stünde sonst eine fremde match_uid
        // in der eigenen Buchführung.
        val paar = Leitungspaar()
        var gefragt = false
        val dienst = tisch(paar, matchUid = "m-1") { gefragt = true; angenommen() }

        gast(paar, gesuch(matchUid = "m-fremd")).starte()

        assertFalse(gefragt, "Die Platzvergabe wurde für eine fremde Partie gefragt.")
        assertTrue(dienst.gaeste.isEmpty())
    }

    @Test
    fun eineAblehnungUeberlebtDasEndeDerLeitung() {
        // Ein Host, der ablehnt, legt danach auf. Wer das Ende als Scheitern
        // wertet, zeigt „keine Verbindung" statt „der Tisch ist voll".
        val paar = Leitungspaar()
        tisch(paar) { Beitrittsantwort.Abgelehnt(Beitrittsablehnung.PARTIE_NIMMT_NICHT_MEHR_AUF) }
        val ablauf = gast(paar)

        ablauf.starte()
        paar.hostSeite.trenne(paar.hostSeite.gegenueber, "Der Tisch nimmt nicht mehr auf.")

        assertEquals(
            Beitrittsablauf.Stand.Abgelehnt(Beitrittsablehnung.PARTIE_NIMMT_NICHT_MEHR_AUF),
            ablauf.stand,
        )
        assertFalse(ablauf.leitungSteht)
    }

    // ── Fehlerbilder ────────────────────────────────────────────────────────

    @Test
    fun einEndeVorDerAntwortIstEinScheitern() {
        val paar = Leitungspaar()
        tisch(paar) { angenommen() }
        val ablauf = Beitrittsablauf(paar.gastSeite, hostAdresse, gesuch())

        // Der Gast hängt sich ein, verbindet aber nicht — die Leitung bricht
        // vor jeder Antwort weg.
        ablauf.starte()
        // Der Beitritt ging schon hinaus und wurde beantwortet; deshalb hier
        // ein eigener Ablauf ohne Antwort:
        val stiller = Leitungspaar()
        val ohneAntwort = Beitrittsablauf(stiller.gastSeite, Gegenstelle("host-1", "host-1"), gesuch())
        ohneAntwort.starte()
        stiller.gastSeite.trenne(stiller.gastSeite.gegenueber, "WLAN weg")

        val stand = assertIs<Beitrittsablauf.Stand.Gescheitert>(ohneAntwort.stand)
        assertTrue(stand.grund.contains("WLAN weg"), stand.grund)
        assertFalse(ohneAntwort.leitungSteht)
    }

    @Test
    fun einFingerabdruckfehlerBleibtImKlartextErklaerbar() {
        // ADR-001/T-065: Ein Angriff darf nicht wie eine Störung aussehen.
        val paar = Leitungspaar()
        val ablauf = Beitrittsablauf(paar.gastSeite, hostAdresse, gesuch())
        ablauf.starte()

        paar.gastSeite.melde(
            TransportEreignis.Fehlgeschlagen(
                hostAdresse,
                de.myhornets.rise1.transport.TransportFehler.FingerabdruckPasstNicht("AAAA", "BBBB"),
            ),
        )

        val stand = assertIs<Beitrittsablauf.Stand.Gescheitert>(ablauf.stand)
        assertTrue(stand.grund.contains("Tischcode"), stand.grund)
        assertTrue(stand.grund.contains("AAAA") && stand.grund.contains("BBBB"), stand.grund)
    }

    @Test
    fun einProtokollfehlerAufDerHostseiteTrenntDieLeitung() {
        val paar = Leitungspaar()
        val dienst = tisch(paar)
        paar.gastSeite.verbinde(hostAdresse)

        // Kein 'R','1' — das Gegenüber spricht etwas anderes.
        paar.gastSeite.sende(hostAdresse, ByteArray(16) { 0x41 })

        assertTrue(paar.hostSeite.verbundene.isEmpty(), "Nach einem Protokollfehler wird getrennt.")
        assertTrue(dienst.meldungen.any { it.contains("Protokollfehler") })
    }

    @Test
    fun einZweitesVerbundenLoestKeinenZweitenBeitrittAus() {
        // Nach einem Wiederaufbau gilt der Wiedereinstieg mit dem
        // `rejoin_token`, nicht ein zweiter Beitritt (TDD 9.1).
        val paar = Leitungspaar()
        var gesuche = 0
        tisch(paar) { gesuche++; angenommen() }
        val ablauf = gast(paar)

        ablauf.starte()
        paar.gastSeite.melde(TransportEreignis.Verbunden(Gegenstelle("host-1", "host-1")))

        assertEquals(1, gesuche)
        assertIs<Beitrittsablauf.Stand.Angenommen>(ablauf.stand)
    }

    @Test
    fun einEreignisEinerFremdenGegenstelleGehtDenGastNichtsAn() {
        val paar = Leitungspaar()
        val ablauf = gast(paar)
        ablauf.starte()
        val vorher = ablauf.stand

        ablauf.verarbeite(TransportEreignis.Getrennt(Gegenstelle("jemand-anders", "X"), "egal"))

        assertEquals(vorher, ablauf.stand)
    }

    @Test
    fun eineVerloreneLeitungLaesstDenSitzplatzStehen() {
        // TDD 9.2: Eine verlorene Leitung ist kein verlorener Platz.
        val paar = Leitungspaar()
        val dienst = tisch(paar) { angenommen(platz = 2) }
        gast(paar).starte()

        paar.gastSeite.trenne(paar.gastSeite.gegenueber, "Bildschirm aus")

        val platz = dienst.gaeste.single()
        assertEquals(2, platz.sitzplatz)
        assertFalse(platz.steht, "Die Leitung ist weg …")
        assertEquals("p-2", platz.participantUid, "… der Platz nicht.")
    }

    // ── Der Stand für die Oberfläche ────────────────────────────────────────

    @Test
    fun derSitzungsstandZeigtBeideSeiten() {
        val paar = Leitungspaar()
        val dienst = tisch(paar) { angenommen(platz = 1) }
        val ablauf = gast(paar)
        ablauf.starte()

        val hostStand = Sitzungsstand.vomHost(partie, "K7F2-9QXM-4TBH", dienst)
        assertEquals(Tischrolle.HOST, hostStand.rolle)
        assertEquals("K7F2-9QXM-4TBH", hostStand.tischcode)
        assertTrue(hostStand.leitungSteht)
        assertEquals(1, hostStand.gegenstellen.size)
        assertEquals("p-1", hostStand.gegenstellen.single().participantUid)

        val gastStand = Sitzungsstand.vomGast(partie, "K7F2-9QXM-4TBH", ablauf, Verbindungszustand.VERBUNDEN)
        assertEquals(Tischrolle.GAST, gastStand.rolle)
        assertTrue(gastStand.amTisch)
        assertEquals("p-1", gastStand.eigenerParticipantUid)
        assertEquals(1, gastStand.eigenerSitzplatz)
        assertEquals(Verbindungszustand.VERBUNDEN, gastStand.verbindungszustand)
        assertEquals("Am Tisch, auf Platz 1.", gastStand.meldung)
    }

    @Test
    fun derSitzungsstandErklaertJedeAblehnungInEinemSatz() {
        Beitrittsablehnung.entries.forEach { grund ->
            val paar = Leitungspaar()
            tisch(paar) { Beitrittsantwort.Abgelehnt(grund) }
            val ablauf = gast(paar)
            ablauf.starte()

            val stand = Sitzungsstand.vomGast(partie, null, ablauf)
            assertFalse(stand.amTisch, "$grund")
            assertTrue((stand.meldung?.length ?: 0) > 15, "Zu $grund steht nichts Brauchbares: ${stand.meldung}")
        }
    }

    // ── Das Format auf der Leitung ──────────────────────────────────────────

    @Test
    fun einBeitrittsgesuchUeberstehtDieLeitungUnveraendert() {
        val vorher = gesuch(name = "Änne Öß", wunschplatz = 7)

        val nachher = Sitzungsprotokoll.liesBeitrittsgesuch(Sitzungsprotokoll.kodiere(vorher))

        assertEquals(vorher, nachher)
    }

    @Test
    fun einGesuchOhneWunschplatzBleibtOhneWunschplatz() {
        val nachher = Sitzungsprotokoll.liesBeitrittsgesuch(Sitzungsprotokoll.kodiere(gesuch()))

        assertNull(nachher.wunschplatz)
    }

    @Test
    fun antwortUndAblehnungTragenVerschiedeneRahmentypen() {
        // Eine Ablehnung ist keine Antwort mit einem Flag darin.
        assertEquals(Rahmentyp.BEITRITT_ANTWORT, Sitzungsprotokoll.kodiere(angenommen()).typ)
        assertEquals(
            Rahmentyp.BEITRITT_ABLEHNUNG,
            Sitzungsprotokoll.kodiere(Beitrittsantwort.Abgelehnt(Beitrittsablehnung.TISCH_VOLL)).typ,
        )
        assertEquals(Rahmentyp.BEITRITT, Sitzungsprotokoll.kodiere(gesuch()).typ)
    }

    @Test
    fun jedeAntwortUeberstehtDieLeitung() {
        val angenommen = angenommen(platz = 4)
        assertEquals(angenommen, Sitzungsprotokoll.liesBeitrittsantwort(Sitzungsprotokoll.kodiere(angenommen)))

        Beitrittsablehnung.entries.forEach { grund ->
            val abgelehnt = Beitrittsantwort.Abgelehnt(grund)
            assertEquals(abgelehnt, Sitzungsprotokoll.liesBeitrittsantwort(Sitzungsprotokoll.kodiere(abgelehnt)))
        }
    }

    @Test
    fun dieKennungenDerAblehnungsgruendeLiegenFest() {
        // Sie stehen auf der Leitung. Verschöben sie sich beim Umsortieren des
        // `enum`, spräche eine neue Fassung stillschweigend etwas anderes.
        assertEquals(1, Sitzungsprotokoll.kennungVon(Beitrittsablehnung.PARTIE_UNBEKANNT))
        assertEquals(2, Sitzungsprotokoll.kennungVon(Beitrittsablehnung.PARTIE_NIMMT_NICHT_MEHR_AUF))
        assertEquals(3, Sitzungsprotokoll.kennungVon(Beitrittsablehnung.TISCH_VOLL))
        assertEquals(4, Sitzungsprotokoll.kennungVon(Beitrittsablehnung.PLATZ_BESETZT))
        assertEquals(5, Sitzungsprotokoll.kennungVon(Beitrittsablehnung.GERAET_SITZT_SCHON))
        assertEquals(8, Rahmentyp.BEITRITT.kennung)
        assertEquals(9, Rahmentyp.BEITRITT_ANTWORT.kennung)
        assertEquals(10, Rahmentyp.BEITRITT_ABLEHNUNG.kennung)
    }

    @Test
    fun eineUnbekannteAblehnungskennungWirdNichtGeraten() {
        val kaputt = Rahmen(Rahmentyp.BEITRITT_ABLEHNUNG, byteArrayOf(Sitzungsprotokoll.FASSUNG, 99))

        assertFailsWith<Protokollfehler> { Sitzungsprotokoll.liesBeitrittsantwort(kaputt) }
    }

    @Test
    fun ueberzaehligeBytesAmEndeWerdenAbgelehnt() {
        val echt = Sitzungsprotokoll.kodiere(gesuch())
        val zuViel = Rahmen(Rahmentyp.BEITRITT, echt.nutzlast + byteArrayOf(0))

        assertFailsWith<Protokollfehler> { Sitzungsprotokoll.liesBeitrittsgesuch(zuViel) }
    }

    @Test
    fun einFremderRahmentypIstKeineAntwort() {
        val fremd = Rahmen(Rahmentyp.EREIGNIS, byteArrayOf(Sitzungsprotokoll.FASSUNG))

        assertFailsWith<Protokollfehler> { Sitzungsprotokoll.liesBeitrittsantwort(fremd) }
        assertFailsWith<Protokollfehler> { Sitzungsprotokoll.liesBeitrittsgesuch(fremd) }
    }
}
