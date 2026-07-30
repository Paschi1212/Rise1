package de.myhornets.rise1

import de.myhornets.rise1.core.log.RiseLog
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T-004 / T-005 — Nachweis, dass das Anwendungsmodul die Protokoll-Schnittstelle
 * aus `core` tatsächlich erreicht und bedienen kann.
 *
 * Bewusst ohne Android-Bezug: Die eigentliche Logcat-Senke sitzt in
 * [StatusActivity] und braucht ein Gerät. Geprüft wird hier der Vertrag —
 * dass eine Senke eingesetzt und wieder entfernt werden kann und dass das
 * Modul `core` vom Anwendungsmodul aus sichtbar ist.
 */
class LogSinkContractTest {

    @AfterTest
    fun aufraeumen() = RiseLog.resetSink()

    @Test
    fun `Senke laesst sich einsetzen und empfaengt Meldungen`() {
        val gesehen = mutableListOf<String>()
        RiseLog.installSink { _, tag, message -> gesehen += "$tag/$message" }

        RiseLog.i("UI", "bereit")

        assertEquals(listOf("UI/bereit"), gesehen)
    }
}
