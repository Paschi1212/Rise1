package de.myhornets.rise1.store

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// T-025b/c — das Event-Log und seine Projektionen in `rise.db`.
//
// ## Wo das Log lebt und warum hier
//
// Entschieden am 2026-08-10: `match_event` liegt in `rise.db` und gehört damit
// `:store` (ADR-003). `:host` bekommt später die **Sequenzvergabe** — also die
// Koordination —, nicht die Persistenz. TDD 2.2 nennt beim Host „Log-Speicherung";
// gemeint ist die Autorität über die Reihenfolge (TDD 6.2), nicht der Besitz der
// Datei. ADR-003 hatte diesen Punkt ausdrücklich für `T-025` offengelassen.
//
// ## Warum `:store` weiterhin keine Modulkante hat
//
// Die Werte von `event_class`, `visibility` und `type` stehen als Zeichenketten
// in der Datenbank, nicht als `enum` aus `:core`. Das hält `:store` bei
// `allowedModuleEdges["store"] = emptySet()` — „eine Ablage ist kein Mitspieler"
// (ADR-003) — und ist zugleich die Voraussetzung dafür, dass ein Event mit
// unbekanntem Typ überhaupt gespeichert werden kann (TDD 5.5).
//
// Der Preis ist eine bewusste Doppelung: Dieselben Zeichenketten stehen in
// `:core` als `enum`. Dass sie auseinanderlaufen könnten, ist ein realer Punkt
// und im Aufgabendokument zu `T-025` als **NEEDS DECISION** vermerkt — er wird
// hier nicht durch eine stille neue Modulkante gelöst.
//
// ## Keine Fremdschlüssel auf diesen Tabellen
//
// Anders als bei `match_participant` — und mit Absicht. Eine Projektion muss
// sich **allein aus dem Log** wieder aufbauen lassen (TDD 5.1). Ein
// Fremdschlüssel auf `match_participant` würde diesen Neuaufbau von Zeilen
// abhängig machen, die ein ganz anderer Weg geschrieben hat: Der Rebuild würde
// scheitern, weil eine Zeile fehlt, die mit dem Log nichts zu tun hat.
//
// Auch `match_event` bekommt keinen: Ein Event trifft möglicherweise ein, bevor
// die Partie lokal angelegt wurde (TDD 6.3, lückenhafte Logs auf Clients). Ein
// Fremdschlüssel würde genau dieses Event abweisen — also den Fall kaputtmachen,
// für den das Log gebaut ist.
//
// ## Keine Synchronisationsfelder
//
// TDD 3.3 verlangt `created_at` / `updated_at` / `deleted_at` /
// `origin_device_uid` an synchronisierten Tabellen. Hier stehen sie bewusst
// nicht:
//
//   `match_event` ist append-only (TDD 5.1). Ein `deleted_at` wäre ein weiches
//   Löschen — also genau das, was der Grundsatz ausschließt. Die Herkunft steht
//   bereits als `origin_device_uid` da, nur als Teil des Dedup-Schlüssels und
//   nicht als Synchronisationsfeld.
//
//   Die drei Projektionstabellen werden nicht synchronisiert. Sie werden
//   **berechnet**. Was aus dem Log neu entsteht, muss nicht übertragen werden.

/**
 * `match_event` — TDD 5.2. Append-only und unveränderlich (TDD 5.1).
 *
 * Der Schutz dieser Zusage steht nicht hier, sondern in [EventLogSql]: Room
 * kennt kein „append-only", also übernehmen es Trigger in der Datenbank. Eine
 * Zusage, die nur darin besteht, dass niemand ein `@Delete` hinschreibt, ist
 * keine.
 *
 * Die Spaltenreihenfolge entspricht der Reihenfolge in TDD 5.2 und ist
 * zugleich die des Migrationsskripts — beides nebeneinander lesbar zu halten
 * war wichtiger als eine Gruppierung nach Datentyp.
 */
@Entity(
    tableName = "match_event",
    indices = [
        // Für den Replay in kanonischer Reihenfolge (TDD 5.2).
        Index(value = ["match_uid", "seq"], name = "index_match_event_match_uid_seq"),
        // Die Dedup-Garantie. Ohne `unique` wäre sie eine Absichtserklärung.
        Index(
            value = ["origin_device_uid", "origin_seq"],
            name = "index_match_event_origin_device_uid_origin_seq",
            unique = true,
        ),
    ],
)
data class MatchEventEntity(
    @PrimaryKey @ColumnInfo(name = "event_uid") val eventUid: String,
    @ColumnInfo(name = "match_uid") val matchUid: String,

    /** `null` = vom Host noch nicht bestätigt. Nur der Host vergibt (TDD 5.2). */
    @ColumnInfo(name = "seq") val seq: Long?,

    @ColumnInfo(name = "origin_device_uid") val originDeviceUid: String,
    @ColumnInfo(name = "origin_seq") val originSeq: Long,
    @ColumnInfo(name = "lamport_clock") val lamportClock: Long,

    /** Wanduhr des Erzeugers — nur zur Anzeige (TDD 6.6). Nie Sortierschlüssel. */
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    @ColumnInfo(name = "recorded_at") val recordedAt: Long?,

    /** Roher Typwert — siehe Dateikommentar zu TDD 5.5. */
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "event_class") val eventClass: String,

    @ColumnInfo(name = "actor_participant_uid") val actorParticipantUid: String?,
    @ColumnInfo(name = "target_participant_uid") val targetParticipantUid: String?,

    /** Nur bei `PUBLIC` (TDD 5.2). Durchgesetzt durch Trigger, nicht durch Absicht. */
    @ColumnInfo(name = "payload_json") val payloadJson: String?,

    /** Ersetzt [payloadJson], sobald die Sichtbarkeit nicht `PUBLIC` ist. */
    @ColumnInfo(name = "payload_ciphertext") val payloadCiphertext: ByteArray?,

    /** Damit ein späterer Verfahrenswechsel alte Events nicht unlesbar macht. */
    @ColumnInfo(name = "enc_scheme") val encScheme: String?,

    @ColumnInfo(name = "payload_schema_version") val payloadSchemaVersion: Int,
    @ColumnInfo(name = "visibility") val visibility: String,

    /** Nur bei `PRIVATE`. */
    @ColumnInfo(name = "recipient_participant_uid") val recipientParticipantUid: String?,

    @ColumnInfo(name = "is_undone") val isUndone: Boolean,
    @ColumnInfo(name = "undone_by_event_uid") val undoneByEventUid: String?,
    @ColumnInfo(name = "has_conflict") val hasConflict: Boolean,
) {
    // `data class` plus `ByteArray` ergibt Referenzgleichheit — zwei Zeilen mit
    // demselben Chiffrat wären ungleich. Das fiele erst auf, wenn ein Test zwei
    // Wege vergleicht, also spät. Deshalb von Hand.

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MatchEventEntity) return false
        return eventUid == other.eventUid &&
            matchUid == other.matchUid &&
            seq == other.seq &&
            originDeviceUid == other.originDeviceUid &&
            originSeq == other.originSeq &&
            lamportClock == other.lamportClock &&
            occurredAt == other.occurredAt &&
            recordedAt == other.recordedAt &&
            type == other.type &&
            eventClass == other.eventClass &&
            actorParticipantUid == other.actorParticipantUid &&
            targetParticipantUid == other.targetParticipantUid &&
            payloadJson == other.payloadJson &&
            (payloadCiphertext?.contentEquals(other.payloadCiphertext) ?: (other.payloadCiphertext == null)) &&
            encScheme == other.encScheme &&
            payloadSchemaVersion == other.payloadSchemaVersion &&
            visibility == other.visibility &&
            recipientParticipantUid == other.recipientParticipantUid &&
            isUndone == other.isUndone &&
            undoneByEventUid == other.undoneByEventUid &&
            hasConflict == other.hasConflict
    }

    override fun hashCode(): Int {
        var r = eventUid.hashCode()
        r = 31 * r + matchUid.hashCode()
        r = 31 * r + (seq?.hashCode() ?: 0)
        r = 31 * r + originDeviceUid.hashCode()
        r = 31 * r + originSeq.hashCode()
        r = 31 * r + lamportClock.hashCode()
        r = 31 * r + occurredAt.hashCode()
        r = 31 * r + (recordedAt?.hashCode() ?: 0)
        r = 31 * r + type.hashCode()
        r = 31 * r + eventClass.hashCode()
        r = 31 * r + (actorParticipantUid?.hashCode() ?: 0)
        r = 31 * r + (targetParticipantUid?.hashCode() ?: 0)
        r = 31 * r + (payloadJson?.hashCode() ?: 0)
        r = 31 * r + (payloadCiphertext?.contentHashCode() ?: 0)
        r = 31 * r + (encScheme?.hashCode() ?: 0)
        r = 31 * r + payloadSchemaVersion
        r = 31 * r + visibility.hashCode()
        r = 31 * r + (recipientParticipantUid?.hashCode() ?: 0)
        r = 31 * r + isUndone.hashCode()
        r = 31 * r + (undoneByEventUid?.hashCode() ?: 0)
        r = 31 * r + hasConflict.hashCode()
        return r
    }

    /** Bewusst ohne Nutzdaten: Ein Chiffrat gehört in kein Protokoll. */
    override fun toString(): String =
        "MatchEventEntity(event_uid=$eventUid, match_uid=$matchUid, seq=$seq, type=$type, " +
            "visibility=$visibility, payload=${if (payloadCiphertext != null) "chiffriert" else if (payloadJson != null) "klartext" else "leer"})"
}

/**
 * `participant_state` — TDD 4.4.
 *
 * **Abweichung von TDD 4.4:** Die Tabelle trägt zusätzlich `match_uid`. TDD
 * nennt dort nur `participant_uid` als Schlüssel. Ohne die Partie ließe sich
 * die Projektion einer einzelnen Partie weder gezielt verwerfen noch neu
 * aufbauen, ohne über `match_participant` zu verbinden — und genau diese
 * Abhängigkeit soll der Neuaufbau nicht haben (siehe Dateikommentar).
 */
@Entity(
    tableName = "participant_state",
    indices = [Index(value = ["match_uid"], name = "index_participant_state_match_uid")],
)
data class ParticipantStateEntity(
    @PrimaryKey @ColumnInfo(name = "participant_uid") val participantUid: String,
    @ColumnInfo(name = "match_uid") val matchUid: String,

    /**
     * Lebenspunkte.
     *
     * Startwert 0. Woher der wirkliche Startwert kommt, ist eine Angabe des
     * Partie-Modus und wird durch ein Event gesetzt — nicht hier angenommen.
     * Eine Zahl, die aus Karten berechnet wird, wäre eine Regelengine.
     */
    @ColumnInfo(name = "life") val life: Int,
    @ColumnInfo(name = "is_revealed") val isRevealed: Boolean,
    @ColumnInfo(name = "is_eliminated") val isEliminated: Boolean,
    @ColumnInfo(name = "last_applied_seq") val lastAppliedSeq: Long,
)

/** `participant_counter` — TDD 4.4. Zusammengesetzter Schlüssel. */
@Entity(
    tableName = "participant_counter",
    primaryKeys = ["participant_uid", "counter_key"],
    indices = [Index(value = ["match_uid"], name = "index_participant_counter_match_uid")],
)
data class ParticipantCounterEntity(
    @ColumnInfo(name = "participant_uid") val participantUid: String,
    @ColumnInfo(name = "counter_key") val counterKey: String,
    /** Abweichung wie bei `participant_state` — aus demselben Grund. */
    @ColumnInfo(name = "match_uid") val matchUid: String,
    @ColumnInfo(name = "value") val value: Int,
)

/** `match_state` — TDD 4.4. */
@Entity(tableName = "match_state")
data class MatchStateEntity(
    @PrimaryKey @ColumnInfo(name = "match_uid") val matchUid: String,

    /**
     * Zugnummer.
     *
     * `D-003` (2026-08-10): Die Zugzählung ist Bestandteil von `match_state`.
     * Das Feld entsteht deshalb **mit diesem Schema**, obwohl `turn_started`
     * und `turn_ended` erst später ausgewertet werden — ausdrücklich, damit die
     * Zuglogik ohne eine weitere Migration dazukommen kann.
     */
    @ColumnInfo(name = "turn_number") val turnNumber: Int,

    /** Ebenfalls aus `D-003`. Bleibt in diesem Slice `null`. */
    @ColumnInfo(name = "active_participant_uid") val activeParticipantUid: String?,

    @ColumnInfo(name = "last_applied_seq") val lastAppliedSeq: Long,
)

/**
 * Die Zeichenketten, die in `event_class` stehen dürfen (TDD 5.2).
 *
 * Doppelung zu `de.myhornets.rise1.core.event.EventClass` — siehe
 * Dateikommentar. Bewusst, nicht übersehen.
 */
object EventClasses {
    const val STATE = "state"
    const val SESSION = "session"
    const val ANNOTATION = "annotation"

    val ALLE = listOf(STATE, SESSION, ANNOTATION)
}

/** Die Zeichenketten, die in `visibility` stehen dürfen (TDD 5.2). */
object Visibilities {
    const val PUBLIC = "PUBLIC"
    const val PLAYER_ONLY = "PLAYER_ONLY"
    const val PRIVATE = "PRIVATE"

    val ALLE = listOf(PUBLIC, PLAYER_ONLY, PRIVATE)
}
