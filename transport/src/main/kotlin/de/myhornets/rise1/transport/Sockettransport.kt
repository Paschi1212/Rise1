package de.myhornets.rise1.transport

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * T-070 — der echte Transport über Sockets.
 *
 * Umsetzung von [[ADR-008 Nebenlaeufigkeit im Transport]] auf dem Weg, den
 * [[ADR-001 Transport]] entschieden hat: lokales WLAN, TCP, TLS. Innerhalb der
 * Verbindung gilt die eigene Rahmung aus `T-072` ([Rahmencodec], [Rahmenleser])
 * — **kein WebSocket**, siehe ADR-001, Ergänzung 10.
 *
 * ## Das Threadmodell, wörtlich aus ADR-008
 *
 * | Thread | Anzahl | Aufgabe |
 * |---|---|---|
 * | **Annahme** | 1 (nur Host) | `accept()`. Tut nichts weiter, als angenommene Verbindungen einzurichten. |
 * | **Lesen** | 1 je Verbindung | Blockierendes `read`, füttert den [Rahmenleser], reicht fertige Rahmen weiter. |
 * | **Senden** | 1 je Verbindung | Nimmt aus einer `BlockingQueue` und schreibt. Kein Aufrufer blockiert je auf dem Socket. |
 * | **Sitzung** | 1 im Prozess | Führt **alle** Rückrufe aus — siehe [Sitzungsthread]. |
 *
 * Die Obergrenze `2 × Sitzplatzzahl + 1` ist **hart**: [sitzplaetze] begrenzt
 * die gleichzeitig angenommenen Verbindungen, und ein Verbindungsversuch ohne
 * Platz wird **abgelehnt, nicht geparkt**.
 *
 * ## Wo der Verbindungsaufbau läuft — und warum dort
 *
 * Auf dem **Lesethread der Verbindung**, als dessen erste Handlung. Ein TLS-
 * Handshake dauert; im aufrufenden Thread würde er die Oberfläche anhalten, auf
 * dem Sitzungsthread jeden anderen Rückruf. Eine eigene Threadgattung dafür wäre
 * eine Erweiterung des Modells aus ADR-008 — und dort steht ausdrücklich, dass
 * dieses Modell vollständig ist. Der Thread, der die Verbindung später liest,
 * stellt sie deshalb auch her. Das kostet keinen Thread und keine Zeile
 * Sonderfall.
 *
 * ## Die einzige nebenläufige Stelle
 *
 * ADR-008: *„Die Grenze verläuft an genau einer Stelle: Der Lesethread übergibt
 * seine Rahmen an eine Warteschlange, der Sitzungsthread nimmt sie heraus. Das
 * ist der einzige Übergabepunkt, und er ist die einzige Stelle im System, die
 * nebenläufig richtig sein muss."*
 *
 * Alles, was diese Klasse an [TransportEreignis] herausgibt, wird über
 * [Sitzungsthread.fuehreAus] gemeldet — ausnahmslos, auch der Fehlerfall.
 *
 * ## Vollständige Rahmen, keine Bruchstücke
 *
 * Der Lesethread füttert einen [Rahmenleser] je Verbindung und gibt erst
 * heraus, was vollständig ist. Nach oben geht der Rahmen wieder **als Bytes**
 * ([Rahmencodec.kodiere]), weil [TransportEreignis.Empfangen] Bytes trägt und
 * die Schicht darüber ihren eigenen Leser führt. Das Ergebnis ist byte-gleich
 * mit dem, was ankam — Kennmarke, Version und Länge sind zu diesem Zeitpunkt
 * bereits geprüft.
 *
 * Ein [UnbekannterRahmen] wird hier **übersprungen**. Das ist dieselbe Zusage
 * wie in `T-072` (*„wird gemeldet und übersprungen, nicht als Angriff
 * behandelt"*), nur eine Schicht tiefer eingelöst: Ein älteres Gerät zerbricht
 * an einem neueren nicht, und die Verbindung bleibt stehen.
 *
 * ## Was hier **nicht** steht
 *
 * **Nichts über TLS, Zertifikate oder Fingerabdrücke.** `:transport` hat keine
 * Modulkante — weder auf `:core` noch auf `:session` (TDD 2.2). Woher ein
 * Socket kommt, entscheidet [Socketquelle] beziehungsweise [Lauschposten]; die
 * TLS-Umsetzung liegt in `:session` neben `FingerabdruckPruefer`, weil das
 * Zertifikat zu **einer Partie** gehört (ADR-006) und eine Partie
 * Sitzungswissen ist.
 *
 * Damit steht auch die Trennung, die ADR-008 für die Prüfbarkeit verlangt:
 *
 * - *„Wie funktioniert die Socket-Mechanik?"* — diese Klasse. Prüfbar über
 *   Klartext-Schleifensockets auf `localhost`.
 * - *„Wie wird der konkrete TLS-Socket erzeugt?"* — [Socketquelle] und
 *   [Lauschposten] in `:session`. **Gerätetest.**
 *
 * Es gibt **keinen Schalter**, der die Fingerabdruckprüfung abschaltet, und
 * keine Klartext-[Socketquelle] im Auslieferungsstand: Wer TLS spricht, spricht
 * über die Umsetzung in `:session`, und die kennt genau einen `TrustManager`
 * (ADR-006). Ein Test, der die Prüfung umgehen kann, prüft sie nicht.
 */
class Sockettransport(

    /** Der eine Sitzungsthread des Prozesses. Alle Rückrufe laufen dort. */
    private val sitzung: Sitzungsthread,

    /**
     * Woher ausgehende Verbindungen kommen.
     *
     * Ein reiner Host braucht keine und lässt es bei [Socketquelle.NurAnnehmend].
     */
    private val socketquelle: Socketquelle = Socketquelle.NurAnnehmend,

    /**
     * Wie viele Verbindungen gleichzeitig angenommen werden.
     *
     * ADR-008: *„Mehr Verbindungen als Sitzplätze nimmt der Host nicht an … ein
     * Verbindungsversuch ohne Platz wird abgelehnt, nicht geparkt."*
     */
    private val sitzplaetze: Int = STANDARD_SITZPLAETZE,

    /** Wie lange das Herunterfahren höchstens auf einen Thread wartet. */
    private val abschiedsfristMillis: Long = ABSCHIEDSFRIST_MILLIS,

    /**
     * Wie viele unversandte Rahmen eine Verbindung höchstens vor sich her
     * schiebt.
     *
     * Einstellbar wie [sitzplaetze] und aus demselben Grund: Die Zahl selbst ist
     * nicht heilig, ihre **Endlichkeit** schon — und ein Fehlerbild, das sich
     * nur mit 256 Rahmen à 1 MiB herstellen lässt, wird nie geprüft (`T-077`).
     */
    private val sendestauGrenze: Int = SENDESTAU_GRENZE,
) : Transport {

    private val hoerer = CopyOnWriteArrayList<(TransportEreignis) -> Unit>()

    /** Die aktuelle Verbindung je Gegenstelle. Höchstens eine. */
    private val verbindungen = ConcurrentHashMap<String, Verbindung>()

    /**
     * Die laufende Nummer aus ADR-008.
     *
     * *„Jede Verbindung bekommt eine laufende Nummer; der Sitzungsthread
     * vergleicht sie mit der aktuellen. Ohne diese Nummer landete nach einem
     * Wiederaufbau der letzte Rahmen der alten Leitung im neuen Ablauf — und
     * `T-108` prüfte ein Delta gegen einen Stand, der zu einer anderen Sitzung
     * gehört."*
     */
    private val nummernzaehler = AtomicLong(0)

    private val geschlossen = AtomicBoolean(false)

    @Volatile
    private var annahmeSocket: ServerSocket? = null

    @Volatile
    private var annahmeThread: Thread? = null

    /**
     * Ob beim letzten [schliesse] alle Threads innerhalb der Frist endeten.
     *
     * `T-077`: Auskunft, kein Steuerknopf. Vor dem ersten Herunterfahren `true`
     * — es gibt nichts, was schiefgegangen sein könnte.
     */
    @Volatile
    var sauberHeruntergefahren: Boolean = true
        private set

    /** Eine Verbindung mit allem, was zu ihr gehört. */
    private inner class Verbindung(val nummer: Long, val gegenstelle: Gegenstelle) {

        /**
         * Die Sendewarteschlange.
         *
         * Begrenzt, und das ist Absicht: Eine unbegrenzte Warteschlange
         * verwandelt eine Gegenstelle, die nicht mehr liest, in einen
         * wachsenden Speicherverbrauch — dieselbe Sorte Fehler, gegen die
         * [Rahmencodec.MAX_NUTZLAST] steht.
         */
        val ausgang = ArrayBlockingQueue<ByteArray>(sendestauGrenze)

        /**
         * Das `AtomicBoolean` aus ADR-008.
         *
         * *„Ein Verbindungsende … meldet **genau ein** `Getrennt` auf dem
         * Sitzungsthread — auch dann, wenn beide Threads das Ende gleichzeitig
         * bemerken. Wer es umlegt, meldet; alle anderen schweigen."*
         */
        val beendet = AtomicBoolean(false)

        @Volatile
        var socket: Socket? = null

        @Volatile
        var leseThread: Thread? = null

        @Volatile
        var sendeThread: Thread? = null

        val steht: Boolean get() = socket != null && !beendet.get()
    }

    // ── Host: annehmen ──────────────────────────────────────────────────────

    /**
     * Beginnt anzunehmen — **nur auf dem Host**, und genau einmal.
     *
     * Ein zweiter Aufruf ist ein Programmfehler, kein Betriebsfall: ADR-008
     * kennt genau einen Annahmethread. Zwei wären zwei Stellen, an denen die
     * Sitzplatzgrenze zu prüfen wäre.
     *
     * ## Ein Fehlschlag wird gemeldet, nicht geworfen (`T-077`)
     *
     * Dass ein Gerät nicht lauschen kann, ist ein **Fehlerbild**, kein
     * Programmfehler: ein belegter Port, eine verweigerte Berechtigung. ADR-001
     * verlangt für genau diesen Fall eine Auskunft — *„Wird sie verweigert, kann
     * dieses Gerät nicht hosten — ein anderes Gerät kann es."* Eine Ausnahme aus
     * `lausche` erreichte die Oberfläche nur, wenn jemand daran denkt, sie zu
     * fangen; das Ereignis erreicht sie über denselben Weg wie jedes andere.
     *
     * @return der tatsächliche Port. Bei Port 0 vergibt ihn das
     *   Betriebssystem — genau das, was `T-068` danach über `NsdManager`
     *   ankündigt, und was ein Test braucht, um nicht an einem belegten Port zu
     *   scheitern. **[KEIN_PORT]**, wenn nicht gelauscht werden konnte; dann
     *   kommt zusätzlich ein [TransportEreignis.Fehlgeschlagen] ohne
     *   Gegenstelle.
     */
    fun lausche(posten: Lauschposten): Int {
        pruefeOffen()
        check(annahmeThread == null) {
            "Es gibt genau einen Annahmethread (ADR-008). Dieser Transport nimmt bereits an."
        }
        val server = try {
            posten.oeffne()
        } catch (fehler: Verbindungsfehler) {
            // Die Fabrik konnte es bereits einordnen.
            meldeSpaeter(TransportEreignis.Fehlgeschlagen(null, fehler.fehler))
            return KEIN_PORT
        } catch (fehler: IOException) {
            meldeSpaeter(TransportEreignis.Fehlgeschlagen(null, deuteAufbau(fehler)))
            return KEIN_PORT
        } catch (fehler: SecurityException) {
            // Der Fall aus ADR-001: Dem Gerät fehlt die Berechtigung. Er kommt
            // als Laufzeitausnahme und nicht als `IOException` — ohne diesen
            // Zweig risse er den aufrufenden Thread mit.
            meldeSpaeter(TransportEreignis.Fehlgeschlagen(null, deuteAufbau(fehler)))
            return KEIN_PORT
        }

        annahmeSocket = server
        val thread = Thread({ nimmAn(server) }, ANNAHME_THREADNAME).apply { isDaemon = true }
        annahmeThread = thread
        thread.start()
        return server.localPort
    }

    /**
     * Der Annahmethread.
     *
     * ## Warum die Schleife **nicht** auf `server.isClosed` prüft (`T-077`)
     *
     * Weil das ein stiller Ausgang wäre: Fällt der Socket aus, während der
     * Thread gerade zwischen zwei Runden steht, endete die Schleife über ihre
     * Bedingung — ohne `accept`, ohne Ausnahme und ohne Meldung. Der Host nähme
     * ab da niemanden mehr an, und niemand erführe es.
     *
     * Es gibt deshalb genau **einen** Ausgang: die Ausnahme aus `accept`. Nach
     * dem Herunterfahren ist sie der vorgesehene Weg, davor ist sie ein
     * Fehlerbild.
     */
    private fun nimmAn(server: ServerSocket) {
        while (!geschlossen.get()) {
            val socket = try {
                server.accept()
            } catch (fehler: IOException) {
                if (!geschlossen.get()) {
                    meldeSpaeter(
                        TransportEreignis.Fehlgeschlagen(
                            null,
                            TransportFehler.VerbindungAbgebrochen(
                                "Der Annahmesocket ist ausgefallen: ${fehler.javaClass.simpleName}. " +
                                    "Dieses Gerät nimmt keine weiteren Verbindungen an.",
                            ),
                        ),
                    )
                }
                return
            }
            if (geschlossen.get()) {
                schliesseLeise(socket)
                return
            }
            if (verbindungen.size >= sitzplaetze) {
                // Abgelehnt, nicht geparkt (ADR-008). Und ein eigener Fehlerfall,
                // damit die Oberfläche „der Tisch ist voll" von „keine Leitung"
                // unterscheiden kann (ADR-001, T-077).
                schliesseLeise(socket)
                val grund = "Kein freier Sitzplatz: $sitzplaetze von $sitzplaetze belegt."
                meldeSpaeter(TransportEreignis.Fehlgeschlagen(null, TransportFehler.Abgelehnt(grund)))
                continue
            }
            starteVerbindung(vorlaeufigeGegenstelle(socket)) { socket }
        }
    }

    /**
     * Wer da angeklopft hat — vorläufig.
     *
     * Eine angenommene Verbindung trägt noch keine `device_uid`: Die kommt erst
     * mit dem Handshake aus TDD 9.3, und den führt `T-101` eine Schicht höher.
     * Bis dahin ist die Gegenstelle über ihre Adresse benannt, die unter allen
     * gleichzeitig offenen Verbindungen eines lauschenden Sockets eindeutig ist.
     *
     * Das [VORLAEUFIG]-Präfix ist keine Zierde: Wer eine solche Kennung
     * irgendwo festschreibt, hat den Handshake übersprungen.
     */
    private fun vorlaeufigeGegenstelle(socket: Socket): Gegenstelle {
        val adresse = "${socket.inetAddress?.hostAddress ?: "?"}:${socket.port}"
        return Gegenstelle(VORLAEUFIG + adresse, adresse)
    }

    // ── Transport ───────────────────────────────────────────────────────────

    override val verbundene: Set<Gegenstelle>
        get() = verbindungen.values.filter { it.steht }.map { it.gegenstelle }.toSet()

    override fun beobachte(hoerer: (TransportEreignis) -> Unit) {
        this.hoerer += hoerer
    }

    override fun verbinde(gegenstelle: Gegenstelle) {
        pruefeOffen()
        // Sofort und laut, nicht später und leise: Ein Host, der sich verbinden
        // will, ist ein Programmfehler. Als TransportFehler gemeldet sähe er aus
        // wie eine Störung im Netz — und würde von der Wiederverbindung aus
        // `T-074` geduldig wiederholt.
        check(socketquelle !== Socketquelle.NurAnnehmend) {
            "Dieser Transport nimmt nur an (ADR-008: Annahmethread nur auf dem Host). " +
                "Für ausgehende Verbindungen braucht er eine Socketquelle."
        }
        if (verbindungen.containsKey(gegenstelle.geraeteUid)) return
        starteVerbindung(gegenstelle) { socketquelle.verbinde(gegenstelle) }
    }

    override fun sende(an: Gegenstelle, rahmen: ByteArray): Boolean {
        pruefeOffen()
        // Keine Verbindung ist kein Fehler, sondern der Normalfall aus TDD 9.
        val verbindung = verbindungen[an.geraeteUid] ?: return false
        if (!verbindung.steht) return false

        // Kopie: Der Aufrufer darf sein Array weiterverwenden, während der
        // Sendethread noch darauf wartet.
        if (!verbindung.ausgang.offer(rahmen.copyOf())) {
            beende(
                verbindung,
                "Sendestau: mehr als $sendestauGrenze unversandte Rahmen. Eine Gegenstelle, " +
                    "die so viel nicht abnimmt, ist praktisch weg.",
            )
            return false
        }
        return true
    }

    override fun trenne(gegenstelle: Gegenstelle, grund: String) {
        pruefeOffen()
        val verbindung = verbindungen[gegenstelle.geraeteUid] ?: return
        beende(verbindung, grund)
    }

    /**
     * Fährt alles herunter.
     *
     * Die Reihenfolge steht in ADR-008: *„Ein Herunterfahren des Servers
     * schließt zuerst den Annahme-Socket, dann alle Verbindungen, und wartet
     * begrenzt auf die Threads. Danach ist der Transport **unbrauchbar** und
     * nicht wiederverwendbar."*
     *
     * Der [Sitzungsthread] wird **nicht** mitbeendet: Er gehört dem Prozess,
     * nicht diesem Transport, und ein Client-Transport darf einen Host-Transport
     * nicht mit herunterreißen.
     */
    override fun schliesse() {
        if (!geschlossen.compareAndSet(false, true)) return

        schliesseLeise(annahmeSocket)

        val alle = verbindungen.values.toList()
        alle.forEach { beende(it, "Der Transport wird heruntergefahren.") }

        val frist = System.currentTimeMillis() + abschiedsfristMillis
        val threads = listOfNotNull(annahmeThread) +
            alle.flatMap { listOfNotNull(it.leseThread, it.sendeThread) }
        threads.forEach { warteAuf(it, frist) }

        // `T-077`: Ein Herunterfahren, bei dem ein Thread die Frist überzieht,
        // ist kein sauberes. Es wird nicht geworfen — der Aufrufer fährt gerade
        // herunter, und eine Ausnahme hier ließe den Rest liegen. Es wird
        // vermerkt, damit ein Test und eine Fehlersuche es finden.
        sauberHeruntergefahren = threads.none { it !== Thread.currentThread() && it.isAlive }

        // Die schon eingereichten Rückrufe — insbesondere die letzten
        // `Getrennt` — laufen noch, bevor die Hörer verschwinden. Vom
        // Sitzungsthread aus aufgerufen entfällt das Warten: Man wartet nicht
        // auf sich selbst.
        sitzung.warteAufLeerlauf(abschiedsfristMillis)
        hoerer.clear()
        annahmeThread = null
    }

    // ── Nur für Tests und Fehlersuche ───────────────────────────────────────

    /**
     * Die laufende Nummer der aktuellen Verbindung, oder `null`.
     *
     * Auskunft, kein Steuerknopf: Ein Test, der prüft, dass nach einem
     * Wiederaufbau eine **neue** Nummer gilt, braucht sie; die Logik hier
     * benutzt sie nicht von außen.
     */
    fun verbindungsnummer(gegenstelle: Gegenstelle): Long? = verbindungen[gegenstelle.geraeteUid]?.nummer

    // ── Innen ───────────────────────────────────────────────────────────────

    private fun starteVerbindung(gegenstelle: Gegenstelle, aufbau: () -> Socket) {
        val verbindung = Verbindung(nummernzaehler.incrementAndGet(), gegenstelle)
        if (verbindungen.putIfAbsent(gegenstelle.geraeteUid, verbindung) != null) return

        val thread = Thread({ leseThreadKoerper(verbindung, aufbau) }, LESER_PRAEFIX + verbindung.nummer)
            .apply { isDaemon = true }
        verbindung.leseThread = thread
        thread.start()
    }

    /**
     * Der Lesethread: erst aufbauen, dann lesen.
     *
     * Warum der Aufbau hier liegt und nicht in einem eigenen Thread, steht im
     * Klassenkommentar.
     */
    private fun leseThreadKoerper(verbindung: Verbindung, aufbau: () -> Socket) {
        val socket = try {
            aufbau()
        } catch (fehler: Verbindungsfehler) {
            // Die Fabrik konnte den Fehler bereits einordnen — etwa als
            // FingerabdruckPasstNicht. Diese Auskunft geht nicht verloren.
            gibAufOhneMeldung(verbindung)
            meldeSpaeter(TransportEreignis.Fehlgeschlagen(verbindung.gegenstelle, fehler.fehler))
            return
        } catch (fehler: IOException) {
            gibAufOhneMeldung(verbindung)
            meldeSpaeter(TransportEreignis.Fehlgeschlagen(verbindung.gegenstelle, deute(fehler, 0)))
            return
        } catch (fehler: Throwable) {
            // Ein Programmfehler in der Fabrik. Die Verbindung wird freigegeben,
            // damit ein späterer Versuch nicht an einem Eintrag scheitert, den
            // niemand mehr bedient — die Ausnahme selbst wird nicht verschluckt.
            gibAufOhneMeldung(verbindung)
            throw fehler
        }

        if (verbindung.beendet.get() || geschlossen.get()) {
            schliesseLeise(socket)
            gibAufOhneMeldung(verbindung)
            return
        }

        verbindung.socket = socket
        // Der Sendethread steht, **bevor** `Verbunden` gemeldet wird: Ein Hörer
        // darf im selben Rückruf senden.
        starteSendethread(verbindung, socket)
        meldeSpaeter(TransportEreignis.Verbunden(verbindung.gegenstelle))

        // `T-077` — das Sicherungsnetz. Ein unerwarteter Fehler in der
        // Leseschleife darf keine Verbindung zurücklassen, die im Verzeichnis
        // steht, deren Thread aber tot ist: Sie meldete nie ein `Getrennt`, und
        // ein `verbinde` auf dieselbe Gegenstelle liefe ins Leere.
        //
        // Das `finally` deckt zugleich den regulären Ausgang ab; `beende` ist
        // ohne Wirkung, wenn das `AtomicBoolean` schon umgelegt ist. Genau ein
        // `Getrennt` bleibt genau ein `Getrennt`.
        try {
            leseSchleife(verbindung, socket)
        } catch (fehler: Throwable) {
            beende(verbindung, "Unerwarteter Fehler im Lesethread: ${fehler.javaClass.simpleName}")
            // Nicht verschluckt: Ein Programmfehler gehört laut in den
            // Standardfehlerkanal des Threads, nicht in eine stille Statistik.
            throw fehler
        } finally {
            beende(verbindung, "Der Lesethread ist beendet.")
        }
    }

    private fun leseSchleife(verbindung: Verbindung, socket: Socket) {
        val leser = Rahmenleser()
        val puffer = ByteArray(LESEPUFFER)

        val ein = try {
            socket.getInputStream()
        } catch (fehler: IOException) {
            beende(verbindung, "Kein Eingangsstrom: ${fehler.javaClass.simpleName}")
            return
        }

        while (!verbindung.beendet.get()) {
            val gelesen = try {
                ein.read(puffer)
            } catch (fehler: IOException) {
                beende(verbindung, "Lesen gescheitert: ${fehler.javaClass.simpleName}")
                return
            }
            if (gelesen < 0) {
                beende(verbindung, "Die Gegenstelle hat die Verbindung geschlossen.")
                return
            }

            val stuecke = try {
                leser.fuettere(puffer.copyOf(gelesen))
            } catch (fehler: Rahmenfehler) {
                // T-072: Nach einem Protokollfehler ist der Leser unbrauchbar
                // und die Verbindung nicht mehr vertrauenswürdig. Trennen, nicht
                // weiterlesen.
                beende(verbindung, "Protokollfehler: ${fehler.message}")
                return
            }

            for (stueck in stuecke) {
                // UnbekannterRahmen: übersprungen, nicht zugestellt — siehe
                // Klassenkommentar.
                if (stueck !is Rahmen) continue
                val bytes = Rahmencodec.kodiere(stueck)
                sitzung.fuehreAus {
                    // Die laufende Nummer aus ADR-008. Ein Rahmen aus einer
                    // bereits abgelösten Leitung wird **verworfen**.
                    if (istAktuell(verbindung)) {
                        melde(TransportEreignis.Empfangen(verbindung.gegenstelle, bytes))
                    }
                }
            }
        }
    }

    private fun starteSendethread(verbindung: Verbindung, socket: Socket) {
        val thread = Thread({
            // `T-077`: dasselbe Sicherungsnetz wie beim Lesethread. Ein
            // Sendethread, der still stirbt, hinterließe eine Verbindung, die
            // Rahmen annimmt und nie eine schickt.
            try {
                sendeSchleife(verbindung, socket)
            } catch (fehler: Throwable) {
                beende(verbindung, "Unerwarteter Fehler im Sendethread: ${fehler.javaClass.simpleName}")
                throw fehler
            }
        }, SENDER_PRAEFIX + verbindung.nummer).apply { isDaemon = true }

        verbindung.sendeThread = thread
        thread.start()
    }

    private fun sendeSchleife(verbindung: Verbindung, socket: Socket) {
        val aus = try {
            socket.getOutputStream()
        } catch (fehler: IOException) {
            beende(verbindung, "Kein Ausgangsstrom: ${fehler.javaClass.simpleName}")
            return
        }
        while (true) {
            val bytes = try {
                verbindung.ausgang.take()
            } catch (_: InterruptedException) {
                // Das Zeichen zum Aufhören. Es kommt aus [beende].
                return
            }
            try {
                aus.write(bytes)
                aus.flush()
            } catch (fehler: IOException) {
                // Die Gegenstelle ist während des Sendens verschwunden. Genau
                // ein `Getrennt` — das AtomicBoolean entscheidet, ob es dieser
                // Thread meldet oder der Lesethread, der es gleichzeitig merkt.
                beende(verbindung, "Senden gescheitert: ${fehler.javaClass.simpleName}")
                return
            }
        }
    }

    /**
     * Das Ende einer Verbindung — genau einmal.
     *
     * Wer das `AtomicBoolean` umlegt, räumt auf und meldet; alle anderen kehren
     * um. Lese- und Sendethread bemerken ein Ende regelmäßig gleichzeitig.
     */
    private fun beende(verbindung: Verbindung, grund: String) {
        if (!verbindung.beendet.compareAndSet(false, true)) return
        verbindungen.remove(verbindung.gegenstelle.geraeteUid, verbindung)
        schliesseLeise(verbindung.socket)
        // Weckt den Sendethread aus `take()`. Der Lesethread kommt über den
        // geschlossenen Socket heraus.
        verbindung.sendeThread?.interrupt()
        meldeSpaeter(TransportEreignis.Getrennt(verbindung.gegenstelle, grund))
    }

    /**
     * Ein Aufbau, der nie zustande kam.
     *
     * Kein [TransportEreignis.Getrennt]: Getrennt werden kann nur, was
     * verbunden war. Der Aufrufer bekommt [TransportEreignis.Fehlgeschlagen] —
     * das ist der Unterschied zwischen „die Leitung ist weggebrochen" und „es
     * gab nie eine", und die Oberfläche muss ihn erklären können (T-077).
     */
    private fun gibAufOhneMeldung(verbindung: Verbindung) {
        verbindung.beendet.set(true)
        verbindungen.remove(verbindung.gegenstelle.geraeteUid, verbindung)
    }

    private fun istAktuell(verbindung: Verbindung): Boolean =
        verbindungen[verbindung.gegenstelle.geraeteUid]?.nummer == verbindung.nummer

    private fun meldeSpaeter(ereignis: TransportEreignis) {
        sitzung.fuehreAus { melde(ereignis) }
    }

    /**
     * Meldet an alle Hörer — auf dem Sitzungsthread.
     *
     * Eine Kopie braucht es nicht: Die Liste kopiert beim Schreiben von sich
     * aus. Ein Hörer, der sich im Rückruf abmeldet, stört den laufenden
     * Durchlauf deshalb nicht.
     *
     * **Eine Ausnahme aus einem Hörer wird hier nicht abgefangen.** Das ist
     * dasselbe Verhalten wie beim [AttrappenTransport] — und dass beide sich
     * gleich verhalten, ist die Zusage aus ADR-008: *„Wer gegen die Attrappe
     * testet, testet weiterhin Reihenfolgen und keinen Scheduler."* Aufgefangen
     * wird sie eine Ebene höher vom [Sitzungsthread], damit sie ihn nicht
     * mitreißt.
     */
    private fun melde(ereignis: TransportEreignis) {
        hoerer.forEach { it(ereignis) }
    }

    private fun pruefeOffen() {
        check(!geschlossen.get()) {
            "Dieser Transport ist heruntergefahren und nicht wiederverwendbar (ADR-008)."
        }
    }

    private fun warteAuf(thread: Thread, frist: Long) {
        val rest = frist - System.currentTimeMillis()
        if (rest <= 0 || thread === Thread.currentThread()) return
        try {
            thread.join(rest)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun schliesseLeise(schliessbar: java.io.Closeable?) {
        try {
            schliessbar?.close()
        } catch (_: IOException) {
            // Ein Socket, der sich nicht schließen lässt, ist bereits zu.
        }
    }

    /**
     * Aus einer `IOException` wird ein benennbarer Fall.
     *
     * ADR-001 verlangt, dass die App fehlendes gemeinsames Netz *erklärt*
     * *„statt eine kryptische Zeitüberschreitung zu zeigen"*. Deshalb wird hier
     * eingeordnet und nicht durchgereicht. Die Meldung trägt den Ausnahmetyp,
     * **nie** Nutzlast (ADR-007).
     */
    private fun deute(fehler: IOException, zeitlimitMillis: Long): TransportFehler = when (fehler) {
        is SocketTimeoutException -> TransportFehler.Zeitueberschreitung(zeitlimitMillis)
        is ConnectException,
        is NoRouteToHostException,
        is PortUnreachableException,
        is UnknownHostException,
        -> TransportFehler.KeinGemeinsamesNetz

        else -> TransportFehler.VerbindungAbgebrochen(fehler.javaClass.simpleName)
    }

    /**
     * Warum dieses Gerät nicht lauschen kann (`T-077`).
     *
     * ## Warum [TransportFehler.Abgelehnt] und kein neuer Fall
     *
     * Weil es hier **keine Gegenstelle** gibt: Das Ereignis ist ein
     * `Fehlgeschlagen(gegenstelle = null, …)`, und in dieser Klasse bedeutet
     * genau diese Kombination bereits „abgelehnt, ohne dass jemand am anderen
     * Ende beteiligt war" — dieselbe Form wie bei der Sitzplatzgrenze.
     *
     * Ein eigener Fall wäre klarer und würde `TransportFehler` erweitern, also
     * den Vertrag aus `T-065` ändern. Das ist eine Entscheidung und keine
     * Nebenbei-Änderung; sie ist im Bericht zu `T-077` als offener Punkt
     * vermerkt. Bis dahin geht **keine Auskunft verloren**: Der Grund nennt den
     * Ausnahmetyp, und `SecurityException` ist der Fall, den ADR-001 meint —
     * *„Wird sie verweigert, kann dieses Gerät nicht hosten."*
     */
    private fun deuteAufbau(fehler: Throwable): TransportFehler = when (fehler) {
        is SecurityException -> TransportFehler.Abgelehnt(
            "Diesem Gerät fehlt die Berechtigung, Verbindungen anzunehmen. Es kann keine " +
                "Partie eröffnen — ein anderes Gerät am Tisch kann es (ADR-001).",
        )

        else -> TransportFehler.Abgelehnt(
            "Dieses Gerät kann nicht lauschen: ${fehler.javaClass.simpleName}. " +
                "Es kann keine Partie eröffnen — ein anderes Gerät am Tisch kann es.",
        )
    }

    companion object {

        /** Acht Spieler, also sieben Gäste plus Reserve — TDD 6.1, ADR-008. */
        const val STANDARD_SITZPLAETZE = 8

        /**
         * Wie viele unversandte Rahmen eine Verbindung höchstens vor sich her
         * schiebt.
         *
         * Die Zahl selbst ist nicht heilig, ihre Endlichkeit schon.
         */
        const val SENDESTAU_GRENZE = 256

        /** Ein Lesevorgang holt höchstens so viele Bytes auf einmal. */
        const val LESEPUFFER = 16 * 1024

        const val ABSCHIEDSFRIST_MILLIS = 2_000L

        /** Threadnamen — damit ein Test und ein Profiler dasselbe sehen. */
        const val ANNAHME_THREADNAME = "rise1-annahme"
        const val LESER_PRAEFIX = "rise1-lesen-"
        const val SENDER_PRAEFIX = "rise1-senden-"

        /** Präfix der Kennung einer noch nicht durch den Handshake benannten Gegenstelle. */
        const val VORLAEUFIG = "vorlaeufig:"

        /**
         * Was [lausche] zurückgibt, wenn nicht gelauscht werden konnte.
         *
         * `-1` und nicht `0`: Null ist ein gültiger Wunschport („vergib einen
         * freien"), und ein Fehlschlag darf nicht wie ein Erfolg aussehen.
         */
        const val KEIN_PORT = -1
    }
}

/**
 * Woher eine ausgehende Verbindung kommt.
 *
 * Die eine Stelle, an der `:transport` von TLS **nichts** wissen muss und
 * trotzdem TLS spricht. Die Umsetzung liegt in `:session` (`TlsSocketquelle`),
 * weil sie das Partie-Zertifikat und den `FingerabdruckPruefer` braucht und
 * beides Sitzungswissen ist (ADR-006).
 */
interface Socketquelle {

    /**
     * Baut die Verbindung auf und gibt einen benutzbaren Socket zurück.
     *
     * Der Aufruf **blockiert** — er läuft auf dem Lesethread der Verbindung und
     * darf das. Ein TLS-Handshake gehört vollständig hierher: Ein Socket, der
     * erst beim ersten Schreiben aushandelt, verschöbe die Fingerabdruckprüfung
     * hinter das erste `Verbunden` — und damit hinter die Zusage, dass eine
     * stehende Verbindung eine **geprüfte** Verbindung ist.
     *
     * @throws Verbindungsfehler wenn der Fehler bereits einzuordnen ist —
     *   insbesondere bei [TransportFehler.FingerabdruckPasstNicht], den nur die
     *   Umsetzung kennt.
     * @throws IOException für alles Übrige; der Transport ordnet es dann selbst ein.
     */
    fun verbinde(gegenstelle: Gegenstelle): Socket

    /**
     * Ein Transport, der nur annimmt.
     *
     * Der Host baut nichts nach außen auf. Ein `verbinde` auf ihm ist ein
     * Programmfehler und kein Betriebsfall — deshalb eine Ausnahme und kein
     * stiller [TransportFehler], der wie eine Störung im Netz aussähe.
     */
    object NurAnnehmend : Socketquelle {
        override fun verbinde(gegenstelle: Gegenstelle): Socket =
            throw UnsupportedOperationException(
                "Dieser Transport nimmt nur an (ADR-008: Annahmethread nur auf dem Host). " +
                    "Für ausgehende Verbindungen braucht er eine Socketquelle.",
            )
    }
}

/**
 * Der lauschende Socket des Hosts.
 *
 * Getrennt von [Socketquelle], weil auf einem Gerät immer nur eines von beidem
 * gebraucht wird — wer hostet, verbindet sich nicht.
 */
interface Lauschposten {

    /**
     * Öffnet den lauschenden Socket.
     *
     * Auf dem Gerät ein `SSLServerSocket` aus dem Partie-Zertifikat (ADR-006).
     *
     * @throws IOException wenn der Port belegt ist oder die Berechtigung fehlt.
     */
    fun oeffne(): ServerSocket
}

/**
 * Ein Aufbaufehler, den die Fabrik bereits einordnen konnte.
 *
 * Der wichtigste Fall ist [TransportFehler.FingerabdruckPasstNicht]: Ihn kennt
 * nur die Umsetzung in `:session`, und er darf unterwegs nicht zu einem
 * gewöhnlichen Verbindungsfehler verwaschen — *„Ein Angriff darf nicht wie eine
 * Störung aussehen"* (T-065).
 */
class Verbindungsfehler(
    val fehler: TransportFehler,
    ursache: Throwable? = null,
) : IOException(fehler.toString(), ursache)
