package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.EventClass
import de.myhornets.rise1.core.event.MatchEvent
import de.myhornets.rise1.core.event.Payload
import de.myhornets.rise1.core.event.Visibility

/**
 * Ereignisse für die Tests dieses Moduls.
 *
 * Bewusst **kein** Standardwert für [visibility] und [empfaenger] jenseits des
 * einfachsten Falls: Wer ein `PRIVATE`-Event bauen will, muss den Empfänger
 * nennen — dieselbe Kopplung, die [MatchEvent] erzwingt. Ein Baukasten, der die
 * Regel des geprüften Typs umgeht, würde die Prüfung aus dem Test heraushalten.
 */
internal fun ereignis(
    seq: Long?,
    typ: String = "turn_started",
    matchUid: String = "m-1",
    visibility: Visibility = Visibility.PUBLIC,
    empfaenger: String? = null,
    eventClass: EventClass = EventClass.STATE,
    akteur: String? = null,
    geraet: String = "d-host",
): MatchEvent {
    val nutzlast: Payload = when (visibility) {
        Visibility.PUBLIC -> Payload.Klartext("{}")
        else -> Payload.Chiffrat(byteArrayOf(1, 2, 3), "aes-gcm-256")
    }
    return MatchEvent(
        eventUid = "e-${typ}-${seq ?: "offen"}-${empfaenger ?: "alle"}",
        matchUid = matchUid,
        seq = seq,
        originDeviceUid = geraet,
        originSeq = (seq ?: 0L).coerceAtLeast(0L),
        lamportClock = (seq ?: 0L).coerceAtLeast(0L),
        occurredAt = 1_000L,
        recordedAt = if (seq == null) null else 1_000L,
        type = typ,
        eventClass = eventClass,
        actorParticipantUid = akteur,
        targetParticipantUid = null,
        payload = nutzlast,
        payloadSchemaVersion = 1,
        visibility = visibility,
        recipientParticipantUid = empfaenger,
    )
}

/** Eine Quelle über einer festen Liste — die Ablage, ohne Datenbank. */
internal class Listenquelle(private val alle: List<MatchEvent>) : Ereignisquelle {

    /** Womit die Quelle zuletzt gerufen wurde. Für Behauptungen über den Bereich. */
    var letzterAufruf: Triple<String, Long, Long>? = null
        private set

    override fun ab(matchUid: String, nachSeq: Long, bisSeq: Long): List<MatchEvent> {
        letzterAufruf = Triple(matchUid, nachSeq, bisSeq)
        return alle
            .filter { it.matchUid == matchUid }
            .filter { e -> e.seq?.let { it > nachSeq && it <= bisSeq } == true }
            .sortedBy { it.seq }
    }
}
