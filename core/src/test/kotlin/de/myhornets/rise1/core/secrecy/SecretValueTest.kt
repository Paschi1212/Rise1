package de.myhornets.rise1.core.secrecy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-005 — Nachweis, dass geheime Werte nichts preisgeben.
 *
 * Architekturbezug: TDD 7.3.
 */
class SecretValueTest {

    /** Steht hier für eine spätere Identität oder einen Schlüssel. */
    private class TestIdentity(private val plaintext: String) : SecretValue("Identity")

    private val geheim = "TRD-2025:050"

    @Test
    fun `redacted enthaelt nur die Kennzeichnung`() {
        val wert = TestIdentity(geheim)
        assertEquals("Identity(***)", wert.redacted())
        assertFalse(wert.redacted().contains(geheim))
    }

    @Test
    fun `toString gibt den Klartext nicht heraus`() {
        val wert = TestIdentity(geheim)
        assertEquals("Identity(***)", wert.toString())
        assertFalse(wert.toString().contains(geheim))
    }

    @Test
    fun `String-Interpolation ist unschaedlich`() {
        val wert = TestIdentity(geheim)
        val zeile = "Sitzplatz 3 hat $wert"
        assertFalse(
            zeile.contains(geheim),
            "String-Interpolation hat den Klartext durchgelassen — das finale toString fehlt",
        )
        assertTrue(zeile.contains("Identity(***)"))
    }

    @Test
    fun `equals wird verweigert`() {
        val a = TestIdentity(geheim)
        val b = TestIdentity(geheim)
        assertFailsWith<UnsupportedOperationException> { a == b }
    }

    @Test
    fun `hashCode wird verweigert`() {
        assertFailsWith<UnsupportedOperationException> { TestIdentity(geheim).hashCode() }
    }

    @Test
    fun `leeres Label wird abgelehnt`() {
        assertFailsWith<IllegalArgumentException> {
            object : SecretValue(" ") {}
        }
    }
}
