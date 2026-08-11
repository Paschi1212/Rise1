package de.myhornets.rise1.transport

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-070 — der Sitzungsthread aus ADR-008.
 *
 * Die vier Zusagen, auf denen alles darüber ruht:
 *
 * 1. Es ist **einer**, und er bleibt derselbe.
 * 2. Die Reihenfolge der Aufgaben ist die Reihenfolge der Einreichung.
 * 3. Ein stolpernder Rückruf reißt ihn **nicht** mit — sonst wäre Zusage 1 ab
 *    dem ersten Fehler still gebrochen.
 * 4. Nach dem Herunterfahren ist er unbrauchbar und wird es leise.
 */
class SitzungsthreadTest {

    private val offene = mutableListOf<Sitzungsthread>()

    private fun sitzungsthread(): Sitzungsthread = Sitzungsthread().also { offene += it }

    @AfterTest
    fun raeumeAuf() {
        offene.forEach { runCatching { it.beende(1_000) } }
    }

    @Test
    fun aufgabenLaufenInDerReihenfolgeIhrerEinreichung() {
        val sitzung = sitzungsthread()
        val gesehen = Collections.synchronizedList(mutableListOf<Int>())

        repeat(200) { nummer -> sitzung.fuehreAus { gesehen += nummer } }

        assertTrue(sitzung.warteAufLeerlauf(5_000))
        assertEquals(List(200) { it }, gesehen.toList())
    }

    @Test
    fun esIstImmerDerselbeThread() {
        val sitzung = sitzungsthread()
        val namen = Collections.synchronizedSet(mutableSetOf<String>())

        repeat(50) { sitzung.fuehreAus { namen += Thread.currentThread().name } }

        assertTrue(sitzung.warteAufLeerlauf(5_000))
        assertEquals(setOf(Sitzungsthread.STANDARDNAME), namen.toSet())
    }

    @Test
    fun einStolpernderRueckrufTauschtDenThreadNichtAus() {
        val sitzung = sitzungsthread()
        val namen = Collections.synchronizedSet(mutableSetOf<String>())

        sitzung.fuehreAus { namen += Thread.currentThread().name }
        sitzung.fuehreAus { throw IllegalArgumentException("Absicht") }
        sitzung.fuehreAus { namen += Thread.currentThread().name }

        assertTrue(sitzung.warteAufLeerlauf(5_000))
        assertEquals(1, namen.size, "Nach einer Ausnahme lief die nächste Aufgabe auf einem anderen Thread.")
        assertEquals(1, sitzung.fehlerzahl, "Die Ausnahme wurde spurlos verschluckt.")
        assertTrue(sitzung.letzterFehler is IllegalArgumentException)
    }

    @Test
    fun istAktuellerThreadStimmtAufBeidenSeiten() {
        val sitzung = sitzungsthread()
        assertFalse(sitzung.istAktuellerThread(), "Der Testthread ist nicht der Sitzungsthread.")

        val drinnen = CountDownLatch(1)
        var innen = false
        sitzung.fuehreAus {
            innen = sitzung.istAktuellerThread()
            sitzung.verlangeSitzungsthread()
            drinnen.countDown()
        }

        assertTrue(drinnen.await(5, TimeUnit.SECONDS))
        assertTrue(innen)
        assertFailsWith<IllegalStateException> { sitzung.verlangeSitzungsthread() }
    }

    @Test
    fun ausstehendZeigtDieWarteschlangeUndNichtDasLaufende() {
        val sitzung = sitzungsthread()
        val laeuft = CountDownLatch(1)
        val sperre = CountDownLatch(1)

        sitzung.fuehreAus {
            laeuft.countDown()
            sperre.await()
        }
        assertTrue(laeuft.await(5, TimeUnit.SECONDS))
        assertEquals(0, sitzung.ausstehend, "Was läuft, wartet nicht.")

        sitzung.fuehreAus { }
        sitzung.fuehreAus { }
        assertEquals(2, sitzung.ausstehend)

        sperre.countDown()
        assertTrue(sitzung.warteAufLeerlauf(5_000))
        assertEquals(0, sitzung.ausstehend)
    }

    @Test
    fun nachDemBeendenWirdNichtsMehrAngenommenUndNichtsGeworfen() {
        val sitzung = sitzungsthread()
        val gelaufen = Collections.synchronizedList(mutableListOf<String>())

        sitzung.fuehreAus { gelaufen += "vorher" }
        sitzung.beende(2_000)
        // Leise, nicht laut: Ein Rückruf, der nach dem Herunterfahren eintrifft,
        // ist der Normalfall — der Lesethread kann ihn schon unterwegs gehabt
        // haben, als geschlossen wurde.
        sitzung.fuehreAus { gelaufen += "nachher" }

        assertEquals(listOf("vorher"), gelaufen.toList())
        assertTrue(sitzung.warteAufLeerlauf(500), "Ein beendeter Sitzungsthread lässt niemanden warten.")
    }
}
