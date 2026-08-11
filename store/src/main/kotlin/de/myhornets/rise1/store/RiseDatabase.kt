package de.myhornets.rise1.store

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// T-020 — `rise.db`.
//
// Das Gegenstück zu `catalog.db`, und in fast jeder Hinsicht sein Gegenteil
// (TDD 3.1):
//
//   `catalog.db` wird als Asset ausgeliefert, ist read-only und wird bei einem
//   neuen Kartenset **komplett ersetzt**.
//
//   `rise.db` entsteht leer auf dem Gerät, wird beschrieben und über
//   Migrationen fortgeschrieben — **niemals ersetzt**.
//
// Daraus folgt unmittelbar, was hier nicht steht: kein `createFromAsset`, und
// vor allem **kein `fallbackToDestructiveMigration()`**. Bei `catalog.db` wäre
// es falsch gewesen, weil es einen Fehler verdeckt hätte; hier wäre es
// schlimmer — es würde die Partien des Nutzers wegwerfen. Fehlt eine Migration,
// soll das Öffnen scheitern.
//
// Zwischen den beiden Datenbanken gibt es keine Fremdschlüssel und keine Joins.
// Die Verbindung von einer Partie zu einer Identität ist eine logische Referenz
// per String-ID, die die App auflöst (TDD 3.1, `T-031`).

/**
 * Schemastand von `rise.db`.
 *
 * Maßgeblich ist der Export unter `store/schemas/`. Jede Änderung an den
 * Entities hebt diese Zahl **und** braucht eine Migration — die Datenbank wird
 * nicht ersetzt.
 *
 * **Version 2 (T-025, 2026-08-10):** `match_event` und die drei
 * Projektionstabellen aus TDD 4.4. Bestehende Tabellen unverändert; die
 * Migration steht in [MIGRATION_1_2].
 *
 * **Version 3 (T-102, 2026-08-10):** `participant_session` aus TDD 4.5.
 * Bestehende Tabellen wieder unverändert; Migration in [MIGRATION_2_3].
 */
const val RISE_SCHEMA_VERSION = 3

@Database(
    entities = [
        DeviceEntity::class,
        PlayerEntity::class,
        MatchEntity::class,
        MatchParticipantEntity::class,
        // ADR-004 — befristet, siehe PrototypeAssignment.kt.
        PrototypeAssignmentEntity::class,
        // T-025 — das Event-Log und seine Projektionen (TDD 5 / 4.4).
        MatchEventEntity::class,
        ParticipantStateEntity::class,
        ParticipantCounterEntity::class,
        MatchStateEntity::class,
        // T-102 — Sitzplatz ⇄ Gerät auf Zeit (TDD 4.5).
        ParticipantSessionEntity::class,
    ],
    version = RISE_SCHEMA_VERSION,
    exportSchema = true,
)
abstract class RiseDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun playerDao(): PlayerDao
    abstract fun matchDao(): MatchDao
    abstract fun matchParticipantDao(): MatchParticipantDao

    /** ADR-004 — befristet. Verschwindet mit T-029/T-030. */
    abstract fun prototypeAssignmentDao(): PrototypeAssignmentDao

    // T-025. Der Schreibweg in den Spielzustand ist nicht dieses DAO, sondern
    // [MatchEventLog] — nur dort hängen Event und Fortschreibung in einer
    // Transaktion zusammen.
    abstract fun eventLogDao(): EventLogDao
    abstract fun projectionDao(): ProjectionDao

    // T-102. Der Schreibweg für Ablösungen ist nicht dieses DAO, sondern
    // [Sitzungsverwaltung] — nur dort hängen Beenden und Eröffnen in einer
    // Transaktion zusammen.
    abstract fun participantSessionDao(): ParticipantSessionDao
}

/**
 * Öffnet `rise.db` auf diesem Gerät.
 *
 * Es gibt bewusst genau einen Weg hierher, wie bei `CatalogAsset.open` im
 * Katalog.
 */
object RiseStore {

    /** Dateiname im App-Datenverzeichnis. */
    const val DATABASE_NAME = "rise.db"

    fun open(context: Context): RiseDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            RiseDatabase::class.java,
            DATABASE_NAME,
        )
            // T-025b. Weiterhin **kein** `fallbackToDestructiveMigration()`:
            // Fehlt eine Migration, soll das Öffnen scheitern, statt die
            // Partien des Nutzers wegzuwerfen und Erfolg zu melden.
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            // Room legt beim ersten Öffnen die Tabellen an, die Trigger nicht.
            // Ohne diesen Rückruf hätte eine Neuinstallation die Zusicherungen
            // aus EventLogSql nicht — eine aktualisierte dagegen schon.
            .addCallback(RiseCallback)
            .build()

    /**
     * Der Schreibweg in den Spielzustand (T-025e).
     *
     * Bewusst hier und nicht als weiteres DAO: Es soll genau einen Weg geben,
     * auf dem sich der Spielzustand verändert — und der hängt ein Event an.
     */
    fun eventLog(datenbank: RiseDatabase): MatchEventLog = MatchEventLog(datenbank)

    /**
     * Der Weg, auf dem Sitzungen entstehen und enden (T-102).
     *
     * Aus demselben Grund wie [eventLog] kein weiteres DAO: Eine Ablösung ist
     * zwei Schreibvorgänge, die zusammen gehören oder gar nicht.
     */
    fun sitzungen(datenbank: RiseDatabase): Sitzungsverwaltung = Sitzungsverwaltung(datenbank)
}
