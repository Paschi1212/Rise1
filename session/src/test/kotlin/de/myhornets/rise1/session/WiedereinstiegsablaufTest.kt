package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.Visibility
import de.myhornets.rise1.transport.Attrappennetz
import de.myhornets.rise1.transport.Gegenstelle
import de.myhornets.rise1.transport.Hostattrappe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-105 + T-108 — der Ablauf auf der Client-Seite.
 *
 * ## Der Test, um den es hier geht
 *
 * [erstNachEinemGeprueftenDeltaIstDerClientAngemeldet]. Es ist die Regel, für
 * die es [Wiedereinstiegsablauf] überhaupt gibt: Zwischen „der Host hat mich
 * wieder eingelassen" und „ich bin im normalen Sitzungsfluss" liegt der
 * verpasste Verlauf. Wer die Phase [Ablaufphase.AUFHOLEN] wegkürzt, meldet einen
 * Spieler an, der die letzten Züge nicht kennt — und dessen Anzeige dann etwas
 * behauptet, was am Tisch nicht gilt.
 *
 * Der zweite ist [einGescheitertesAufholenSchreibtKeineAblehnungInDenVerlauf].
 * Ein `rejoin_rejected` ist nach TDD 5.4 sicherheitsrelevant und für **alle**
 * sichtbar. Es zu schreiben, wenn niemand etwas abgelehnt hat, würde einem
 * Mitspieler am Tisch einen Angriffsversuch unterstellen.
 */
class WiedereinstiegsablaufTest {

    private val token = "0123456789abcdef-ein-langer-token"
    private val salz = "salz-m1-p1"

    private var jetzt = 0L

    private fun automat(startzeit: Long = 0L) =
        Verbindungsautomat("p-1", Verbindungsschwellen(), startzeit)

    private fun ablauf(
        automat: Verbindungsautomat,
        stand: Long = -1,
        token: String = this.token,
    ) = Wiedereinstiegsablauf(
        matchUid = "m-1",
        eigenerParticipantUid = "p-1",
        eigenesGeraeteUid = "d-a",
        token = { token },
        automat = automat,
        uhr = { jetzt },
        startStand = stand,
    )

    /** Bringt den Automaten nach `offline` — so, wie es TDD 9.2 vorsieht. */
    private fun nachOffline(a: Verbindungsautomat) {
        jetzt = 40_000
        a.zeitLaeuft(jetzt)
        check(a.zustand == Verbindungszustand.OFFLINE) { "Aufbau falsch: ${a.zustand}" }
    }

    private fun typen(meldungen: List<Verbindungsmeldung>) =
        meldungen.filterIsInstance<Verbindungsmeldung.Sitzungsereignis>().map { it.typ }

    // ── Der gute Weg ────────────────────────────────────────────────────────

    @Test
    fun erstNachEinemGeprueftenDeltaIstDerClientAngemeldet() {
        val a = automat()
        nachOffline(a)
        val ablauf = ablauf(a, stand = 2)

        val gesuch = ablauf.beginne()!!
        assertEquals(2L, gesuch.lastSeqSeen, "Der eigene Stand geht als last_seq_seen mit.")
        assertEquals(Verbindungszustand.WIEDEREINSTIEG, a.zustand)

        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", "s-alt", bisSeq = 5))
        assertEquals(Ablaufphase.AUFHOLEN, ablauf.phase)
        assertFalse(ablauf.angemeldet, "Handshake bestanden heißt noch nicht: auf dem Stand.")
        assertEquals(
            Verbindungszustand.WIEDEREINSTIEG,
            a.zustand,
            "Der Automat bleibt im Wartezimmer, bis das Delta geprüft ist.",
        )

        val delta = Aufholung.Delta("m-1", "p-1", 2, 5, listOf(ereignis(3), ereignis(4), ereignis(5)))
        val ergebnis = ablauf.delta(delta)

        assertTrue(ergebnis is Deltaergebnis.Angenommen)
        assertTrue(ablauf.angemeldet)
        assertEquals(Verbindungszustand.VERBUNDEN, a.zustand)
        assertEquals(5L, ablauf.stand)
        assertEquals(listOf(3L, 4L, 5L), ablauf.uebernommene().mapNotNull { it.seq })
        assertEquals(
            listOf(Sitzungsereignistypen.VERBINDUNG_ZURUECK),
            typen(ablauf.meldungen()),
            "Genau ein participant_reconnected, und keine zweite Trennung davor.",
        )
    }

    @Test
    fun derStandWaechstMitJedemAngenommenenDelta() {
        val a = automat()
        nachOffline(a)
        val ablauf = ablauf(a, stand = -1)

        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", null, bisSeq = 1))
        ablauf.delta(Aufholung.Delta("m-1", "p-1", -1, 1, listOf(ereignis(0), ereignis(1))))

        assertEquals(1L, ablauf.stand)
        assertEquals("s-1", ablauf.sitzungsUid)
    }

    // ── Die Ablehnung durch den Host ────────────────────────────────────────

    @Test
    fun eineAblehnungFuehrtZurueckNachOfflineUndInDenVerlauf() {
        val a = automat()
        nachOffline(a)
        val ablauf = ablauf(a)
        ablauf.beginne()

        val meldungen = ablauf.antwort(
            Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.NACHWEIS_FALSCH),
        )

        assertEquals(Verbindungszustand.OFFLINE, a.zustand)
        assertEquals(Ablaufphase.RUHT, ablauf.phase)
        assertEquals(Ablehnungsgrund.NACHWEIS_FALSCH, ablauf.letzteAblehnung)
        assertEquals(listOf(Sitzungsereignistypen.WIEDEREINSTIEG_ABGELEHNT), typen(meldungen))
    }

    @Test
    fun eineAblehnungErzeugtKeinZweitesParticipantDisconnected() {
        // Der Spieler war die ganze Zeit weg. Ein zweites
        // `participant_disconnected` würde im Verlauf eine Trennung behaupten,
        // die es nicht gab.
        val a = automat()
        jetzt = 40_000
        val bisOffline = typen(a.zeitLaeuft(jetzt))
        assertEquals(listOf(Sitzungsereignistypen.VERBINDUNG_VERLOREN), bisOffline)

        val ablauf = ablauf(a)
        ablauf.beginne()
        val meldungen = ablauf.antwort(Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.ZU_VIELE_VERSUCHE))

        assertFalse(
            typen(meldungen).contains(Sitzungsereignistypen.VERBINDUNG_VERLOREN),
            "Aus dem Wiedereinstieg heraus gibt es keinen neuen Verbindungsverlust.",
        )
    }

    @Test
    fun nachEinerAblehnungKannErneutVersuchtWerden() {
        val a = automat()
        nachOffline(a)
        val ablauf = ablauf(a)
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.NACHWEIS_FALSCH))

        jetzt += 10_000
        val zweites = ablauf.beginne()
        assertTrue(zweites != null, "Eine Ablehnung ist kein Endzustand.")
        assertEquals(Verbindungszustand.WIEDEREINSTIEG, a.zustand)
    }

    // ── Das gescheiterte Aufholen (T-108) ───────────────────────────────────

    @Test
    fun einGescheitertesAufholenSchreibtKeineAblehnungInDenVerlauf() {
        val a = automat()
        nachOffline(a)
        val ablauf = ablauf(a, stand = 2)
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", null, bisSeq = 9))

        // Der Host liefert ein Delta, das nicht anschließt.
        val ergebnis = ablauf.delta(Aufholung.Delta("m-1", "p-1", 5, 9, listOf(ereignis(6))))

        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.FALSCHER_ANSCHLUSS, 5),
            ergebnis,
        )
        assertFalse(ablauf.angemeldet)
        assertEquals(Verbindungszustand.OFFLINE, a.zustand)
        assertEquals(
            emptyList<String>(),
            typen(ablauf.meldungen()),
            "Kein rejoin_rejected — der Host hat nichts abgelehnt. Und kein " +
                "participant_disconnected — es gab keine neue Trennung.",
        )
    }

    @Test
    fun einAbgelehntesDeltaLaesstStandUndSitzungUnberuehrt() {
        val a = automat()
        nachOffline(a)
        val ablauf = ablauf(a, stand = 2)
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", null, bisSeq = 5))
        ablauf.delta(
            Aufholung.Delta(
                "m-1", "p-1", 2, 5,
                listOf(
                    ereignis(3),
                    ereignis(
                        4,
                        typ = "deal_key_packet",
                        visibility = Visibility.PRIVATE,
                        empfaenger = "p-2",
                    ),
                ),
            ),
        )

        assertEquals(2L, ablauf.stand, "Auch der gute Teil davor wird nicht übernommen.")
        assertEquals(emptyList<Long>(), ablauf.uebernommene().mapNotNull { it.seq })
        assertNull(ablauf.sitzungsUid)
        assertEquals(Deltafehler.FREMDES_PRIVATES, ablauf.letzterDeltafehler)
    }

    @Test
    fun einVerlangterSchnappschussLaesstDenClientOffline() {
        // Noch nicht umgesetzt (TDD 9.5, personalisierter Schnappschuss). Der
        // Ablauf bricht sauber ab, statt so zu tun, als wäre er auf dem Stand.
        val a = automat()
        nachOffline(a)
        val ablauf = ablauf(a)
        ablauf.beginne()
        ablauf.antwort(Wiedereinstiegsantwort.Angenommen("s-1", null, bisSeq = 900))

        ablauf.schnappschussNoetig()

        assertFalse(ablauf.angemeldet)
        assertEquals(Verbindungszustand.OFFLINE, a.zustand)
        assertEquals(emptyList<String>(), typen(ablauf.meldungen()))
    }

    // ── Reihenfolge und Wiederholung ────────────────────────────────────────

    @Test
    fun einZweitesGesuchWaehrendDesLaufendenGibtEsNicht() {
        // Sonst zählte jeder Wackler beim Host als weiterer Versuch und liefe
        // gegen die Ratenbegrenzung aus TDD 9.3.
        val a = automat()
        nachOffline(a)
        val ablauf = ablauf(a)

        assertTrue(ablauf.beginne() != null)
        assertNull(ablauf.beginne(), "Ein zweites Gesuch, solange das erste unterwegs ist.")
    }

    @Test
    fun einDeltaOhneHandshakeWirdNichtAngenommen() {
        val a = automat()
        nachOffline(a)
        val ablauf = ablauf(a, stand = 2)

        val ergebnis = ablauf.delta(Aufholung.Delta("m-1", "p-1", 2, 3, listOf(ereignis(3))))

        assertTrue(ergebnis is Deltaergebnis.Abgelehnt)
        assertFalse(ablauf.angemeldet)
        assertEquals(2L, ablauf.stand)
    }

    @Test
    fun nachDemVerlassenBeginntNichtsMehr() {
        val a = automat()
        a.verlassen(1_000)
        val ablauf = ablauf(a)
        assertNull(ablauf.beginne(), "GEGANGEN ist ein Endzustand (TDD 9.2).")
    }

    // ── Die ganze Kette gegen das Attrappennetz ─────────────────────────────

    @Test
    fun vomLeitungsverlustBisZumAngemeldetenClient() {
        // Der vollständige Weg aus TDD 9: Leitung weg, Leitung wieder da,
        // Handshake, Delta, angemeldet. Alles auf einer Uhr, ohne Netz.
        val netz = Attrappennetz(verzoegerungMillis = 10)
        val hostAdresse = Gegenstelle("d-host", "Tisch")
        val host = netz.melde(Hostattrappe(hostAdresse))

        val verbindung = Sitzungsverbindung(
            transport = netz.transportFuer("d-a"),
            host = hostAdresse,
            eigenerParticipantUid = "p-1",
            uhr = { netz.jetzt },
        )
        verbindung.starte()
        netz.laufeBis(10)
        assertTrue(verbindung.transportVerbunden)

        // Der Host schweigt — über wackelig nach offline.
        host.erreichbar = false
        netz.laufeBis(40_000)
        verbindung.takt()
        netz.laufeBis(40_030)
        assertEquals(Verbindungszustand.OFFLINE, verbindung.automat.zustand)
        assertFalse(verbindung.transportVerbunden, "Der Host war weg, als der Herzschlag ankam.")

        // Die Leitung kommt zurück. Die Sitzung nicht.
        host.erreichbar = true
        var t = 40_030L
        while (!verbindung.transportVerbunden && t < 400_000) {
            t += 500
            netz.laufeBis(t)
            verbindung.takt()
        }
        assertTrue(verbindung.transportVerbunden)
        assertEquals(Verbindungszustand.OFFLINE, verbindung.automat.zustand)
        assertTrue(verbindung.bereitFuerHandshake)

        // Jetzt erst der Handshake — geprüft vom echten Prüfer der Host-Seite.
        val nachschlag = object : Partienachschlag {
            override fun status(matchUid: String) = Partiestatus.ACTIVE
            override fun sitzplatz(matchUid: String, participantUid: String) =
                Sitzplatznachweis("p-1", RejoinPruefer.tokenHash(token, salz), salz)

            override fun eroeffneSitzung(matchUid: String, participantUid: String, deviceUid: String) = "s-7"
            override fun hoechsteSeq(matchUid: String) = 4L
        }
        val pruefer = RejoinPruefer(nachschlag, { netz.jetzt })
        val quelle = Listenquelle((0L..4L).map { ereignis(it) })
        val auswahl = Deltaauswahl(quelle)

        val ablauf = Wiedereinstiegsablauf(
            matchUid = "m-1",
            eigenerParticipantUid = "p-1",
            eigenesGeraeteUid = "d-a",
            token = { token },
            automat = verbindung.automat,
            uhr = { netz.jetzt },
            startStand = 1,
        )

        val gesuch = ablauf.beginne()!!
        val antwort = pruefer.pruefe(gesuch)
        ablauf.antwort(antwort)
        assertEquals(Ablaufphase.AUFHOLEN, ablauf.phase)

        val angenommen = antwort as Wiedereinstiegsantwort.Angenommen
        val aufholung = auswahl.fuer("m-1", "p-1", gesuch.lastSeqSeen, angenommen.bisSeq)
        val ergebnis = ablauf.delta(aufholung as Aufholung.Delta)

        assertTrue(ergebnis is Deltaergebnis.Angenommen)
        assertTrue(ablauf.angemeldet)
        assertEquals(Verbindungszustand.VERBUNDEN, verbindung.automat.zustand)
        assertEquals(listOf(2L, 3L, 4L), ablauf.uebernommene().mapNotNull { it.seq })
    }
}
