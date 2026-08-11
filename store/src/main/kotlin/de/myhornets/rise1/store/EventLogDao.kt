package de.myhornets.rise1.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * T-025b — Zugriff auf `match_event`.
 *
 * ## Was hier fehlt, und warum das der Punkt ist
 *
 * Kein `@Delete`. Kein `@Update`. Keine `@Query("DELETE …")`. Das Log ist
 * append-only (TDD 5.1).
 *
 * Diese Auslassung ist allerdings **keine Zusage** — die nächste Ergänzung
 * dieses Interface hebt sie auf, und niemandem fiele es auf. Die Zusage steht
 * in den Triggern aus [EventLogSql]: Sie liegen zwischen jedem Schreibweg und
 * der Datei, auch zwischen einem, der noch nicht geschrieben ist.
 *
 * ## Dedup
 *
 * [anhaengen] fügt mit `IGNORE` ein. Trifft dasselbe Event zweimal ein — über
 * zwei Wege, nach einem Reconnect, durch einen Wiederholungsversuch —, greift
 * der eindeutige Index auf `(origin_device_uid, origin_seq)` und die zweite
 * Zeile entsteht gar nicht erst. Der Rückgabewert sagt, was passiert ist.
 */
@Dao
interface EventLogDao {

    /**
     * Hängt ein Event an.
     *
     * @return die rowid, oder `-1`, wenn das Event schon vorhanden war. Der
     *   Aufrufer braucht die Unterscheidung: Nur ein wirklich neues Event darf
     *   die Projektion fortschreiben, sonst zählte ein doppelt eingetroffenes
     *   Ereignis zweimal.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun anhaengen(event: MatchEventEntity): Long

    /**
     * Die bestätigten Events einer Partie in kanonischer Reihenfolge.
     *
     * Sortiert nach `seq` — der **vom Host vergebenen** Ordnung (TDD 6.2), nicht
     * nach `occurred_at`, das nur zur Anzeige taugt (TDD 6.6). Events ohne `seq`
     * fehlen hier bewusst: Ihre Position steht noch nicht fest.
     */
    @Query(
        "SELECT * FROM match_event WHERE match_uid = :matchUid AND seq IS NOT NULL ORDER BY seq",
    )
    fun bestaetigte(matchUid: String): List<MatchEventEntity>

    /** Alles, auch Unbestätigtes — für Anzeige und Fehlersuche, nicht für die Faltung. */
    @Query(
        "SELECT * FROM match_event WHERE match_uid = :matchUid " +
            "ORDER BY seq IS NULL, seq, lamport_clock, event_uid",
    )
    fun alle(matchUid: String): List<MatchEventEntity>

    @Query("SELECT * FROM match_event WHERE event_uid = :eventUid")
    fun nachUid(eventUid: String): MatchEventEntity?

    @Query("SELECT COUNT(*) FROM match_event WHERE match_uid = :matchUid")
    fun anzahl(matchUid: String): Int

    /** Die höchste bestätigte `seq` — `null`, solange keine vergeben wurde. */
    @Query("SELECT MAX(seq) FROM match_event WHERE match_uid = :matchUid")
    fun hoechsteSeq(matchUid: String): Long?

    /**
     * Die höchste `origin_seq` **dieses Geräts** — über alle Partien hinweg.
     *
     * Bewusst nicht je Partie: `(origin_device_uid, origin_seq)` ist der
     * Dedup-Schlüssel und eindeutig über die ganze Tabelle. Zählte man je
     * Partie, kollidierte die zweite Partie desselben Geräts mit der ersten,
     * und das Einfügen liefe still ins Leere — `INSERT OR IGNORE` sagt nichts.
     */
    @Query("SELECT MAX(origin_seq) FROM match_event WHERE origin_device_uid = :deviceUid")
    fun hoechsteOriginSeq(deviceUid: String): Long?

    /**
     * Markiert ein Event als aufgehoben (TDD 5.3).
     *
     * **Das ist die einzige veränderende Anweisung in diesem DAO, und sie steht
     * hier mit Absicht so eng gefasst wie möglich.** Sie rührt genau die zwei
     * Felder an, die TDD 5.3 dafür vorsieht, und nur an einem noch nicht
     * aufgehobenen Event. Der Inhalt bleibt unberührt — dafür sorgt zusätzlich
     * der Trigger `match_event_unveraenderlich`, der diese Anweisung
     * durchlässt und jede weitergehende abweist.
     *
     * Ein Undo **löscht nichts**. Die Zeile bleibt vollständig stehen; nur die
     * Faltung überspringt sie ab jetzt.
     *
     * @return Anzahl der geänderten Zeilen: 1 bei Erfolg, 0 wenn das Event
     *   fehlt oder bereits aufgehoben war.
     */
    @Query(
        "UPDATE match_event SET is_undone = 1, undone_by_event_uid = :durchEventUid " +
            "WHERE event_uid = :zielEventUid AND is_undone = 0",
    )
    fun markiereAufgehoben(zielEventUid: String, durchEventUid: String): Int

    /** Die nicht aufgehobenen Events eines Typs — für das Zurücknehmen einer Runde. */
    @Query(
        "SELECT * FROM match_event WHERE match_uid = :matchUid AND type = :typ " +
            "AND is_undone = 0 ORDER BY seq",
    )
    fun nachTyp(matchUid: String, typ: String): List<MatchEventEntity>
}

/**
 * T-025c — Zugriff auf die Projektionstabellen.
 *
 * Hier ist `DELETE` ausdrücklich **erlaubt**, und das ist kein Widerspruch zum
 * Log: Eine Projektion ist abgeleitet. Sie zu verwerfen kostet nichts, weil sie
 * sich aus dem Log wieder herstellen lässt (TDD 5.1) — genau das tut
 * [MatchEventLog.neuAufbauen].
 */
@Dao
interface ProjectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setzeTeilnehmer(zustand: ParticipantStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setzeZaehler(zaehler: ParticipantCounterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun setzePartie(zustand: MatchStateEntity)

    @Query("SELECT * FROM participant_state WHERE participant_uid = :participantUid")
    fun teilnehmer(participantUid: String): ParticipantStateEntity?

    @Query("SELECT * FROM participant_state WHERE match_uid = :matchUid ORDER BY participant_uid")
    fun teilnehmerDerPartie(matchUid: String): List<ParticipantStateEntity>

    @Query(
        "SELECT * FROM participant_counter WHERE match_uid = :matchUid " +
            "ORDER BY participant_uid, counter_key",
    )
    fun zaehlerDerPartie(matchUid: String): List<ParticipantCounterEntity>

    @Query("SELECT * FROM match_state WHERE match_uid = :matchUid")
    fun partie(matchUid: String): MatchStateEntity?

    @Query("DELETE FROM participant_state WHERE match_uid = :matchUid")
    fun verwirfTeilnehmer(matchUid: String)

    @Query("DELETE FROM participant_counter WHERE match_uid = :matchUid")
    fun verwirfZaehler(matchUid: String)

    @Query("DELETE FROM match_state WHERE match_uid = :matchUid")
    fun verwirfPartie(matchUid: String)
}
