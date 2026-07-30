package de.myhornets.rise1.tools.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-010 — Prüfsumme. Fester Vektor, damit ein Wechsel der Umgebung auffällt.
 */
class SourceChecksumTest {

    @Test
    fun `bekannter Testvektor`() {
        // SHA-256 von "abc" — der Standardvektor
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            SourceChecksum.of("abc".toByteArray()),
        )
    }

    @Test
    fun `leere Eingabe`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SourceChecksum.of(ByteArray(0)),
        )
    }

    @Test
    fun `ein geaendertes Byte aendert die Summe`() {
        assertFalse(SourceChecksum.of("abc".toByteArray()) == SourceChecksum.of("abd".toByteArray()))
    }

    @Test
    fun `Vergleich ignoriert Leerraum und Grossschreibung`() {
        val s = SourceChecksum.of("abc".toByteArray())
        assertTrue(SourceChecksum.matches(s, "  ${s.uppercase()}  "))
        assertFalse(SourceChecksum.matches(s, s.dropLast(1) + "0"))
    }
}
