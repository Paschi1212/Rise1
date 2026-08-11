package de.myhornets.rise1.transport

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Werkzeug für die JVM-Tests aus `T-070`.
 *
 * ## Warum Klartext — und warum das keine Hintertür ist
 *
 * ADR-008, „Was ein JVM-Test hier prüfen kann": *„die Übergabestelle, das
 * Beenden, die laufende Nummer gegen verspätete Rahmen — über Schleifen-Sockets
 * auf `localhost`. **Was er nicht kann:** TLS mit einem echten
 * Partie-Zertifikat … Dieser Teil ist ein **Gerätetest** und wird als solcher
 * gekennzeichnet, statt ihn mit einem abgeschwächten TrustManager scheinbar
 * prüfbar zu machen."*
 *
 * Genau diese Aufteilung steht hier. Was in dieser Datei liegt, spricht **kein
 * TLS** — es kann deshalb auch keine TLS-Prüfung abschwächen oder umgehen. Es
 * gibt hier keinen `TrustManager`, keinen Schalter und keinen Weg, mit dem eine
 * TLS-Verbindung ohne passenden Fingerabdruck zustande käme; der einzige Weg
 * zu TLS führt durch `TlsSocketquelle` in `:session`, und der einzige
 * `TrustManager` dort ist `FingerabdruckPruefer` (ADR-006).
 *
 * Diese Klassen liegen im **Testquellbaum** und sind nicht Teil der App.
 */

/** Eine ausgehende Klartextverbindung auf die Schleife. */
internal class KlartextSocketquelle(
    private val port: Int,
    private val zeitlimitMillis: Int = 2_000,
) : Socketquelle {

    override fun verbinde(gegenstelle: Gegenstelle): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), zeitlimitMillis)
        return socket
    }
}

/** Ein lauschender Klartextsocket auf der Schleife. Port 0: das System vergibt ihn. */
internal class KlartextLauschposten(private val port: Int = 0) : Lauschposten {
    override fun oeffne(): ServerSocket = ServerSocket(port, 16, InetAddress.getLoopbackAddress())
}

/**
 * Ein Gegenüber aus rohen Bytes.
 *
 * Für alles, was sich mit einem zweiten [Sockettransport] **nicht** zeigen
 * lässt: einen Rahmen in Stücken schicken, mit Absicht falsch rahmen, oder die
 * Leitung mitten im Satz zuschlagen. Es ist der Prozess auf dem Rechner, den
 * ADR-001 zum Kriterium gemacht hat.
 */
internal class Gegenserver : AutoCloseable {

    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val eingegangen = LinkedBlockingQueue<Socket>()
    private val offene = Collections.synchronizedList(mutableListOf<Socket>())

    val port: Int get() = server.localPort

    private val annahme = Thread({
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                return@Thread
            }
            offene += socket
            eingegangen.put(socket)
        }
    }, "test-gegenserver").apply { isDaemon = true; start() }

    /** Die nächste angenommene Verbindung. */
    fun naechsteVerbindung(millis: Long = 5_000): Socket =
        eingegangen.poll(millis, TimeUnit.MILLISECONDS)
            ?: error("Innerhalb von $millis ms hat sich niemand verbunden.")

    fun schreibe(socket: Socket, bytes: ByteArray) {
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    override fun close() {
        offene.toList().forEach { runCatching { it.close() } }
        runCatching { server.close() }
        annahme.join(1_000)
    }
}

/**
 * Was der Transport gemeldet hat — und **auf welchem Thread**.
 *
 * Der zweite Teil ist der wichtigere: ADR-008 sagt, alle Rückrufe laufen auf
 * dem Sitzungsthread. Eine Zusage, die niemand nachzählt, ist eine Absicht.
 */
internal class Mitschnitt(private val sitzung: Sitzungsthread) {

    private val gesammelt = Collections.synchronizedList(mutableListOf<TransportEreignis>())

    /** Wie oft ein Rückruf **nicht** auf dem Sitzungsthread lief. Muss 0 bleiben. */
    val fremdeThreads = AtomicInteger(0)

    val ereignisse: List<TransportEreignis> get() = gesammelt.toList()

    fun hoerer(): (TransportEreignis) -> Unit = { ereignis ->
        if (!sitzung.istAktuellerThread()) fremdeThreads.incrementAndGet()
        gesammelt += ereignis
    }

    inline fun <reified T : TransportEreignis> von(): List<T> = ereignisse.filterIsInstance<T>()

    /** Wartet, bis mindestens [anzahl] Ereignisse dieser Art da sind. */
    inline fun <reified T : TransportEreignis> warteAuf(anzahl: Int, millis: Long = 5_000): Boolean =
        warteBis(millis) { von<T>().size >= anzahl }

    fun empfangeneNutzlasten(): List<String> =
        von<TransportEreignis.Empfangen>()
            .flatMap { Rahmenleser().fuettere(it.rahmen).filterIsInstance<Rahmen>() }
            .map { String(it.nutzlast) }
}

/** Wartet auf eine Bedingung statt auf eine Uhr. */
internal fun warteBis(millis: Long = 5_000, bedingung: () -> Boolean): Boolean {
    val ende = System.currentTimeMillis() + millis
    while (System.currentTimeMillis() < ende) {
        if (bedingung()) return true
        Thread.sleep(2)
    }
    return bedingung()
}

/** Wie viele lebende Threads genau so heißen. */
internal fun lebendeThreads(name: String): Int =
    Thread.getAllStackTraces().keys.count { it.isAlive && it.name == name }

/** Ein Port, auf dem sicher niemand lauscht. */
internal fun geschlossenerPort(): Int =
    ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
