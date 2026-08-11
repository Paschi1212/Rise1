package de.myhornets.rise1.session

import de.myhornets.rise1.transport.Gegenstelle
import de.myhornets.rise1.transport.Rahmen
import de.myhornets.rise1.transport.Rahmencodec
import de.myhornets.rise1.transport.Rahmenleser
import de.myhornets.rise1.transport.Rahmentyp
import de.myhornets.rise1.transport.Transport
import de.myhornets.rise1.transport.TransportEreignis

/**
 * T-073 / T-074 — Herzschlag und Wiederverbindung.
 *
 * ## Was diese Klasse zusammenbringt
 *
 * Drei Teile, die einzeln schon geprüft sind, und ihr Zusammenspiel:
 * der [Transport] (T-065), der [Verbindungsautomat] aus TDD 9.2 (T-103) und
 * der [Wiederverbindungsplan] (T-074). Sie hält eine Verbindung am Leben und
 * baut sie wieder auf, wenn sie wegbricht.
 *
 * ## Wo die Grenze zur Sitzung verläuft
 *
 * Hier endet der **Transport**. Eine wiederhergestellte TCP-Verbindung ist noch
 * keine wiederhergestellte Sitzung: Dazwischen liegt der Handshake aus TDD 9.3
 * mit `rejoin_token` und Prüfung in konstanter Zeit — das ist `T-105` und steht
 * bewusst **nicht** hier.
 *
 * Deshalb bleibt der Automat nach einem Wiederaufbau der Leitung im Zustand
 * `offline` und diese Klasse meldet nur [bereitFuerHandshake]. Sie selbst ruft
 * `handshakeErfolgreich` **nie** auf. Täte sie es, wäre aus „die Leitung steht
 * wieder" ein „du bist wieder angemeldet" geworden — und TDD 9.1 sagt dazu:
 * *„Wiedereinstieg ist Authentifizierung, nicht Wiedererkennung."*
 *
 * ## Zeit kommt von außen
 *
 * Über [uhr]. Im Betrieb `System::currentTimeMillis`, im Test die Uhr des
 * Attrappennetzes. Damit läuft ein Test über eine Stunde Verbindungsverlust in
 * Millisekunden und liefert bei jedem Lauf dasselbe.
 *
 * ## Was ein Lebenszeichen ist
 *
 * **Jeder** eingetroffene Rahmen, nicht nur ein `HERZSCHLAG`. Wenn Ereignisse
 * fließen, ist die Gegenstelle offensichtlich da; ein zusätzlicher Herzschlag
 * wäre Verkehr ohne Aussage. Der Herzschlag ist das, was gesendet wird, **wenn
 * sonst nichts fließt**.
 */
class Sitzungsverbindung(
    private val transport: Transport,
    /** Der Host dieser Partie. */
    val host: Gegenstelle,
    /** Der eigene Sitzplatz — der Automat gehört zu ihm. */
    val eigenerParticipantUid: String,
    private val uhr: () -> Long,
    val schwellen: Verbindungsschwellen = Verbindungsschwellen(),
    val plan: Wiederverbindungsplan = Wiederverbindungsplan(),
) {

    val automat: Verbindungsautomat = Verbindungsautomat(eigenerParticipantUid, schwellen, uhr())

    private val leser = Rahmenleser()
    private var letzterHerzschlagGesendet: Long = uhr()

    /** Ob die **Leitung** steht. Nicht zu verwechseln mit dem Sitzungszustand. */
    var transportVerbunden: Boolean = false
        private set

    /**
     * Die Leitung steht, die Sitzung nicht — der Handshake aus TDD 9.3 ist fällig.
     *
     * Der Aufrufer (`T-105`) führt ihn und meldet das Ergebnis an [automat].
     */
    val bereitFuerHandshake: Boolean
        get() = transportVerbunden &&
            automat.zustand in setOf(Verbindungszustand.OFFLINE, Verbindungszustand.WIEDEREINSTIEG)

    private var seitWannGetrennt: Long? = null
    private var versuche = 0

    /** Wie oft seit dem letzten Erfolg neu verbunden wurde. Für Anzeige und Tests. */
    val versuchszaehler: Int get() = versuche

    /** Alle empfangenen Rahmen, die nicht Herzschläge sind — für die Schicht darüber. */
    val eingegangen = mutableListOf<Rahmen>()

    private val gesammelt = mutableListOf<Verbindungsmeldung>()

    // ── Aufbau ──────────────────────────────────────────────────────────────

    /** Hängt sich an den Transport und baut die erste Verbindung auf. */
    fun starte() {
        transport.beobachte { ereignis -> verarbeite(ereignis) }
        transport.verbinde(host)
    }

    /**
     * Ein Zeitschritt.
     *
     * Drei Dinge, in dieser Reihenfolge — und die Reihenfolge ist keine
     * Geschmacksfrage:
     *
     * 1. **Herzschlag senden**, wenn die Leitung steht und lange genug nichts
     *    gesendet wurde.
     * 2. **Den Automaten die Zeit rechnen lassen.** Erst danach, damit ein
     *    gerade gesendeter Herzschlag nicht im selben Schritt schon als
     *    verpasst gilt.
     * 3. **Wiederverbindung**, wenn die Leitung weg ist und die Wartezeit
     *    abgelaufen ist.
     */
    fun takt(): List<Verbindungsmeldung> {
        val jetzt = uhr()
        val meldungen = mutableListOf<Verbindungsmeldung>()

        if (transportVerbunden && jetzt - letzterHerzschlagGesendet >= schwellen.herzschlagIntervallMillis) {
            sendeHerzschlag(jetzt)
        }

        meldungen += automat.zeitLaeuft(jetzt)
        meldungen += pruefeWiederverbindung(jetzt)

        gesammelt += meldungen
        return meldungen
    }

    /** Alles, was seit dem Start gemeldet wurde. */
    fun bisherigeMeldungen(): List<Verbindungsmeldung> = gesammelt.toList()

    /** Einen Rahmen senden. `false`, wenn die Leitung gerade nicht steht. */
    fun sende(rahmen: Rahmen): Boolean = transport.sende(host, Rahmencodec.kodiere(rahmen))

    // ── Innen ───────────────────────────────────────────────────────────────

    private fun sendeHerzschlag(jetzt: Long) {
        // Kein Inhalt. Ein Herzschlag sagt „ich bin da" und sonst nichts —
        // alles Weitere wäre Nutzlast, die niemand angefordert hat.
        if (transport.sende(host, Rahmencodec.kodiere(Rahmen(Rahmentyp.HERZSCHLAG, ByteArray(0))))) {
            letzterHerzschlagGesendet = jetzt
        }
    }

    private fun pruefeWiederverbindung(jetzt: Long): List<Verbindungsmeldung> {
        if (transportVerbunden) return emptyList()
        if (automat.zustand.istEndzustand) return emptyList()
        val seit = seitWannGetrennt ?: return emptyList()

        if (jetzt < plan.faelligAb(seit, versuche + 1)) return emptyList()

        versuche++
        // Der nächste Versuch wird ab **jetzt** gerechnet, nicht ab dem
        // ursprünglichen Verlust. Sonst würden alle folgenden Versuche
        // rückwirkend fällig und liefen ohne Pause hintereinander.
        seitWannGetrennt = jetzt
        transport.verbinde(host)
        return emptyList()
    }

    private fun verarbeite(ereignis: TransportEreignis) {
        val jetzt = uhr()
        when (ereignis) {
            is TransportEreignis.Verbunden -> {
                if (ereignis.gegenstelle != host) return
                transportVerbunden = true
                seitWannGetrennt = null
                versuche = 0
                letzterHerzschlagGesendet = jetzt
                // Bewusst **kein** `handshakeErfolgreich`. Die Leitung steht,
                // die Sitzung nicht — siehe Klassenkommentar.
            }

            is TransportEreignis.Empfangen -> {
                if (ereignis.von != host) return
                // Jeder Rahmen ist ein Lebenszeichen.
                gesammelt += automat.herzschlag(jetzt)
                leser.fuettere(ereignis.rahmen)
                    .filterIsInstance<Rahmen>()
                    .filter { it.typ != Rahmentyp.HERZSCHLAG }
                    .forEach { eingegangen += it }
            }

            is TransportEreignis.Getrennt -> {
                if (ereignis.gegenstelle != host) return
                transportVerbunden = false
                if (seitWannGetrennt == null) seitWannGetrennt = jetzt
            }

            is TransportEreignis.Fehlgeschlagen -> {
                transportVerbunden = false
                if (seitWannGetrennt == null) seitWannGetrennt = jetzt
            }
        }
    }
}
