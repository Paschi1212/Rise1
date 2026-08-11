package de.myhornets.rise1.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// T-020 / T-023 — die Entities aus TDD 4.3 und 4.4.
//
// Vier Tabellen: `device`, `player`, `match`, `match_participant`. Das ist die
// Datenbasis für Partie, Spieler und Sitzordnung — mehr nicht.
//
// ── Was hier ausdrücklich NICHT steht ───────────────────────────────────────
//
// `own_identity` und `active_membership` fehlen. TDD 3.2 verlangt für beide den
// vom Betriebssystem geschützten Speicher, nicht die normale Datenbankdatei.
// Sie entstehen mit `T-030`, nicht früher.
//
// Die Verteiltabellen (`deal_envelope`, `deal_key_packet`, `deal_role_assignment`,
// `participant_key`) fehlen. Sie gehören zum Zwei-Parteien-Verfahren aus TDD 8.3
// und zu `T-029`.
//
// **Und es gibt nirgends eine Zuordnung Spieler → Rolle.** Das ist keine Lücke,
// sondern die Kernzusage: TDD 8.1 — „Wer die Rollen zuordnet, kennt die
// Zuordnung. Damit niemand die Verteilung kennt, darf sie niemand in einem Stück
// berechnen." Eine Tabelle, die auf einem Gerät hielte, wer welche Rolle hat,
// wäre genau das ausgeschlossene Gebilde.
//
// Die Felder `revealed_identity_uid`, `identity_name_snapshot` und
// `identity_role_snapshot` in `match_participant` sind **kein** Widerspruch dazu:
// Sie tragen das **Ergebnis einer Aufdeckung**, sind vorher leer und werden nie
// vorab gefüllt. TDD 3.4 sagt es so: „Vor dem Aufdecken sind diese Felder leer —
// es gibt nichts zu snapshotten, weil der Host die Identität nicht kennt."
//
// ── Was für alle vier Tabellen gilt ─────────────────────────────────────────
//
// **Primärschlüssel sind UUIDs als String** mit dem Suffix `_uid` (TDD 3.3).
// Fortlaufende Zahlen wären unbrauchbar, sobald zwei Geräte unabhängig
// Datensätze anlegen.
//
// **Die vier Synchronisationsfelder** `created_at`, `updated_at`, `deleted_at`
// und `origin_device_uid` trägt nach TDD 3.3 „jede synchronisierte Tabelle". Das
// TDD zählt nicht auf, welche das sind — es zählt in 3.2 die Ausnahmen auf
// (verschlüsselt, host-lokal flüchtig, rein lokal). Keine der vier Tabellen hier
// steht auf einer dieser Listen, also tragen sie die Felder. Abgeleitet, nicht
// abgeschrieben; wenn diese Lesart falsch ist, ist es hier zu korrigieren.
//
// **`deleted_at` ist ein Soft Delete.** TDD 3.3: „ein hartes DELETE ist nicht
// propagierbar". Deshalb steht an keiner Fremdschlüsselbeziehung ein `CASCADE` —
// gelöscht wird durch Setzen, nicht durch Entfernen.
//
// **Gespeichert werden stabile englische Schlüssel, keine Anzeigetexte**
// (TDD 3.5). Übersetzt wird in `:ui` über String-Ressourcen.
//
// ── Nullbarkeit ─────────────────────────────────────────────────────────────
//
// Das TDD legt sie nicht fest. Die Regel hier: Ein Feld ist nullbar, wenn es
// einen Zeitpunkt im Lebenslauf des Datensatzes gibt, zu dem es der Sache nach
// noch keinen Wert haben kann. Jede solche Stelle ist unten einzeln begründet.

/** Zulässige Werte für `match.status` (TDD 4.4). */
object MatchStatus {
    const val SETUP = "setup"
    const val DEALING = "dealing"
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val FINISHED = "finished"
    const val ABANDONED = "abandoned"

    val ALLE = listOf(SETUP, DEALING, ACTIVE, PAUSED, FINISHED, ABANDONED)
}

/** Zulässige Werte für `match_participant.seat_state` (TDD 4.4). */
object SeatState {
    const val ACTIVE = "active"
    const val RESERVED = "reserved"
    const val RELEASED = "released"

    val ALLE = listOf(ACTIVE, RESERVED, RELEASED)
}

/** Zulässige Werte für `match_participant.final_status` (TDD 4.4). */
object FinalStatus {
    const val WON = "won"
    const val LOST = "lost"
    const val ELIMINATED = "eliminated"
    const val DRAW = "draw"

    val ALLE = listOf(WON, LOST, ELIMINATED, DRAW)
}

/**
 * Ein Gerät. TDD 4.3.
 *
 * `is_self` markiert das Gerät, auf dem diese Datenbank liegt — genau eines.
 */
@Entity(tableName = "device")
data class DeviceEntity(
    @PrimaryKey
    @ColumnInfo(name = "device_uid") val deviceUid: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "platform") val platform: String,
    @ColumnInfo(name = "is_self") val isSelf: Boolean,
    /** Nullbar: Ein gerade erst bekannt gewordenes Gerät wurde noch nie gesehen. */
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "origin_device_uid") val originDeviceUid: String,
)

/**
 * Ein dauerhafter Spieler — Grundlage jeder Statistik. TDD 4.3.
 *
 * Nicht zu verwechseln mit [MatchParticipantEntity]: Der Spieler überdauert die
 * Partie, der Teilnehmer ist ein Sitzplatz in genau einer.
 */
@Entity(
    tableName = "player",
    indices = [Index(value = ["linked_device_uid"], name = "index_player_linked_device_uid")],
)
data class PlayerEntity(
    @PrimaryKey
    @ColumnInfo(name = "player_uid") val playerUid: String,
    /** Vom Nutzer eingetippt und deshalb verbatim gespeichert (TDD 3.5). */
    @ColumnInfo(name = "display_name") val displayName: String,
    /** Nullbar: Ein Avatar ist optional. */
    @ColumnInfo(name = "avatar_ref") val avatarRef: String?,
    /** Nullbar: Eine Farbmarke ist optional. */
    @ColumnInfo(name = "color_tag") val colorTag: String?,
    @ColumnInfo(name = "is_guest") val isGuest: Boolean,
    /** Nullbar: Ein Gast hat kein verknüpftes Gerät. */
    @ColumnInfo(name = "linked_device_uid") val linkedDeviceUid: String?,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "origin_device_uid") val originDeviceUid: String,
)

/**
 * Eine Partie. TDD 4.4.
 *
 * `mode_uid` verweist auf `game_mode` aus TDD 4.2 — die Tabelle entsteht erst
 * mit `T-022`. Deshalb steht die Spalte hier, aber **kein** Fremdschlüssel: Room
 * vergleicht Fremdschlüssel beim Öffnen, und einer auf eine fehlende Tabelle
 * ließe sich nicht anlegen. Er kommt mit `T-022` per Migration nach.
 *
 * `moderator_mode` ist nach TDD 4.4 **nur im Status `setup` setzbar**. Das ist
 * eine Invariante über den Lebenslauf einer Partie und wird dort durchgesetzt,
 * wo Partien verändert werden — nicht hier. Eine Ablage prüft keine Übergänge.
 */
@Entity(
    tableName = "match",
    foreignKeys = [
        ForeignKey(
            entity = DeviceEntity::class,
            parentColumns = ["device_uid"],
            childColumns = ["host_device_uid"],
        ),
    ],
    indices = [Index(value = ["host_device_uid"], name = "index_match_host_device_uid")],
)
data class MatchEntity(
    @PrimaryKey
    @ColumnInfo(name = "match_uid") val matchUid: String,
    @ColumnInfo(name = "mode_uid") val modeUid: String,
    @ColumnInfo(name = "host_device_uid") val hostDeviceUid: String,
    /** Einer aus [MatchStatus]. */
    @ColumnInfo(name = "status") val status: String,
    /** Nullbar: Vor dem ersten Lebenszeichen des Hosts gibt es keines. */
    @ColumnInfo(name = "host_heartbeat_at") val hostHeartbeatAt: Long?,
    /** Redundant zur Zahl der Sitzplätze, aber praktisch für die Statistik (TDD 4.4). */
    @ColumnInfo(name = "player_count") val playerCount: Int,
    /** Historische Integrität nach TDD 3.4 — steht schon bei der Anlage fest. */
    @ColumnInfo(name = "catalog_version_used") val catalogVersionUsed: String,
    @ColumnInfo(name = "set_code_used") val setCodeUsed: String,
    /** Nullbar: entsteht erst mit dem Verteilen (TDD 8.3). */
    @ColumnInfo(name = "deal_transcript_digest") val dealTranscriptDigest: String?,
    @ColumnInfo(name = "moderator_mode") val moderatorMode: Boolean,
    /** Nullbar: Eine Partie im Status `setup` hat noch nicht begonnen. */
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    /** Nullbar: dito für das Ende. */
    @ColumnInfo(name = "ended_at") val endedAt: Long?,
    /** Stand der Projektion (TDD 4.4). Vor dem ersten Event 0. */
    @ColumnInfo(name = "last_applied_seq") val lastAppliedSeq: Long,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "origin_device_uid") val originDeviceUid: String,
)

/**
 * Ein Sitzplatz in einer Partie. TDD 4.4.
 *
 * **`seat_index` bestimmt die Zugfolge**, und `(match_uid, seat_index)` ist
 * eindeutig — das ist die Sitzordnung.
 *
 * **Und hier fehlt etwas mit Absicht: die Position im Kartenstapel.** TDD 8.4
 * ist unmissverständlich — sie ist ein Geheimnis, bekannt beim Verteiler und
 * beim Eigentümer in `own_identity.position_index`, „sonst nirgends,
 * insbesondere nicht auf dem Host und nicht in `match_participant`". Wäre sie
 * hier, könnte der Packer allein die gesamte Verteilung ableiten, und das
 * Zwei-Parteien-Verfahren wäre wirkungslos.
 *
 * Das ist einer der drei Fehler, die TDD Kapitel 0 bei der Konsolidierung
 * korrigiert hat. `T-024` macht daraus einen automatischen Wächter, damit die
 * Spalte nicht in einem stillen Moment zurückkehrt — siehe `SchemaWaechterTest`.
 */
@Entity(
    tableName = "match_participant",
    foreignKeys = [
        ForeignKey(
            entity = MatchEntity::class,
            parentColumns = ["match_uid"],
            childColumns = ["match_uid"],
        ),
        ForeignKey(
            entity = PlayerEntity::class,
            parentColumns = ["player_uid"],
            childColumns = ["player_uid"],
        ),
    ],
    indices = [
        Index(
            value = ["match_uid", "seat_index"],
            name = "index_match_participant_match_uid_seat_index",
            unique = true,
        ),
        Index(value = ["player_uid"], name = "index_match_participant_player_uid"),
    ],
)
data class MatchParticipantEntity(
    @PrimaryKey
    @ColumnInfo(name = "participant_uid") val participantUid: String,
    @ColumnInfo(name = "match_uid") val matchUid: String,
    /** Nullbar nach TDD 4.4: „null bei namenlosen Gästen". */
    @ColumnInfo(name = "player_uid") val playerUid: String?,
    /** Bestimmt die Zugfolge. Eindeutig je Partie. */
    @ColumnInfo(name = "seat_index") val seatIndex: Int,
    /** Überlebt spätere Umbenennungen des Spielers (TDD 4.4). */
    @ColumnInfo(name = "display_name_snapshot") val displayNameSnapshot: String,

    /**
     * Nullbar: Wird vom Eigentümer erst **nach dem Öffnen** veröffentlicht
     * (TDD 8.3, Runde 5). Bindet ihn an seine Karte und verrät nichts.
     */
    @ColumnInfo(name = "identity_commitment") val identityCommitment: String?,

    // ── Ergebnis einer Aufdeckung — vorher ausnahmslos leer (TDD 3.4) ────────
    @ColumnInfo(name = "is_revealed") val isRevealed: Boolean,
    @ColumnInfo(name = "revealed_at_seq") val revealedAtSeq: Long?,
    @ColumnInfo(name = "revealed_identity_uid") val revealedIdentityUid: String?,
    @ColumnInfo(name = "identity_name_snapshot") val identityNameSnapshot: String?,
    @ColumnInfo(name = "identity_role_snapshot") val identityRoleSnapshot: String?,

    /** Nullbar: Einer aus [FinalStatus], steht erst am Partieende fest. */
    @ColumnInfo(name = "final_status") val finalStatus: String?,
    /** Nullbar: dito. */
    @ColumnInfo(name = "placement") val placement: Int?,

    /**
     * Nullbar — **eine bewusste Abweichung vom TDD**, das das Feld ohne
     * Nullbarkeit führt.
     *
     * Der Token wird von der Sitzungsschicht ausgestellt (TDD 9.3); ein
     * Sitzplatz im Status `setup` hat noch keinen. Die Alternative wäre ein
     * Platzhalterwert gewesen — also ein Geheimnisfeld, das aussieht, als trüge
     * es ein Geheimnis. Nullbar sagt die Wahrheit.
     *
     * Gespeichert wird ausschließlich der **gesalzene Hash**; der Klartext lebt
     * nur auf dem Spielergerät (TDD 4.4).
     */
    @ColumnInfo(name = "rejoin_token_hash") val rejoinTokenHash: String?,

    /** Einer aus [SeatState]. */
    @ColumnInfo(name = "seat_state") val seatState: String,
    /** Nullbar: Ein reservierter Sitzplatz wurde noch nie gesehen. */
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long?,

    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "origin_device_uid") val originDeviceUid: String,
)
