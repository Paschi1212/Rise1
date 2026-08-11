package de.myhornets.rise1.store

import android.content.Context
import android.database.SQLException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-025b/e/f — das Event-Log an einer echten Datenbank.
 *
 * ## Warum eine Datei und keine In-Memory-Datenbank
 *
 * Die Zusicherungen dieses Slices stecken in **Triggern**, und die legt Room
 * nicht an — das tut [RiseCallback], der an [RiseStore.open] hängt. Eine mit
 * `inMemoryDatabaseBuilder` gebaute Datenbank hätte ihn nicht, und dieser Test
 * wäre grün, ohne die Zusicherungen je berührt zu haben. Geprüft wird deshalb
 * genau der Weg, den die App nimmt.
 */
@RunWith(AndroidJUnit4::class)
class EventLogTest {

    private lateinit var kontext: Context
    private lateinit var datenbank: RiseDatabase
    private lateinit var eventLog: MatchEventLog

    private val partie = "m-1"

    /**
     * Ein Typ aus dem Vokabular (TDD 5.4), den dieser Slice nicht auswertet.
     *
     * Gebraucht wird er für die nicht-öffentlichen Formen: `match_created` ist
     * nach TDD 5.4 immer PUBLIC, taugt also nicht als Träger eines Chiffrats.
     */
    private val NICHT_ANGEWANDT = "note_added"

    @Before
    fun oeffnen() {
        kontext = ApplicationProvider.getApplicationContext()
        kontext.deleteDatabase(RiseStore.DATABASE_NAME)
        datenbank = RiseStore.open(kontext)
        eventLog = RiseStore.eventLog(datenbank)
    }

    @After
    fun schliessen() {
        datenbank.close()
        kontext.deleteDatabase(RiseStore.DATABASE_NAME)
    }

    // ── Die Zusicherungen sind überhaupt da ─────────────────────────────────

    @Test
    fun dieDreiTriggerSindAngelegt() {
        // Ohne diesen Test wären alle folgenden grün, weil nichts sie
        // abgewiesen hat — dieselbe Familie von Fehlern wie `NO-SOURCE`.
        val gefunden = mutableSetOf<String>()
        datenbank.openHelper.writableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='trigger'")
            .use { c -> while (c.moveToNext()) gefunden += c.getString(0) }
        assertTrue(
            "Es fehlen Trigger. Gefunden: $gefunden, erwartet: ${EventLogSql.TRIGGER_NAMEN}. " +
                "Ohne sie sind append-only und die Trennung von PUBLIC/PRIVATE nur Absicht.",
            gefunden.containsAll(EventLogSql.TRIGGER_NAMEN),
        )
    }

    // ── Append-only (TDD 5.1) ───────────────────────────────────────────────

    @Test
    fun einEventLaesstSichNichtLoeschen() {
        eventLog.anhaengen(event(0, EventTypen.MATCH_CREATED))
        try {
            datenbank.openHelper.writableDatabase.execSQL("DELETE FROM match_event")
            fail("DELETE auf match_event war erlaubt. Das Log ist append-only (TDD 5.1).")
        } catch (erwartet: SQLException) {
            assertTrue(
                "Der Trigger hat abgewiesen, aber ohne die Begründung: ${erwartet.message}",
                erwartet.message.orEmpty().contains("append-only"),
            )
        }
        assertEquals(1, datenbank.eventLogDao().anzahl(partie))
    }

    @Test
    fun derInhaltEinesEventsLaesstSichNichtAendern() {
        eventLog.anhaengen(event(0, EventTypen.MATCH_CREATED))
        listOf(
            "UPDATE match_event SET type = 'life_changed'",
            "UPDATE match_event SET visibility = 'PRIVATE'",
            "UPDATE match_event SET payload_json = '{\"x\":1}'",
            "UPDATE match_event SET actor_participant_uid = 'p-fremd'",
            "UPDATE match_event SET origin_device_uid = 'd-fremd'",
        ).forEach { anweisung ->
            try {
                datenbank.openHelper.writableDatabase.execSQL(anweisung)
                fail("Erlaubt, obwohl der Inhalt unveränderlich ist (TDD 5.1): $anweisung")
            } catch (erwartet: SQLException) {
                assertTrue(erwartet.message.orEmpty().contains("unveraenderlich"))
            }
        }
    }

    @Test
    fun derHostDarfSeqUndRecordedAtNachtragen() {
        // „Append-only" heißt nicht „kein UPDATE": seq und recorded_at trägt
        // der Host bei der Bestätigung nach (TDD 5.2 / 6.2). Ein Trigger, der
        // auch das verböte, würde den Entwurf brechen statt ihn zu schützen.
        eventLog.anhaengen(event(0, EventTypen.MATCH_CREATED).copy(seq = null, recordedAt = null))
        datenbank.openHelper.writableDatabase
            .execSQL("UPDATE match_event SET seq = 0, recorded_at = 5000")
        assertEquals(0L, datenbank.eventLogDao().hoechsteSeq(partie))
    }

    @Test
    fun eineVergebeneSeqLaesstSichNichtUmschreiben() {
        eventLog.anhaengen(event(7, EventTypen.MATCH_CREATED))
        try {
            datenbank.openHelper.writableDatabase.execSQL("UPDATE match_event SET seq = 8")
            fail("Eine vergebene seq war überschreibbar — damit ließe sich die Geschichte umschreiben.")
        } catch (erwartet: SQLException) {
            assertTrue(erwartet.message.orEmpty().contains("unveraenderlich"))
        }
    }

    @Test
    fun einUndoDarfMarkieren() {
        // TDD 5.3: Ein Undo löscht nichts, es markiert.
        eventLog.anhaengen(event(0, EventTypen.MATCH_CREATED))
        datenbank.openHelper.writableDatabase
            .execSQL("UPDATE match_event SET is_undone = 1, undone_by_event_uid = 'e-undo'")
        assertTrue(datenbank.eventLogDao().nachUid("e-0")!!.isUndone)
    }

    // ── Dedup (TDD 5.2) ─────────────────────────────────────────────────────

    @Test
    fun dasselbeEventZweimalErgibtEineZeile() {
        val e = event(0, EventTypen.MATCH_CREATED)
        assertTrue("Das erste Anhängen muss neu sein.", eventLog.anhaengen(e))
        assertFalse(
            "Das zweite Anhängen wurde als neu gemeldet. Damit würde die Projektion " +
                "ein zweites Mal fortgeschrieben (TDD 5.2).",
            eventLog.anhaengen(e.copy(eventUid = "e-anders")),
        )
        assertEquals(1, datenbank.eventLogDao().anzahl(partie))
    }

    @Test
    fun einAnderesGeraetMitGleicherOriginSeqIstKeinDuplikat() {
        eventLog.anhaengen(event(0, EventTypen.MATCH_CREATED))
        val fremd = event(1, EventTypen.MATCH_CREATED)
            .copy(eventUid = "e-fremd", originDeviceUid = "d-2", originSeq = 0)
        assertTrue(eventLog.anhaengen(fremd))
        assertEquals(2, datenbank.eventLogDao().anzahl(partie))
    }

    // ── PUBLIC und PRIVATE bleiben getrennt (TDD 5.2 / 7.3) ─────────────────

    @Test
    fun einPublicEventMitChiffratWirdAbgewiesen() {
        verlangeAbweisung(
            event(0, EventTypen.MATCH_CREATED).copy(
                payloadCiphertext = byteArrayOf(1, 2, 3),
                encScheme = "AES-GCM",
            ),
        )
    }

    @Test
    fun einNichtOeffentlichesEventMitKlartextWirdAbgewiesen() {
        verlangeAbweisung(
            event(0, EventTypen.MATCH_CREATED).copy(
                visibility = Visibilities.PLAYER_ONLY,
                payloadJson = "{\"a\":1}",
            ),
        )
        verlangeAbweisung(
            event(1, EventTypen.MATCH_CREATED).copy(
                eventUid = "e-b",
                visibility = Visibilities.PRIVATE,
                recipientParticipantUid = "p-2",
                payloadJson = "{\"a\":1}",
            ),
        )
    }

    @Test
    fun einEmpfaengerAusserhalbVonPrivateWirdAbgewiesen() {
        verlangeAbweisung(
            event(0, EventTypen.MATCH_CREATED).copy(recipientParticipantUid = "p-2"),
        )
    }

    @Test
    fun einPrivateEventOhneEmpfaengerWirdAbgewiesen() {
        verlangeAbweisung(
            event(0, EventTypen.MATCH_CREATED).copy(
                visibility = Visibilities.PRIVATE,
                payloadCiphertext = byteArrayOf(9),
                encScheme = "AES-GCM",
            ),
        )
    }

    @Test
    fun dieVierZulaessigenFormenGehenDurch() {
        // Ohne diesen Test wäre nur belegt, dass der Wächter abweist — nicht,
        // dass er das Richtige durchlässt.
        assertTrue(eventLog.anhaengen(event(0, EventTypen.MATCH_CREATED)))
        assertTrue(
            eventLog.anhaengen(
                event(1, EventTypen.MATCH_CREATED).copy(eventUid = "e-b", payloadJson = "{\"a\":1}"),
            ),
        )
        assertTrue(
            eventLog.anhaengen(
                event(2, NICHT_ANGEWANDT).copy(
                    eventUid = "e-c",
                    visibility = Visibilities.PLAYER_ONLY,
                    payloadCiphertext = byteArrayOf(1),
                    encScheme = "AES-GCM",
                ),
            ),
        )
        assertTrue(
            eventLog.anhaengen(
                event(3, NICHT_ANGEWANDT).copy(
                    eventUid = "e-d",
                    visibility = Visibilities.PRIVATE,
                    recipientParticipantUid = "p-2",
                    payloadCiphertext = byteArrayOf(1),
                    encScheme = "AES-GCM",
                ),
            ),
        )
        assertEquals(4, datenbank.eventLogDao().anzahl(partie))
    }

    // ── Fortschreibung (TDD 5.1) ────────────────────────────────────────────

    @Test
    fun eineRundeEntstehtAusdemLog() {
        runde()
        val zustand = datenbank.projectionDao()
        assertNotNull("match_state fehlt.", zustand.partie(partie))
        assertEquals(2, zustand.teilnehmerDerPartie(partie).size)
        assertTrue(zustand.teilnehmer("p-1")!!.isRevealed)
        assertFalse(zustand.teilnehmer("p-2")!!.isRevealed)
        assertEquals(3L, zustand.partie(partie)!!.lastAppliedSeq)
    }

    @Test
    fun dieZugzaehlungBleibtInDiesemSliceUnberuehrt() {
        runde()
        val zustand = datenbank.projectionDao().partie(partie)!!
        assertEquals(0, zustand.turnNumber)
        assertNull(zustand.activeParticipantUid)
    }

    @Test
    fun einUnbekannterTypZerstoertDiePartieNicht() {
        // TDD 5.5 — der ganze Sinn der Regel.
        runde()
        assertTrue(
            eventLog.anhaengen(
                event(4, "etwas_aus_version_2").copy(eventUid = "e-fremd", actorParticipantUid = "p-2"),
            ),
        )
        assertEquals(2, datenbank.projectionDao().teilnehmerDerPartie(partie).size)
        assertEquals(4L, datenbank.projectionDao().partie(partie)!!.lastAppliedSeq)
    }

    @Test
    fun einAufdeckenOhneBeitretenVeraendertNichts() {
        eventLog.anhaengen(event(0, EventTypen.MATCH_CREATED))
        eventLog.anhaengen(event(1, EventTypen.IDENTITY_REVEALED).copy(actorParticipantUid = "p-9"))
        assertTrue(datenbank.projectionDao().teilnehmerDerPartie(partie).isEmpty())
    }

    // ── Der Neuaufbau (T-025f) ──────────────────────────────────────────────

    @Test
    fun derNeuaufbauErgibtDenselbenZustand() {
        runde()
        val vorher = zustandsAbbild()

        datenbank.projectionDao().verwirfTeilnehmer(partie)
        datenbank.projectionDao().verwirfZaehler(partie)
        datenbank.projectionDao().verwirfPartie(partie)
        assertTrue(
            "Das Verwerfen hat nichts entfernt — der Vergleich danach wäre wertlos.",
            datenbank.projectionDao().teilnehmerDerPartie(partie).isEmpty(),
        )

        eventLog.neuAufbauen(partie)
        assertEquals(
            "Der Neuaufbau aus dem Log ergibt einen anderen Zustand als die Fortschreibung. " +
                "Eine der beiden Seiten ist falsch (TDD 5.1).",
            vorher,
            zustandsAbbild(),
        )
    }

    @Test
    fun derNeuaufbauLaesstSichWiederholen() {
        runde()
        eventLog.neuAufbauen(partie)
        val einmal = zustandsAbbild()
        eventLog.neuAufbauen(partie)
        assertEquals(einmal, zustandsAbbild())
    }

    @Test
    fun dasLogUeberlebtDenNeuaufbau() {
        runde()
        val vorher = datenbank.eventLogDao().anzahl(partie)
        eventLog.neuAufbauen(partie)
        assertEquals("Der Neuaufbau hat Events angefasst.", vorher, datenbank.eventLogDao().anzahl(partie))
    }

    @Test
    fun einVerspaetetesEventFuehrtZumNeuaufbau() {
        // TDD 6.5. Der inkrementelle Weg kann nicht rückwärts rechnen; er
        // erkennt den Fall und baut neu auf. Geprüft wird das Ergebnis, nicht
        // der Weg dorthin.
        eventLog.anhaengen(event(0, EventTypen.MATCH_CREATED))
        eventLog.anhaengen(event(3, EventTypen.PARTICIPANT_JOINED).copy(actorParticipantUid = "p-2"))
        eventLog.anhaengen(event(1, EventTypen.PARTICIPANT_JOINED).copy(actorParticipantUid = "p-1"))
        eventLog.anhaengen(event(2, EventTypen.IDENTITY_REVEALED).copy(actorParticipantUid = "p-1"))

        assertEquals(2, datenbank.projectionDao().teilnehmerDerPartie(partie).size)
        assertTrue(
            "Nach dem verspäteten Beitreten muss das Aufdecken wirken — sonst hängt der " +
                "Zustand an der Reihenfolge des Eintreffens statt an seq.",
            datenbank.projectionDao().teilnehmer("p-1")!!.isRevealed,
        )
    }

    // ── Hilfen ──────────────────────────────────────────────────────────────

    private fun runde() {
        eventLog.anhaengen(event(0, EventTypen.MATCH_CREATED))
        eventLog.anhaengen(event(1, EventTypen.PARTICIPANT_JOINED).copy(actorParticipantUid = "p-1"))
        eventLog.anhaengen(event(2, EventTypen.PARTICIPANT_JOINED).copy(actorParticipantUid = "p-2"))
        eventLog.anhaengen(event(3, EventTypen.IDENTITY_REVEALED).copy(actorParticipantUid = "p-1"))
    }

    /** Alles, was die Projektion ausmacht, als vergleichbarer Text. */
    private fun zustandsAbbild(): String {
        val dao = datenbank.projectionDao()
        return buildString {
            appendLine(dao.partie(partie).toString())
            dao.teilnehmerDerPartie(partie).forEach { appendLine(it.toString()) }
            dao.zaehlerDerPartie(partie).forEach { appendLine(it.toString()) }
        }
    }

    private fun verlangeAbweisung(e: MatchEventEntity) {
        try {
            eventLog.anhaengen(e)
            fail(
                "Ein Event mit vermischten Nutzdaten wurde angenommen: visibility=${e.visibility}, " +
                    "json=${e.payloadJson != null}, chiffrat=${e.payloadCiphertext != null}, " +
                    "empfaenger=${e.recipientParticipantUid}. PUBLIC und PRIVATE dürfen nicht " +
                    "vermischt werden (TDD 5.2 / 7.3).",
            )
        } catch (erwartet: SQLException) {
            assertTrue(
                "Abgewiesen, aber ohne die Begründung: ${erwartet.message}",
                erwartet.message.orEmpty().contains("vermischt"),
            )
        }
        assertEquals(0, datenbank.eventLogDao().anzahl(partie))
    }

    private fun event(seq: Long, typ: String) = MatchEventEntity(
        eventUid = "e-$seq",
        matchUid = partie,
        seq = seq,
        originDeviceUid = "d-1",
        originSeq = seq,
        lamportClock = seq,
        occurredAt = 1_000L + seq,
        recordedAt = 1_100L + seq,
        type = typ,
        eventClass = EventClasses.STATE,
        actorParticipantUid = null,
        targetParticipantUid = null,
        payloadJson = null,
        payloadCiphertext = null,
        encScheme = null,
        payloadSchemaVersion = 1,
        visibility = Visibilities.PUBLIC,
        recipientParticipantUid = null,
        isUndone = false,
        undoneByEventUid = null,
        hasConflict = false,
    )
}
