package de.myhornets.rise1.store

/**
 * T-102 — Sitzungen eröffnen und ablösen (TDD 9.3).
 *
 * ## Warum das nicht im DAO steht
 *
 * Weil eine Ablösung **zwei** Schreibvorgänge ist, die zusammen gehören oder
 * gar nicht: Die alte Sitzung endet mit `end_reason = superseded`, die neue
 * entsteht. Bräche es dazwischen ab, gäbe es entweder zwei laufende Sitzungen
 * — das verhindert der Trigger, und der Handshake schlüge fehl — oder gar
 * keine, und der Spieler käme nicht zurück.
 *
 * Dieselbe Begründung wie bei [MatchEventLog]: Es gibt genau einen Weg, auf dem
 * sich diese Tabelle verändert.
 *
 * ## Was hier nicht passiert
 *
 * **Keine Prüfung des Nachweises.** Ob jemand zurückdarf, entscheidet
 * `RejoinPruefer` in `:session` (TDD 9.3) — diese Klasse führt aus, was dort
 * entschieden wurde. Eine Ablage, die selbst über Zugang entscheidet, wäre eine
 * zweite Stelle mit derselben Aufgabe.
 *
 * **Kein Event.** Dass jemand zurück ist, gehört als `participant_reconnected`
 * ins Log — das schreibt der Aufrufer über [MatchEventLog]. Diese Klasse und
 * das Log stehen nebeneinander, nicht ineinander: Die Sitzung ist Betriebs-
 * wissen des Hosts, das Event ist Verlauf.
 */
class Sitzungsverwaltung(private val datenbank: RiseDatabase) {

    private val dao get() = datenbank.participantSessionDao()

    /** Die laufende Sitzung dieses Sitzplatzes, oder `null`. */
    fun laufende(participantUid: String): ParticipantSessionEntity? = dao.laufende(participantUid)

    /**
     * Eröffnet eine Sitzung und löst eine etwaige laufende ab.
     *
     * **Idempotent nach TDD 9.3:** Meldet sich dasselbe Gerät erneut, bleibt die
     * bestehende Sitzung bestehen und wird zurückgegeben. Es löst sich nicht
     * selbst ab — sonst füllte sich die Tabelle bei jedem Funkloch mit
     * Ablösungen, die nie stattgefunden haben.
     *
     * @param sitzungsUid die Kennung der **neuen** Sitzung. Wird sie nicht
     *   gebraucht, weil dasselbe Gerät schon sitzt, verfällt sie ungenutzt.
     * @return die nun laufende Sitzung und, falls es eine gab, die abgelöste.
     */
    fun eroeffne(
        sitzungsUid: String,
        participantUid: String,
        deviceUid: String,
        jetzt: Long,
    ): Abloesung = datenbank.runInTransaction<Abloesung> {
        val bisherige = dao.laufende(participantUid)

        if (bisherige != null && bisherige.deviceUid == deviceUid) {
            return@runInTransaction Abloesung(laufend = bisherige, abgeloest = null)
        }

        if (bisherige != null) {
            val betroffen = dao.beende(bisherige.sessionUid, jetzt, EndReason.SUPERSEDED)
            check(betroffen == 1) {
                "Die laufende Sitzung ${bisherige.sessionUid} ließ sich nicht beenden. " +
                    "Ohne ihr Ende darf die neue nicht entstehen (TDD 4.5)."
            }
        }

        dao.eroeffne(
            ParticipantSessionEntity(
                sessionUid = sitzungsUid,
                participantUid = participantUid,
                deviceUid = deviceUid,
                startedAt = jetzt,
                endedAt = null,
                endReason = null,
                isCurrent = true,
                // -1 heißt: dieser Sitzung ging noch nichts hinaus. Nicht 0 —
                // das wäre die erste seq und damit eine Zustellung, die es nicht gab.
                lastDeliveredSeq = -1,
            ),
        )
        Abloesung(
            laufend = dao.nach(sitzungsUid) ?: error("Die eben angelegte Sitzung ist nicht da."),
            abgeloest = bisherige?.sessionUid,
        )
    }

    /**
     * Beendet eine Sitzung mit Grund.
     *
     * @return `true`, wenn sie lief. `false` heißt: Sie war schon beendet —
     *   kein Fehler, sondern der Normalfall bei einer doppelt eintreffenden
     *   Nachricht.
     */
    fun beende(sitzungsUid: String, jetzt: Long, grund: String): Boolean {
        require(grund in EndReason.ALLE) {
            "Unbekannter end_reason `$grund`. Erlaubt sind ${EndReason.ALLE} (TDD 4.5)."
        }
        return dao.beende(sitzungsUid, jetzt, grund) == 1
    }

    /**
     * Schreibt fest, bis wohin dieser Sitzung zugestellt wurde (TDD 4.5).
     *
     * Nur vorwärts — das erledigt die Abfrage selbst. Ein Rückschritt wäre die
     * stille Aufforderung, alles noch einmal zu schicken.
     */
    fun merkeZugestellt(sitzungsUid: String, seq: Long): Boolean =
        dao.merkeZugestellt(sitzungsUid, seq) == 1

    /** Was bei einer Eröffnung geschah — die Ablösung. */
    data class Abloesung(
        val laufend: ParticipantSessionEntity,
        /** Die abgelöste Sitzung, oder `null` — dann gab es keine oder es war dasselbe Gerät. */
        val abgeloest: String?,
    )
}
