package de.myhornets.rise1.projection

import de.myhornets.rise1.core.event.EventClass
import de.myhornets.rise1.core.event.EventType
import de.myhornets.rise1.core.event.MatchEvent
import de.myhornets.rise1.core.event.Payload
import de.myhornets.rise1.core.event.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-025d — die Faltung.
 *
 * Die Tests zerfallen in drei Gruppen, und die mittlere ist die wichtigste:
 *
 * 1. **Wirkung** — was ein ausgewerteter Typ verändert.
 * 2. **Determinismus** — dass dieselben Events immer dasselbe ergeben. Ohne das
 *    ist der Neuaufbau aus `T-025f` kein Nachweis, sondern ein Vergleich zweier
 *    beliebiger Ergebnisse.
 * 3. **Was übersprungen wird** — und dass es gezählt statt verschwiegen wird.
 */
class MatchFoldTest {

    private val partie = "m-1"

    // ── Wirkung ──────────────────────────────────────────────────────────────

    @Test
    fun eineLeereFolgeErgibtEineLeereProjektion() {
        val p = MatchFold.falte(partie, emptyList())
        assertFalse(p.partieAngelegt, "Ohne match_created ist nichts angelegt.")
        assertTrue(p.participants.isEmpty())
        assertEquals(0L, p.matchState.lastAppliedSeq)
    }

    @Test
    fun matchCreatedLegtDiePartieAn() {
        val p = MatchFold.falte(partie, listOf(angelegt(seq = 0)))
        assertTrue(p.partieAngelegt)
        assertEquals(partie, p.matchState.matchUid)
    }

    @Test
    fun participantJoinedLegtEinenTeilnehmerAn() {
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1")))
        assertEquals(setOf("p-1"), p.participants.keys)
        assertEquals(ParticipantState("p-1", life = 0, lastAppliedSeq = 1L), p.participants["p-1"])
    }

    @Test
    fun identityRevealedDecktGenauEinenTeilnehmerAuf() {
        val p = MatchFold.falte(
            partie,
            listOf(angelegt(0), beigetreten(1, "p-1"), beigetreten(2, "p-2"), aufgedeckt(3, "p-1")),
        )
        assertTrue(p.participants.getValue("p-1").isRevealed)
        assertFalse(p.participants.getValue("p-2").isRevealed)
        assertEquals(3L, p.participants.getValue("p-1").lastAppliedSeq)
        assertEquals(2L, p.participants.getValue("p-2").lastAppliedSeq)
    }

    @Test
    fun einZweitesBeitretenUeberschreibtNichts() {
        val p = MatchFold.falte(
            partie,
            listOf(angelegt(0), beigetreten(1, "p-1"), aufgedeckt(2, "p-1"), beigetreten(3, "p-1")),
        )
        assertTrue(
            p.participants.getValue("p-1").isRevealed,
            "Ein wiederholtes participant_joined hat einen bereits gefalteten Zustand gelöscht.",
        )
    }

    @Test
    fun ohneZugEreignisseIstDieZugzaehlungNull() {
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1")))
        assertEquals(0, p.matchState.turnNumber)
        assertEquals(null, p.matchState.activeParticipantUid)
    }

    // ── S3: Zugzählung (D-003) ───────────────────────────────────────────────

    @Test
    fun einBegonnenerZugZaehltUndSetztDenAktivenSitzplatz() {
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1"), zugBegonnen(2, "p-1")))
        assertEquals(1, p.matchState.turnNumber)
        assertEquals("p-1", p.matchState.activeParticipantUid)
    }

    @Test
    fun dreiZuegeErgebenZugnummerDrei() {
        val p = MatchFold.falte(
            partie,
            listOf(
                angelegt(0), beigetreten(1, "p-1"), beigetreten(2, "p-2"),
                zugBegonnen(3, "p-1"), zugBeendet(4), zugBegonnen(5, "p-2"), zugBeendet(6),
                zugBegonnen(7, "p-1"),
            ),
        )
        assertEquals(3, p.matchState.turnNumber)
        assertEquals("p-1", p.matchState.activeParticipantUid)
    }

    @Test
    fun einBeendeterZugAendertDieZugnummerNicht() {
        // Sie zählt **begonnene** Züge. Beim Beenden mitzuzählen hieße, denselben
        // Zug zweimal zu zählen.
        val p = MatchFold.falte(
            partie,
            listOf(angelegt(0), beigetreten(1, "p-1"), zugBegonnen(2, "p-1"), zugBeendet(3)),
        )
        assertEquals(1, p.matchState.turnNumber)
        assertEquals(null, p.matchState.activeParticipantUid)
    }

    @Test
    fun einZugFuerEinenUnbekanntenSitzplatzZaehltNicht() {
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1"), zugBegonnen(2, "p-9")))
        assertEquals(0, p.matchState.turnNumber)
        assertEquals(1, p.uebersprungen[Uebersprungsgrund.UNBEKANNTER_TEILNEHMER])
    }

    // ── S3: Ausscheiden ──────────────────────────────────────────────────────

    @Test
    fun ausscheidenMarkiertGenauEinenSitzplatz() {
        val p = MatchFold.falte(
            partie,
            listOf(angelegt(0), beigetreten(1, "p-1"), beigetreten(2, "p-2"), ausgeschieden(3, "p-1")),
        )
        assertTrue(p.participants.getValue("p-1").isEliminated)
        assertFalse(p.participants.getValue("p-2").isEliminated)
    }

    @Test
    fun verlassenWirktWieAusscheiden() {
        // TDD 5.4 unterscheidet die Typen; für den Zustand sind sie dasselbe.
        val p = MatchFold.falte(
            partie,
            listOf(angelegt(0), beigetreten(1, "p-1"), basis(2, EventType.PARTICIPANT_LEFT, "p-1")),
        )
        assertTrue(p.participants.getValue("p-1").isEliminated)
    }

    @Test
    fun werAusscheidetIstNichtMehrAmZug() {
        val p = MatchFold.falte(
            partie,
            listOf(angelegt(0), beigetreten(1, "p-1"), zugBegonnen(2, "p-1"), ausgeschieden(3, "p-1")),
        )
        assertEquals(null, p.matchState.activeParticipantUid)
        assertEquals(1, p.matchState.turnNumber, "Die gezählten Züge bleiben gezählt.")
    }

    @Test
    fun einAufgehobenerZugZaehltNicht() {
        val zurueck = zugBegonnen(2, "p-1").copy(isUndone = true, undoneByEventUid = "e-undo")
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1"), zurueck))
        assertEquals(0, p.matchState.turnNumber)
    }

    // ── Determinismus ────────────────────────────────────────────────────────

    @Test
    fun dieReihenfolgeDesEintreffensIstEgal() {
        val events = listOf(angelegt(0), beigetreten(1, "p-1"), beigetreten(2, "p-2"), aufgedeckt(3, "p-2"))
        val geordnet = MatchFold.falte(partie, events)
        val verdreht = MatchFold.falte(partie, events.reversed())
        val gemischt = MatchFold.falte(partie, listOf(events[2], events[0], events[3], events[1]))
        assertEquals(geordnet, verdreht)
        assertEquals(geordnet, gemischt)
    }

    @Test
    fun zweimalFaltenErgibtDasselbe() {
        val events = listOf(angelegt(0), beigetreten(1, "p-1"), aufgedeckt(2, "p-1"))
        assertEquals(MatchFold.falte(partie, events), MatchFold.falte(partie, events))
    }

    @Test
    fun sortiertWirdNachSeqUndNichtNachDerWanduhr() {
        // occurred_at ist die Uhr des Erzeugers und taugt nur zur Anzeige
        // (TDD 6.6). Hier läuft sie absichtlich rückwärts.
        val events = listOf(
            angelegt(0).copy(occurredAt = 9_000L),
            beigetreten(1, "p-1").copy(occurredAt = 8_000L),
            aufgedeckt(2, "p-1").copy(occurredAt = 7_000L),
        )
        val p = MatchFold.falte(partie, events)
        assertTrue(p.participants.getValue("p-1").isRevealed)
    }

    @Test
    fun zweiEventsMitDerselbenSeqSindEinFehler() {
        val doppelt = listOf(angelegt(0), beigetreten(1, "p-1"), beigetreten(1, "p-2"))
        val fehler = assertFailsWith<IllegalArgumentException> { MatchFold.falte(partie, doppelt) }
        assertTrue(fehler.message!!.contains("seq"))
    }

    @Test
    fun eventsEinerAnderenPartieSindEinFehler() {
        val fremd = angelegt(0).copy(matchUid = "m-2")
        assertFailsWith<IllegalArgumentException> { MatchFold.falte(partie, listOf(fremd)) }
    }

    // ── Was übersprungen wird ────────────────────────────────────────────────

    @Test
    fun einUnbestaetigtesEventVeraendertNichts() {
        val offen = aufgedeckt(3, "p-1").copy(seq = null, recordedAt = null)
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1"), offen))
        assertFalse(p.participants.getValue("p-1").isRevealed)
        assertEquals(1, p.uebersprungen[Uebersprungsgrund.NICHT_BESTAETIGT])
    }

    @Test
    fun einAufgehobenesEventVeraendertNichts() {
        val zurueck = aufgedeckt(2, "p-1").copy(isUndone = true, undoneByEventUid = "e-undo")
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1"), zurueck))
        assertFalse(p.participants.getValue("p-1").isRevealed)
        assertEquals(1, p.uebersprungen[Uebersprungsgrund.AUFGEHOBEN])
    }

    @Test
    fun einSessionEventVeraendertNichts() {
        val sitzung = MatchEvent(
            eventUid = "e-s", matchUid = partie, seq = 2L,
            originDeviceUid = "d-1", originSeq = 2L, lamportClock = 2L,
            occurredAt = 2_000L, recordedAt = 2_001L,
            type = EventType.PARTICIPANT_DISCONNECTED.wert, eventClass = EventClass.SESSION,
            actorParticipantUid = "p-1", targetParticipantUid = null,
            payload = Payload.Leer, payloadSchemaVersion = 1,
            visibility = Visibility.PUBLIC, recipientParticipantUid = null,
        )
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1"), sitzung))
        assertEquals(1, p.uebersprungen[Uebersprungsgrund.KEINE_ZUSTANDSKLASSE])
        assertEquals(1, p.participants.size)
    }

    @Test
    fun einUnbekannterTypZerstoertDiePartieNicht() {
        // TDD 5.5 — der ganze Sinn der Regel.
        val fremd = beigetreten(2, "p-2").copy(type = "etwas_aus_version_2")
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1"), fremd))
        assertEquals(setOf("p-1"), p.participants.keys)
        assertEquals(1, p.uebersprungen[Uebersprungsgrund.UNBEKANNTER_TYP])
        assertEquals(2L, p.matchState.lastAppliedSeq, "Auch ein fremdes Event ist gesehen worden.")
    }

    @Test
    fun einBekannterAberNochNichtAusgewerteterTypWirdGezaehlt() {
        // `life_changed` trägt seine Wirkung nach TDD 5.4 in den Nutzdaten
        // („Delta und Absolutwert"). Solange die Faltung keine Nutzdaten liest,
        // ist es bekannt, aber wirkungslos — und wird gezählt.
        val leben = beigetreten(2, "p-2").copy(type = EventType.LIFE_CHANGED.wert)
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1"), leben))
        assertEquals(1, p.uebersprungen[Uebersprungsgrund.NOCH_NICHT_ANGEWANDT])
    }

    @Test
    fun einAufdeckenOhneBeitretenWirdNichtStillGeglaettet() {
        val p = MatchFold.falte(partie, listOf(angelegt(0), aufgedeckt(1, "p-9")))
        assertTrue(p.participants.isEmpty())
        assertEquals(1, p.uebersprungen[Uebersprungsgrund.UNBEKANNTER_TEILNEHMER])
    }

    @Test
    fun einBeitretenOhneAkteurIstUnvollstaendig() {
        val ohne = beigetreten(1, "p-1").copy(actorParticipantUid = null)
        val p = MatchFold.falte(partie, listOf(angelegt(0), ohne))
        assertTrue(p.participants.isEmpty())
        assertEquals(1, p.uebersprungen[Uebersprungsgrund.UNVOLLSTAENDIG])
    }

    @Test
    fun lastAppliedSeqSteigtAuchUeberUebersprungeneEvents() {
        val fremd = beigetreten(5, "p-2").copy(type = "etwas_aus_version_2")
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1"), fremd))
        assertEquals(5L, p.matchState.lastAppliedSeq)
    }

    @Test
    fun ohneUebersprungeneEventsIstDieListeLeer() {
        val p = MatchFold.falte(partie, listOf(angelegt(0), beigetreten(1, "p-1")))
        assertTrue(p.uebersprungen.isEmpty())
    }

    // ── Hilfen ───────────────────────────────────────────────────────────────

    private fun basis(
        seq: Long,
        typ: EventType,
        akteur: String?,
    ) = MatchEvent(
        eventUid = "e-$seq",
        matchUid = partie,
        seq = seq,
        originDeviceUid = "d-1",
        originSeq = seq,
        lamportClock = seq,
        occurredAt = 1_000L + seq,
        recordedAt = 1_100L + seq,
        type = typ.wert,
        eventClass = EventClass.STATE,
        actorParticipantUid = akteur,
        targetParticipantUid = null,
        payload = Payload.Leer,
        payloadSchemaVersion = 1,
        visibility = Visibility.PUBLIC,
        recipientParticipantUid = null,
    )

    private fun angelegt(seq: Long) = basis(seq, EventType.MATCH_CREATED, akteur = null)
    private fun beigetreten(seq: Long, uid: String) = basis(seq, EventType.PARTICIPANT_JOINED, uid)
    private fun aufgedeckt(seq: Long, uid: String) = basis(seq, EventType.IDENTITY_REVEALED, uid)
    private fun zugBegonnen(seq: Long, uid: String) = basis(seq, EventType.TURN_STARTED, uid)
    private fun zugBeendet(seq: Long) = basis(seq, EventType.TURN_ENDED, akteur = null)
    private fun ausgeschieden(seq: Long, uid: String) = basis(seq, EventType.PARTICIPANT_ELIMINATED, uid)
}
