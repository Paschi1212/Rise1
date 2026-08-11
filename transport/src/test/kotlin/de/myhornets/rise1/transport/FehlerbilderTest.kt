package de.myhornets.rise1.transport

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-077 — die Fehlerbilder.
 *
 * ## Wofür diese Datei da ist
 *
 * ADR-001 nennt den Maßstab: Die App muss den Fehlerfall **erklären** *„statt
 * eine kryptische Zeitüberschreitung zu zeigen"*. Erklären kann nur, wer
 * unterscheidet — und unterscheiden kann nur, wer jeden Fall einmal hergestellt
 * hat.
 *
 * `SockettransportTest` prüft, dass der Normalbetrieb stimmt. Hier steht das
 * Gegenteil: was passiert, wenn es **nicht** stimmt. Beides getrennt, weil die
 * Fragen verschieden sind — dort „tut es das Richtige", hier „meldet es das
 * Richtige und nichts darüber hinaus".
 *
 * ## Die Regel, die über allem steht
 *
 * **Kein falsches Folgeereignis.** Ein gescheiterter Aufbau ist kein
 * `Getrennt`. Ein Ende ist genau ein `Getrennt`. Ein Fehler einer Verbindung
 * ist kein Fehler der anderen. Ein stolpernder Hörer ist kein Ende des
 * Sitzungsthreads. Jede dieser vier Zusagen hat hier einen eigenen Test, weil
 * ihre Verletzung sich sonst erst in `T-105`/`T-108` zeigt — als ein Delta
 * gegen den falschen Stand.
 */
class FehlerbilderTest {

    private val host = Gegenstelle("host-1", "Tisch am Fenster")

    private val sitzungen = mutableListOf<Sitzungsthread>()
    private val transporte = mutableListOf<Sockettransport>()
    private val schliessbares = mutableListOf<AutoCloseable>()

    private fun sitzungsthread(fehlerbehandler: (Throwable) -> Unit = {}): Sitzungsthread =
        Sitzungsthread(Sitzungsthread.STANDARDNAME, fehlerbehandler).also { sitzungen += it }

    private fun transport(
        sitzung: Sitzungsthread,
        quelle: Socketquelle = Socketquelle.NurAnnehmend,
        sitzplaetze: Int = Sockettransport.STANDARD_SITZPLAETZE,
        sendestauGrenze: Int = Sockettransport.SENDESTAU_GRENZE,
    ): Sockettransport = Sockettransport(
        sitzung = sitzung,
        socketquelle = quelle,
        sitzplaetze = sitzplaetze,
        sendestauGrenze = sendestauGrenze,
    ).also { transporte += it }

    private fun gegenserver(): Gegenserver = Gegenserver().also { schliessbares += it }

    @AfterTest
    fun raeumeAuf() {
        transporte.forEach { runCatching { it.schliesse() } }
        schliessbares.forEach { runCatching { it.close() } }
        sitzungen.forEach { runCatching { it.beende(1_000) } }
    }

    private fun ereignis(text: String): ByteArray =
        Rahmencodec.kodiere(Rahmen(Rahmentyp.EREIGNIS, text.toByteArray()))

    /** Ein Lauschposten, der scheitert — und zwar so, wie es die Plattform täte. */
    private class ScheiternderPosten(private val fehler: Throwable) : Lauschposten {
        override fun oeffne(): ServerSocket = throw fehler
    }

    /** Ein Lauschposten, der seinen Socket herausgibt, damit ein Test ihn kaputt machen kann. */
    private class OffenerPosten : Lauschposten {
        @Volatile
        var socket: ServerSocket? = null

        override fun oeffne(): ServerSocket =
            ServerSocket(0, 16, InetAddress.getLoopbackAddress()).also { socket = it }
    }

    // ── Der Aufbau: lauschen ────────────────────────────────────────────────

    @Test
    fun einBelegterPortMeldetFehlgeschlagenStattZuWerfen() {
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)
        val mitschnitt = Mitschnitt(sitzung)
        wirt.beobachte(mitschnitt.hoerer())

        val port = wirt.lausche(ScheiternderPosten(IOException("Address already in use")))

        assertEquals(Sockettransport.KEIN_PORT, port, "Ein Fehlschlag darf nicht wie ein Erfolg aussehen.")
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Fehlgeschlagen>(1))
        val fehlgeschlagen = mitschnitt.von<TransportEreignis.Fehlgeschlagen>().single()
        assertNull(fehlgeschlagen.gegenstelle, "Beim Lauschen ist niemand am anderen Ende.")
        assertTrue(fehlgeschlagen.fehler is TransportFehler.Abgelehnt)
    }

    @Test
    fun eineFehlendeBerechtigungKommtAlsErklaerbarerFallAn() {
        // ADR-001: „Wird sie verweigert, kann dieses Gerät nicht hosten — ein
        // anderes Gerät kann es." Genau das muss in der Meldung stehen, sonst
        // kann die Oberfläche es nicht sagen. Auf Android kommt dieser Fall als
        // SecurityException, also NICHT als IOException.
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)
        val mitschnitt = Mitschnitt(sitzung)
        wirt.beobachte(mitschnitt.hoerer())

        val port = wirt.lausche(ScheiternderPosten(SecurityException("ACCESS_LOCAL_NETWORK")))

        assertEquals(Sockettransport.KEIN_PORT, port)
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Fehlgeschlagen>(1))
        val grund = (mitschnitt.von<TransportEreignis.Fehlgeschlagen>().single().fehler as TransportFehler.Abgelehnt)
        assertTrue(grund.grund.contains("Berechtigung"), "Der Grund nennt die Berechtigung nicht: ${grund.grund}")
        assertTrue(grund.grund.contains("anderes Gerät"), "Der Ausweg fehlt: ${grund.grund}")
    }

    @Test
    fun einVerbindungsfehlerAusDerFabrikBleibtUnveraendert() {
        // Eine Fabrik, die den Fall schon kennt, wird nicht überstimmt.
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)
        val mitschnitt = Mitschnitt(sitzung)
        wirt.beobachte(mitschnitt.hoerer())

        wirt.lausche(ScheiternderPosten(Verbindungsfehler(TransportFehler.KeinGemeinsamesNetz)))

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Fehlgeschlagen>(1))
        assertEquals(
            TransportFehler.KeinGemeinsamesNetz,
            mitschnitt.von<TransportEreignis.Fehlgeschlagen>().single().fehler,
        )
    }

    @Test
    fun nachEinemGescheitertenLauschenDarfErneutGelauschtWerden() {
        // Ein Fehlschlag hinterlässt keinen halben Annahmethread — sonst wäre
        // der erste Fehlversuch endgültig.
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)
        val mitschnitt = Mitschnitt(sitzung)
        wirt.beobachte(mitschnitt.hoerer())

        wirt.lausche(ScheiternderPosten(IOException("Address already in use")))
        assertEquals(0, lebendeThreads(Sockettransport.ANNAHME_THREADNAME))

        val port = wirt.lausche(KlartextLauschposten())

        assertTrue(port > 0, "Der zweite Anlauf muss gelingen.")
        assertEquals(1, lebendeThreads(Sockettransport.ANNAHME_THREADNAME))
    }

    @Test
    fun einAusfallDesAnnahmesocketsWirdGemeldet() {
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)
        val mitschnitt = Mitschnitt(sitzung)
        wirt.beobachte(mitschnitt.hoerer())
        val posten = OffenerPosten()
        wirt.lausche(posten)

        // Nicht das Herunterfahren, sondern ein Ausfall: Der Socket geht weg,
        // der Transport bleibt offen. Ohne Meldung nähme dieses Gerät ab jetzt
        // niemanden mehr an, und niemand wüsste es.
        assertNotNull(posten.socket).close()

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Fehlgeschlagen>(1), "Der Ausfall wurde nicht gemeldet.")
        val fehler = mitschnitt.von<TransportEreignis.Fehlgeschlagen>().single()
        assertNull(fehler.gegenstelle)
        assertTrue(fehler.fehler is TransportFehler.VerbindungAbgebrochen)
    }

    @Test
    fun dasRegulaereHerunterfahrenMeldetKeinenAnnahmefehler() {
        // Derselbe geschlossene Socket, andere Ursache. Ein Herunterfahren, das
        // sich als Störung meldet, macht jede Statusanzeige wertlos.
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)
        val mitschnitt = Mitschnitt(sitzung)
        wirt.beobachte(mitschnitt.hoerer())
        wirt.lausche(KlartextLauschposten())

        wirt.schliesse()

        assertEquals(0, mitschnitt.von<TransportEreignis.Fehlgeschlagen>().size)
        assertEquals(0, lebendeThreads(Sockettransport.ANNAHME_THREADNAME))
        assertTrue(wirt.sauberHeruntergefahren, "Alle Threads endeten innerhalb der Frist.")
    }

    // ── Der Aufbau: verbinden ───────────────────────────────────────────────

    @Test
    fun einAbbruchWaehrendDesAufbausMeldetFehlgeschlagenUndNiemalsGetrennt() {
        // Die Gegenstelle legt mitten im Handshake auf. Es gab nie eine
        // stehende Verbindung — also gibt es auch nichts zu trennen.
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val abbrechend = object : Socketquelle {
            override fun verbinde(gegenstelle: Gegenstelle): Socket {
                val socket = Socket(InetAddress.getLoopbackAddress(), gegen.port)
                socket.close()
                throw IOException("Die Gegenstelle hat während des Handshakes aufgelegt.")
            }
        }
        val transport = transport(sitzung, abbrechend)
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Fehlgeschlagen>(1))
        assertTrue(sitzung.warteAufLeerlauf(2_000))
        assertEquals(1, mitschnitt.von<TransportEreignis.Fehlgeschlagen>().size, "Genau ein Fehlgeschlagen.")
        assertEquals(0, mitschnitt.von<TransportEreignis.Getrennt>().size, "Und kein Getrennt.")
        assertEquals(0, mitschnitt.von<TransportEreignis.Verbunden>().size)
        assertNull(transport.verbindungsnummer(host))
    }

    @Test
    fun nachEinemGescheitertenAufbauLaesstSichErneutVerbinden() {
        // T-074 lebt davon: Ein Fehlversuch darf den Platz nicht blockieren.
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val wechselhaft = object : Socketquelle {
            @Volatile
            var scheitert = true

            override fun verbinde(gegenstelle: Gegenstelle): Socket {
                if (scheitert) throw IOException("Noch kein Netz.")
                return Socket(InetAddress.getLoopbackAddress(), gegen.port)
            }
        }
        val transport = transport(sitzung, wechselhaft)
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Fehlgeschlagen>(1))

        wechselhaft.scheitert = false
        transport.verbinde(host)

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1), "Der zweite Versuch kam nicht durch.")
        assertEquals(setOf(host), transport.verbundene)
    }

    // ── Die laufende Verbindung ─────────────────────────────────────────────

    @Test
    fun einFehlerEinerVerbindungLaesstDieAndereUnberuehrt() {
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)
        val mitschnitt = Mitschnitt(sitzung)
        wirt.beobachte(mitschnitt.hoerer())
        val port = wirt.lausche(KlartextLauschposten())

        val ersterGast = Socket(InetAddress.getLoopbackAddress(), port).also { schliessbares += it }
        val zweiterGast = Socket(InetAddress.getLoopbackAddress(), port).also { schliessbares += it }
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(2))

        // Der erste spricht ein anderes Protokoll — das ist ein Protokollfehler
        // und beendet seine Leitung.
        ersterGast.getOutputStream().write(ByteArray(16) { 0x41 })
        ersterGast.getOutputStream().flush()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Getrennt>(1))

        // Und der zweite spricht danach ganz normal weiter.
        zweiterGast.getOutputStream().write(ereignis("mich betrifft das nicht"))
        zweiterGast.getOutputStream().flush()

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Empfangen>(1), "Die zweite Leitung wurde mitgerissen.")
        assertEquals(listOf("mich betrifft das nicht"), mitschnitt.empfangeneNutzlasten())
        assertEquals(1, mitschnitt.von<TransportEreignis.Getrennt>().size, "Genau eine Leitung ging.")
        assertEquals(1, wirt.verbundene.size)
    }

    @Test
    fun einAbrupterAbbruchEinerLeitungLaesstDieAndereStehen() {
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)
        val mitschnitt = Mitschnitt(sitzung)
        wirt.beobachte(mitschnitt.hoerer())
        val port = wirt.lausche(KlartextLauschposten())

        val ersterGast = Socket(InetAddress.getLoopbackAddress(), port)
        val zweiterGast = Socket(InetAddress.getLoopbackAddress(), port).also { schliessbares += it }
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(2))

        ersterGast.close()

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Getrennt>(1))
        assertTrue(sitzung.warteAufLeerlauf(2_000))
        assertEquals(1, mitschnitt.von<TransportEreignis.Getrennt>().size)
        assertEquals(1, wirt.verbundene.size)

        zweiterGast.getOutputStream().write(ereignis("noch da"))
        zweiterGast.getOutputStream().flush()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Empfangen>(1))
    }

    // ── Senden ──────────────────────────────────────────────────────────────

    @Test
    fun eineVolleSendewarteschlangeBeendetDieVerbindungGenauEinmal() {
        // Die Gegenstelle nimmt nichts ab. Ohne Grenze wüchse der Speicher, bis
        // nichts mehr geht — dieselbe Sorte Fehler, gegen die MAX_NUTZLAST steht.
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(
            sitzung,
            KlartextSocketquelle(gegen.port, sendepufferBytes = KLEINER_PUFFER),
            sendestauGrenze = 2,
        )
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        gegen.naechsteVerbindung() // angenommen, aber nie gelesen
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))

        val brocken = Rahmencodec.kodiere(Rahmen(Rahmentyp.SCHNAPPSCHUSS, ByteArray(GROSSE_NUTZLAST)))
        var abgewiesen = false
        for (versuch in 1..HOECHSTENS_SENDUNGEN) {
            if (!transport.sende(host, brocken)) {
                abgewiesen = true
                break
            }
        }

        assertTrue(abgewiesen, "Die Warteschlange lief nie voll — dann prüft dieser Test nichts.")
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Getrennt>(1))
        assertTrue(sitzung.warteAufLeerlauf(2_000))
        assertEquals(1, mitschnitt.von<TransportEreignis.Getrennt>().size, "Genau ein Getrennt.")
        assertTrue(
            mitschnitt.von<TransportEreignis.Getrennt>().single().grund.contains("Sendestau"),
            "Der Grund sagt nicht, was los war.",
        )
        assertFalse(transport.sende(host, brocken), "Danach gibt es keine Verbindung mehr.")
    }

    @Test
    fun eineVerschwundeneGegenstelleErgibtBeimSendenGenauEinGetrennt() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        val leitung = gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))

        // Lese- und Sendethread bemerken das Ende gleichzeitig — der Fall, für
        // den ADR-008 das AtomicBoolean vorschreibt.
        leitung.close()
        repeat(20) { transport.sende(host, ereignis("ins Leere-$it")) }

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Getrennt>(1))
        assertTrue(sitzung.warteAufLeerlauf(2_000))
        assertEquals(1, mitschnitt.von<TransportEreignis.Getrennt>().size)
        assertFalse(transport.sende(host, ereignis("zu spät")))
    }

    // ── Hörer und Sitzungsthread ────────────────────────────────────────────

    @Test
    fun einFehlerImHoererErreichtDenFehlerbehandler() {
        // T-077: Nichts wird verschluckt. `:transport` hat keinen RiseLog —
        // deshalb der Haken, den `:session` mit der Protokollierung füllt.
        val gesehene = Collections.synchronizedList(mutableListOf<Throwable>())
        val angekommen = CountDownLatch(1)
        val sitzung = sitzungsthread { fehler ->
            gesehene += fehler
            angekommen.countDown()
        }
        val gegen = gegenserver()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        transport.beobachte { throw IllegalStateException("Ein Hörer, der stolpert.") }

        transport.verbinde(host)
        gegen.naechsteVerbindung()

        assertTrue(angekommen.await(5, TimeUnit.SECONDS), "Der Fehler kam nie beim Behandler an.")
        assertTrue(gesehene.first() is IllegalStateException)
        assertEquals(gesehene.size, sitzung.fehlerzahl)
    }

    @Test
    fun einStolpernderBehandlerBeendetDenSitzungsthreadNicht() {
        // Die Fehlerbehandlung darf nicht die neue Fehlerquelle sein.
        val sitzung = sitzungsthread { throw IllegalStateException("Auch der Behandler stolpert.") }
        val gegen = gegenserver()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        // Der Mitschnitt zuerst: Ein stolpernder Hörer reißt die **nach** ihm
        // eingetragenen mit — genau wie beim `AttrappenTransport`, der die
        // Ausnahme ebenfalls durchreicht. Geprüft wird hier, was darüber
        // hinausgeht: Der Sitzungsthread überlebt beides.
        transport.beobachte(mitschnitt.hoerer())
        transport.beobachte { throw IllegalStateException("Ein Hörer, der stolpert.") }

        transport.verbinde(host)
        val leitung = gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))
        gegen.schreibe(leitung, ereignis("trotzdem"))

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Empfangen>(1), "Nach dem Fehler lief nichts mehr.")
        assertEquals(0, mitschnitt.fremdeThreads.get(), "Der Sitzungsthread wurde ausgetauscht.")
    }

    @Test
    fun einFehlerImHoererLaesstDenTransportWeiterarbeiten() {
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)
        val mitschnitt = Mitschnitt(sitzung)
        wirt.beobachte(mitschnitt.hoerer())
        wirt.beobachte { throw IllegalStateException("Ein Hörer, der stolpert.") }
        val port = wirt.lausche(KlartextLauschposten())

        val ersterGast = Socket(InetAddress.getLoopbackAddress(), port).also { schliessbares += it }
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))

        // Nach dem Fehler wird eine zweite Verbindung angenommen — die Annahme
        // hängt nicht am Wohlergehen eines Hörers.
        val zweiterGast = Socket(InetAddress.getLoopbackAddress(), port).also { schliessbares += it }
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(2), "Die zweite Annahme blieb aus.")

        zweiterGast.getOutputStream().write(ereignis("zwei"))
        zweiterGast.getOutputStream().flush()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Empfangen>(1))
        assertTrue(sitzung.fehlerzahl > 0, "Der Fehler wurde spurlos verschluckt.")
        assertTrue(ersterGast.isConnected)
    }

    // ── Herunterfahren ──────────────────────────────────────────────────────

    @Test
    fun dasHerunterfahrenMitOffenenVerbindungenBleibtSauber() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))
        val nummer = assertNotNull(transport.verbindungsnummer(host))

        transport.schliesse()

        assertTrue(transport.sauberHeruntergefahren, "Ein Thread hat die Frist überzogen.")
        assertEquals(0, lebendeThreads(Sockettransport.LESER_PRAEFIX + nummer))
        assertEquals(0, lebendeThreads(Sockettransport.SENDER_PRAEFIX + nummer))
        assertEquals(1, mitschnitt.von<TransportEreignis.Getrennt>().size, "Genau ein Getrennt beim Ende.")
    }

    @Test
    fun einZweitesHerunterfahrenAendertDenBefundNicht() {
        val sitzung = sitzungsthread()
        val transport = transport(sitzung)
        transport.lausche(KlartextLauschposten())

        transport.schliesse()
        val ersterBefund = transport.sauberHeruntergefahren
        transport.schliesse()

        assertEquals(ersterBefund, transport.sauberHeruntergefahren)
        assertTrue(ersterBefund)
    }

    companion object {

        /**
         * Klein genug, dass ein Schreiben auf der Schleife wirklich blockiert.
         *
         * Ohne das puffert das Betriebssystem so großzügig, dass die
         * Sendewarteschlange nie volläuft — und der Test prüfte nichts.
         */
        const val KLEINER_PUFFER = 4 * 1024

        /** Groß genug, dass wenige Rahmen den Puffer füllen. */
        const val GROSSE_NUTZLAST = 64 * 1024

        /** Eine Obergrenze, damit ein Fehler im Transport nicht zu einer Endlosschleife wird. */
        const val HOECHSTENS_SENDUNGEN = 500
    }
}
