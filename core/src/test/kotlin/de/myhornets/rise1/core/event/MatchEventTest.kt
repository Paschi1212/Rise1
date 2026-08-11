package de.myhornets.rise1.core.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-025a — die Zusagen, die an einem einzelnen Event zu sehen sind.
 *
 * Der Schwerpunkt liegt auf der Trennung von PUBLIC und nicht-PUBLIC. Sie ist
 * die einzige Zusage hier, deren Bruch nicht auffällt, sondern **wirkt**: Ein
 * Klartext-Payload in einem PRIVATE-Event wäre für den Host lesbar, und niemand
 * würde es merken, weil die Anzeige unverändert aussähe.
 */
class MatchEventTest {

    // ── Die Trennung ─────────────────────────────────────────────────────────

    @Test
    fun einPublicEventTraegtKlartext() {
        val e = event(visibility = Visibility.PUBLIC, payload = Payload.Klartext("""{"a":1}"""))
        assertTrue(e.payload.istKlartext)
    }

    @Test
    fun einPublicEventDarfKeinChiffratTragen() {
        assertFailsWith<IllegalArgumentException> {
            event(visibility = Visibility.PUBLIC, payload = chiffrat())
        }
    }

    @Test
    fun einPlayerOnlyEventDarfKeinenKlartextTragen() {
        assertFailsWith<IllegalArgumentException> {
            event(visibility = Visibility.PLAYER_ONLY, payload = Payload.Klartext("""{"a":1}"""))
        }
    }

    @Test
    fun einPrivateEventDarfKeinenKlartextTragen() {
        assertFailsWith<IllegalArgumentException> {
            event(
                visibility = Visibility.PRIVATE,
                payload = Payload.Klartext("""{"a":1}"""),
                recipientParticipantUid = "p-1",
            )
        }
    }

    @Test
    fun beidesZugleichIstNichtDarstellbar() {
        // Kein Test im üblichen Sinn, sondern die Festschreibung einer Form:
        // `Payload` ist versiegelt, ein Wert ist entweder Klartext oder
        // Chiffrat oder leer. Ein Event mit beidem gäbe es nur, wenn hier
        // zwei nullbare Felder stünden — genau das ist vermieden.
        val alle: List<Payload> = listOf(Payload.Klartext("{}"), chiffrat(), Payload.Leer)
        assertEquals(3, alle.size)
        assertEquals(1, alle.count { it.istKlartext })
    }

    @Test
    fun einLeererPayloadIstBeiJederSichtbarkeitErlaubt() {
        Visibility.entries.forEach { sichtbarkeit ->
            val e = event(
                visibility = sichtbarkeit,
                payload = Payload.Leer,
                recipientParticipantUid = if (sichtbarkeit == Visibility.PRIVATE) "p-1" else null,
            )
            assertEquals(Payload.Leer, e.payload)
        }
    }

    // ── Empfänger ────────────────────────────────────────────────────────────

    @Test
    fun einPrivateEventBrauchtEinenEmpfaenger() {
        assertFailsWith<IllegalArgumentException> {
            event(visibility = Visibility.PRIVATE, payload = chiffrat(), recipientParticipantUid = null)
        }
    }

    @Test
    fun einNichtPrivateEventDarfKeinenEmpfaengerTragen() {
        assertFailsWith<IllegalArgumentException> {
            event(visibility = Visibility.PUBLIC, recipientParticipantUid = "p-1")
        }
        assertFailsWith<IllegalArgumentException> {
            event(
                visibility = Visibility.PLAYER_ONLY,
                payload = chiffrat(),
                recipientParticipantUid = "p-1",
            )
        }
    }

    // ── Dedup-Schlüssel und Pflichtfelder ────────────────────────────────────

    @Test
    fun derDedupSchluesselIstPflicht() {
        assertFailsWith<IllegalArgumentException> { event(originDeviceUid = "") }
        assertFailsWith<IllegalArgumentException> { event(originSeq = -1) }
    }

    @Test
    fun leereKennungenWerdenAbgelehnt() {
        assertFailsWith<IllegalArgumentException> { event(eventUid = "") }
        assertFailsWith<IllegalArgumentException> { event(matchUid = "") }
        assertFailsWith<IllegalArgumentException> { event(type = "  ") }
    }

    // ── seq, recorded_at, Undo ───────────────────────────────────────────────

    @Test
    fun ohneSeqGibtEsKeinRecordedAt() {
        assertFailsWith<IllegalArgumentException> { event(seq = null, recordedAt = 1_000L) }
    }

    @Test
    fun einUnbestaetigtesEventIstNichtZustandswirksam() {
        assertFalse(event(seq = null, recordedAt = null).istZustandswirksam)
    }

    @Test
    fun einAufgehobenesEventIstNichtZustandswirksam() {
        val e = event(isUndone = true, undoneByEventUid = "e-99")
        assertFalse(e.istZustandswirksam)
    }

    @Test
    fun einAufgehobenesEventNenntSeinenAufheber() {
        assertFailsWith<IllegalArgumentException> { event(isUndone = true, undoneByEventUid = null) }
        assertFailsWith<IllegalArgumentException> { event(isUndone = false, undoneByEventUid = "e-99") }
    }

    @Test
    fun einSessionEventIstNichtZustandswirksam() {
        val e = event(
            type = EventType.PARTICIPANT_DISCONNECTED.wert,
            eventClass = EventClass.SESSION,
        )
        assertFalse(e.istZustandswirksam)
    }

    @Test
    fun einBestaetigtesStateEventIstZustandswirksam() {
        assertTrue(event().istZustandswirksam)
    }

    // ── Unbekannte Typen ─────────────────────────────────────────────────────

    @Test
    fun einUnbekannterTypIstDarstellbar() {
        // TDD 5.5: Ein Gerät mit älterer App muss ein fremdes Event speichern
        // können, ohne daran kaputtzugehen.
        val e = event(type = "etwas_aus_version_2")
        assertNull(e.typKennung)
        assertTrue(e.passtZumVokabular(), "Ein fremdes Event ist nicht deshalb falsch, weil es fremd ist.")
    }

    @Test
    fun einBekannterTypMitFalscherKlasseFaelltAuf() {
        val e = event(type = EventType.IDENTITY_REVEALED.wert, eventClass = EventClass.ANNOTATION)
        assertFalse(e.passtZumVokabular())
    }

    @Test
    fun einBekannterTypMitUnzulaessigerSichtbarkeitFaelltAuf() {
        val e = event(
            type = EventType.IDENTITY_REVEALED.wert,
            visibility = Visibility.PLAYER_ONLY,
            payload = chiffrat(),
        )
        assertFalse(e.passtZumVokabular())
    }

    @Test
    fun einBekannterTypMitPassenderKlasseUndSichtbarkeitIstInOrdnung() {
        assertTrue(event().passtZumVokabular())
    }

    // ── Chiffrat-Gleichheit ──────────────────────────────────────────────────

    @Test
    fun zweiGleicheChiffrateSindGleich() {
        // Ohne eigenes equals verglichen sich ByteArrays über die Referenz.
        // Das fiele erst auf, wenn ein Test zwei Wege vergleicht — also spät.
        assertEquals(chiffrat(), chiffrat())
        assertEquals(chiffrat().hashCode(), chiffrat().hashCode())
    }

    @Test
    fun einChiffratZeigtSeineBytesNichtImText() {
        assertFalse(chiffrat().toString().contains("42"))
    }

    // ── Hilfen ───────────────────────────────────────────────────────────────

    private fun chiffrat() = Payload.Chiffrat(byteArrayOf(42, 7, 13), "AES-GCM")

    private fun event(
        eventUid: String = "e-1",
        matchUid: String = "m-1",
        seq: Long? = 1L,
        originDeviceUid: String = "d-1",
        originSeq: Long = 1L,
        lamportClock: Long = 1L,
        occurredAt: Long = 1_000L,
        recordedAt: Long? = 1_001L,
        type: String = EventType.IDENTITY_REVEALED.wert,
        eventClass: EventClass = EventClass.STATE,
        actorParticipantUid: String? = "p-1",
        targetParticipantUid: String? = null,
        payload: Payload = Payload.Leer,
        payloadSchemaVersion: Int = 1,
        visibility: Visibility = Visibility.PUBLIC,
        recipientParticipantUid: String? = null,
        isUndone: Boolean = false,
        undoneByEventUid: String? = null,
        hasConflict: Boolean = false,
    ) = MatchEvent(
        eventUid = eventUid,
        matchUid = matchUid,
        seq = seq,
        originDeviceUid = originDeviceUid,
        originSeq = originSeq,
        lamportClock = lamportClock,
        occurredAt = occurredAt,
        recordedAt = recordedAt,
        type = type,
        eventClass = eventClass,
        actorParticipantUid = actorParticipantUid,
        targetParticipantUid = targetParticipantUid,
        payload = payload,
        payloadSchemaVersion = payloadSchemaVersion,
        visibility = visibility,
        recipientParticipantUid = recipientParticipantUid,
        isUndone = isUndone,
        undoneByEventUid = undoneByEventUid,
        hasConflict = hasConflict,
    )
}
