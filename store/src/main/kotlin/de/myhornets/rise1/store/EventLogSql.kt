package de.myhornets.rise1.store

/**
 * T-025b — das SQL, das Room nicht selbst erzeugt.
 *
 * ## Warum es diese Datei gibt
 *
 * Zwei Zusagen des Entwurfs lassen sich in Room nicht ausdrücken:
 *
 * 1. **Append-only** (TDD 5.1). Room kennt keine unveränderliche Tabelle. Ein
 *    DAO ohne `@Delete` ist keine Zusage, sondern eine Gewohnheit — die nächste
 *    `@Query("DELETE FROM …")` hebt sie auf, und niemand merkt es.
 * 2. **Die Trennung von PUBLIC und nicht-PUBLIC** (TDD 5.2 / 7.3). In `:core`
 *    ist sie strukturell, weil `Payload` versiegelt ist. In der Datenbank sind
 *    es zwei nullbare Spalten, und dort ist beides zugleich schreibbar.
 *
 * Beides übernehmen deshalb Trigger. Sie stehen zwischen jedem Schreibweg und
 * der Datei — auch zwischen einem, den es heute noch nicht gibt.
 *
 * ## Was veränderlich bleiben muss
 *
 * „Append-only" heißt nicht „kein UPDATE". TDD verlangt an drei Stellen
 * ausdrücklich eines:
 *
 *   `seq` und `recorded_at` trägt der **Host** bei der Bestätigung nach
 *   (TDD 5.2 / 6.2). Vorher steht dort `null`.
 *
 *   `is_undone` und `undone_by_event_uid` setzt ein Undo (TDD 5.3) — es
 *   **löscht nicht**, es markiert.
 *
 *   `has_conflict` setzt ein nachträglich eingetroffenes Event (TDD 6.5).
 *
 * Unveränderlich ist der **Inhalt**: Typ, Klasse, Sichtbarkeit, Nutzdaten,
 * Beteiligte, Herkunft, Zeitpunkt der Entstehung. Und eine einmal vergebene
 * `seq` — sie ist die kanonische Ordnung; wäre sie überschreibbar, ließe sich
 * die Geschichte umschreiben, ohne ein Event anzufassen.
 *
 * ## Diese Anweisungen werden an zwei Stellen ausgeführt
 *
 * In der Migration 1 → 2 für bestehende Dateien, und im `onCreate`-Rückruf für
 * frisch angelegte. Room erzeugt beim `onCreate` die Tabellen selbst, die
 * Trigger aber nicht — ohne den Rückruf hätte eine Neuinstallation die
 * Zusicherungen nicht. Alle Anweisungen sind mit `IF NOT EXISTS` geschrieben
 * und damit an beiden Stellen unschädlich.
 */
object EventLogSql {

    // ── Tabellen (T-025b/c) ─────────────────────────────────────────────────
    //
    // Die Spaltenreihenfolge entspricht der von Room aus den Entities erzeugten
    // — sonst schlägt die Schemaprüfung beim nächsten Öffnen fehl, und zwar mit
    // einer Meldung über den Hash statt über die Spalte.

    const val TABELLE_MATCH_EVENT: String =
        "CREATE TABLE IF NOT EXISTS `match_event` (" +
            "`event_uid` TEXT NOT NULL, `match_uid` TEXT NOT NULL, `seq` INTEGER, " +
            "`origin_device_uid` TEXT NOT NULL, `origin_seq` INTEGER NOT NULL, " +
            "`lamport_clock` INTEGER NOT NULL, `occurred_at` INTEGER NOT NULL, `recorded_at` INTEGER, " +
            "`type` TEXT NOT NULL, `event_class` TEXT NOT NULL, " +
            "`actor_participant_uid` TEXT, `target_participant_uid` TEXT, " +
            "`payload_json` TEXT, `payload_ciphertext` BLOB, `enc_scheme` TEXT, " +
            "`payload_schema_version` INTEGER NOT NULL, `visibility` TEXT NOT NULL, " +
            "`recipient_participant_uid` TEXT, `is_undone` INTEGER NOT NULL, " +
            "`undone_by_event_uid` TEXT, `has_conflict` INTEGER NOT NULL, " +
            "PRIMARY KEY(`event_uid`))"

    const val INDEX_MATCH_EVENT_REPLAY: String =
        "CREATE INDEX IF NOT EXISTS `index_match_event_match_uid_seq` " +
            "ON `match_event` (`match_uid`, `seq`)"

    /** Die Dedup-Garantie aus TDD 5.2. `unique` ist hier der ganze Punkt. */
    const val INDEX_MATCH_EVENT_DEDUP: String =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_match_event_origin_device_uid_origin_seq` " +
            "ON `match_event` (`origin_device_uid`, `origin_seq`)"

    const val TABELLE_PARTICIPANT_STATE: String =
        "CREATE TABLE IF NOT EXISTS `participant_state` (" +
            "`participant_uid` TEXT NOT NULL, `match_uid` TEXT NOT NULL, `life` INTEGER NOT NULL, " +
            "`is_revealed` INTEGER NOT NULL, `is_eliminated` INTEGER NOT NULL, " +
            "`last_applied_seq` INTEGER NOT NULL, PRIMARY KEY(`participant_uid`))"

    const val INDEX_PARTICIPANT_STATE_MATCH: String =
        "CREATE INDEX IF NOT EXISTS `index_participant_state_match_uid` " +
            "ON `participant_state` (`match_uid`)"

    const val TABELLE_PARTICIPANT_COUNTER: String =
        "CREATE TABLE IF NOT EXISTS `participant_counter` (" +
            "`participant_uid` TEXT NOT NULL, `counter_key` TEXT NOT NULL, `match_uid` TEXT NOT NULL, " +
            "`value` INTEGER NOT NULL, PRIMARY KEY(`participant_uid`, `counter_key`))"

    const val INDEX_PARTICIPANT_COUNTER_MATCH: String =
        "CREATE INDEX IF NOT EXISTS `index_participant_counter_match_uid` " +
            "ON `participant_counter` (`match_uid`)"

    const val TABELLE_MATCH_STATE: String =
        "CREATE TABLE IF NOT EXISTS `match_state` (" +
            "`match_uid` TEXT NOT NULL, `turn_number` INTEGER NOT NULL, " +
            "`active_participant_uid` TEXT, `last_applied_seq` INTEGER NOT NULL, " +
            "PRIMARY KEY(`match_uid`))"

    // ── Trigger (T-025b) ────────────────────────────────────────────────────

    /** Kein Löschen. Nie. Ein Undo markiert (TDD 5.3), es entfernt nicht. */
    const val TRIGGER_KEIN_LOESCHEN: String =
        "CREATE TRIGGER IF NOT EXISTS `match_event_kein_loeschen` " +
            "BEFORE DELETE ON `match_event` " +
            "BEGIN SELECT RAISE(ABORT, " +
            "'match_event ist append-only: es wird nie ein Event geloescht (TDD 5.1). " +
            "Ein Undo markiert, es entfernt nicht (TDD 5.3).'); END"

    /**
     * Der Inhalt eines Events ist unveränderlich.
     *
     * `IS NOT` statt `<>`, weil `NULL <> NULL` in SQL nicht `true` ist: Mit
     * `<>` würde jede Änderung, an der ein `NULL` beteiligt ist, unbemerkt
     * durchgehen — also gerade die Nutzdatenfelder.
     */
    const val TRIGGER_UNVERAENDERLICH: String =
        "CREATE TRIGGER IF NOT EXISTS `match_event_unveraenderlich` " +
            "BEFORE UPDATE ON `match_event` FOR EACH ROW WHEN " +
            "     OLD.`event_uid` IS NOT NEW.`event_uid`" +
            "  OR OLD.`match_uid` IS NOT NEW.`match_uid`" +
            "  OR OLD.`origin_device_uid` IS NOT NEW.`origin_device_uid`" +
            "  OR OLD.`origin_seq` IS NOT NEW.`origin_seq`" +
            "  OR OLD.`lamport_clock` IS NOT NEW.`lamport_clock`" +
            "  OR OLD.`occurred_at` IS NOT NEW.`occurred_at`" +
            "  OR OLD.`type` IS NOT NEW.`type`" +
            "  OR OLD.`event_class` IS NOT NEW.`event_class`" +
            "  OR OLD.`visibility` IS NOT NEW.`visibility`" +
            "  OR OLD.`payload_json` IS NOT NEW.`payload_json`" +
            "  OR OLD.`payload_ciphertext` IS NOT NEW.`payload_ciphertext`" +
            "  OR OLD.`enc_scheme` IS NOT NEW.`enc_scheme`" +
            "  OR OLD.`payload_schema_version` IS NOT NEW.`payload_schema_version`" +
            "  OR OLD.`actor_participant_uid` IS NOT NEW.`actor_participant_uid`" +
            "  OR OLD.`target_participant_uid` IS NOT NEW.`target_participant_uid`" +
            "  OR OLD.`recipient_participant_uid` IS NOT NEW.`recipient_participant_uid`" +
            "  OR (OLD.`seq` IS NOT NULL AND OLD.`seq` IS NOT NEW.`seq`) " +
            "BEGIN SELECT RAISE(ABORT, " +
            "'Der Inhalt eines Events ist unveraenderlich (TDD 5.1). Veraenderbar sind nur die " +
            "Koordinationsfelder: seq und recorded_at bei der Bestaetigung durch den Host, " +
            "is_undone/undone_by_event_uid beim Undo, has_conflict.'); END"

    /**
     * PUBLIC und PRIVATE werden nicht vermischt.
     *
     * Vier Fälle, jeder einzeln aus TDD 5.2:
     * Klartext nur bei `PUBLIC` · Chiffrat nur sonst ·
     * Empfänger nur bei `PRIVATE` · `PRIVATE` immer mit Empfänger.
     */
    const val TRIGGER_SICHTBARKEIT: String =
        "CREATE TRIGGER IF NOT EXISTS `match_event_sichtbarkeit` " +
            "BEFORE INSERT ON `match_event` FOR EACH ROW WHEN " +
            "     (NEW.`visibility` = 'PUBLIC' AND NEW.`payload_ciphertext` IS NOT NULL)" +
            "  OR (NEW.`visibility` <> 'PUBLIC' AND NEW.`payload_json` IS NOT NULL)" +
            "  OR (NEW.`visibility` <> 'PRIVATE' AND NEW.`recipient_participant_uid` IS NOT NULL)" +
            "  OR (NEW.`visibility` = 'PRIVATE' AND NEW.`recipient_participant_uid` IS NULL) " +
            "BEGIN SELECT RAISE(ABORT, " +
            "'PUBLIC und PRIVATE duerfen nicht vermischt werden (TDD 5.2/7.3): payload_json nur bei " +
            "PUBLIC, payload_ciphertext nur sonst, recipient_participant_uid nur bei PRIVATE.'); END"

    // ── Zusammenstellungen ──────────────────────────────────────────────────

    /** Alles, was die Migration 1 → 2 anlegt. */
    val TABELLEN: List<String> = listOf(
        TABELLE_MATCH_EVENT,
        INDEX_MATCH_EVENT_REPLAY,
        INDEX_MATCH_EVENT_DEDUP,
        TABELLE_PARTICIPANT_STATE,
        INDEX_PARTICIPANT_STATE_MATCH,
        TABELLE_PARTICIPANT_COUNTER,
        INDEX_PARTICIPANT_COUNTER_MATCH,
        TABELLE_MATCH_STATE,
    )

    /** Die Zusicherungen. Auch bei `onCreate` nötig — Room legt sie nicht an. */
    val TRIGGER: List<String> = listOf(
        TRIGGER_KEIN_LOESCHEN,
        TRIGGER_UNVERAENDERLICH,
        TRIGGER_SICHTBARKEIT,
    )

    /** Die Namen der Trigger — für den Wächtertest. */
    val TRIGGER_NAMEN: List<String> = listOf(
        "match_event_kein_loeschen",
        "match_event_unveraenderlich",
        "match_event_sichtbarkeit",
    )
}
