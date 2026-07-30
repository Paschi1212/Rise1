package de.myhornets.rise1.tools.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T-011 — Slug-Erzeugung. Die Beispiele stammen aus der echten Quelle. */
class SlugAsciiTest {

    @Test
    fun `Ae wird zu ae statt wegzufallen`() {
        // Der Quell-Slug lautet hier "the-Ætherist" und ist damit unbrauchbar.
        assertEquals("the-aetherist", SlugAscii.of("The Ætherist"))
    }

    @Test
    fun `gewoehnlicher Name`() {
        assertEquals("the-augur", SlugAscii.of("The Augur"))
        assertEquals("the-blood-empress", SlugAscii.of("The Blood Empress"))
    }

    @Test
    fun `Akzente werden zerlegt`() {
        assertEquals("cafe", SlugAscii.of("Café"))
        assertEquals("uber", SlugAscii.of("Über"))
    }

    @Test
    fun `Apostroph verschwindet ohne Trenner`() {
        assertEquals("deaths-shadow", SlugAscii.of("Death's Shadow"))
        assertEquals("deaths-shadow", SlugAscii.of("Death’s Shadow"))
    }

    @Test
    fun `Interpunktion und Mehrfachleerzeichen werden zu einem Trenner`() {
        assertEquals("a-b-c", SlugAscii.of("A -- B,  C"))
    }

    @Test
    fun `kein fuehrender oder abschliessender Trenner`() {
        val slug = SlugAscii.of("  ...The End!  ")
        assertEquals("the-end", slug)
        assertTrue(!slug.startsWith("-") && !slug.endsWith("-"))
    }

    @Test
    fun `Ergebnis ist immer reines ASCII`() {
        listOf("The Ætherist", "Café", "Über", "Œuvre", "Straße", "Þorn", "Øre")
            .forEach { name ->
                val slug = SlugAscii.of(name)
                assertTrue(
                    slug.all { it in 'a'..'z' || it in '0'..'9' || it == '-' },
                    "Slug '$slug' aus '$name' ist nicht ASCII-rein",
                )
                assertTrue(slug.isNotEmpty(), "Slug aus '$name' ist leer geworden")
            }
    }

    @Test
    fun `Duplikate werden gemeldet`() {
        val treffer = SlugAscii.duplikate(listOf("a", "b", "a", "c", "a"))
        assertEquals(mapOf("a" to 3), treffer)
    }

    @Test
    fun `ohne Duplikate ist die Meldung leer`() {
        assertTrue(SlugAscii.duplikate(listOf("a", "b", "c")).isEmpty())
    }
}
