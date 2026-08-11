package de.myhornets.rise1.sitzung

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.myhornets.rise1.StatusActivity
import de.myhornets.rise1.session.Sitzungsbausatz
import de.myhornets.rise1.session.Sitzungslaufzeit
import de.myhornets.rise1.session.Sitzungsverbindung
import de.myhornets.rise1.transport.AttrappenTransport
import de.myhornets.rise1.transport.Gegenstelle
import de.myhornets.rise1.transport.Sitzungsthread
import de.myhornets.rise1.transport.Transport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-075 — **GERÄTETEST**. Der Vordergrunddienst und der Activity-Lebenszyklus.
 *
 * ## Warum das hier steht und nicht als JVM-Test
 *
 * Alles, was ein Verhalten hat, steht in `SitzungslaufzeitTest` und läuft ohne
 * Gerät: genau einmal aufbauen, bei einem Fehler nichts Halbfertiges
 * zurücklassen, kontrolliert herunterfahren, alles oberhalb von `Transport` nur
 * auf dem Sitzungsthread. Was **nicht** ohne Gerät geht, ist genau der Satz,
 * um den es in T-075 überhaupt geht — ADR-008:
 *
 * > *„Der Vordergrunddienst hält den Sitzungsthread am Leben, nicht die
 * > Oberfläche. Eine Activity, die verschwindet, darf keine Verbindung
 * > beenden."*
 *
 * Eine Activity, die wirklich zerstört wird; ein Dienst, der wirklich im
 * Vordergrund läuft; eine Bindung, die wirklich gelöst wird. Ein nachgebauter
 * Lebenszyklus wäre eine Prüfung der Nachbildung.
 *
 * ## Warum jeder Test aus der Oberfläche heraus startet
 *
 * Seit Android 12 darf eine App im Hintergrund keinen Vordergrunddienst
 * starten. [starteAusDerOberflaeche] öffnet deshalb eine echte Activity, startet
 * von dort und **schließt sie wieder** — was zugleich der Ablauf ist, den T-075
 * absichern soll.
 *
 * ## Aufruf
 *
 * ```
 * ./gradlew :ui:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=de.myhornets.rise1.sitzung.SitzungsdienstTest
 * ```
 *
 * `checkAll` fährt ihn nicht mit — `check` führt keine instrumentierten Tests aus.
 *
 * ## Der wichtigste
 *
 * [dieSitzungUeberlebtDasZerstoerenDerActivity]. Er ist der Grund, aus dem es
 * T-075 gibt.
 */
@RunWith(AndroidJUnit4::class)
class SitzungsdienstTest {

    private lateinit var kontext: Context

    /** Ein Bausatz ohne Netz: Geprüft wird Lebensdauer, nicht Transport. */
    private class Attrappenbausatz : Sitzungsbausatz {
        private val host = Gegenstelle("host-test", "Tisch im Test")
        override fun transport(sitzung: Sitzungsthread): Transport = AttrappenTransport()
        override fun verbindung(transport: Transport): Sitzungsverbindung =
            Sitzungsverbindung(transport, host, "p-1", uhr = { 0L })
    }

    /**
     * Die Leseklappe.
     *
     * Ausdrücklich **ohne** `BIND_AUTO_CREATE`: Diese Bindung darf den Dienst
     * weder erzeugen noch am Leben halten. Damit ist sie zugleich die Sonde für
     * die Frage „läuft der Dienst überhaupt noch?" — `bindService` gibt `false`,
     * wenn es ihn nicht mehr gibt.
     */
    private inner class Klammer : ServiceConnection {

        private val verbunden = CountDownLatch(1)

        @Volatile
        private var anschluss: Sitzungsdienst.Anschluss? = null

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            anschluss = binder as Sitzungsdienst.Anschluss
            verbunden.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            anschluss = null
        }

        fun binde(): Sitzungslaufzeit {
            assertTrue("Der Dienst ließ sich nicht binden — läuft er?", versucheZuBinden())
            assertTrue("Die Bindung kam nicht zustande.", verbunden.await(FRIST_SEKUNDEN, TimeUnit.SECONDS))
            val laufzeit = anschluss?.laufzeit
            assertTrue("Der Dienst hält keine Laufzeit.", laufzeit != null)
            return laufzeit!!
        }

        fun versucheZuBinden(): Boolean =
            kontext.bindService(Intent(kontext, Sitzungsdienst::class.java), this, 0)

        fun loese() {
            runCatching { kontext.unbindService(this) }
        }
    }

    private val klammern = mutableListOf<Klammer>()

    private fun klammer(): Klammer = Klammer().also { klammern += it }

    private fun loeseAlle() {
        klammern.forEach { it.loese() }
        klammern.clear()
    }

    /**
     * Startet den Dienst aus einer echten, danach wieder geschlossenen Activity.
     *
     * Das ist zugleich der Ablauf, den T-075 absichert: Die Oberfläche stößt an
     * und verschwindet; was bleibt, hält der Dienst.
     */
    private fun starteAusDerOberflaeche(bausatz: Sitzungsbausatz = Attrappenbausatz()) {
        ActivityScenario.launch(StatusActivity::class.java).use { szene ->
            szene.onActivity { Sitzungsdienst.starte(kontext, bausatz) }
        }
    }

    @Before
    fun bereiteVor() {
        kontext = ApplicationProvider.getApplicationContext()
    }

    @After
    fun raeumeAuf() {
        loeseAlle()
        Sitzungsdienst.stoppe(kontext)
    }

    // ── Start ───────────────────────────────────────────────────────────────

    @Test
    fun derDienststartBautDieSitzungAuf() {
        starteAusDerOberflaeche()

        val laufzeit = klammer().binde()
        assertTrue("Die Sitzung läuft nicht.", warteBis { laufzeit.laeuft })
        assertEquals(1, laufzeit.startzahl)
    }

    @Test
    fun einWiederholterStartErzeugtKeineZweiteSitzung() {
        starteAusDerOberflaeche()
        val erste = klammer().binde()
        assertTrue(warteBis { erste.laeuft })

        // Das, was eine Activity bei jeder Drehung auslöst.
        repeat(3) { starteAusDerOberflaeche() }

        val zweite = klammer().binde()
        assertSame("Es ist dieselbe Laufzeit — also dieselbe Sitzung.", erste, zweite)
        assertEquals("Genau einmal aufgebaut.", 1, erste.startzahl)
        assertTrue(erste.laeuft)
    }

    @Test
    fun ohneBausatzEndetDerDienstStattLeerZuLaufen() {
        // Der Fall, für den `START_NOT_STICKY` steht: ein Start ohne die
        // Angaben, aus denen eine Sitzung entstünde. Ein Dienst, der das
        // aushielte und weiterliefe, wäre eine Benachrichtigung ohne Sitzung.
        Sitzungswerk.raeumeAb()
        ActivityScenario.launch(StatusActivity::class.java).use { szene ->
            szene.onActivity { kontext.startForegroundService(Intent(kontext, Sitzungsdienst::class.java)) }
        }

        val sonde = klammer()
        assertTrue(
            "Der Dienst steht immer noch — ohne Bausatz hätte er enden müssen.",
            warteBis {
                val gebunden = sonde.versucheZuBinden()
                if (gebunden) sonde.loese()
                !gebunden
            },
        )
    }

    // ── Der Satz aus ADR-008 ────────────────────────────────────────────────

    @Test
    fun dieSitzungUeberlebtDasZerstoerenDerActivity() {
        starteAusDerOberflaeche()
        val laufzeit = klammer().binde()
        assertTrue(warteBis { laufzeit.laeuft })

        // Eine echte Activity, echt erzeugt und echt zerstört — mehrfach, wie
        // beim Drehen des Geräts.
        repeat(3) {
            ActivityScenario.launch(StatusActivity::class.java).use { szene ->
                szene.onActivity { assertTrue("Die Sitzung endete beim Erscheinen der Activity.", laufzeit.laeuft) }
            }
            assertTrue(
                "ADR-008: Eine Activity, die verschwindet, darf keine Verbindung beenden.",
                laufzeit.laeuft,
            )
        }
        assertEquals("Und sie darf auch keine zweite aufbauen.", 1, laufzeit.startzahl)

        // Derselbe Beweis über die Bindung: Loslassen ist kein Beenden. Der
        // Dienst wurde gestartet, nicht nur gebunden.
        loeseAlle()
        assertTrue(laufzeit.laeuft)
        assertSame(laufzeit, klammer().binde())
    }

    // ── Ende ────────────────────────────────────────────────────────────────

    @Test
    fun dasStoppenFaehrtDieSitzungKontrolliertHerunter() {
        starteAusDerOberflaeche()
        val laufzeit = klammer().binde()
        assertTrue(warteBis { laufzeit.laeuft })

        Sitzungsdienst.stoppe(kontext)

        assertTrue(
            "Der Dienst fährt die Sitzung beim Beenden herunter.",
            warteBis { laufzeit.zustand == Sitzungslaufzeit.Zustand.BEENDET },
        )
        assertFalse(laufzeit.laeuft)
        assertNull("Die Bauanleitung wird mit der Sitzung weggeräumt.", Sitzungswerk.bausatz)
    }

    companion object {

        private const val FRIST_SEKUNDEN = 10L
        private const val FRIST_MILLIS = 10_000L

        /**
         * Wartet auf eine Bedingung statt auf eine Uhr.
         *
         * Lebenszyklus-Rückrufe kommen, wenn Android sie schickt. Ein Test mit
         * fester Pause wäre auf einem langsamen Gerät rot und sagte nichts.
         */
        private fun warteBis(millis: Long = FRIST_MILLIS, bedingung: () -> Boolean): Boolean {
            val ende = System.currentTimeMillis() + millis
            while (System.currentTimeMillis() < ende) {
                if (bedingung()) return true
                Thread.sleep(5)
            }
            return bedingung()
        }
    }
}
