package de.myhornets.rise1.tools.catalog

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Erzeugt `catalog.db` aus den abgebildeten Daten. T-013, TDD 4.1.
 *
 * **Die Datenbank speichert beschreibende Daten und bereitet keine Regellogik
 * vor** — Architekturprinzip aus dem Projekt-README. Es gibt keine abgeleiteten
 * Spalten, keine Kostenzerlegung, keine Wirkungsfelder. `text_raw` und
 * `unveil_cost` sind Zeichenketten, die angezeigt und durchsucht werden.
 *
 * **Reproduzierbarkeit ist eine Zusage, die geprüft wird.** SQLite bettet weder
 * Zeitstempel noch Zufall ein; dieselbe Eingabe ergibt bit-gleich dieselbe
 * Datei — aber nur bei gleicher SQLite-Version, gleicher Seitengröße und
 * gleicher Reihenfolge der Operationen. Deshalb sind alle drei hier
 * festgeschrieben, und [buildeZweimal] prüft das Ergebnis, statt ihm zu trauen.
 */
public class CatalogDatabase(
    private val seitengroesse: Int = 4096,
) {

    /** Feste Reihenfolge: Sie ist Teil der Reproduzierbarkeit, kein Stilfrage. */
    private fun sortiert(daten: CatalogData) = Pair(
        daten.identities.sortedBy { it.cardNumber },
        daten.rulings.sortedWith(compareBy({ it.identityUid }, { it.ordinal })),
    )

    public fun schreibe(daten: CatalogData, ziel: File) {
        if (ziel.exists()) ziel.delete()
        ziel.parentFile?.mkdirs()

        DriverManager.getConnection("jdbc:sqlite:${ziel.absolutePath}").use { c ->
            c.autoCommit = false
            c.createStatement().use { s ->
                s.execute("PRAGMA page_size = $seitengroesse")
                s.execute("PRAGMA journal_mode = DELETE")
                SCHEMA.forEach(s::execute)
            }
            fuelle(c, daten)
            c.commit()
            // VACUUM räumt die Seiten auf und macht die Datei unabhängig davon,
            // in welcher Reihenfolge SQLite intern Platz belegt hat.
            c.autoCommit = true
            c.createStatement().use { it.execute("VACUUM") }
        }
    }

    private fun fuelle(c: Connection, daten: CatalogData) {
        val (identitaeten, rulings) = sortiert(daten)

        c.prepareStatement(
            "INSERT INTO catalog_meta (id, catalog_version, source_api_version, source_set_code, " +
                "source_url, source_checksum, imported_at, sqlite_version) VALUES (1,?,?,?,?,?,?,?)"
        ).use { p ->
            val m = daten.meta
            p.setString(1, m.catalogVersion); p.setString(2, m.sourceApiVersion)
            p.setString(3, m.sourceSetCode); p.setString(4, m.sourceUrl)
            p.setString(5, m.sourceChecksum); p.setString(6, m.importedAt)
            p.setString(7, sqliteVersion(c))
            p.executeUpdate()
        }

        c.prepareStatement(
            "INSERT INTO card_set (set_code, name, lang, card_count, release_year, is_official) VALUES (?,?,?,?,?,?)"
        ).use { p ->
            val s = daten.cardSet
            p.setString(1, s.setCode); p.setString(2, s.name); p.setString(3, s.lang)
            p.setInt(4, s.cardCount)
            if (s.releaseYear != null) p.setInt(5, s.releaseYear) else p.setNull(5, java.sql.Types.INTEGER)
            p.setInt(6, if (s.isOfficial) 1 else 0)
            p.executeUpdate()
        }

        c.prepareStatement(
            "INSERT INTO identity (identity_uid, set_code, card_number, name, slug_ascii, role, color, " +
                "type_line, rarity, text_raw, flavor, artist, unveil_cost, image_asset, source_uri) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { p ->
            identitaeten.forEach { i ->
                p.setString(1, i.identityUid); p.setString(2, i.setCode); p.setInt(3, i.cardNumber)
                p.setString(4, i.name); p.setString(5, i.slugAscii); p.setString(6, i.role)
                p.setString(7, i.color); p.setString(8, i.typeLine); p.setString(9, i.rarity)
                p.setString(10, i.textRaw); p.setString(11, i.flavor); p.setString(12, i.artist)
                p.setString(13, i.unveilCost); p.setString(14, i.imageAsset); p.setString(15, i.sourceUri)
                p.addBatch()
            }
            p.executeBatch()
        }

        c.prepareStatement(
            "INSERT INTO identity_ruling (ruling_uid, identity_uid, ordinal, text) VALUES (?,?,?,?)"
        ).use { p ->
            rulings.forEach { r ->
                p.setString(1, r.rulingUid); p.setString(2, r.identityUid)
                p.setInt(3, r.ordinal); p.setString(4, r.text)
                p.addBatch()
            }
            p.executeBatch()
        }
    }

    public fun sqliteVersion(c: Connection): String =
        c.createStatement().use { s ->
            s.executeQuery("SELECT sqlite_version()").use { r -> r.next(); r.getString(1) }
        }

    /**
     * Baut zweimal und vergleicht. Der Nachweis, dass die Erzeugung
     * reproduzierbar ist — nicht die Zusage, dass sie es sei.
     */
    public fun buildeZweimal(daten: CatalogData, ziel: File): Reproduzierbarkeit {
        val probe = File(ziel.parentFile, "${ziel.name}.probe")
        schreibe(daten, ziel)
        schreibe(daten, probe)
        val a = SourceChecksum.of(ziel)
        val b = SourceChecksum.of(probe)
        probe.delete()
        return Reproduzierbarkeit(a == b, a, b, ziel.length())
    }

    public data class Reproduzierbarkeit(
        val bitgleich: Boolean,
        val ersteSumme: String,
        val zweiteSumme: String,
        val bytes: Long,
    )

    private companion object {
        /**
         * Schema nach TDD 4.1. Ausschließlich beschreibende Spalten — keine
         * abgeleiteten Regelfelder. `sqlite_version` in `catalog_meta` ist
         * ergänzt, damit ein späterer Binärunterschied zuzuordnen ist.
         */
        val SCHEMA = listOf(
            """CREATE TABLE catalog_meta (
                 id INTEGER PRIMARY KEY NOT NULL, catalog_version TEXT NOT NULL,
                 source_api_version TEXT NOT NULL, source_set_code TEXT NOT NULL,
                 source_url TEXT NOT NULL, source_checksum TEXT NOT NULL,
                 imported_at TEXT NOT NULL, sqlite_version TEXT NOT NULL)""",
            """CREATE TABLE card_set (
                 set_code TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, lang TEXT NOT NULL,
                 card_count INTEGER NOT NULL, release_year INTEGER, is_official INTEGER NOT NULL)""",
            """CREATE TABLE identity (
                 identity_uid TEXT PRIMARY KEY NOT NULL, set_code TEXT NOT NULL,
                 card_number INTEGER NOT NULL, name TEXT NOT NULL, slug_ascii TEXT NOT NULL,
                 role TEXT NOT NULL, color TEXT NOT NULL, type_line TEXT NOT NULL,
                 rarity TEXT NOT NULL, text_raw TEXT NOT NULL, flavor TEXT,
                 artist TEXT NOT NULL, unveil_cost TEXT, image_asset TEXT, source_uri TEXT NOT NULL)""",
            """CREATE TABLE identity_ruling (
                 ruling_uid TEXT PRIMARY KEY NOT NULL, identity_uid TEXT NOT NULL,
                 ordinal INTEGER NOT NULL, text TEXT NOT NULL)""",
            "CREATE UNIQUE INDEX index_identity_slug_ascii ON identity(slug_ascii)",
            "CREATE INDEX index_identity_role ON identity(role)",
            "CREATE INDEX index_identity_color ON identity(color)",
            "CREATE INDEX index_identity_set_code ON identity(set_code)",
            "CREATE INDEX index_identity_ruling_identity_uid ON identity_ruling(identity_uid)",
        )
    }
}
