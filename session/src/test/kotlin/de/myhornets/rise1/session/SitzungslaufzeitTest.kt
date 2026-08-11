package de.myhornets.rise1.session

import de.myhornets.rise1.transport.AttrappenTransport
import de.myhornets.rise1.transport.Gegenstelle
import de.myhornets.rise1.transport.Sitzungsthread
import de.myhornets.rise1.transport.Transport
import de.myhornets.rise1.transport.TransportEreignis
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * T-075 — die langlebige Sitzungskomponente.
 *
 * ## Warum das hier und nicht auf einem Gerät geprüft wird
 *
 * Weil genau diese Zusagen tragen müssen, und weil sie nichts mit Android zu
 * tun haben: genau einmal aufbauen, bei einem Fehler nichts Halbfertiges
 * zurücklassen, kontrolliert herunterfahren, und alles oberhalb von `Transport`
 * auf dem Sitzungsthread halten (ADR-008).
 *
 * Der `Service` in `:ui` besteht danach nur noch aus Weiterreichen. Was an ihm
 * wirklich Android ist — Vordergrundstart, Benachrichtigung, Bindung, das
 * Verschwinden einer Activity — steht in `SitzungsdienstTest` und ist ein
 * **Gerätetest**.
 *
 * ## Der wichtigste
 *
 * [einZweiterStartBautNichtsNeues]. Eine Activity, die sich dreht, ruft den
 * Dienst erneut. Ohne diese Zusage entstünde bei jeder Drehung ein zweiter
 * Sitzungsthread, ein zweiter Transport und eine zweite Verbindung zum selben
 * Host — und der Fehler zeigte sich erst als Sitzplatzgrenze auf dem Host.
 *
 * ## Eigene Threadnamen je Test
 *
 * Damit [lebendeThreads] etwas aussagt. Zwei Tests mit demselben Namen wären
 * eine Zählung, die von der Reihenfolge abhängt.
 */
class SitzungslaufzeitTest {

    private val host = Gegenstelle("host-1", "Tisch am Fenster")
    private val nameZaehler = AtomicInteger(0)
    private val offene = mutableListOf<Sitzungslaufzeit>()

    @AfterTest
    fun raeumeAuf() {
        offene.forEach { runCatching { it.beende() } }
    }

    private fun eigenerName(): String = "rise1-sitzung-test-${nameZaehler.incrementAndGet()}-${hashCode()}"

    /** Merkt sich, auf welchem Thread der Transport benutzt wurde. */
    private class Fadenspion(private val innen: Transport) : Transport {

        val benutztVon: MutableList<String> = Collections.synchronizedList(mutableListOf<String>())
        val geschlossen = AtomicBoolean(false)

        override fun beobachte(hoerer: (TransportEreignis) -> Unit) {
            notiere(); innen.beobachte(hoerer)
        }

        override fun verbinde(gegenstelle: Gegenstelle) {
            notiere(); innen.verbinde(gegenstelle)
        }

        override fun sende(an: Gegenstelle, rahmen: ByteArray): Boolean {
            notiere(); return innen.sende(an, rahmen)
        }

        override fun trenne(gegenstelle: Gegenstelle, grund: String) {
            notiere(); innen.trenne(gegenstelle, grund)
        }

        override fun schliesse() {
            geschlossen.set(true)
            innen.schliesse()
        }

        override val verbundene: Set<Gegenstelle> get() = innen.verbundene

        private fun notiere() {
            benutztVon += Thread.currentThread().name
        }
    }

    /** Ein Bausatz, der zählt und auf Wunsch scheitert. */
    private inner class Pruefbausatz(
        var transportScheitert: Boolean = false,
        var verbindungScheitert: Boolean = false,
    ) : Sitzungsbausatz {

        val transporte = mutableListOf<Fadenspion>()
        val verbindungen = mutableListOf<Sitzungsverbindung>()

        override fun transport(sitzung: Sitzungsthread): Transport {
            if (transportScheitert) throw IllegalStateException("Kein Partie-Zertifikat.")
            return Fadenspion(AttrappenTransport()).also { transporte += it }
        }

        override fun verbindung(transport: Transport): Sitzungsverbindung {
            if (verbindungScheitert) throw IllegalStateException("Kein Sitzplatz.")
            return Sitzungsverbindung(transport, host, "p-1", uhr = { 0L }).also { verbindungen += it }
        }
    }

    private fun laufzeit(bausatz: Sitzungsbausatz, name: String): Sitzungslaufzeit =
        Sitzungslaufzeit(bausatz, name).also { offene += it }

    private fun lebendeThreads(name: String): Int =
        Thread.getAllStackTraces().keys.count { it.isAlive && it.name == name }

    // ── Aufbau ──────────────────────────────────────────────────────────────

    @Test
    fun derStartBautGenauEinmalAuf() {
        val bausatz = Pruefbausatz()
        val name = eigenerName()
        val laufzeit = laufzeit(bausatz, name)

        assertEquals(Sitzungslaufzeit.Zustand.RUHT, laufzeit.zustand)
        assertTrue(laufzeit.starte(), "Der erste Start hat aufgebaut.")

        assertEquals(Sitzungslaufzeit.Zustand.LAEUFT, laufzeit.zustand)
        assertTrue(laufzeit.laeuft)
        assertEquals(1, laufzeit.startzahl)
        assertEquals(1, bausatz.transporte.size)
        assertEquals(1, bausatz.verbindungen.size)
        assertEquals(1, lebendeThreads(name), "Genau ein Sitzungsthread.")
    }

    @Test
    fun einZweiterStartBautNichtsNeues() {
        val bausatz = Pruefbausatz()
        val name = eigenerName()
        val laufzeit = laufzeit(bausatz, name)

        assertTrue(laufzeit.starte())
        val ersteVerbindung = bausatz.verbindungen.single()

        // Das, was eine sich drehende Activity auslöst — dreimal.
        assertFalse(laufzeit.starte(), "Ein zweiter Start baut nichts.")
        assertFalse(laufzeit.starte())
        assertFalse(laufzeit.starte())

        assertEquals(1, laufzeit.startzahl)
        assertEquals(1, bausatz.transporte.size, "Kein zweiter Transport.")
        assertEquals(1, bausatz.verbindungen.size, "Keine zweite Sitzungsverbindung.")
        assertSame(ersteVerbindung, bausatz.verbindungen.single())
        assertEquals(1, lebendeThreads(name), "Kein zweiter Sitzungsthread.")
    }

    @Test
    fun derAufbauDerVerbindungLaeuftAufDemSitzungsthread() {
        val bausatz = Pruefbausatz()
        val name = eigenerName()
        val laufzeit = laufzeit(bausatz, name)

        assertTrue(laufzeit.starte())
        assertTrue(laufzeit.warteAufLeerlauf(5_000))

        val spion = bausatz.transporte.single()
        assertTrue(spion.benutztVon.isNotEmpty(), "Der Transport wurde nie benutzt — dann sagt der Test nichts.")
        assertEquals(
            setOf(name),
            spion.benutztVon.toSet(),
            "ADR-008: Alles oberhalb von Transport läuft auf dem Sitzungsthread — und nur dort.",
        )
    }

    // ── Fehler beim Aufbau ──────────────────────────────────────────────────

    @Test
    fun einFehlerImTransportbauLaesstNichtsZurueck() {
        val bausatz = Pruefbausatz(transportScheitert = true)
        val name = eigenerName()
        val laufzeit = laufzeit(bausatz, name)

        assertFailsWith<IllegalStateException> { laufzeit.starte() }

        assertEquals(Sitzungslaufzeit.Zustand.RUHT, laufzeit.zustand, "Kein halbfertiger Zustand.")
        assertEquals(0, laufzeit.startzahl)
        assertEquals(0, lebendeThreads(name), "Der schon erzeugte Sitzungsthread wurde wieder beendet.")
    }

    @Test
    fun einFehlerImVerbindungsbauSchliesstDenSchonGebautenTransport() {
        val bausatz = Pruefbausatz(verbindungScheitert = true)
        val name = eigenerName()
        val laufzeit = laufzeit(bausatz, name)

        assertFailsWith<IllegalStateException> { laufzeit.starte() }

        val spion = bausatz.transporte.single()
        assertTrue(spion.geschlossen.get(), "Ein Transport, der schon einen Socket halten könnte, bleibt nicht offen.")
        assertEquals(Sitzungslaufzeit.Zustand.RUHT, laufzeit.zustand)
        assertEquals(0, lebendeThreads(name))
    }

    @Test
    fun nachEinemGescheitertenStartLaesstSichDieselbeLaufzeitErneutStarten() {
        // Der eigentliche Beweis, dass nichts halb fertig zurückblieb: Ein
        // zweiter Anlauf **an derselben Laufzeit** gelingt vollständig. Wäre
        // etwas liegengeblieben, stünde hier jetzt ein zweiter Thread oder ein
        // Zustand, der den Start verweigert.
        val bausatz = Pruefbausatz(verbindungScheitert = true)
        val name = eigenerName()
        val laufzeit = laufzeit(bausatz, name)
        assertFailsWith<IllegalStateException> { laufzeit.starte() }

        bausatz.verbindungScheitert = false
        assertTrue(laufzeit.starte(), "Nach einem gescheiterten Start ist ein neuer möglich.")

        assertEquals(Sitzungslaufzeit.Zustand.LAEUFT, laufzeit.zustand)
        assertEquals(1, laufzeit.startzahl)
        assertEquals(2, bausatz.transporte.size, "Der zweite Anlauf baut einen frischen Transport.")
        assertTrue(bausatz.transporte.first().geschlossen.get(), "Der erste blieb nicht offen.")
        assertEquals(1, lebendeThreads(name), "Genau ein Sitzungsthread, nicht zwei.")
    }

    // ── Herunterfahren ──────────────────────────────────────────────────────

    @Test
    fun dasBeendenFaehrtTransportUndSitzungsthreadHerunter() {
        val bausatz = Pruefbausatz()
        val name = eigenerName()
        val laufzeit = laufzeit(bausatz, name)
        assertTrue(laufzeit.starte())

        assertTrue(laufzeit.beende(), "Es lief etwas, also war etwas zu beenden.")

        assertEquals(Sitzungslaufzeit.Zustand.BEENDET, laufzeit.zustand)
        assertFalse(laufzeit.laeuft)
        assertTrue(bausatz.transporte.single().geschlossen.get(), "Der Transport wurde geschlossen.")
        assertEquals(0, lebendeThreads(name), "Der Sitzungsthread wurde beendet.")
    }

    @Test
    fun zweimalBeendenUndBeendenOhneStartSindErlaubt() {
        val name = eigenerName()
        val nieGestartet = laufzeit(Pruefbausatz(), name)
        // `onDestroy` kommt auch für einen Dienst, dessen Start gescheitert ist.
        assertFalse(nieGestartet.beende())
        assertEquals(Sitzungslaufzeit.Zustand.BEENDET, nieGestartet.zustand)

        val gestartet = laufzeit(Pruefbausatz(), eigenerName())
        gestartet.starte()
        assertTrue(gestartet.beende())
        assertFalse(gestartet.beende(), "Das zweite Beenden hat nichts mehr zu beenden.")
    }

    @Test
    fun nachDemBeendenLaesstSichNichtWiederStarten() {
        val laufzeit = laufzeit(Pruefbausatz(), eigenerName())
        laufzeit.starte()
        laufzeit.beende()

        // Dieselbe Regel wie beim Rahmenleser nach einem Protokollfehler und
        // beim Sockettransport nach dem Herunterfahren (ADR-008).
        assertFailsWith<IllegalStateException> { laufzeit.starte() }
    }

    // ── Die Regel aus ADR-008 an der Grenze ─────────────────────────────────

    @Test
    fun aufSitzungsthreadErreichtDieVerbindungAufDemRichtigenThread() {
        val bausatz = Pruefbausatz()
        val name = eigenerName()
        val laufzeit = laufzeit(bausatz, name)
        laufzeit.starte()

        val angekommen = CountDownLatch(1)
        var gesehenerThread: String? = null
        var gesehen: Sitzungsverbindung? = null

        assertTrue(
            laufzeit.aufSitzungsthread { verbindung ->
                gesehenerThread = Thread.currentThread().name
                gesehen = verbindung
                angekommen.countDown()
            },
        )

        assertTrue(angekommen.await(5, TimeUnit.SECONDS), "Die Aufgabe kam nie an.")
        assertEquals(name, gesehenerThread)
        assertSame(bausatz.verbindungen.single(), assertNotNull(gesehen))
    }

    @Test
    fun aufSitzungsthreadTutNichtsWennKeineSitzungLaeuft() {
        val laufzeit = laufzeit(Pruefbausatz(), eigenerName())
        val gelaufen = AtomicBoolean(false)

        assertFalse(laufzeit.aufSitzungsthread { gelaufen.set(true) }, "Vor dem Start gibt es nichts zu tun.")

        laufzeit.starte()
        laufzeit.beende()
        assertFalse(laufzeit.aufSitzungsthread { gelaufen.set(true) }, "Nach dem Ende auch nicht.")
        assertFalse(gelaufen.get())
    }

    @Test
    fun dasLoslassenDurchDenAufruferBeendetDieSitzungNicht() {
        // Die JVM-Hälfte des Satzes aus ADR-008: „Eine Activity, die
        // verschwindet, darf keine Verbindung beenden." Hier lässt der Aufrufer
        // alles los, was er von der Sitzung hatte — die Laufzeit selbst hält
        // der Dienst. Die andere Hälfte, der echte Activity-Lebenszyklus, ist
        // ein Gerätetest.
        val bausatz = Pruefbausatz()
        val name = eigenerName()
        val laufzeit = laufzeit(bausatz, name)
        laufzeit.starte()

        // Der Aufrufer sieht sich die Sitzung an und verschwindet danach —
        // ohne `beende`. Genau das tut eine Activity bei jeder Drehung.
        assertNotNull(bausatz.verbindungen.single())

        assertTrue(laufzeit.laeuft, "Loslassen ist kein Beenden.")
        assertEquals(1, lebendeThreads(name))

        val nochDa = CountDownLatch(1)
        assertTrue(laufzeit.aufSitzungsthread { nochDa.countDown() })
        assertTrue(nochDa.await(5, TimeUnit.SECONDS), "Der Sitzungsthread arbeitet weiter.")
        assertFalse(bausatz.transporte.single().geschlossen.get(), "Der Transport blieb offen.")
    }
}
