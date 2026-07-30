package de.myhornets.rise1.tools.catalog

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-013 — Erzeugung von `catalog.db`. */
class CatalogDatabaseTest {

    private val ordner: File = Files.createTempDirectory("rise1-db").toFile()
    private val ziel = File(ordner, "catalog.db")

    @AfterTest fun aufraeumen() { ordner.deleteRecursively() }

    private fun identity(n: Int, name: String, role: String = "guardian", cost: String? = "{3}") = Identity(
        identityUid = "TRD-2025:${n.toString().padStart(3, '0')}", setCode = "TRD-2025",
        cardNumber = n, name = name, slugAscii = SlugAscii.of(name), role = role,
        color = "blue", typeLine = "Identity — X", rarity = "R",
        textRaw = "Undercover|Unveil {3}|Wirkung.", flavor = null, artist = "Jemand",
        unveilCost = cost, imageAsset = "${SlugAscii.of(name)}.jpg",
        sourceUri = "https://example.invalid/$n",
    )

    private fun daten(vararg i: Identity) = CatalogData(
        meta = CatalogMeta("1", "0.2", "TRD-2025", "https://example.invalid/x.json", "abc123", "2026-07-30T00:00:00Z"),
        cardSet = CardSet("TRD-2025", "Treachery", "EN", i.size, 2025, false),
        identities = i.toList(),
        rulings = i.flatMapIndexed { idx, id ->
            listOf(IdentityRuling("${id.identityUid}:r01", id.identityUid, 1, "Ruling $idx."))
        },
    )

    private val beispiel = daten(
        identity(1, "The Ætherist"),
        identity(2, "The Augur"),
        identity(50, "The Blood Empress", role = "leader", cost = null),
    )

    private fun <T> abfrage(sql: String, lies: (java.sql.ResultSet) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${ziel.absolutePath}").use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); lies(r) } }
        }

    @Test
    fun `alle Zeilen landen in der Datenbank`() {
        CatalogDatabase().schreibe(beispiel, ziel)
        assertEquals(3, abfrage("SELECT COUNT(*) FROM identity") { it.getInt(1) })
        assertEquals(3, abfrage("SELECT COUNT(*) FROM identity_ruling") { it.getInt(1) })
        assertEquals(1, abfrage("SELECT COUNT(*) FROM card_set") { it.getInt(1) })
        assertEquals(1, abfrage("SELECT COUNT(*) FROM catalog_meta") { it.getInt(1) })
    }

    @Test
    fun `Unicode ueberlebt die Rundreise`() {
        CatalogDatabase().schreibe(beispiel, ziel)
        assertEquals("The Ætherist", abfrage("SELECT name FROM identity WHERE card_number = 1") { it.getString(1) })
    }

    @Test
    fun `unveil_cost darf null sein`() {
        // Leader haben keinen — die Spalte ist deshalb nullable, siehe T-011.
        CatalogDatabase().schreibe(beispiel, ziel)
        assertNull(abfrage("SELECT unveil_cost FROM identity WHERE card_number = 50") { it.getString(1) })
        assertEquals("{3}", abfrage("SELECT unveil_cost FROM identity WHERE card_number = 1") { it.getString(1) })
    }

    @Test
    fun `Prüfsumme und SQLite-Version stehen in catalog_meta`() {
        CatalogDatabase().schreibe(beispiel, ziel)
        assertEquals("abc123", abfrage("SELECT source_checksum FROM catalog_meta") { it.getString(1) })
        val version = abfrage("SELECT sqlite_version FROM catalog_meta") { it.getString(1) }
        assertTrue(version.isNotBlank(), "SQLite-Version fehlt")
    }

    @Test
    fun `zweimal gebaut ergibt bit-gleiche Dateien`() {
        // Der Kern von T-013: Ohne diese Zusage rauscht bei jedem Import ein
        // Binärunterschied durch die Versionsverwaltung.
        val e = CatalogDatabase().buildeZweimal(beispiel, ziel)
        assertTrue(e.bitgleich, "nicht reproduzierbar: ${e.ersteSumme} / ${e.zweiteSumme}")
        assertEquals(e.ersteSumme, e.zweiteSumme)
        assertTrue(e.bytes > 0)
    }

    @Test
    fun `geaenderte Daten aendern die Pruefsumme`() {
        // Die Gegenprobe: Reproduzierbar heißt nicht unempfindlich.
        val a = CatalogDatabase().buildeZweimal(beispiel, ziel).ersteSumme
        val anders = daten(identity(1, "The Ætherist"), identity(2, "Ein anderer Name"), identity(50, "X", "leader", null))
        val b = CatalogDatabase().buildeZweimal(anders, ziel).ersteSumme
        assertTrue(a != b, "verschiedene Daten, gleiche Prüfsumme")
    }

    @Test
    fun `die Reihenfolge der Eingabe aendert das Ergebnis nicht`() {
        val a = CatalogDatabase().buildeZweimal(beispiel, ziel).ersteSumme
        val gedreht = beispiel.copy(identities = beispiel.identities.reversed(), rulings = beispiel.rulings.reversed())
        val b = CatalogDatabase().buildeZweimal(gedreht, ziel).ersteSumme
        assertEquals(a, b, "Sortierung greift nicht — die Datei hängt an der Eingabereihenfolge")
    }

    @Test
    fun `eine bestehende Datei wird ersetzt, nicht ergaenzt`() {
        CatalogDatabase().schreibe(beispiel, ziel)
        CatalogDatabase().schreibe(beispiel, ziel)
        assertEquals(3, abfrage("SELECT COUNT(*) FROM identity") { it.getInt(1) })
    }

    @Test
    fun `Indizes fuer Anzeige und Filter sind vorhanden`() {
        // Anzeige, Suche, Filter — nicht mehr. Keine Regellogik im Schema.
        CatalogDatabase().schreibe(beispiel, ziel)
        val namen = DriverManager.getConnection("jdbc:sqlite:${ziel.absolutePath}").use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%'")
                    .use { r -> generateSequence { if (r.next()) r.getString(1) else null }.toList() }
            }
        }
        listOf("index_identity_role", "index_identity_color", "index_identity_slug_ascii")
            .forEach { assertTrue(it in namen, "Index $it fehlt — gefunden: $namen") }
    }
}
