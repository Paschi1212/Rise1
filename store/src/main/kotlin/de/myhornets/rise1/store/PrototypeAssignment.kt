package de.myhornets.rise1.store

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.ColumnInfo

// ADR-004 — der eingezäunte Teil des lokalen Prototyp-Modus.
//
// ┌───────────────────────────────────────────────────────────────────────────┐
// │ Diese Tabelle ist die EINZIGE Stelle im System mit einer vollständigen     │
// │ Zuordnung Spieler → Identität. TDD 8.1 schließt genau das aus:             │
// │ „Wer die Rollen zuordnet, kennt die Zuordnung."                            │
// │                                                                            │
// │ Sie existiert befristet, damit sich der Spielablauf am Tisch erproben      │
// │ lässt, bevor das Zwei-Parteien-Verfahren aus TDD 8.3 gebaut ist. Sie ist   │
// │ NICHT verschlüsselt, NICHT committet und gegenüber demjenigen, der das     │
// │ Gerät hält, NICHT geheim.                                                  │
// │                                                                            │
// │ Rückbau mit T-029/T-030: Tabelle per Migration entfernen. Der Name sagt    │
// │ absichtlich, was sie ist — damit sie beim Aufräumen nicht übersehen wird.  │
// └───────────────────────────────────────────────────────────────────────────┘
//
// Sie ist ausdrücklich **nicht** `own_identity` aus TDD 4.6. Jene entsteht
// später und anders: verschlüsselt, client-lokal, mit `position_index`. Der
// Prototyp nimmt ihren Namen nicht — sonst sähe der Rückbau später aus wie eine
// Umbenennung statt wie eine Löschung.
//
// Und sie trägt **keine** Position im Kartenstapel. Es gibt im Prototyp keinen
// Stapel; es gibt eine Zuordnung. Der Wächter aus `T-024` bleibt unangetastet.

/**
 * Wer im Prototyp welche Identität bekommen hat.
 *
 * Ohne die vier Synchronisationsfelder aus TDD 3.3 — diese Tabelle wird nie
 * synchronisiert und nie weich gelöscht. Beim Zurücksetzen einer Runde
 * verschwindet sie hart, und das ist hier richtig: Ein Prototypstand soll
 * restlos weg sein, wenn man ihn wegwirft.
 */
@Entity(tableName = "prototype_assignment", primaryKeys = ["match_uid", "participant_uid"])
data class PrototypeAssignmentEntity(
    @ColumnInfo(name = "match_uid") val matchUid: String,
    @ColumnInfo(name = "participant_uid") val participantUid: String,
    /** Logische Referenz in den Katalog, etwa `TRD-2025:001` (TDD 3.1). */
    @ColumnInfo(name = "identity_uid") val identityUid: String,
    @ColumnInfo(name = "assigned_at") val assignedAt: Long,
)

@Dao
interface PrototypeAssignmentDao {

    @Insert
    fun einfuegen(zuordnungen: List<PrototypeAssignmentEntity>)

    @Query("SELECT * FROM prototype_assignment WHERE match_uid = :matchUid")
    fun fuerPartie(matchUid: String): List<PrototypeAssignmentEntity>

    @Query(
        """
        SELECT * FROM prototype_assignment
        WHERE match_uid = :matchUid AND participant_uid = :participantUid
        """,
    )
    fun fuerSitzplatz(matchUid: String, participantUid: String): PrototypeAssignmentEntity?

    @Query("SELECT COUNT(*) FROM prototype_assignment WHERE match_uid = :matchUid")
    fun anzahl(matchUid: String): Int

    /** Hartes Löschen — siehe Klassenkommentar. */
    @Query("DELETE FROM prototype_assignment WHERE match_uid = :matchUid")
    fun loeschen(matchUid: String)
}
