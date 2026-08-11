package de.myhornets.rise1.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

// T-020 / T-023 — Zugriff auf `rise.db`.
//
// Diese Abfragen legen ab und holen zurück. Sie entscheiden nichts: kein
// Zustandsübergang, keine Prüfung, ob eine Partie beginnen darf, keine
// Sitzplatzvergabe. Wer das braucht, baut es über dieser Schicht — eine Ablage
// ist kein Mitspieler (ADR-003).
//
// **Soft Delete** (TDD 3.3): Lesende Abfragen übergehen Datensätze mit gesetztem
// `deleted_at`. Ein hartes `DELETE` gibt es nicht, weil es sich nicht
// propagieren ließe.
//
// **`match` ist in SQLite ein Schlüsselwort** und steht deshalb in
// Backticks. SQLite verzeiht es meistens auch ohne — verlassen wollen wir uns
// darauf nicht.

@Dao
interface DeviceDao {

    @Insert
    fun einfuegen(device: DeviceEntity)

    @Update
    fun aktualisieren(device: DeviceEntity)

    @Query("SELECT * FROM device WHERE device_uid = :deviceUid AND deleted_at IS NULL")
    fun nachUid(deviceUid: String): DeviceEntity?

    /** Das Gerät, auf dem diese Datenbank liegt. Genau eines trägt `is_self`. */
    @Query("SELECT * FROM device WHERE is_self = 1 AND deleted_at IS NULL")
    fun eigenes(): DeviceEntity?

    @Query("SELECT * FROM device WHERE deleted_at IS NULL ORDER BY display_name")
    fun alle(): List<DeviceEntity>
}

@Dao
interface PlayerDao {

    @Insert
    fun einfuegen(player: PlayerEntity)

    @Update
    fun aktualisieren(player: PlayerEntity)

    @Query("SELECT * FROM player WHERE player_uid = :playerUid AND deleted_at IS NULL")
    fun nachUid(playerUid: String): PlayerEntity?

    @Query("SELECT * FROM player WHERE deleted_at IS NULL ORDER BY display_name")
    fun alle(): List<PlayerEntity>

    @Query("SELECT COUNT(*) FROM player WHERE deleted_at IS NULL")
    fun anzahl(): Int
}

@Dao
interface MatchDao {

    @Insert
    fun einfuegen(match: MatchEntity)

    @Update
    fun aktualisieren(match: MatchEntity)

    @Query("SELECT * FROM `match` WHERE match_uid = :matchUid AND deleted_at IS NULL")
    fun nachUid(matchUid: String): MatchEntity?

    @Query("SELECT * FROM `match` WHERE deleted_at IS NULL ORDER BY created_at DESC")
    fun alle(): List<MatchEntity>

    @Query("SELECT * FROM `match` WHERE status = :status AND deleted_at IS NULL ORDER BY created_at DESC")
    fun nachStatus(status: String): List<MatchEntity>

    @Query("SELECT COUNT(*) FROM `match` WHERE deleted_at IS NULL")
    fun anzahl(): Int
}

@Dao
interface MatchParticipantDao {

    @Insert
    fun einfuegen(teilnehmer: MatchParticipantEntity)

    @Update
    fun aktualisieren(teilnehmer: MatchParticipantEntity)

    @Query("SELECT * FROM match_participant WHERE participant_uid = :participantUid AND deleted_at IS NULL")
    fun nachUid(participantUid: String): MatchParticipantEntity?

    /**
     * Die Sitzordnung einer Partie.
     *
     * `seat_index` bestimmt die Zugfolge (TDD 4.4) — die Sortierung ist deshalb
     * nicht Geschmackssache, sondern die Reihenfolge selbst.
     */
    @Query(
        """
        SELECT * FROM match_participant
        WHERE match_uid = :matchUid AND deleted_at IS NULL
        ORDER BY seat_index
        """,
    )
    fun sitzordnung(matchUid: String): List<MatchParticipantEntity>

    @Query(
        """
        SELECT * FROM match_participant
        WHERE match_uid = :matchUid AND seat_index = :seatIndex AND deleted_at IS NULL
        """,
    )
    fun aufSitzplatz(matchUid: String, seatIndex: Int): MatchParticipantEntity?

    @Query("SELECT COUNT(*) FROM match_participant WHERE match_uid = :matchUid AND deleted_at IS NULL")
    fun anzahl(matchUid: String): Int

    /**
     * Die bereits aufgedeckten Teilnehmer.
     *
     * Beantwortet „wer liegt offen", nicht „wer ist was" — die Identität steht
     * hier nur, wenn sie ohnehin öffentlich ist.
     */
    @Query(
        """
        SELECT * FROM match_participant
        WHERE match_uid = :matchUid AND is_revealed = 1 AND deleted_at IS NULL
        ORDER BY seat_index
        """,
    )
    fun aufgedeckte(matchUid: String): List<MatchParticipantEntity>
}
