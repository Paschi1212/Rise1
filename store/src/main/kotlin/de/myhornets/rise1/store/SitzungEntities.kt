package de.myhornets.rise1.store

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * T-102 — `participant_session` nach TDD 4.5.
 *
 * ## Was diese Tabelle ist
 *
 * **Sitzplatz ⇄ Gerät auf Zeit.** TDD 9.1: *„Ein Sitzplatz gehört einem
 * Spieler, nicht einem Gerät. Die Bindung an die Person ist dauerhaft, die an
 * das Gerät ist eine Sitzung und darf wechseln."* Diese Tabelle ist die zweite
 * Hälfte dieses Satzes.
 *
 * ## Was sie nicht ist
 *
 * **Kein Spielzustand.** TDD 9.1: *„Verbindungszustand ist kein Spielzustand."*
 * Eine Sitzung endet, ein Sitzplatz bleibt. Nichts in dieser Datei kann eine
 * Partie beenden, einen Sitzplatz freigeben oder eine Identität berühren.
 *
 * **Kein Ersatz für das Log.** Dass jemand zurückgekehrt ist, steht als
 * `participant_reconnected` im Event-Log (TDD 9.3). Diese Tabelle sagt nur, wer
 * gerade an welchem Gerät sitzt — sie ist Betriebswissen des Hosts, kein
 * Verlauf.
 */
object EndReason {
    /** Ein neueres Gerät hat übernommen (TDD 9.3). */
    const val SUPERSEDED = "superseded"

    /** Der Spieler hat den Tisch verlassen — eine bewusste Handlung. */
    const val LEFT = "left"

    /** Die Partie ist vorbei. */
    const val MATCH_ENDED = "match_ended"

    /** Zurückgezogen, etwa nach einer Wiederzulassung durch Menschen (TDD 9.6). */
    const val REVOKED = "revoked"

    val ALLE = listOf(SUPERSEDED, LEFT, MATCH_ENDED, REVOKED)
}

@Entity(
    tableName = "participant_session",
    foreignKeys = [
        ForeignKey(
            entity = MatchParticipantEntity::class,
            parentColumns = ["participant_uid"],
            childColumns = ["participant_uid"],
        ),
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["device_uid"],
            childColumns = ["device_uid"],
        ),
    ],
    indices = [
        Index(value = ["participant_uid"], name = "index_participant_session_participant_uid"),
        Index(value = ["device_uid"], name = "index_participant_session_device_uid"),
    ],
)
data class ParticipantSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_uid") val sessionUid: String,
    @ColumnInfo(name = "participant_uid") val participantUid: String,
    @ColumnInfo(name = "device_uid") val deviceUid: String,

    @ColumnInfo(name = "started_at") val startedAt: Long,
    /** Nullbar: Eine laufende Sitzung hat kein Ende. */
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    /** Nullbar: Einer aus [EndReason], gesetzt genau dann, wenn [endedAt] gesetzt ist. */
    @ColumnInfo(name = "end_reason") val endReason: String?,

    /**
     * Die laufende Sitzung dieses Sitzplatzes.
     *
     * Höchstens eine je Teilnehmer — das ist keine Konvention, sondern ein
     * Trigger (siehe [SitzungSql]). Zwei aktive Sitzungen hießen, dass zwei
     * Geräte dieselbe Identität empfangen dürften.
     */
    @ColumnInfo(name = "is_current") val isCurrent: Boolean,

    /**
     * Bis wohin dieser Sitzung zugestellt wurde (TDD 4.5).
     *
     * Grundlage des Aufholens: `-1` heißt, es ging noch nichts hinaus. Der Wert
     * gehört zur **Sitzung** und nicht zum Gerät — TDD 9.5: *„Der Snapshot geht
     * an die Sitzung, die den Handshake bestanden hat."*
     */
    @ColumnInfo(name = "last_delivered_seq") val lastDeliveredSeq: Long,
)

/**
 * Das SQL, das Room nicht selbst erzeugt — dieselbe Aufgabe wie [EventLogSql].
 *
 * ## Warum ein Trigger und kein Index
 *
 * „Höchstens eine aktive Sitzung je Teilnehmer" wäre ein **partieller**
 * eindeutiger Index (`WHERE is_current = 1`). Room kennt so einen nicht, und
 * seine Schemaprüfung vergleicht die Indizes der Datei mit denen der Entities —
 * ein Index, den Room nicht kennt, ist beim nächsten Öffnen ein Fehler über
 * einen Hash. Trigger prüft Room nicht; sie sind der Weg, den dieses Projekt
 * seit `T-025b` geht.
 */
object SitzungSql {

    // Spaltenreihenfolge wie von Room erzeugt — sonst schlägt die Schemaprüfung
    // beim nächsten Öffnen fehl, und zwar mit einer Meldung über den Hash statt
    // über die Spalte.
    const val TABELLE_PARTICIPANT_SESSION: String =
        "CREATE TABLE IF NOT EXISTS `participant_session` (" +
            "`session_uid` TEXT NOT NULL, `participant_uid` TEXT NOT NULL, " +
            "`device_uid` TEXT NOT NULL, `started_at` INTEGER NOT NULL, " +
            "`ended_at` INTEGER, `end_reason` TEXT, `is_current` INTEGER NOT NULL, " +
            "`last_delivered_seq` INTEGER NOT NULL, " +
            "PRIMARY KEY(`session_uid`), " +
            "FOREIGN KEY(`participant_uid`) REFERENCES `match_participant`(`participant_uid`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION, " +
            "FOREIGN KEY(`device_uid`) REFERENCES `device`(`device_uid`) " +
            "ON UPDATE NO ACTION ON DELETE NO ACTION)"

    const val INDEX_PARTICIPANT: String =
        "CREATE INDEX IF NOT EXISTS `index_participant_session_participant_uid` " +
            "ON `participant_session` (`participant_uid`)"

    const val INDEX_DEVICE: String =
        "CREATE INDEX IF NOT EXISTS `index_participant_session_device_uid` " +
            "ON `participant_session` (`device_uid`)"

    /**
     * Höchstens eine laufende Sitzung je Teilnehmer (TDD 4.5).
     *
     * Beim Einfügen **und** beim Aktualisieren: Wer die alte Sitzung nicht
     * beendet, bekommt die neue nicht. Das ist die Zusicherung, auf der die
     * Ablösung aus TDD 9.3 steht — ohne sie wäre `end_reason = superseded` eine
     * Absichtserklärung.
     */
    const val TRIGGER_EINE_AKTIVE_EINFUEGEN: String =
        "CREATE TRIGGER IF NOT EXISTS `participant_session_eine_aktive_einfuegen` " +
            "BEFORE INSERT ON `participant_session` FOR EACH ROW " +
            "WHEN NEW.`is_current` = 1 AND EXISTS (" +
            "SELECT 1 FROM `participant_session` WHERE `participant_uid` = NEW.`participant_uid` " +
            "AND `is_current` = 1) " +
            "BEGIN SELECT RAISE(ABORT, " +
            "'Höchstens eine laufende Sitzung je Teilnehmer (TDD 4.5). Die frühere ist zuerst " +
            "zu beenden — mit end_reason.'); END"

    const val TRIGGER_EINE_AKTIVE_AENDERN: String =
        "CREATE TRIGGER IF NOT EXISTS `participant_session_eine_aktive_aendern` " +
            "BEFORE UPDATE ON `participant_session` FOR EACH ROW " +
            "WHEN NEW.`is_current` = 1 AND OLD.`is_current` = 0 AND EXISTS (" +
            "SELECT 1 FROM `participant_session` WHERE `participant_uid` = NEW.`participant_uid` " +
            "AND `is_current` = 1 AND `session_uid` <> NEW.`session_uid`) " +
            "BEGIN SELECT RAISE(ABORT, " +
            "'Höchstens eine laufende Sitzung je Teilnehmer (TDD 4.5).'); END"

    /**
     * Eine beendete Sitzung bleibt beendet.
     *
     * Ohne diese Zusicherung ließe sich eine abgelöste Sitzung wiederbeleben —
     * und das alte Gerät, das nach TDD 9.6 seine Sicht auf die Identität löschen
     * soll, hätte plötzlich wieder eine gültige Sitzung.
     */
    const val TRIGGER_ENDE_IST_ENDGUELTIG: String =
        "CREATE TRIGGER IF NOT EXISTS `participant_session_ende_ist_endgueltig` " +
            "BEFORE UPDATE ON `participant_session` FOR EACH ROW " +
            "WHEN OLD.`ended_at` IS NOT NULL AND (" +
            "NEW.`ended_at` IS NULL OR NEW.`is_current` = 1 " +
            "OR NEW.`ended_at` IS NOT OLD.`ended_at` OR NEW.`end_reason` IS NOT OLD.`end_reason`) " +
            "BEGIN SELECT RAISE(ABORT, " +
            "'Eine beendete Sitzung bleibt beendet (TDD 9.6).'); END"

    /**
     * Ende und Grund gehören zusammen.
     *
     * Ein Ende ohne Grund wäre eine Sitzung, von der niemand mehr weiß, ob sie
     * abgelöst wurde oder ob der Spieler gegangen ist — der Unterschied
     * entscheidet, was die App dem alten Gerät anzeigt.
     */
    const val TRIGGER_ENDE_BRAUCHT_GRUND: String =
        "CREATE TRIGGER IF NOT EXISTS `participant_session_ende_braucht_grund` " +
            "BEFORE UPDATE ON `participant_session` FOR EACH ROW " +
            "WHEN (NEW.`ended_at` IS NULL) IS NOT (NEW.`end_reason` IS NULL) " +
            "BEGIN SELECT RAISE(ABORT, " +
            "'ended_at und end_reason werden gemeinsam gesetzt (TDD 4.5).'); END"

    val TABELLEN: List<String> = listOf(TABELLE_PARTICIPANT_SESSION, INDEX_PARTICIPANT, INDEX_DEVICE)

    val TRIGGER: List<String> = listOf(
        TRIGGER_EINE_AKTIVE_EINFUEGEN,
        TRIGGER_EINE_AKTIVE_AENDERN,
        TRIGGER_ENDE_IST_ENDGUELTIG,
        TRIGGER_ENDE_BRAUCHT_GRUND,
    )
}

/**
 * Zugriff auf `participant_session`.
 *
 * Kein `@Delete`: Eine Sitzung wird **beendet**, nicht gelöscht. Sonst verlöre
 * der Host die Spur, welches Gerät wann abgelöst wurde — und genau die braucht
 * er, wenn jemand fragt, warum sein Handy die Ansicht gesperrt hat.
 */
@Dao
interface ParticipantSessionDao {

    @Insert
    fun eroeffne(sitzung: ParticipantSessionEntity)

    @Query(
        "SELECT * FROM participant_session " +
            "WHERE participant_uid = :participantUid AND is_current = 1 LIMIT 1",
    )
    fun laufende(participantUid: String): ParticipantSessionEntity?

    @Query("SELECT * FROM participant_session WHERE session_uid = :sessionUid")
    fun nach(sessionUid: String): ParticipantSessionEntity?

    @Query(
        "UPDATE participant_session SET is_current = 0, ended_at = :beendetAm, " +
            "end_reason = :grund WHERE session_uid = :sessionUid AND is_current = 1",
    )
    fun beende(sessionUid: String, beendetAm: Long, grund: String): Int

    @Query(
        "UPDATE participant_session SET last_delivered_seq = :seq " +
            "WHERE session_uid = :sessionUid AND last_delivered_seq < :seq",
    )
    fun merkeZugestellt(sessionUid: String, seq: Long): Int

    @Query("SELECT * FROM participant_session WHERE participant_uid = :participantUid ORDER BY started_at")
    fun alle(participantUid: String): List<ParticipantSessionEntity>
}
