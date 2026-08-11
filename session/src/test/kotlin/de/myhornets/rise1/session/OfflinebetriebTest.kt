package de.myhornets.rise1.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-112 — Offline-Verhalten (TDD 6.4 / 9.1).
 *
 * ## Die Tests, um die es hier geht
 *
 * [keinZustandBeendetDiePartie] — TDD 9.1 in einer Schleife über alle
 * Verbindungszustände. Es ist die Regel, die im Code am leichtesten kaputtgeht:
 * Irgendwann schreibt jemand `if (offline) partieBeenden()`, weil es in dem
 * Moment vernünftig aussieht.
 *
 * [einZweitesAnbietenLegtKeinenZweitenEintragAn] — Idempotenz des
 * Ausgangsstapels. Ein doppelt gedrückter Knopf darf keine zweite Aktion
 * erzeugen.
 *
 * [einHerzschlagHoltNiemandenAusOffline] — die sicherheitsrelevante Kante: Aus
 * `offline` kommt man **nur** über einen bestandenen Handshake zurück (TDD 9.1).
 */
class OfflinebetriebTest {

    private var zaehler = 0
    private fun stapel() = Ausgangsstapel { "o-${++zaehler}" }

    // ── Der Ausgangsstapel (sync_outbox, TDD 4.5) ───────────────────────────

    @Test
    fun eineOfflineAktionLandetImStapel() {
        val s = stapel()
        val eintrag = s.lege("match_event", "e-1")

        assertEquals(Versandzustand.OFFEN, eintrag.zustand)
        assertEquals(0, eintrag.versuche)
        assertNull(eintrag.letzterFehler)
        assertEquals(listOf("e-1"), s.offene().map { it.entityUid })
    }

    @Test
    fun einZweitesAnbietenLegtKeinenZweitenEintragAn() {
        val s = stapel()
        val erst = s.lege("match_event", "e-1")
        val nochmal = s.lege("match_event", "e-1")

        assertEquals(erst, nochmal, "Derselbe Bezug, derselbe Eintrag.")
        assertEquals(1, s.alle().size)
    }

    @Test
    fun dieReihenfolgeIstDieDesHandelns() {
        // Was der Spieler zuerst getan hat, geht zuerst hinaus — alles andere
        // verdrehte die Kausalität, die die Lamport-Uhr festhalten soll.
        val s = stapel()
        listOf("e-1", "e-2", "e-3").forEach { s.lege("match_event", it) }
        s.gescheitert("e-1", "kein Netz")

        assertEquals(listOf("e-1", "e-2", "e-3"), s.offene().map { it.entityUid })
        assertEquals("e-1", s.naechste()?.entityUid, "Auch ein gescheiterter bleibt vorn.")
    }

    @Test
    fun einVersuchWirdGezaehltUndEinFehlerFestgehalten() {
        val s = stapel()
        s.lege("match_event", "e-1")
        assertEquals(1, s.unterwegs("e-1")?.versuche)

        val gescheitert = s.gescheitert("e-1", "Zeitüberschreitung")
        assertEquals(Versandzustand.GESCHEITERT, gescheitert?.zustand)
        assertEquals("Zeitüberschreitung", gescheitert?.letzterFehler)

        assertEquals(2, s.unterwegs("e-1")?.versuche, "Der zweite Versuch zählt weiter.")
        assertNull(s.unterwegs("e-1")?.letzterFehler ?: null)
    }

    @Test
    fun einGescheiterterVersandVerwirftNichts() {
        // Was der Spieler offline getan hat, hat er getan.
        val s = stapel()
        s.lege("match_event", "e-1")
        repeat(5) {
            s.unterwegs("e-1")
            s.gescheitert("e-1", "kein Netz")
        }
        assertEquals(listOf("e-1"), s.offene().map { it.entityUid })
        assertEquals(5, s.alle().single().versuche)
    }

    @Test
    fun eineZweiteBestaetigungAendertNichts() {
        // Genau so kommt sie im Betrieb an: Der Client hat gesendet, die
        // Bestätigung ging verloren, er hat noch einmal gesendet.
        val s = stapel()
        s.lege("match_event", "e-1")
        s.unterwegs("e-1")
        val erst = s.zugestellt("e-1")
        val nochmal = s.zugestellt("e-1")

        assertEquals(erst, nochmal)
        assertEquals(emptyList<Ausgangseintrag>(), s.offene())
    }

    @Test
    fun einBestaetigterEintragFaelltNichtZurueck() {
        val s = stapel()
        s.lege("match_event", "e-1")
        s.zugestellt("e-1")

        s.gescheitert("e-1", "spät eingetroffener Fehler")
        s.unterwegs("e-1")

        assertEquals(Versandzustand.ZUGESTELLT, s.alle().single().zustand)
        assertEquals(emptyList<Ausgangseintrag>(), s.offene())
    }

    @Test
    fun nachEinemAbbruchGehtAllesUnterwegseWiederHinaus() {
        // Was unterwegs war, ist im Zweifel nicht angekommen. Es noch einmal zu
        // schicken ist ungefährlich — der Log dedupt —, es nicht zu schicken
        // wäre ein verlorener Spielzug.
        val s = stapel()
        listOf("e-1", "e-2", "e-3").forEach { s.lege("match_event", it) }
        s.unterwegs("e-1")
        s.unterwegs("e-2")
        s.zugestellt("e-2")

        assertEquals(1, s.zuruecksetzenNachAbbruch())
        assertEquals(listOf("e-1", "e-3"), s.offene().map { it.entityUid })
    }

    @Test
    fun einUnbekannterBezugAendertNichts() {
        val s = stapel()
        assertNull(s.unterwegs("gibt-es-nicht"))
        assertNull(s.zugestellt("gibt-es-nicht"))
        assertNull(s.gescheitert("gibt-es-nicht", "x"))
        assertEquals(emptyList<Ausgangseintrag>(), s.alle())
    }

    // ── Die Anzeigelage (TDD 6.4) ───────────────────────────────────────────

    @Test
    fun verbundenUndAufDemStandIstNichtVeraltet() {
        val lage = Anzeigelage(Verbindungszustand.VERBUNDEN, eigenerStand = 9, zugesagterStand = 9, ausstehend = 0)
        assertFalse(lage.veraltet)
        assertTrue(lage.bedienbar)
    }

    @Test
    fun ohneVerbindungIstDerTischzustandVeraltet() {
        listOf(
            Verbindungszustand.WACKELIG,
            Verbindungszustand.OFFLINE,
            Verbindungszustand.WIEDEREINSTIEG,
            Verbindungszustand.GEGANGEN,
        ).forEach { zustand ->
            assertTrue(
                Anzeigelage(zustand, 9, 9, 0).veraltet,
                "$zustand müsste als veraltet gelten (TDD 6.4).",
            )
        }
    }

    @Test
    fun einVorsprungDesHostsMachtDenStandVeraltet() {
        assertTrue(Anzeigelage(Verbindungszustand.VERBUNDEN, 5, 9, 0).veraltet)
    }

    @Test
    fun eigeneUnbestaetigteAktionenMachenDenStandVeraltet() {
        // Der Fall, den man leicht vergisst: Wer offline einen Zug macht, sieht
        // ihn lokal — eingeordnet ist er erst, wenn der Host ihn bestätigt hat
        // (TDD 6.2).
        assertTrue(Anzeigelage(Verbindungszustand.VERBUNDEN, 9, 9, ausstehend = 1).veraltet)
    }

    @Test
    fun einClientOhneVerbindungBleibtBedienbar() {
        // TDD 6.4 lässt hier keinen Spielraum.
        Verbindungszustand.entries.forEach { zustand ->
            assertTrue(Anzeigelage(zustand, 0, null, 3).bedienbar, "$zustand")
        }
    }

    @Test
    fun keinZustandBeendetDiePartie() {
        // TDD 9.1: Nur explizite Ereignisse beenden eine Partie — kein Timeout,
        // kein Herzschlagausfall, keine Fehlerbedingung.
        Verbindungszustand.entries.forEach { zustand ->
            assertFalse(
                Anzeigelage(zustand, 0, 99, 5).partieBeendet,
                "$zustand hat die Partie beendet — das darf kein Zustand.",
            )
        }
    }

    // ── Die Kante zum Wiedereinstieg ────────────────────────────────────────

    @Test
    fun einHerzschlagHoltNiemandenAusOffline() {
        // Die sicherheitsrelevante Kante, hier noch einmal aus der Sicht des
        // Offline-Betriebs: Ein eintreffendes Lebenszeichen ist kein Nachweis.
        val a = Verbindungsautomat("p-1", Verbindungsschwellen(), startzeit = 0)
        a.zeitLaeuft(40_000)
        assertEquals(Verbindungszustand.OFFLINE, a.zustand)

        a.herzschlag(41_000)
        a.herzschlag(42_000)

        assertEquals(Verbindungszustand.OFFLINE, a.zustand, "Wiedereinstieg ist Authentifizierung (TDD 9.1).")
    }

    @Test
    fun dieWiederaufnahmeIstIdempotent() {
        // Der ganze Weg zurück, zweimal angestoßen: Am Ende steht genau eine
        // Anmeldung, ein Stand und ein Ereignis im Verlauf.
        var jetzt = 40_000L
        val a = Verbindungsautomat("p-1", Verbindungsschwellen(), startzeit = 0)
        a.zeitLaeuft(jetzt)

        val ablauf = Wiedereinstiegsablauf(
            matchUid = "m-1",
            eigenerParticipantUid = "p-1",
            eigenesGeraeteUid = "d-a",
            token = { "0123456789abcdef-token" },
            automat = a,
            uhr = { jetzt },
            startStand = 2,
        )

        ablauf.beginne()
        assertNull(ablauf.beginne(), "Ein zweites Gesuch, solange das erste läuft.")
        val antwort = Wiedereinstiegsantwort.Angenommen("s-1", null, bisSeq = 4)
        ablauf.antwort(antwort)
        ablauf.antwort(antwort)

        val delta = Aufholung.Delta("m-1", "p-1", 2, 4, listOf(ereignis(3), ereignis(4)))
        ablauf.delta(delta)
        ablauf.delta(delta)

        assertTrue(ablauf.angemeldet)
        assertEquals(4L, ablauf.stand)
        assertEquals(listOf(3L, 4L), ablauf.uebernommene().mapNotNull { it.seq })
        assertEquals(
            listOf(Sitzungsereignistypen.VERBINDUNG_ZURUECK),
            ablauf.meldungen().filterIsInstance<Verbindungsmeldung.Sitzungsereignis>().map { it.typ },
        )
    }

    @Test
    fun nachDerRueckkehrIstDerStandNichtMehrVeraltet() {
        // Das Zusammenspiel von T-108 und T-112: Erst das geprüfte Delta macht
        // aus einem veralteten Bild ein aktuelles.
        val vorher = Anzeigelage(Verbindungszustand.OFFLINE, eigenerStand = 2, zugesagterStand = 4, ausstehend = 0)
        assertTrue(vorher.veraltet)

        val nachher = Anzeigelage(Verbindungszustand.VERBUNDEN, eigenerStand = 4, zugesagterStand = 4, ausstehend = 0)
        assertFalse(nachher.veraltet)
    }
}
