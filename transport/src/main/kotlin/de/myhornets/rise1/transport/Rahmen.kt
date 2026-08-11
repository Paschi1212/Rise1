package de.myhornets.rise1.transport

/**
 * T-072 — die Rahmung.
 *
 * ## Wofür
 *
 * ADR-001 entscheidet TCP. TCP ist ein **Strom**, kein Nachrichtendienst: Was
 * als ein `send` losgeschickt wird, kommt als zwei Stücke an, oder zwei
 * Sendungen kommen als eines. Wer das ignoriert, baut ein Protokoll, das im
 * WLAN des Entwicklers funktioniert und am Küchentisch nicht.
 *
 * Genau diese Lücke war in `T-067` als bekannte Einschränkung vermerkt: *„Die
 * Attrappe modelliert Laufzeit und Verlust, aber keine Teilrahmen … das gehört
 * zu `T-072`, sobald es ein Rahmenformat gibt, an dem sich das zeigen lässt."*
 * Jetzt gibt es eines, und [Rahmenleser] wird gegen genau diesen Fall geprüft.
 *
 * ## Was die Rahmung **nicht** tut
 *
 * **Sie schaut nicht in die Nutzlast.** Ein Rahmen trägt Bytes. Ob darin ein
 * öffentliches Ereignis oder ein Chiffrat steckt, entscheidet die Schicht
 * darüber — und die Trennung von PUBLIC und nicht-PUBLIC ist bereits zweifach
 * durchgesetzt: strukturell in `:core` durch den versiegelten `Payload` und in
 * der Datenbank durch den Trigger `match_event_sichtbarkeit`.
 *
 * Sie hier ein drittes Mal nachzubauen wäre kein zusätzlicher Schutz, sondern
 * eine dritte Stelle, die auseinanderlaufen kann. Die Rahmung rahmt.
 *
 * **Sie protokolliert keine Nutzlast.** [Rahmen.toString] zeigt Typ und Länge,
 * nie den Inhalt. Ein Chiffrat gehört in kein Protokoll, und ein Klartext-Event
 * auch nicht.
 */

/**
 * Die Rahmentypen.
 *
 * Bewusst als geschlossene Menge mit festen Byte-Kennungen: Die Kennung steht
 * auf der Leitung und darf sich zwischen Fassungen **nie** verschieben. Ein
 * `enum` ohne feste Zahl würde sich beim Umsortieren still ändern.
 */
enum class Rahmentyp(val kennung: Byte) {

    /** Beitritt oder Wiedereinstieg — TDD 9.3. */
    HANDSHAKE(1),

    /** Antwort des Hosts darauf. */
    HANDSHAKE_ANTWORT(2),

    /** Ein Event aus dem Log. Nutzlast ist undurchsichtig. */
    EREIGNIS(3),

    /** Lebenszeichen — TDD 9.2. Nutzlast leer oder winzig. */
    HERZSCHLAG(4),

    /** Personalisierter Schnappschuss — TDD 9.5. */
    SCHNAPPSCHUSS(5),

    /** Der Host lehnt ab, mit Grund. */
    ABLEHNUNG(6),

    /**
     * Das Aufholen nach dem Wiedereinstieg — TDD 9.5, [[ADR-007 Nutzlastformat der Sitzungsrahmen]].
     *
     * Ein eigener Typ und keine Folge von [EREIGNIS]-Rahmen: Ein Delta trägt den
     * **abgedeckten Bereich** mit sich (`abSeqExklusiv`, `bisSeq`), und ohne den
     * könnte der Empfänger eine echte Lücke nicht von einer gefilterten
     * unterscheiden. Einzelne Ereignisrahmen hätten diese Klammer nicht.
     */
    DELTA(7),

    /**
     * Das Beitrittsgesuch eines neuen Geräts — TDD 9.3, `T-101`.
     *
     * ## Warum ein eigener Typ neben [HANDSHAKE]
     *
     * Weil es eine andere Nachricht ist. Ein **Wiedereinstieg** weist sich mit
     * einem `rejoin_token` aus, den es schon hat; ein **Beitritt** hat noch
     * keinen und bittet um einen Sitzplatz. Beides in [HANDSHAKE] zu legen
     * verlangte ein Unterscheidungsbyte in der Nutzlast — und damit einen
     * Leser, der erst hineinschaut, um zu wissen, was er liest.
     *
     * Es ist dieselbe Begründung, mit der [DELTA] entstanden ist
     * ([[ADR-007 Nutzlastformat der Sitzungsrahmen]]), und derselbe Preis:
     * eine Kennung mehr, die sich nie wieder verschieben darf. Ältere Geräte
     * überspringen sie sauber — dafür gibt es [UnbekannterRahmen].
     */
    BEITRITT(8),

    /** Der Host nimmt auf: Sitzplatz, `participant_uid`, `rejoin_token`. */
    BEITRITT_ANTWORT(9),

    /**
     * Der Host nimmt **nicht** auf.
     *
     * Eigener Typ und kein Flag in [BEITRITT_ANTWORT] — dieselbe Regel wie bei
     * [ABLEHNUNG]: *„Eine Ablehnung ist keine Antwort mit einem Flag darin: Wer
     * sie als solche liest, kann sie übersehen."* Und ein eigener Typ neben
     * [ABLEHNUNG], weil die Gründe verschieden sind: Ein Tisch ist voll, ein
     * Wiedereinstieg scheitert am Nachweis.
     */
    BEITRITT_ABLEHNUNG(10),
    ;

    companion object {
        /**
         * `null` bei unbekannter Kennung.
         *
         * Dasselbe Prinzip wie bei den Event-Typen (TDD 5.5): Ein Gerät mit
         * neuerer App darf einen Rahmen schicken, den dieses nicht kennt. Es
         * wird gemeldet und übersprungen, nicht als Angriff behandelt.
         */
        fun vonKennung(kennung: Byte): Rahmentyp? = entries.firstOrNull { it.kennung == kennung }
    }
}

/**
 * Was [Rahmenleser] herausgibt.
 *
 * Versiegelt statt `Any`: Der Aufrufer soll gezwungen sein, den unbekannten
 * Fall zu behandeln, statt ihn wegzucasten.
 */
sealed interface Gelesenes

/** Ein Rahmen auf der Leitung. */
class Rahmen(val typ: Rahmentyp, val nutzlast: ByteArray) : Gelesenes {

    init {
        require(nutzlast.size <= Rahmencodec.MAX_NUTZLAST) {
            "Nutzlast von ${nutzlast.size} Bytes überschreitet die Obergrenze von " +
                "${Rahmencodec.MAX_NUTZLAST} Bytes."
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Rahmen && typ == other.typ && nutzlast.contentEquals(other.nutzlast))

    override fun hashCode(): Int = 31 * typ.hashCode() + nutzlast.contentHashCode()

    /** Typ und Länge, **nie** der Inhalt. */
    override fun toString(): String = "Rahmen(${typ.name}, ${nutzlast.size} Bytes)"
}

/** Ein Rahmen, dessen Typ dieses Gerät nicht kennt (TDD 5.5 sinngemäß). */
class UnbekannterRahmen(val kennung: Byte, val laenge: Int) : Gelesenes {
    override fun toString(): String = "UnbekannterRahmen(kennung=$kennung, $laenge Bytes)"
}

/**
 * Ein Protokollfehler.
 *
 * **Kein erwarteter Fall.** Wer falsch gerahmte Bytes schickt, spricht ein
 * anderes Protokoll oder versucht etwas. In beiden Fällen ist die Verbindung
 * nicht mehr vertrauenswürdig: Der Aufrufer muss sie **trennen**, nicht
 * weiterlesen. Deshalb eine Ausnahme und kein Rückgabewert — anders als bei
 * einem Verbindungsabbruch, der der Normalfall ist (TDD 9).
 */
class Rahmenfehler(meldung: String) : IllegalStateException(meldung)

/**
 * Das Format auf der Leitung.
 *
 * ```
 *  0      1      2       3      4                8              8+n
 *  ┌──────┬──────┬───────┬──────┬────────────────┬───────────────┐
 *  │ 'R'  │ '1'  │ Vers. │ Typ  │ Länge (4, BE)  │ Nutzlast (n)  │
 *  └──────┴──────┴───────┴──────┴────────────────┴───────────────┘
 * ```
 *
 * **Warum eine Kennmarke.** Zwei Bytes, die jeder Rahmen trägt. Sie kosten
 * nichts und beantworten die Frage, die man sonst nie sicher beantworten kann:
 * Spricht das Gegenüber überhaupt dieses Protokoll? Ohne sie wird ein
 * versehentlich verbundener fremder Dienst als Längenangabe gelesen — und
 * fordert dann 3 GB Puffer an.
 *
 * **Warum die Länge vor der Nutzlast.** Damit der Leser weiß, wie viel er
 * sammeln muss, bevor er etwas ausliefert. Das ist der ganze Zweck der Übung.
 *
 * **Warum Big-Endian.** Netzwerkreihenfolge. Es gibt keinen zweiten Grund, und
 * es braucht keinen — wichtig ist nur, dass es festgeschrieben ist.
 */
object Rahmencodec {

    const val KENNMARKE_1: Byte = 'R'.code.toByte()
    const val KENNMARKE_2: Byte = '1'.code.toByte()

    /** Erhöht sich nur mit einer ADR. Ein Rahmen fremder Version wird abgewiesen. */
    const val PROTOKOLLVERSION: Byte = 1

    /** Kopflänge in Bytes. */
    const val KOPF_LAENGE = 8

    /**
     * Obergrenze der Nutzlast: 1 MiB.
     *
     * **Nicht Sparsamkeit, sondern Schutz.** Die Längenangabe kommt vom
     * Gegenüber. Ohne Obergrenze könnte ein Gerät im Netz `0x7FFFFFFF` schicken
     * und den Puffer eines jeden Zuhörers wachsen lassen, bis der Speicher
     * ausgeht — ohne ein einziges weiteres Byte zu senden.
     *
     * 1 MiB ist großzügig: Der größte vorgesehene Rahmen ist der
     * personalisierte Schnappschuss aus TDD 9.5, und der ist eine Partie, keine
     * Mediathek.
     */
    const val MAX_NUTZLAST = 1 shl 20

    /** Rahmen → Bytes. */
    fun kodiere(rahmen: Rahmen): ByteArray {
        val n = rahmen.nutzlast.size
        val bytes = ByteArray(KOPF_LAENGE + n)
        bytes[0] = KENNMARKE_1
        bytes[1] = KENNMARKE_2
        bytes[2] = PROTOKOLLVERSION
        bytes[3] = rahmen.typ.kennung
        bytes[4] = (n ushr 24 and 0xFF).toByte()
        bytes[5] = (n ushr 16 and 0xFF).toByte()
        bytes[6] = (n ushr 8 and 0xFF).toByte()
        bytes[7] = (n and 0xFF).toByte()
        rahmen.nutzlast.copyInto(bytes, KOPF_LAENGE)
        return bytes
    }
}

/**
 * Sammelt Bytes und gibt vollständige Rahmen heraus.
 *
 * Ein Leser gehört zu **einer** Verbindung und ist nicht nebenläufig benutzbar.
 * Nach einem [Rahmenfehler] ist er unbrauchbar — die Verbindung auch.
 */
class Rahmenleser {

    private var puffer = ByteArray(0)
    private var kaputt = false

    /** Was gerade unvollständig im Puffer liegt. Für Tests und Fehlersuche. */
    val angesammelt: Int get() = puffer.size

    /**
     * Füttert Bytes und liefert alles, was dadurch vollständig wurde.
     *
     * Die Aufteilung der Eingabe ist **gleichgültig**: byteweise, in einem
     * Stück oder in beliebigen Brocken ergibt dieselbe Folge von Rahmen. Genau
     * das ist die Zusage, ohne die TCP nicht benutzbar wäre.
     *
     * @return fertige Rahmen. Ein [UnbekannterRahmen] steht für einen Typ, den
     *   dieses Gerät nicht kennt — er wird korrekt übersprungen, damit ein
     *   älteres Gerät an einem neueren nicht zerbricht.
     * @throws Rahmenfehler bei falscher Kennmarke, fremder Version oder einer
     *   Längenangabe über der Obergrenze. Die Verbindung ist dann zu trennen.
     */
    fun fuettere(bytes: ByteArray): List<Gelesenes> {
        check(!kaputt) { "Dieser Leser hat einen Protokollfehler gesehen und ist unbrauchbar." }
        puffer += bytes

        val fertig = mutableListOf<Gelesenes>()
        while (true) {
            if (puffer.size < Rahmencodec.KOPF_LAENGE) break

            if (puffer[0] != Rahmencodec.KENNMARKE_1 || puffer[1] != Rahmencodec.KENNMARKE_2) {
                kaputt = true
                throw Rahmenfehler(
                    "Kennmarke fehlt — das Gegenüber spricht nicht dieses Protokoll. " +
                        "Die Verbindung ist zu trennen, nicht weiterzulesen.",
                )
            }
            if (puffer[2] != Rahmencodec.PROTOKOLLVERSION) {
                kaputt = true
                throw Rahmenfehler(
                    "Protokollversion ${puffer[2]}, erwartet ${Rahmencodec.PROTOKOLLVERSION}. " +
                        "Eine andere Version ist eine andere Sprache, kein unbekannter Rahmen.",
                )
            }

            val laenge = ((puffer[4].toInt() and 0xFF) shl 24) or
                ((puffer[5].toInt() and 0xFF) shl 16) or
                ((puffer[6].toInt() and 0xFF) shl 8) or
                (puffer[7].toInt() and 0xFF)

            if (laenge < 0 || laenge > Rahmencodec.MAX_NUTZLAST) {
                kaputt = true
                throw Rahmenfehler(
                    "Angekündigte Nutzlast von $laenge Bytes über der Obergrenze von " +
                        "${Rahmencodec.MAX_NUTZLAST}. Die Längenangabe kommt vom Gegenüber und " +
                        "wird deshalb nicht geglaubt.",
                )
            }

            val gesamt = Rahmencodec.KOPF_LAENGE + laenge
            if (puffer.size < gesamt) break // Noch nicht vollständig — weiter sammeln.

            val kennung = puffer[3]
            val nutzlast = puffer.copyOfRange(Rahmencodec.KOPF_LAENGE, gesamt)
            puffer = puffer.copyOfRange(gesamt, puffer.size)

            val typ = Rahmentyp.vonKennung(kennung)
            fertig += if (typ != null) Rahmen(typ, nutzlast) else UnbekannterRahmen(kennung, laenge)
        }
        return fertig
    }
}
