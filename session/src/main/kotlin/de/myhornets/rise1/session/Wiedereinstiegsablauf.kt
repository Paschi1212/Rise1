package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.MatchEvent
import de.myhornets.rise1.core.event.Schnappschuss

/**
 * T-105 + T-108 — der Ablauf auf der Client-Seite.
 *
 * ## Wozu es diese Klasse gibt
 *
 * Weil die Regel, um die es geht, sonst nirgends stünde:
 *
 * > Der Client darf erst nach erfolgreicher Validierung des Deltas wieder in den
 * > normalen Sitzungsfluss wechseln.
 *
 * [Sitzungsverbindung] endet beim Transport und ruft `handshakeErfolgreich`
 * absichtlich nie auf. [RejoinPruefer] steht auf der Host-Seite. [Deltapruefung]
 * ist zustandslos. Zwischen diesen drei Teilen liegt eine Reihenfolge — und eine
 * Reihenfolge, die nur im Kopf des Aufrufers existiert, ist keine.
 *
 * Hier ist sie ein Zustandsautomat mit vier Phasen, und der einzige Weg nach
 * [Ablaufphase.ANGEMELDET] führt durch ein angenommenes Delta.
 *
 * ## Die Kette
 *
 *     Leitung steht (Sitzungsverbindung.bereitFuerHandshake)
 *       → beginne()                → Zustand WIEDEREINSTIEG, Gesuch geht raus
 *       → antwort(Angenommen)      → Phase AUFHOLEN — noch **nicht** angemeldet
 *       → delta(...)  angenommen   → handshakeErfolgreich, participant_reconnected
 *                     abgelehnt    → aufholenGescheitert, zurück nach OFFLINE
 *
 * ## Was hier bewusst nicht passiert
 *
 * **Kein Falten.** Die angenommenen Events gibt [uebernommene] heraus; was daraus
 * ein Spielzustand wird, entscheidet `:projection`. Ein Sitzungsobjekt, das
 * nebenbei den Spielstand fortschreibt, wäre die zweite Quelle der Wahrheit, die
 * es nach TDD 5.1 nicht geben darf.
 *
 * **Kein Token im Feld.** Der Nachweis kommt über [token] erst dann, wenn ein
 * Gesuch gebaut wird. Ein Geheimnis, das für die Dauer einer Partie in einem
 * Objekt liegt, liegt auch in jedem Speicherabbild dieser Partie.
 */
class Wiedereinstiegsablauf(
    val matchUid: String,
    val eigenerParticipantUid: String,
    val eigenesGeraeteUid: String,
    /** Liefert den `rejoin_token`. Wird nur beim Bauen eines Gesuchs gerufen. */
    private val token: () -> String,
    private val automat: Verbindungsautomat,
    private val uhr: () -> Long,
    /** Der eigene Stand beim Start. `-1` heißt: nichts gesehen. */
    startStand: Long = -1,
) {

    private val pruefung = Deltapruefung(matchUid, eigenerParticipantUid)
    private val gesammelt = mutableListOf<Verbindungsmeldung>()
    private val uebernommen = mutableListOf<MatchEvent>()

    /** Die höchste `seq`, die dieser Client übernommen hat. */
    var stand: Long = startStand
        private set

    var phase: Ablaufphase = Ablaufphase.RUHT
        private set

    /** Die Sitzung, die den Handshake bestanden hat (TDD 9.5). `null`, solange keine. */
    var sitzungsUid: String? = null
        private set

    /** Warum der letzte Versuch scheiterte — für die Anzeige. */
    var letzteAblehnung: Ablehnungsgrund? = null
        private set

    /** Warum das letzte Delta nicht angenommen wurde. */
    var letzterDeltafehler: Deltafehler? = null
        private set

    /** Der zuletzt angenommene Schnappschuss — Ausgangspunkt der Projektion. */
    var letzterSchnappschuss: Schnappschuss? = null
        private set

    private var zugesagtesEnde: Long? = null

    /**
     * Beginnt den Wiedereinstieg und liefert das Gesuch für TDD 9.3.
     *
     * `null`, wenn gerade keiner fällig ist — ein zweites Gesuch, während das
     * erste unterwegs ist, wäre für den Host ein zweiter Versuch und ginge auf
     * die Ratenbegrenzung.
     */
    fun beginne(): Wiedereinstiegsgesuch? {
        if (phase != Ablaufphase.RUHT) return null
        if (automat.zustand.istEndzustand) return null

        val jetzt = uhr()
        gesammelt += automat.handshakeBegonnen(jetzt)
        if (automat.zustand != Verbindungszustand.WIEDEREINSTIEG) {
            // Der Automat hat den Beginn nicht angenommen. Dann gibt es auch
            // kein Gesuch — die Phase folgt dem Automaten, nicht umgekehrt.
            return null
        }
        phase = Ablaufphase.HANDSHAKE
        letzteAblehnung = null
        letzterDeltafehler = null
        return Wiedereinstiegsgesuch(
            matchUid = matchUid,
            participantUid = eigenerParticipantUid,
            rejoinToken = token(),
            deviceUid = eigenesGeraeteUid,
            lastSeqSeen = stand,
        )
    }

    /**
     * Die Antwort des Hosts ist da.
     *
     * Bei [Wiedereinstiegsantwort.Angenommen] wird **nicht** angemeldet. Die
     * Sitzung existiert dann auf der Host-Seite, aber dieser Client hat den
     * verpassten Verlauf noch nicht — und ein Spieler, der einen Zug nicht
     * gesehen hat, ist nicht im normalen Sitzungsfluss.
     */
    fun antwort(antwort: Wiedereinstiegsantwort): List<Verbindungsmeldung> {
        if (phase != Ablaufphase.HANDSHAKE) return emptyList()
        val jetzt = uhr()

        return when (antwort) {
            is Wiedereinstiegsantwort.Abgelehnt -> {
                letzteAblehnung = antwort.grund
                phase = Ablaufphase.RUHT
                automat.handshakeAbgelehnt(jetzt).also { gesammelt += it }
            }

            is Wiedereinstiegsantwort.Angenommen -> {
                sitzungsUid = antwort.sitzungsUid
                zugesagtesEnde = antwort.bisSeq
                phase = Ablaufphase.AUFHOLEN
                emptyList()
            }
        }
    }

    /**
     * Das Delta ist da (TDD 9.5).
     *
     * Nur bei [Deltaergebnis.Angenommen] wird der Stand fortgeschrieben und der
     * Automat auf `verbunden` gesetzt. Bei Ablehnung passiert das Gegenteil von
     * halb: gar nichts wird übernommen, und der Automat geht über
     * [Verbindungsautomat.aufholenGescheitert] zurück nach `offline`.
     */
    fun delta(delta: Aufholung.Delta): Deltaergebnis {
        if (phase != Ablaufphase.AUFHOLEN) {
            return Deltaergebnis.Abgelehnt(Deltafehler.PASST_NICHT_ZUR_ANTWORT)
        }
        val jetzt = uhr()
        val ergebnis = pruefung.pruefe(stand, delta, zugesagtesEnde)

        when (ergebnis) {
            is Deltaergebnis.Angenommen -> {
                uebernommen += ergebnis.events
                stand = ergebnis.neuerStand
                phase = Ablaufphase.ANGEMELDET
                letzterDeltafehler = null
                gesammelt += automat.handshakeErfolgreich(jetzt)
            }

            is Deltaergebnis.Abgelehnt -> {
                letzterDeltafehler = ergebnis.fehler
                phase = Ablaufphase.RUHT
                zugesagtesEnde = null
                sitzungsUid = null
                gesammelt += automat.aufholenGescheitert(jetzt)
            }
        }
        return ergebnis
    }

    /**
     * Der Schnappschuss ist da (TDD 9.5, `T-109`).
     *
     * Dieselbe Regel wie beim Delta: Erst nach bestandener Prüfung wechselt der
     * Client in den normalen Sitzungsfluss. Geprüft wird, was der Client selbst
     * wissen kann — Partie, Empfänger und der zugesagte Stand. Dass keine
     * fremden `PRIVATE`-Blobs enthalten sind, erzwingt bereits der Werttyp
     * [Schnappschuss]; hier bliebe nichts zu prüfen übrig.
     *
     * Der Stand springt auf `bisSeq`: Ein Schnappschuss **ersetzt** den
     * bisherigen Stand, er ergänzt ihn nicht. Genau dafür gibt es ihn.
     */
    fun schnappschuss(schnappschuss: Schnappschuss): Deltaergebnis {
        if (phase != Ablaufphase.AUFHOLEN) {
            return Deltaergebnis.Abgelehnt(Deltafehler.PASST_NICHT_ZUR_ANTWORT)
        }
        val jetzt = uhr()

        val fehler = when {
            schnappschuss.matchUid != matchUid -> Deltafehler.FREMDE_PARTIE
            schnappschuss.empfaenger != eigenerParticipantUid -> Deltafehler.FREMDER_EMPFAENGER
            zugesagtesEnde != null && schnappschuss.bisSeq != zugesagtesEnde ->
                Deltafehler.PASST_NICHT_ZUR_ANTWORT

            else -> null
        }
        if (fehler != null) {
            letzterDeltafehler = fehler
            phase = Ablaufphase.RUHT
            zugesagtesEnde = null
            sitzungsUid = null
            gesammelt += automat.aufholenGescheitert(jetzt)
            return Deltaergebnis.Abgelehnt(fehler, schnappschuss.bisSeq)
        }

        letzterSchnappschuss = schnappschuss
        uebernommen += schnappschuss.transkript + schnappschuss.eigenePrivate
        stand = schnappschuss.bisSeq
        phase = Ablaufphase.ANGEMELDET
        letzterDeltafehler = null
        gesammelt += automat.handshakeErfolgreich(jetzt)
        return Deltaergebnis.Angenommen(
            schnappschuss.transkript + schnappschuss.eigenePrivate,
            schnappschuss.bisSeq,
        )
    }

    /**
     * Der Host verlangt einen Schnappschuss, liefert ihn aber nicht (TDD 9.5).
     *
     * Der Ablauf bricht sauber ab und der Client bleibt `offline`, statt so zu
     * tun, als wäre er auf dem Stand. Das ist der Unterschied zwischen einer
     * offenen Stelle und einem stillen Fehler — und der Fall bleibt bestehen,
     * seit es [schnappschuss] gibt: Ein angekündigter Schnappschuss, der nie
     * ankommt, darf niemanden anmelden.
     */
    fun schnappschussNoetig(): List<Verbindungsmeldung> {
        if (phase != Ablaufphase.AUFHOLEN) return emptyList()
        phase = Ablaufphase.RUHT
        zugesagtesEnde = null
        sitzungsUid = null
        return automat.aufholenGescheitert(uhr()).also { gesammelt += it }
    }

    /** Die angenommenen Events, in der Reihenfolge ihrer Annahme. Für `:projection`. */
    fun uebernommene(): List<MatchEvent> = uebernommen.toList()

    /** Alles, was der Automat in diesem Ablauf gemeldet hat. */
    fun meldungen(): List<Verbindungsmeldung> = gesammelt.toList()

    /** Ob der Client im normalen Sitzungsfluss ist. */
    val angemeldet: Boolean get() = phase == Ablaufphase.ANGEMELDET
}

/** Die Phasen des Wiedereinstiegs auf der Client-Seite. */
enum class Ablaufphase {
    /** Kein Versuch unterwegs. */
    RUHT,

    /** Gesuch ist raus, Antwort steht aus (TDD 9.3). */
    HANDSHAKE,

    /**
     * Handshake bestanden, Delta steht aus (TDD 9.5).
     *
     * Die wichtigste Phase dieser Aufzählung: Hier ist der Sitzplatz auf der
     * Host-Seite schon wieder besetzt, dieser Client aber noch nicht auf dem
     * Stand. Wer diese Phase weglässt, meldet einen Spieler an, der die letzten
     * Züge nicht kennt.
     */
    AUFHOLEN,

    /** Im normalen Sitzungsfluss. */
    ANGEMELDET,
}
