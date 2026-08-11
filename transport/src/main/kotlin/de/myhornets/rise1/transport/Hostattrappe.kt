package de.myhornets.rise1.transport

/**
 * T-067 — die Host-Attrappe.
 *
 * ## Warum sie vor der ersten echten Transportarbeit kommt
 *
 * [[E06 Transport]]: *„`T-067` gehört **vor** die erste echte Transportarbeit —
 * sie ist der Grund, warum ADR-001 so entschieden wurde."* Und ADR-001 selbst:
 * *„Gegen einen Prozess auf dem Rechner entwickeln zu können, Abbrüche gezielt
 * zu provozieren und Verkehr mitzuschneiden, ist bei diesem Umfang kein
 * Komfort, sondern der Unterschied zwischen fertig werden und stecken bleiben."*
 *
 * ## Der Unterschied zu [AttrappenTransport]
 *
 * [AttrappenTransport] (T-066) ist **einseitig**: Er stellt dem Client einen
 * Transport hin, dessen Antworten der Test vorgibt. Er hat kein Gegenüber, das
 * etwas *entscheidet*.
 *
 * Diese Klasse **ist** das Gegenüber. Sie hat eigenen Zustand: Sie merkt sich,
 * was sie empfangen hat, sie ist erreichbar oder nicht, sie trägt einen
 * Fingerabdruck, den ein Client prüfen kann, und sie entscheidet je Rahmen, was
 * sie tut. Zusammen mit [Attrappennetz] entsteht daraus eine vollständige
 * Zweiseitigkeit — ohne Netz, ohne Gerät, ohne Zufall.
 *
 * Beides bleibt nebeneinander bestehen, und das ist Absicht: Ein Test, der nur
 * das Verhalten des Clients bei Verlusten prüft, soll sich kein Gegenüber
 * einrichten müssen.
 *
 * ## Was sie ausdrücklich **nicht** tut
 *
 * **Sie kennt das Beitrittsprotokoll nicht.** Kein Handshake, keine
 * Sitzungsverwaltung, keine Sequenzvergabe. Das ist [[E08 Sitzung und Reconnect]]
 * und wird **gegen** diese Attrappe gebaut, nicht in sie hinein. Wäre das
 * Protokoll hier schon drin, prüfte ein späterer Test die Attrappe gegen sich
 * selbst.
 *
 * **Sie kennt keine Nutzdaten.** Ein Rahmen ist ein `ByteArray`. Was darin
 * steht, entscheidet die Schicht darüber.
 *
 * **Sie verschlüsselt nichts.** Der [fingerabdruck] ist eine Zeichenkette, die
 * sie behauptet — genau so viel, wie ein Test braucht, um die Prüfung aus
 * `T-071` gegen einen passenden **und** einen unpassenden Fall zu führen.
 */
class Hostattrappe(

    /** Wer dieser Host ist. Entspricht dem, was `NsdManager` später ausgibt. */
    val gegenstelle: Gegenstelle,

    /**
     * Der Fingerabdruck des Partie-Zertifikats.
     *
     * ADR-001: selbstsigniert, *„sein Fingerabdruck wird als kurzer Code oder QR
     * angezeigt und vom beitretenden Gerät geprüft"*. Hier ist er schlicht eine
     * Zeichenkette — die Prüfung ist eine Gleichheit, und die will man an einem
     * passenden und einem unpassenden Fall geprüft haben, nicht an Kryptografie.
     */
    val fingerabdruck: String = "TEST-FINGERABDRUCK",
) {

    /**
     * Ob der Host gerade erreichbar ist.
     *
     * Der Schalter für Wiederverbindungstests (`T-074`): auf `false` setzen,
     * den Abbruch beobachten, wieder auf `true` setzen, den erneuten Aufbau
     * beobachten. Ein Test, der dafür ein WLAN abschalten müsste, würde nicht
     * geschrieben.
     */
    var erreichbar: Boolean = true

    private val mitschnitt = mutableListOf<ByteArray>()

    /** Alles, was dieser Host empfangen hat — in Reihenfolge, als Kopien. */
    val empfangen: List<ByteArray> get() = mitschnitt.map { it.copyOf() }

    /** Bequemlichkeit für Behauptungen über Textrahmen. */
    val empfangenAlsText: List<String> get() = mitschnitt.map { String(it) }

    private var regel: (ByteArray) -> Antwort = { Antwort.Schweige }
    private var schweigtAb: Int? = null
    private var trenntBei: Int? = null

    /** Was der Host auf einen Rahmen tut. */
    sealed interface Antwort {

        /** Ein Rahmen zurück. */
        class Sende(val rahmen: ByteArray) : Antwort

        /**
         * Nichts.
         *
         * Ein eigener Fall und nicht dasselbe wie [Trenne]: Ein Host, der
         * schweigt, hält die Verbindung. Genau diesen Unterschied muss der
         * Herzschlag aus `T-073` erkennen — sonst gilt jede Denkpause als Ausfall.
         */
        data object Schweige : Antwort

        /** Verbindung beenden. Der Client sieht [TransportEreignis.Getrennt]. */
        data class Trenne(val grund: String) : Antwort

        /**
         * Ablehnen — etwa, weil die Partie voll ist.
         *
         * Getrennt von [Trenne], weil es etwas anderes bedeutet: Der Host ist
         * da und sagt nein. Das muss die Oberfläche anders erklären als einen
         * Abbruch (ADR-001, „Fehlerbilder erklären", `T-077`).
         */
        data class LehneAb(val grund: String) : Antwort
    }

    // ── Verhalten festlegen ─────────────────────────────────────────────────

    /**
     * Die Regel, nach der dieser Host antwortet.
     *
     * Bewusst eine Funktion und keine Liste vorbereiteter Antworten: Ein
     * Beitrittsprotokoll antwortet auf den Inhalt des Rahmens, nicht auf seine
     * Nummer. Mit einer Liste ließe sich `T-101` später nicht abbilden.
     */
    fun antworteMit(regel: (ByteArray) -> Antwort): Hostattrappe {
        this.regel = regel
        return this
    }

    /** Kurzform: auf jeden Rahmen dasselbe zurück, mit einem Präfix. */
    fun spiegle(praefix: String = ""): Hostattrappe =
        antworteMit { Antwort.Sende((praefix + String(it)).toByteArray()) }

    /** Ab dem n-ten empfangenen Rahmen (1-basiert) schweigt der Host. */
    fun schweigeAb(nummer: Int): Hostattrappe {
        schweigtAb = nummer
        return this
    }

    /** Beim n-ten empfangenen Rahmen (1-basiert) trennt der Host. */
    fun trenneBei(nummer: Int, grund: String = "Attrappe: geplanter Abbruch"): Hostattrappe {
        trenntBei = nummer
        trennGrund = grund
        return this
    }

    private var trennGrund: String = "Attrappe: geplanter Abbruch"

    /** Setzt Mitschnitt und Zähler zurück — nicht das Verhalten. */
    fun vergissEmpfangenes() {
        mitschnitt.clear()
    }

    // ── Vom Netz aufgerufen ─────────────────────────────────────────────────

    /**
     * Ein Rahmen kommt an.
     *
     * Die Reihenfolge ist wichtig und festgeschrieben: **erst mitschneiden,
     * dann entscheiden.** Ein Rahmen, der zum Abbruch führt, ist trotzdem
     * angekommen — hätte man ihn nicht im Mitschnitt, wäre nach dem Abbruch
     * nicht mehr feststellbar, was ihn ausgelöst hat.
     */
    internal fun nimmEntgegen(rahmen: ByteArray): Antwort {
        mitschnitt += rahmen.copyOf()
        val nummer = mitschnitt.size
        trenntBei?.let { if (nummer == it) return Antwort.Trenne(trennGrund) }
        schweigtAb?.let { if (nummer >= it) return Antwort.Schweige }
        return regel(rahmen)
    }
}
