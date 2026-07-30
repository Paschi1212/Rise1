package de.myhornets.rise1.tools.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** T-011 — Abbildung auf das Zielschema aus TDD 4.1. */
class IdentityMapperTest {

    private val mapper = IdentityMapper(setCode = "TRD-2025", katalogVersion = "1")

    private fun karte(
        id: Int,
        name: String,
        subtype: String = "Guardian",
        text: String = "Undercover|Unveil {3}|Wirkung.",
        flavor: String = "",
        rulings: List<String> = emptyList(),
    ) = mapOf(
        "id" to id, "name" to name, "name_anchor" to "anchor-$id",
        "uri" to "https://mtgtreachery.net/rules/oracle/?card=anchor-$id",
        "cost" to "", "cmc" to 0, "color" to "blue",
        "type" to "Identity — $subtype",
        "types" to mapOf("supertype" to "Identity", "subtype" to subtype),
        "rarity" to "R", "text" to text, "flavor" to flavor,
        "artist" to "Jemand", "rulings" to rulings,
    )

    private fun quelle(vararg karten: Map<String, Any?>) = mapOf(
        "game_variant" to "MTG Treachery", "api_author" to "Stefouch, Tymbaroth",
        "api_version" to 0.2, "set_name" to "Treachery", "set_code" to "TRD-2025",
        "set_lang" to "EN", "cards_count" to karten.size, "cards" to karten.toList(),
    )

    private fun map(vararg karten: Map<String, Any?>) =
        mapper.map(quelle(*karten), "https://example.invalid/x.json", "abc123", "2026-07-30T00:00:00Z")

    @Test
    fun `eine Karte wird vollstaendig abgebildet`() {
        val r = map(karte(1, "The Ætherist"))
        assertTrue(r.isValid, r.render())
        val i = r.data!!.identities.single()
        assertEquals("TRD-2025:001", i.identityUid)
        assertEquals(1, i.cardNumber)
        assertEquals("The Ætherist", i.name)
        assertEquals("the-aetherist", i.slugAscii)
        assertEquals("guardian", i.role)
        assertEquals("Identity — Guardian", i.typeLine)
        assertEquals("{3}", i.unveilCost)
        assertNull(i.imageAsset)
    }

    @Test
    fun `die UID ist dreistellig aufgefuellt`() {
        val r = map(karte(7, "Sieben"), karte(62, "Zweiundsechzig"))
        assertEquals(listOf("TRD-2025:007", "TRD-2025:062"), r.data!!.identities.map { it.identityUid })
    }

    @Test
    fun `leerer Flavourtext wird zu null`() {
        assertNull(map(karte(1, "A", flavor = "")).data!!.identities.single().flavor)
        assertNull(map(karte(1, "A", flavor = "   ")).data!!.identities.single().flavor)
        assertEquals("Ein Spruch.", map(karte(1, "A", flavor = "Ein Spruch.")).data!!.identities.single().flavor)
    }

    @Test
    fun `Leader bekommt keinen Unveil-Cost`() {
        val r = map(karte(50, "The Blood Empress", subtype = "Leader",
            text = "(Start the game with this identity face up in the command zone.)|Wirkung."))
        assertNull(r.data!!.identities.single().unveilCost)
        assertEquals("leader", r.data!!.identities.single().role)
    }

    @Test
    fun `Rulings werden durchnummeriert`() {
        val r = map(karte(1, "A", rulings = listOf("Erstens.", "Zweitens.", "Drittens.")))
        val rl = r.data!!.rulings
        assertEquals(3, rl.size)
        assertEquals(listOf(1, 2, 3), rl.map { it.ordinal })
        assertEquals(listOf("TRD-2025:001:r01", "TRD-2025:001:r02", "TRD-2025:001:r03"), rl.map { it.rulingUid })
        assertTrue(rl.all { it.identityUid == "TRD-2025:001" })
    }

    @Test
    fun `leere Rulings werden uebergangen`() {
        val r = map(karte(1, "A", rulings = listOf("Erstens.", "   ", "Drittens.")))
        assertEquals(2, r.data!!.rulings.size)
    }

    @Test
    fun `doppelter Slug wird gemeldet`() {
        // Zwei verschiedene Namen, ein Slug — genau der Fall, den TDD 4.1 mit
        // `unique` ausschließt und den erst das Abbilden sichtbar macht.
        val r = map(karte(1, "Death's Shadow"), karte(2, "Death’s Shadow"))
        assertTrue(!r.isValid)
        assertTrue(r.findings.any { it.message.contains("deaths-shadow") })
        assertNull(r.data)
    }

    @Test
    fun `unbekannte Rolle wird gemeldet`() {
        val r = map(karte(1, "A", subtype = "Kingmaker"))
        assertTrue(!r.isValid)
        assertTrue(r.findings.any { it.message.contains("Kingmaker") })
    }

    @Test
    fun `Kopfdaten landen in Meta und CardSet`() {
        val r = map(karte(1, "A"))
        val d = r.data!!
        assertEquals("TRD-2025", d.meta.sourceSetCode)
        assertEquals("abc123", d.meta.sourceChecksum)
        assertEquals("2026-07-30T00:00:00Z", d.meta.importedAt)
        assertEquals("Treachery", d.cardSet.name)
        assertEquals("EN", d.cardSet.lang)
        assertEquals(2025, d.cardSet.releaseYear)
        assertEquals(1, d.cardSet.cardCount)
        assertTrue(!d.cardSet.isOfficial)
    }

    @Test
    fun `Bericht nennt Rollenverteilung und Unveil-Statistik`() {
        val r = map(
            karte(1, "Eins", subtype = "Guardian"),
            karte(50, "Fuenfzig", subtype = "Leader", text = "(Face up.)|Wirkung."),
        )
        val text = r.render()
        assertTrue(text.contains("Rolle guardian: 1"), text)
        assertTrue(text.contains("Rolle leader: 1"), text)
        assertTrue(text.contains("1 ohne (Leader)"), text)
    }
}
