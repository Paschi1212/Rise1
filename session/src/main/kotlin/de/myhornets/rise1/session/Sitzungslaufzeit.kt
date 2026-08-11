package de.myhornets.rise1.session

import de.myhornets.rise1.core.log.RiseLog
import de.myhornets.rise1.transport.Sitzungsthread
import de.myhornets.rise1.transport.Transport

/**
 * T-075 — die langlebige Sitzungskomponente.
 *
 * ## Was hier entschieden wird
 *
 * ADR-008: *„Der Vordergrunddienst (`T-075`) hält den Sitzungsthread am Leben,
 * **nicht die Oberfläche**. Eine Activity, die verschwindet, darf keine
 * Verbindung beenden."*
 *
 * Diese Klasse ist der Gegenstand dieses Satzes: Sie **besitzt** den
 * [Sitzungsthread], den [Transport] und die [Sitzungsverbindung], und sie ist
 * das Einzige, was sie beendet. Wer sie hält, hält die Sitzung. Der
 * Vordergrunddienst hält sie; eine Activity darf sie ansehen und nie besitzen.
 *
 * ## Warum sie in `:session` liegt und nicht im Dienst
 *
 * Weil an ihr das Verhalten hängt, das man prüfen können muss: genau einmal
 * erzeugen, bei Fehlern nichts Halbfertiges zurücklassen, kontrolliert
 * herunterfahren. Läge das im `Service`, wäre jede dieser Zusagen nur auf einem
 * Gerät prüfbar — und `android.app.Service` gehört zu den Klassen, gegen die man
 * nicht entwickelt, sondern hofft.
 *
 * Im Dienst (`:ui`) steht deshalb nur noch, was ohne Android nicht geht:
 * Benachrichtigung, Vordergrundstart, Lebenszyklus-Rückrufe. Dieselbe
 * Aufteilung wie bei `Sitzungsanbindung` und `NsdDienstverzeichnis`.
 *
 * ## Einmal benutzbar
 *
 * Nach [beende] ist eine Laufzeit **unbrauchbar**, wie der `Rahmenleser` nach
 * einem Protokollfehler und der `Sockettransport` nach dem Herunterfahren
 * (ADR-008). Eine wiederverwendbare Laufzeit hätte einen zweiten Zustand
 * „beendet, aber wiederbelebbar", in dem niemand sagen könnte, welcher
 * Sitzungsthread gerade gemeint ist. Ein neuer Dienststart baut eine neue.
 *
 * ## Das `synchronized`, das hier erlaubt ist
 *
 * ADR-008 sagt, ein `synchronized` in `Verbindungsautomat` wäre ein Hinweis
 * darauf, dass die Regel gebrochen wurde. Hier ist es das Gegenteil: Diese
 * Klasse ist die **Grenze**, an der die Sitzung von außen betreten wird — vom
 * Hauptthread von Android, aus `onStartCommand` und `onDestroy`. Sie sieht
 * zwangsläufig zwei Threads, und genau deshalb liegt das Schloss hier und
 * nirgends dahinter. Alles hinter [aufSitzungsthread] sieht weiterhin nur einen.
 */
class Sitzungslaufzeit(
    private val bausatz: Sitzungsbausatz,
    private val threadname: String = Sitzungsthread.STANDARDNAME,
    /** Wie lange [beende] höchstens auf den Sitzungsthread wartet. */
    private val abschiedsfristMillis: Long = Sitzungsthread.ABSCHIEDSFRIST_MILLIS,
) {

    /** Drei Zustände, und der Weg dazwischen geht nur vorwärts. */
    enum class Zustand { RUHT, LAEUFT, BEENDET }

    private val schloss = Any()

    @Volatile
    private var zustandIntern: Zustand = Zustand.RUHT

    private var sitzungsthread: Sitzungsthread? = null
    private var transport: Transport? = null
    private var verbindung: Sitzungsverbindung? = null

    /**
     * Wie oft tatsächlich aufgebaut wurde.
     *
     * Auskunft, kein Steuerknopf. Sie beantwortet die Frage, um die es in T-075
     * geht: Vervielfacht ein wiederholtes `onStartCommand` die Sitzung? Ein
     * Zähler, der bei 1 stehen bleibt, ist die Antwort.
     */
    @Volatile
    var startzahl: Int = 0
        private set

    val zustand: Zustand get() = zustandIntern

    val laeuft: Boolean get() = zustandIntern == Zustand.LAEUFT

    /**
     * Baut die Sitzung auf — **höchstens einmal**.
     *
     * Der Bau selbst läuft im aufrufenden Thread und ist kurz: Ein
     * [Sitzungsthread] und ein [Transport] entstehen, ohne dass etwas über eine
     * Leitung geht. Erst [Sitzungsverbindung.starte] spricht mit dem Transport,
     * und das läuft — wie alles oberhalb von `Transport` — auf dem
     * Sitzungsthread (ADR-008).
     *
     * @return `true`, wenn dieser Aufruf die Sitzung aufgebaut hat. `false`,
     *   wenn sie bereits lief; dann ist **nichts** geschehen. Ein wiederholter
     *   Dienststart ist der Normalfall und kein Fehler.
     * @throws IllegalStateException nach [beende].
     * @throws Throwable was der [Sitzungsbausatz] wirft. Vorher ist alles wieder
     *   abgeräumt: Der Zustand steht dann auf [Zustand.RUHT], es läuft kein
     *   Thread und kein Socket. Ein halb aufgebauter Zustand ist der einzige,
     *   den es hier nicht gibt.
     */
    fun starte(): Boolean = synchronized(schloss) {
        when (zustandIntern) {
            Zustand.LAEUFT -> return false
            Zustand.BEENDET -> error(
                "Diese Sitzungslaufzeit ist beendet und nicht wiederverwendbar. " +
                    "Ein neuer Dienststart baut eine neue.",
            )

            Zustand.RUHT -> Unit
        }

        // `T-077`: Der Sitzungsthread verschluckt nichts. Was ein Rückruf wirft,
        // geht hier ins Protokoll — `:transport` kann das nicht selbst, es hat
        // keine Kante auf `:core` (TDD 2.2).
        val neuerThread = Sitzungsthread(threadname) { fehler ->
            RiseLog.e(PROTOKOLLMARKE, "Fehler in einem Rückruf auf dem Sitzungsthread.", fehler)
        }
        var neuerTransport: Transport? = null
        try {
            neuerTransport = bausatz.transport(neuerThread)
            val neueVerbindung = bausatz.verbindung(neuerTransport)

            sitzungsthread = neuerThread
            transport = neuerTransport
            verbindung = neueVerbindung
            zustandIntern = Zustand.LAEUFT
            startzahl++

            // Erst ab hier wird gesprochen — und zwar dort, wo es hingehört.
            neuerThread.fuehreAus { neueVerbindung.starte() }
            RiseLog.i(PROTOKOLLMARKE, "Sitzung aufgebaut (Start $startzahl).")
            return true
        } catch (fehler: Throwable) {
            // Rückwärts in der Reihenfolge des Aufbaus. Der Transport zuerst:
            // Er kann bereits einen Socket halten, und ein Socket überlebt einen
            // Thread.
            runCatching { neuerTransport?.schliesse() }
            neuerThread.beende(abschiedsfristMillis)
            sitzungsthread = null
            transport = null
            verbindung = null
            zustandIntern = Zustand.RUHT
            RiseLog.e(PROTOKOLLMARKE, "Aufbau der Sitzung gescheitert, nichts blieb zurück.", fehler)
            throw fehler
        }
    }

    /**
     * Fährt die Sitzung kontrolliert herunter.
     *
     * Die Reihenfolge ist dieselbe wie überall in ADR-008: erst der Transport —
     * der schließt seine Sockets, meldet je Verbindung **genau ein** `Getrennt`
     * und lässt die eingereihten Rückrufe auslaufen —, danach der
     * Sitzungsthread. Andersherum liefen die letzten `Getrennt` ins Leere.
     *
     * Mehrfach aufrufbar und auch auf einer nie gestarteten Laufzeit erlaubt:
     * `onDestroy` kommt auch für einen Dienst, dessen Start gescheitert ist.
     *
     * @return `true`, wenn dieser Aufruf eine laufende Sitzung beendet hat.
     */
    fun beende(): Boolean = synchronized(schloss) {
        if (zustandIntern != Zustand.LAEUFT) {
            zustandIntern = Zustand.BEENDET
            return false
        }
        val alterTransport = transport
        val alterThread = sitzungsthread

        zustandIntern = Zustand.BEENDET
        transport = null
        verbindung = null
        sitzungsthread = null

        runCatching { alterTransport?.schliesse() }
        alterThread?.beende(abschiedsfristMillis)
        RiseLog.i(PROTOKOLLMARKE, "Sitzung heruntergefahren.")
        return true
    }

    /**
     * Der einzige Weg an die [Sitzungsverbindung].
     *
     * Es gibt bewusst keinen Zugriff, der sie herausgibt. ADR-008: *„Alles, was
     * oberhalb von `Transport` liegt, läuft auf dem Sitzungsthread — und nur
     * dort."* Eine Activity, die sich die Verbindung geben ließe, läse sie vom
     * Hauptthread — und `Sitzungsverbindung` ist nicht threadsicher, soll es
     * nicht werden und braucht es nicht.
     *
     * @return `false`, wenn gerade keine Sitzung läuft. Die Aufgabe entfällt
     *   dann; das ist der Normalfall zwischen zwei Partien und kein Fehler.
     */
    fun aufSitzungsthread(aufgabe: (Sitzungsverbindung) -> Unit): Boolean {
        val (thread, ziel) = synchronized(schloss) {
            if (zustandIntern != Zustand.LAEUFT) return false
            sitzungsthread to verbindung
        }
        if (thread == null || ziel == null) return false
        thread.fuehreAus { aufgabe(ziel) }
        return true
    }

    /**
     * Wartet, bis der Sitzungsthread alles Eingereichte abgearbeitet hat.
     *
     * Für den Dienst nicht nötig, für Tests unentbehrlich: Sie ersetzt die
     * Pause, die man sonst ins Blaue hinein einbauen würde.
     */
    fun warteAufLeerlauf(millis: Long): Boolean {
        val thread = synchronized(schloss) { sitzungsthread } ?: return true
        return thread.warteAufLeerlauf(millis)
    }

    companion object {
        private const val PROTOKOLLMARKE = "Sitzung"
    }
}

/**
 * Woraus eine Sitzung gebaut wird.
 *
 * ## Warum eine Schnittstelle und kein Konstruktor mit sechs Feldern
 *
 * Weil [Sitzungslaufzeit] die Reihenfolge und die Fehlerbehandlung des Aufbaus
 * festlegt, nicht seinen Inhalt. Was ein Transport ist — ein `Sockettransport`
 * über TLS auf dem Gerät, ein `AttrappenTransport` im Test —, gehört nicht
 * hierher: `:session` weiß von TLS so wenig wie `:transport`, und das soll so
 * bleiben (ADR-006: das Partie-Zertifikat kommt aus dem `AndroidKeyStore`, also
 * von einem Gerät).
 *
 * Der [Sitzungsthread] wird **hereingereicht** und nicht hier erzeugt: Es gibt
 * genau einen im Prozess, und wer ihn baut, entscheidet [Sitzungslaufzeit].
 */
interface Sitzungsbausatz {

    /**
     * Der Transport dieser Sitzung.
     *
     * @throws Throwable wenn er sich nicht bauen lässt — etwa, weil das
     *   Partie-Zertifikat fehlt. Die Laufzeit räumt dann vollständig ab.
     */
    fun transport(sitzung: Sitzungsthread): Transport

    /**
     * Die Sitzungsverbindung über diesem Transport.
     *
     * Wird **nicht** gestartet: Das tut die Laufzeit, auf dem Sitzungsthread.
     */
    fun verbindung(transport: Transport): Sitzungsverbindung
}
