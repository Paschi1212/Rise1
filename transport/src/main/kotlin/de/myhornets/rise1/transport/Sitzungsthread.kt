package de.myhornets.rise1.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * T-070 — der Sitzungsthread aus [[ADR-008 Nebenlaeufigkeit im Transport]].
 *
 * ## Die Regel, aus der alles folgt
 *
 * ADR-008: *„**Alles, was oberhalb von `Transport` liegt, läuft auf dem
 * Sitzungsthread — und nur dort.**"*
 *
 * Deshalb muss keine der bestehenden Klassen threadsicher werden.
 * `Sitzungsverbindung`, `Verbindungsautomat`, `Wiedereinstiegsablauf` und
 * `Ausgangsstapel` sind es nicht, sie sollen es nicht sein, und sie brauchen es
 * nicht: Sie sehen nie zwei Threads. Ein `synchronized` in einer dieser Klassen
 * wäre nicht überflüssig, sondern ein Hinweis darauf, dass diese Regel gebrochen
 * wurde.
 *
 * Es gibt **einen** davon im Prozess. Host und Client teilen ihn sich, falls ein
 * Gerät je beides wäre; im Betrieb hält ihn der Vordergrunddienst am Leben
 * (`T-075`) und nicht die Oberfläche.
 *
 * ## Warum ein `ExecutorService` und keine Coroutine
 *
 * ADR-008, „Was ausdrücklich nicht entschieden wird": *„Keine Coroutinen. Nicht
 * aus Abneigung, sondern weil `:transport` heute keine Abhängigkeit hat … Ein
 * `ExecutorService` mit einem Thread leistet dasselbe und ist in einem JVM-Test
 * ohne Testbibliothek anzuhalten."*
 *
 * Konkret ein [ThreadPoolExecutor] mit fester Größe eins und **nicht**
 * `Executors.newSingleThreadExecutor`: Nur so ist die Warteschlange sichtbar
 * ([ausstehend]), und die braucht ein Test, der eine Übergabe beobachten will,
 * ohne zu schlafen.
 *
 * ## Warum ein Rückruf diesen Thread nie mitreißt
 *
 * Ein `ThreadPoolExecutor` ersetzt seinen Thread, wenn eine Aufgabe mit einer
 * Ausnahme endet. Dann wäre [istAktuellerThread] ab da falsch — und die
 * wichtigste Zusage dieser Klasse stillschweigend gebrochen. Deshalb fängt
 * [fuehreAus] **jedes** `Throwable` ab und legt es in [letzterFehler].
 *
 * Verschluckt wird es damit nicht: `:transport` hat keine Kante auf `:core` und
 * deshalb keinen `RiseLog` (Modulgrenzen, TDD 2.2). Statt still zu scheitern,
 * zählt diese Klasse mit und hebt den letzten Fehler auf, damit ein Test und
 * eine spätere Fehlersuche ihn finden.
 */
class Sitzungsthread(name: String = STANDARDNAME) : AutoCloseable {

    private val warteschlange = LinkedBlockingQueue<Runnable>()

    private val traeger = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, warteschlange) { auftrag ->
        // Daemon: Ein vergessener Sitzungsthread darf eine JVM nicht am Leben
        // halten — weder einen Test noch die App.
        Thread(auftrag, name).apply { isDaemon = true }
    }

    @Volatile
    private var eigener: Thread? = null

    @Volatile
    var letzterFehler: Throwable? = null
        private set

    @Volatile
    var fehlerzahl: Int = 0
        private set

    init {
        // Den Thread sofort erzeugen und seine Identität festhalten. Ohne das
        // wäre [istAktuellerThread] vor der ersten Aufgabe nicht beantwortbar.
        val bereit = CountDownLatch(1)
        traeger.execute {
            eigener = Thread.currentThread()
            bereit.countDown()
        }
        bereit.await()
    }

    /** Wie viele Aufgaben warten. Für Tests und Fehlersuche, nicht für Steuerung. */
    val ausstehend: Int get() = warteschlange.size

    /** Läuft der aufrufende Code auf dem Sitzungsthread? */
    fun istAktuellerThread(): Boolean = Thread.currentThread() === eigener

    /**
     * Die Zusage aus ADR-008 als Prüfung.
     *
     * Gedacht für Stellen, die sie behaupten. Sie kostet nichts und ersetzt eine
     * Fehlersuche, die sonst mit einem sporadisch falschen Zustand beginnt.
     */
    fun verlangeSitzungsthread() {
        check(istAktuellerThread()) {
            "Diese Stelle gehört auf den Sitzungsthread (ADR-008), läuft aber auf " +
                "[${Thread.currentThread().name}]."
        }
    }

    /** Eine Aufgabe auf dem Sitzungsthread ausführen. Kehrt sofort zurück. */
    fun fuehreAus(aufgabe: () -> Unit) {
        if (traeger.isShutdown) return
        try {
            traeger.execute {
                try {
                    aufgabe()
                } catch (fehler: Throwable) {
                    letzterFehler = fehler
                    fehlerzahl++
                }
            }
        } catch (_: RejectedExecutionException) {
            // Der Thread wurde zwischen Prüfung und Übergabe beendet. Nach dem
            // Herunterfahren ist das der vorgesehene Ausgang, kein Fehler.
        }
    }

    /**
     * Wartet, bis alles Eingereichte abgearbeitet ist.
     *
     * Über eine Sperre am Ende der Warteschlange — der einzige Weg, der keine
     * Annahme über Ausführungszeiten macht.
     *
     * @return `false` bei Zeitüberschreitung. Vom Sitzungsthread selbst
     *   aufgerufen `true`, ohne zu warten: Auf sich selbst zu warten wäre ein
     *   Stillstand, kein Warten.
     */
    fun warteAufLeerlauf(millis: Long): Boolean {
        if (istAktuellerThread() || traeger.isShutdown) return true
        val sperre = CountDownLatch(1)
        try {
            traeger.execute { sperre.countDown() }
        } catch (_: RejectedExecutionException) {
            return true
        }
        return sperre.await(millis, TimeUnit.MILLISECONDS)
    }

    /**
     * Hält den Thread an.
     *
     * Erst nach Feierabend: Was schon eingereicht ist, läuft noch. Danach ist
     * dieser Sitzungsthread **unbrauchbar** — dieselbe Regel wie beim
     * `Rahmenleser` nach einem Protokollfehler und beim [Sockettransport] nach
     * dem Herunterfahren (ADR-008).
     */
    fun beende(warteMillis: Long = ABSCHIEDSFRIST_MILLIS) {
        traeger.shutdown()
        if (istAktuellerThread()) return
        if (!traeger.awaitTermination(warteMillis, TimeUnit.MILLISECONDS)) {
            traeger.shutdownNow()
        }
    }

    override fun close() = beende()

    companion object {
        const val STANDARDNAME = "rise1-sitzung"

        /** Wie lange ein Herunterfahren höchstens auf den Thread wartet. */
        const val ABSCHIEDSFRIST_MILLIS = 2_000L
    }
}
