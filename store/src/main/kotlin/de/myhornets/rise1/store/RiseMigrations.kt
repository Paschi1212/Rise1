package de.myhornets.rise1.store

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * T-025b — die erste echte Migration von `rise.db`.
 *
 * ## Warum das hier ein eigener Meilenstein ist
 *
 * `T-020` hat festgehalten, dass `rise.db` niemals ersetzt, sondern migriert
 * wird — und dass deshalb kein `fallbackToDestructiveMigration()` in
 * [RiseStore] steht. Bis heute war das eine Zusage ohne Anwendungsfall: Es gab
 * nur Version 1. Diese Datei ist der erste Fall, in dem sie etwas kostet und
 * damit auch etwas wert ist.
 *
 * Fehlt eine Migration, soll das Öffnen **scheitern**. Eine Datenbank, die
 * beim Versionswechsel stillschweigend geleert wird, wirft die Partien des
 * Nutzers weg — und meldet Erfolg.
 *
 * ## Was diese Migration tut
 *
 * Sie legt an, was `T-025` braucht: `match_event` und die drei
 * Projektionstabellen aus TDD 4.4, dazu die Trigger aus [EventLogSql]. Sie
 * **rührt keine bestehende Tabelle an**. `device`, `player`, `match`,
 * `match_participant` und `prototype_assignment` bleiben Spalte für Spalte, wie
 * sie sind; eine bestehende Partie überlebt den Wechsel unverändert.
 *
 * ## Versionsempfindlich
 *
 * Die Signaturen von [Migration.migrate] und [RoomDatabase.Callback.onCreate]
 * haben sich zwischen Room-Reihen bewegt. Hier steht die Variante mit
 * `SupportSQLiteDatabase`, die es seit Room 2.0 gibt und die auf Android auch
 * in 2.8 vorhanden ist. **Ungeprüft** wie alle Versionsannahmen in diesem
 * Projekt — der erste Build entscheidet.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        EventLogSql.TABELLEN.forEach { db.execSQL(it) }
        EventLogSql.TRIGGER.forEach { db.execSQL(it) }
    }
}

/**
 * T-102 — `participant_session` (TDD 4.5).
 *
 * Dieselbe Regel wie bei [MIGRATION_1_2]: **keine bestehende Tabelle wird
 * angefasst.** Eine laufende Partie überlebt den Wechsel unverändert; es kommt
 * eine Tabelle mit zwei Indizes und vier Triggern dazu.
 *
 * Die Trigger stehen in [SitzungSql] und nicht in einem partiellen Index, weil
 * Room die Indizes der Datei gegen die Entities prüft und einen ihm unbekannten
 * Index als Schemafehler meldet. Trigger prüft es nicht — deshalb sind sie seit
 * `T-025b` der Weg für Zusicherungen, die Room nicht ausdrücken kann.
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        SitzungSql.TABELLEN.forEach { db.execSQL(it) }
        SitzungSql.TRIGGER.forEach { db.execSQL(it) }
    }
}

/**
 * Legt die Trigger bei einer **frisch angelegten** Datenbank an.
 *
 * Room erzeugt beim ersten Öffnen die Tabellen aus den Entities — Trigger kennt
 * es nicht. Ohne diesen Rückruf hätte eine Neuinstallation die Zusicherungen
 * aus [EventLogSql] nicht, eine aktualisierte Installation dagegen schon. Genau
 * diese Sorte Unterschied fällt erst auf, wenn er zählt.
 *
 * Alle Anweisungen tragen `IF NOT EXISTS` und sind damit an beiden Wegen
 * unschädlich.
 */
object RiseCallback : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        EventLogSql.TRIGGER.forEach { db.execSQL(it) }
        // T-102. Ohne diese Zeile hätte eine Neuinstallation die Zusicherung
        // [höchstens eine laufende Sitzung] nicht, eine aktualisierte dagegen
        // schon — genau die Sorte Unterschied, die erst auffällt, wenn sie zählt.
        SitzungSql.TRIGGER.forEach { db.execSQL(it) }
    }
}
