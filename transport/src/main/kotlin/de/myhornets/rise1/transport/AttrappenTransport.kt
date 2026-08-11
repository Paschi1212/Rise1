package de.myhornets.rise1.transport

/**
 * T-066 — der Attrappen-Transport.
 *
 * ## Kein Zwischenprodukt
 *
 * ADR-001 verlangt ihn ausdrücklich: eine Attrappe, *„die Abbrüche und
 * Verzögerungen deterministisch erzeugt"*. Alles, was in E06, E07 und E08 noch
 * kommt — Wiederverbindung mit ansteigenden Wartezeiten, der Zustandsautomat
 * aus TDD 9.2, das Aufholen per Delta —, wird gegen **diese** Klasse
 * entwickelt und nicht gegen ein WLAN. Ein Fehlerfall, den man nicht auf Knopf
 * erzeugen kann, wird nicht geprüft.
 *
 * ## Was „deterministisch" hier heißt
 *
 * **Keine echte Zeit.** [jetzt] ist eine Zahl, die nur [laufeBis] verändert.
 * Kein `System.currentTimeMillis()`, kein Scheduler, kein Thread.
 *
 * **Kein Zufall.** Verluste und Abbrüche werden **abgezählt**, nicht gewürfelt:
 * [verwirfNaechste] und [brichAbNach] wirken auf die n-te Sendung. Ein Test mit
 * einer Wahrscheinlichkeit wäre manchmal grün und manchmal rot, und beides
 * hieße nichts.
 *
 * **Keine Nebenläufigkeit.** Alles passiert im aufrufenden Thread, in der
 * Reihenfolge des Aufrufs. Ein Test, der eine Reihenfolge prüft, prüft dann die
 * Reihenfolge und nicht den Scheduler.
 *
 * ## Ablauf
 *
 * Gesendete Rahmen werden **eingeplant**, nicht zugestellt. Zugestellt wird
 * erst, wenn die Uhr über den Zustellzeitpunkt hinausläuft:
 *
 *     val t = AttrappenTransport(verzoegerungMillis = 50)
 *     t.verbinde(host)
 *     t.sende(host, rahmen)
 *     t.laufeBis(49)   // nichts angekommen
 *     t.laufeBis(50)   // jetzt
 *
 * Was die Gegenstelle antwortet, bestimmt der Test über [antworteMit] — die
 * Attrappe erfindet nichts.
 */
class AttrappenTransport(
    /** Grundverzögerung jeder Zustellung. 0 heißt: sofort beim nächsten [laufeBis]. */
    private val verzoegerungMillis: Long = 0L,
) : Transport {

    /** Die Uhr. Bewegt sich ausschließlich durch [laufeBis]. */
    var jetzt: Long = 0L
        private set

    private val hoerer = mutableListOf<(TransportEreignis) -> Unit>()
    private val offen = linkedSetOf<Gegenstelle>()
    private val geplant = mutableListOf<Zustellung>()
    private var geschlossen = false

    /** Alles, was gesendet wurde — auch das Verworfene. Für Behauptungen im Test. */
    val gesendet = mutableListOf<Pair<Gegenstelle, ByteArray>>()

    private var sendezaehler = 0
    private val zuVerwerfen = mutableSetOf<Int>()
    private var abbruchNach: Int? = null
    private var naechsteVerbindungScheitertMit: TransportFehler? = null
    private val antworten = mutableMapOf<String, (ByteArray) -> ByteArray?>()

    private data class Zustellung(val faellig: Long, val ereignis: TransportEreignis)

    // ── Steuerung durch den Test ────────────────────────────────────────────

    /** Die n-te Sendung (1-basiert) kommt nicht an. Sie gilt trotzdem als gesendet. */
    fun verwirfNaechste(vararg nummern: Int) {
        zuVerwerfen += nummern.toList()
    }

    /** Nach der n-ten Sendung bricht die Verbindung ab. */
    fun brichAbNach(nummer: Int) {
        abbruchNach = nummer
    }

    /** Der nächste [verbinde]-Versuch scheitert mit diesem Fehler. */
    fun lassVerbindungScheitern(fehler: TransportFehler) {
        naechsteVerbindungScheitertMit = fehler
    }

    /**
     * Was die Gegenstelle auf einen Rahmen antwortet.
     *
     * `null` bedeutet Schweigen — auch das ist ein Fall, den man prüfen können
     * muss: Ein Gerät, das nicht antwortet, ist nicht dasselbe wie eines, das
     * die Verbindung abbricht.
     */
    fun antworteMit(gegenstelle: Gegenstelle, antwort: (ByteArray) -> ByteArray?) {
        antworten[gegenstelle.geraeteUid] = antwort
    }

    /**
     * Lässt die Uhr laufen und stellt alles zu, was bis dahin fällig ist.
     *
     * Zugestellt wird in der Reihenfolge der Fälligkeit; bei gleicher
     * Fälligkeit in der Reihenfolge des Sendens. Rückwärts geht die Uhr nicht.
     */
    fun laufeBis(zeitpunkt: Long) {
        require(zeitpunkt >= jetzt) { "Die Uhr läuft nicht rückwärts: $jetzt → $zeitpunkt." }
        while (true) {
            val naechste = geplant.filter { it.faellig <= zeitpunkt }.minByOrNull { it.faellig } ?: break
            geplant.remove(naechste)
            // Uhr auf die Fälligkeit, dann zustellen — dieselbe Regel wie im
            // Attrappennetz. Hier plant zwar heute kein Auftrag einen weiteren
            // ein, aber zwei Attrappen mit verschiedener Zeitrechnung wären
            // eine Falle für den, der später zwischen ihnen wechselt.
            jetzt = maxOf(jetzt, naechste.faellig)
            melde(naechste.ereignis)
        }
        jetzt = zeitpunkt
    }

    /** Bequemlichkeit: um die Grundverzögerung weiterlaufen. */
    fun laufeWeiter(millis: Long = verzoegerungMillis) = laufeBis(jetzt + millis)

    // ── Transport ───────────────────────────────────────────────────────────

    override val verbundene: Set<Gegenstelle> get() = offen.toSet()

    override fun beobachte(hoerer: (TransportEreignis) -> Unit) {
        this.hoerer += hoerer
    }

    override fun verbinde(gegenstelle: Gegenstelle) {
        pruefeOffen()
        val fehler = naechsteVerbindungScheitertMit
        if (fehler != null) {
            naechsteVerbindungScheitertMit = null
            plane(TransportEreignis.Fehlgeschlagen(gegenstelle, fehler))
            return
        }
        if (gegenstelle in offen) return
        offen += gegenstelle
        plane(TransportEreignis.Verbunden(gegenstelle))
    }

    override fun sende(an: Gegenstelle, rahmen: ByteArray): Boolean {
        pruefeOffen()
        // Keine Verbindung ist kein Fehler, sondern der Normalfall aus TDD 9.
        if (an !in offen) return false

        sendezaehler++
        gesendet += an to rahmen.copyOf()

        if (abbruchNach == sendezaehler) {
            offen -= an
            plane(TransportEreignis.Getrennt(an, "Attrappe: Abbruch nach Sendung $sendezaehler"))
            return true
        }
        // Verworfen heißt: gesendet, aber nie angekommen. Genau der Fall, den
        // ein Herzschlag entdecken muss.
        if (sendezaehler in zuVerwerfen) return true

        antworten[an.geraeteUid]?.invoke(rahmen)?.let { antwort ->
            plane(TransportEreignis.Empfangen(an, antwort))
        }
        return true
    }

    override fun trenne(gegenstelle: Gegenstelle, grund: String) {
        pruefeOffen()
        if (offen.remove(gegenstelle)) plane(TransportEreignis.Getrennt(gegenstelle, grund))
    }

    override fun schliesse() {
        if (geschlossen) return
        geschlossen = true
        offen.clear()
        geplant.clear()
        hoerer.clear()
    }

    // ── Innen ───────────────────────────────────────────────────────────────

    private fun plane(ereignis: TransportEreignis) {
        geplant += Zustellung(jetzt + verzoegerungMillis, ereignis)
        // Bei Verzögerung 0 wird trotzdem nicht sofort zugestellt: Der Test
        // soll den Zeitpunkt bestimmen, sonst hinge das Ergebnis an der
        // Aufrufreihenfolge innerhalb einer Methode.
    }

    private fun melde(ereignis: TransportEreignis) {
        // Über eine Kopie: Ein Hörer, der sich beim Empfang abmeldet, würde
        // sonst die Liste unter dem Iterator verändern.
        hoerer.toList().forEach { it(ereignis) }
    }

    private fun pruefeOffen() {
        check(!geschlossen) { "Dieser Transport ist geschlossen." }
    }
}
