package de.myhornets.rise1.store

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-025b — der Nachweis, dass die Migration 1 → 2 den Bestand erhält.
 *
 * ## Warum dieser Test der teuerste des Slices ist und trotzdem sein muss
 *
 * `T-020` hat festgehalten: `rise.db` wird **niemals ersetzt**, deshalb steht
 * in [RiseStore] kein `fallbackToDestructiveMigration()`. Das war bis heute
 * eine Zusage ohne Anwendungsfall — es gab nur Version 1.
 *
 * Eine Migration, die nie an einer Datei mit Inhalt gelaufen ist, ist eine
 * Vermutung. Der teure Teil ist nicht das Anlegen der neuen Tabellen; der teure
 * Teil ist die Frage, ob die **alten** danach noch dieselben sind. Genau das
 * prüft [MigrationTestHelper.runMigrationsAndValidate]: Es vergleicht das
 * Ergebnis Spalte für Spalte mit dem exportierten Schema unter
 * `store/schemas/…/2.json`.
 *
 * ## Voraussetzung
 *
 * Der Schemaexport muss dem Testlauf als Asset vorliegen — dafür sorgt
 * `sourceSets["androidTest"].assets.srcDir(…)` in `store/build.gradle.kts`.
 * Fehlt er, scheitert der Helfer beim Anlegen. Das ist die richtige Reaktion:
 * grün ohne Vergleich wäre schlimmer.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helfer: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        RiseDatabase::class.java,
    )

    @Test
    fun migrationVonEinsAufZweiErhaeltDenBestand() {
        // Eine Version-1-Datenbank mit echtem Inhalt: ein Gerät, ein Spieler,
        // eine Partie, ein Sitzplatz. Nichts davon darf sich ändern.
        helfer.createDatabase(DATENBANK, 1).use { alt ->
            alt.execSQL(
                "INSERT INTO device (device_uid, display_name, platform, is_self, last_seen_at, " +
                    "created_at, updated_at, deleted_at, origin_device_uid) " +
                    "VALUES ('d-1', 'Tisch', 'android', 1, NULL, 100, 100, NULL, 'd-1')",
            )
            alt.execSQL(
                "INSERT INTO player (player_uid, display_name, avatar_ref, color_tag, is_guest, " +
                    "linked_device_uid, created_at, updated_at, deleted_at, origin_device_uid) " +
                    "VALUES ('s-1', 'Paschi', NULL, NULL, 0, NULL, 100, 100, NULL, 'd-1')",
            )
            alt.execSQL(
                "INSERT INTO `match` (match_uid, mode_uid, host_device_uid, status, " +
                    "host_heartbeat_at, player_count, catalog_version_used, set_code_used, " +
                    "deal_transcript_digest, moderator_mode, started_at, ended_at, last_applied_seq, " +
                    "created_at, updated_at, deleted_at, origin_device_uid) " +
                    "VALUES ('m-1', 'mode-1', 'd-1', 'setup', NULL, 4, '1', 'TRD-2025', " +
                    "NULL, 0, NULL, NULL, 0, 100, 100, NULL, 'd-1')",
            )
            alt.execSQL(
                "INSERT INTO match_participant (participant_uid, match_uid, player_uid, seat_index, " +
                    "display_name_snapshot, identity_commitment, is_revealed, revealed_at_seq, " +
                    "revealed_identity_uid, identity_name_snapshot, identity_role_snapshot, " +
                    "final_status, placement, rejoin_token_hash, seat_state, last_seen_at, " +
                    "created_at, updated_at, deleted_at, origin_device_uid) " +
                    "VALUES ('p-1', 'm-1', 's-1', 0, 'Paschi', NULL, 0, NULL, NULL, NULL, NULL, " +
                    "NULL, NULL, NULL, 'active', NULL, 100, 100, NULL, 'd-1')",
            )
        }

        // `validateDroppedTables = true`: Auch eine Tabelle, die die Migration
        // vergessen hätte zu entfernen, wäre ein Befund.
        val neu = helfer.runMigrationsAndValidate(DATENBANK, 2, true, MIGRATION_1_2)

        neu.query("SELECT status, player_count FROM `match` WHERE match_uid = 'm-1'").use { c ->
            assertTrue("Die Partie hat die Migration nicht überlebt.", c.moveToFirst())
            assertEquals("setup", c.getString(0))
            assertEquals(4, c.getInt(1))
        }
        neu.query("SELECT display_name_snapshot, seat_index FROM match_participant").use { c ->
            assertTrue("Der Sitzplatz hat die Migration nicht überlebt.", c.moveToFirst())
            assertEquals("Paschi", c.getString(0))
            assertEquals(0, c.getInt(1))
        }
        neu.query("SELECT COUNT(*) FROM device").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }

        // Die neuen Tabellen sind da und leer — eine Migration erfindet keine Daten.
        neu.query("SELECT COUNT(*) FROM match_event").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }
        listOf("participant_state", "participant_counter", "match_state").forEach { tabelle ->
            neu.query("SELECT COUNT(*) FROM `$tabelle`").use { c ->
                c.moveToFirst(); assertEquals("$tabelle ist nicht leer.", 0, c.getInt(0))
            }
        }

        // Und die Zusicherungen sind mitgekommen.
        val trigger = mutableSetOf<String>()
        neu.query("SELECT name FROM sqlite_master WHERE type='trigger'").use { c ->
            while (c.moveToNext()) trigger += c.getString(0)
        }
        assertTrue(
            "Nach der Migration fehlen Trigger: gefunden $trigger, erwartet ${EventLogSql.TRIGGER_NAMEN}.",
            trigger.containsAll(EventLogSql.TRIGGER_NAMEN),
        )
        neu.close()
    }

    private companion object {
        const val DATENBANK = "rise-migration-test.db"
    }
}
