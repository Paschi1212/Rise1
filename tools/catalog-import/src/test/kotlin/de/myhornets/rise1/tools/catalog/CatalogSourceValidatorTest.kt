package de.myhornets.rise1.tools.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-010 — Prüfung der Validierungsregeln.
 *
 * Ohne Datei, ohne Parser, ohne Netz: Die Eingaben werden von Hand gebaut.
 * Genau dafür kennt [CatalogSourceValidator] kein JSON.
 */
class CatalogSourceValidatorTest {

    private val pool = mapOf("Leader" to 13, "Guardian" to 18, "Assassin" to 18, "Traitor" to 13)

    private fun karte(id: Int, subtype: String) = mapOf(
        "id" to id,
        "name" to "Karte $id",
        "name_anchor" to "karte-$id",
        "uri" to "https://example.invalid/$id",
        "type" to "Identity — $subtype",
        "types" to mapOf("supertype" to "Identity", "subtype" to subtype),
        "text" to "Undercover|Unveil {3}|Wirkung.",
        "artist" to "Jemand",
    )

    /** Baut eine Quelle mit genau der offiziellen Poolverteilung 13/18/18/13. */
    private fun gueltigeQuelle(): Map<String, Any?> {
        val karten = mutableListOf<Map<String, Any?>>()
        var id = 1
        pool.forEach { (rolle, anzahl) -> repeat(anzahl) { karten += karte(id++, rolle) } }
        return mapOf(
            "game_variant" to "MTG Treachery",
            "api_author" to "Stefouch, Tymbaroth",
            "api_version" to 0.2,
            "set_name" to "Treachery",
            "set_code" to "TRD-2025",
            "set_lang" to "EN",
            "cards_count" to 62,
            "cards" to karten,
        )
    }

    @Test
    fun `gueltige Quelle wird angenommen`() {
        val bericht = CatalogSourceValidator().validate(gueltigeQuelle())
        assertTrue(bericht.isValid, bericht.render())
        assertEquals(62, bericht.header!!.cardsCount)
        assertEquals("TRD-2025", bericht.header!!.setCode)
        assertEquals("EN", bericht.header!!.setLang)
    }

    @Test
    fun `fehlendes Kopffeld wird erkannt`() {
        val bericht = CatalogSourceValidator().validate(gueltigeQuelle() - "set_code")
        assertFalse(bericht.isValid)
        assertTrue(bericht.findings.any { it is Finding.Fehlend && it.message.contains("set_code") })
    }

    @Test
    fun `abweichende Kartenzahl wird erkannt`() {
        val q = gueltigeQuelle().toMutableMap()
        q["cards"] = (q["cards"] as List<*>).drop(1)
        val bericht = CatalogSourceValidator().validate(q)
        assertFalse(bericht.isValid)
        assertTrue(bericht.findings.any { it is Finding.Anzahl })
    }

    @Test
    fun `widerspruch zwischen cards_count und Liste wird erkannt`() {
        val q = gueltigeQuelle().toMutableMap()
        q["cards_count"] = 61
        val bericht = CatalogSourceValidator().validate(q)
        assertFalse(bericht.isValid)
        assertTrue(bericht.findings.any { it is Finding.Anzahl && it.message.contains("61") })
    }

    @Test
    fun `luecke in der Nummerierung wird erkannt`() {
        val q = gueltigeQuelle().toMutableMap()
        val karten = (q["cards"] as List<*>).map { it as Map<*, *> }.toMutableList()
        karten[5] = karten[5].toMutableMap().apply { this["id"] = 999 }
        q["cards"] = karten
        val bericht = CatalogSourceValidator().validate(q)
        assertFalse(bericht.isValid)
        assertTrue(bericht.findings.any { it is Finding.Nummerierung && it.message.contains("Fehlende") })
        assertTrue(bericht.findings.any { it is Finding.Nummerierung && it.message.contains("außerhalb") })
    }

    @Test
    fun `doppelte ID wird erkannt`() {
        val q = gueltigeQuelle().toMutableMap()
        val karten = (q["cards"] as List<*>).map { it as Map<*, *> }.toMutableList()
        karten[5] = karten[5].toMutableMap().apply { this["id"] = karten[4]["id"] }
        q["cards"] = karten
        val bericht = CatalogSourceValidator().validate(q)
        assertFalse(bericht.isValid)
        assertTrue(bericht.findings.any { it is Finding.Nummerierung && it.message.contains("Doppelte") })
    }

    @Test
    fun `falsche Poolverteilung wird erkannt`() {
        // Ein Guardian wird zum Assassin: 13/17/19/13 statt 13/18/18/13
        val q = gueltigeQuelle().toMutableMap()
        val karten = (q["cards"] as List<*>).map { it as Map<*, *> }.toMutableList()
        val idx = karten.indexOfFirst { (it["types"] as Map<*, *>)["subtype"] == "Guardian" }
        karten[idx] = karten[idx].toMutableMap().apply {
            this["types"] = mapOf("supertype" to "Identity", "subtype" to "Assassin")
        }
        q["cards"] = karten
        val bericht = CatalogSourceValidator().validate(q)
        assertFalse(bericht.isValid)
        assertTrue(bericht.findings.any { it is Finding.Pool && it.message.contains("Guardian") })
        assertTrue(bericht.findings.any { it is Finding.Pool && it.message.contains("Assassin") })
    }

    @Test
    fun `unbekannter subtype wird erkannt`() {
        val q = gueltigeQuelle().toMutableMap()
        val karten = (q["cards"] as List<*>).map { it as Map<*, *> }.toMutableList()
        karten[0] = karten[0].toMutableMap().apply {
            this["types"] = mapOf("supertype" to "Identity", "subtype" to "Kingmaker")
        }
        q["cards"] = karten
        val bericht = CatalogSourceValidator().validate(q)
        assertFalse(bericht.isValid)
        assertTrue(bericht.findings.any { it is Finding.Wert && it.message.contains("Kingmaker") })
    }

    @Test
    fun `falscher supertype wird erkannt`() {
        val q = gueltigeQuelle().toMutableMap()
        val karten = (q["cards"] as List<*>).map { it as Map<*, *> }.toMutableList()
        karten[0] = karten[0].toMutableMap().apply {
            this["types"] = mapOf("supertype" to "Creature", "subtype" to "Leader")
        }
        q["cards"] = karten
        val bericht = CatalogSourceValidator().validate(q)
        assertFalse(bericht.isValid)
        assertTrue(bericht.findings.any { it is Finding.Wert && it.message.contains("Creature") })
    }

    @Test
    fun `leerer Pflichttext wird erkannt`() {
        val q = gueltigeQuelle().toMutableMap()
        val karten = (q["cards"] as List<*>).map { it as Map<*, *> }.toMutableList()
        karten[0] = karten[0].toMutableMap().apply { this["artist"] = "  " }
        q["cards"] = karten
        val bericht = CatalogSourceValidator().validate(q)
        assertFalse(bericht.isValid)
        assertTrue(bericht.findings.any { it is Finding.Fehlend && it.message.contains("artist") })
    }

    @Test
    fun `ungepflegte Felder cost und cmc stoeren nicht`() {
        // In der Quelle sind sie leer bzw. 0, obwohl ein Unveil-Cost existiert.
        // Sie werden bewusst nicht geprüft und nicht übernommen.
        val q = gueltigeQuelle().toMutableMap()
        val karten = (q["cards"] as List<*>).map { it as Map<*, *> }.toMutableList()
        karten[0] = karten[0].toMutableMap().apply {
            this["cost"] = ""
            this["cmc"] = 0
        }
        q["cards"] = karten
        assertTrue(CatalogSourceValidator().validate(q).isValid)
    }

    @Test
    fun `fehlende cards-Liste bricht sofort ab`() {
        val bericht = CatalogSourceValidator().validate(gueltigeQuelle() - "cards")
        assertFalse(bericht.isValid)
        assertTrue(bericht.findings.any { it is Finding.Struktur })
        assertEquals(null, bericht.header)
    }

    @Test
    fun `Bericht nennt bei Fehlern die Anzahl der Befunde`() {
        val bericht = CatalogSourceValidator().validate(gueltigeQuelle() - "set_code" - "set_lang")
        assertTrue(bericht.render().contains("2 Befund"))
        assertTrue(bericht.render().contains("Der Import wird abgebrochen"))
    }
}
