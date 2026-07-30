package de.myhornets.rise1.core.log

import de.myhornets.rise1.core.secrecy.SecretValue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-005 — Nachweis, dass die Protokollierung keine Geheimnisse durchlässt.
 *
 * Architekturbezug: TDD 7.3.
 *
 * Der wichtigste Schutz — die als Fehler markierte Überladung für [Secret] —
 * lässt sich hier nicht prüfen, weil er den **Compiler** scheitern lässt und
 * ein solcher Aufruf sich nicht kompilieren ließe. Das ist Absicht: Er ist
 * damit stärker als ein Test. Geprüft wird hier die zweite Schicht.
 */
class RiseLogTest {

    private class TestSecret(private val plaintext: String) : SecretValue("Secret")

    private val aufgezeichnet = mutableListOf<Triple<RiseLog.Level, String, String>>()

    @BeforeTest
    fun senkeEinsetzen() {
        aufgezeichnet.clear()
        RiseLog.installSink { level, tag, message -> aufgezeichnet += Triple(level, tag, message) }
    }

    @AfterTest
    fun senkeZuruecksetzen() {
        RiseLog.resetSink()
    }

    @Test
    fun `Stufen und Kennzeichnung werden durchgereicht`() {
        RiseLog.d("Deal", "gestartet")
        RiseLog.i("Deal", "läuft")
        RiseLog.w("Deal", "wackelig")
        RiseLog.e("Deal", "gescheitert")

        assertEquals(4, aufgezeichnet.size)
        assertEquals(RiseLog.Level.DEBUG, aufgezeichnet[0].first)
        assertEquals(RiseLog.Level.ERROR, aufgezeichnet[3].first)
        assertEquals("Deal", aufgezeichnet[0].second)
        assertEquals("gestartet", aufgezeichnet[0].third)
    }

    @Test
    fun `interpoliertes Geheimnis erscheint nur redigiert`() {
        val geheim = "TRD-2025:050"
        val wert = TestSecret(geheim)

        RiseLog.i("Deal", "Umschlag geöffnet: $wert")

        val zeile = aufgezeichnet.single().third
        assertFalse(zeile.contains(geheim), "Der Klartext ist im Protokoll gelandet")
        assertTrue(zeile.contains("Secret(***)"))
    }

    @Test
    fun `redacted darf protokolliert werden`() {
        val wert = TestSecret("TRD-2025:007")
        RiseLog.d("Deal", "Zustand von ${wert.redacted()}")
        assertEquals("Zustand von Secret(***)", aufgezeichnet.single().third)
    }

    @Test
    fun `Ursache wird ohne Stacktrace protokolliert`() {
        RiseLog.e("Transport", "Verbindung verloren", IllegalStateException("Socket geschlossen"))

        val zeile = aufgezeichnet.single().third
        assertTrue(zeile.contains("Verbindung verloren"))
        assertTrue(zeile.contains("IllegalStateException"))
        assertTrue(zeile.contains("Socket geschlossen"))
        assertFalse(zeile.contains("at "), "Es ist ein Stacktrace ins Protokoll geraten")
    }
}
