package de.myhornets.rise1.core.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-005 — Nachweis, dass das Fehlerkonzept trägt.
 *
 * Architekturbezug: TDD 7.3.
 */
class RiseErrorTest {

    @Test
    fun `Unexpected hat einen stabilen Code`() {
        val fehler = RiseError.Unexpected("Projektion konnte nicht aufgebaut werden")
        assertEquals("core.unexpected", fehler.code)
    }

    @Test
    fun `RiseException traegt Code und technische Meldung`() {
        val fehler = RiseError.Unexpected("Projektion konnte nicht aufgebaut werden")
        val ausnahme = RiseException(fehler)

        assertEquals(fehler, ausnahme.error)
        assertTrue(ausnahme.message!!.contains("core.unexpected"))
        assertTrue(ausnahme.message!!.contains("Projektion konnte nicht aufgebaut werden"))
    }

    @Test
    fun `Ursache bleibt erhalten`() {
        val ursache = IllegalArgumentException("ungültige Sequenznummer")
        val ausnahme = RiseException(RiseError.Unexpected("Replay abgebrochen"), ursache)
        assertEquals(ursache, ausnahme.cause)
    }
}
