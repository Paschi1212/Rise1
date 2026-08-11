package de.myhornets.rise1.transport

/**
 * T-068 / T-069 — Dienstregistrierung und Dienstsuche hinter einer Abstraktion.
 *
 * ## Warum nicht `NsdManager` direkt
 *
 * ADR-001 nennt `NsdManager` namentlich, und die Umsetzung steht in
 * [NsdDienstverzeichnis]. Hier steht sie **nicht**, aus demselben Grund, aus dem
 * [Transport] nichts von TLS weiß: Was die Schicht darüber benutzt, muss sich
 * ohne Netz und ohne Gerät prüfen lassen. `NsdManager` verlangt einen
 * `Context`, arbeitet mit Rückrufen auf fremden Threads und lässt sich nicht
 * dazu bringen, einen bestimmten Fehler zu melden — gegen so etwas entwickelt
 * man nicht, man hofft.
 *
 * ## Die Entscheidung, die hier drinsteckt: der Fingerabdruck steht **nicht** im Dienst
 *
 * Es wäre bequem, den Host-Fingerabdruck als TXT-Merkmal mitzuveröffentlichen —
 * dann müsste niemand etwas abtippen. Es wäre auch wertlos:
 *
 * > **Wer geprüft wird, darf nicht die Prüfgrundlage liefern.**
 * > — [[ADR-002A Key Verification]], Abschnitt 3.2
 *
 * Wer den Dienst vortäuschen kann, kann auch das Merkmal setzen. Der
 * Fingerabdruck muss deshalb **außerhalb dieses Kanals** zum Client kommen: vom
 * Bildschirm des Hosts abgelesen oder als QR abfotografiert (ADR-001). Deshalb
 * weist [Dienst] ein solches Merkmal ausdrücklich zurück, statt sich darauf zu
 * verlassen, dass niemand auf die Idee kommt.
 */

/** Der Diensttyp aus ADR-001. */
const val DIENSTTYP = "_rise1._tcp"

/**
 * Ein angebotener Dienst.
 *
 * Alles daran ist **unbeglaubigt**: Jedes Gerät im Netz kann so etwas
 * ausrufen. Was hier steht, taugt zur Auswahl in einer Liste und zu nichts
 * sonst.
 */
data class Dienst(
    val gegenstelle: Gegenstelle,
    val port: Int,
    /**
     * TXT-Merkmale — was ohne Verbindung sichtbar ist.
     *
     * Sparsam halten: Was hier steht, sieht jedes Gerät im Netz, auch eines,
     * das nie beitritt.
     */
    val merkmale: Map<String, String> = emptyMap(),

    /**
     * Wo dieser Dienst zu erreichen ist — vom **Auflöser** gefüllt, nicht aus
     * der Ankündigung gelesen.
     *
     * Ohne sie ist ein gefundener Dienst nutzlos: Ein Port ohne Adresse führt
     * nirgendwohin. Sie steht getrennt von [merkmale], weil sie kein TXT-Eintrag
     * ist, den irgendjemand setzen könnte, sondern das Ergebnis der Auflösung.
     *
     * `null` bei einer Attrappe oder solange nicht aufgelöst wurde. Auch eine
     * aufgelöste Adresse ist **unbeglaubigt** — geprüft wird der Host über
     * seinen Fingerabdruck (ADR-001, ADR-002A 3.2), nicht über seine IP.
     */
    val adresse: String? = null,
) {
    init {
        require(port in 1..65_535) { "Kein gültiger Port: $port." }
        val verboten = merkmale.keys.filter { it.lowercase() in VERBOTENE_MERKMALE }
        require(verboten.isEmpty()) {
            "Die Merkmale $verboten gehören nicht in eine Dienstankündigung. Wer den Dienst " +
                "vortäuschen kann, kann auch das Merkmal setzen — der Fingerabdruck muss " +
                "außerhalb dieses Kanals zum Client kommen (ADR-001, ADR-002A 3.2). " +
                "Wer geprüft wird, darf nicht die Prüfgrundlage liefern."
        }
    }

    companion object {
        /** Übliche Merkmale, die tragbar sind. */
        const val MERKMAL_PARTIENAME = "partie"
        const val MERKMAL_PROTOKOLL = "protokoll"
        const val MERKMAL_SITZPLAETZE_FREI = "frei"

        /**
         * Was hier nicht hineindarf.
         *
         * Nicht aus Prinzipienreiterei: Ein Fingerabdruck aus der Ankündigung
         * ist genau so viel wert wie die Ankündigung selbst, also nichts.
         */
        val VERBOTENE_MERKMALE = setOf("fp", "fingerabdruck", "fingerprint", "cert", "zertifikat")
    }
}

/** Ergebnis einer Registrierung (T-068). */
sealed interface Registrierung {
    data class Erfolgreich(val dienst: Dienst) : Registrierung

    /**
     * Gescheitert.
     *
     * Der wichtigste Fall ist die verweigerte Berechtigung: ADR-001 sagt, ab
     * Android 17 braucht der Host `ACCESS_LOCAL_NETWORK`, und *„wird sie
     * verweigert, kann dieses Gerät nicht hosten — ein anderes Gerät kann es"*.
     * Das ist eine Auskunft, die die Oberfläche geben können muss, und kein
     * allgemeiner Fehler.
     */
    data class Gescheitert(val grund: Registrierungsfehler) : Registrierung
}

sealed interface Registrierungsfehler {
    /** Berechtigung fehlt — dieses Gerät kann nicht hosten. */
    data object BerechtigungFehlt : Registrierungsfehler

    /** Der Name ist im Netz schon vergeben. */
    data class NameVergeben(val vorgeschlagen: String) : Registrierungsfehler

    /** Alles andere, mit der Meldung der Plattform. */
    data class Sonstiges(val meldung: String) : Registrierungsfehler
}

/** Was die Suche meldet (T-069). */
sealed interface Suchereignis {
    /**
     * Ein Dienst ist da.
     *
     * Kann für dieselbe Gegenstelle mehrfach kommen — Bonjour kündigt neu an,
     * wenn sich etwas ändert. Der Aufrufer führt eine Menge, keine Liste.
     */
    data class Gefunden(val dienst: Dienst) : Suchereignis

    /** Ein Dienst ist weg. Nicht zu verwechseln mit einer getrennten Verbindung. */
    data class Verschwunden(val gegenstelle: Gegenstelle) : Suchereignis

    /** Die Suche selbst ist gescheitert. */
    data class Gescheitert(val meldung: String) : Suchereignis
}

/**
 * Dienstregistrierung (T-068) und Dienstsuche (T-069).
 *
 * Bewusst ein Typ für beides: Auf einem Gerät ist immer nur eines von beiden
 * aktiv — wer hostet, sucht nicht —, und zwei Schnittstellen mit demselben
 * Lebenszyklus wären zwei Gelegenheiten, ihn falsch zu bedienen.
 *
 * **Alle Rückmeldungen kommen als Rückruf**, nicht als Rückgabewert: Beides
 * dauert, und beides kann mehrfach etwas melden.
 */
interface Dienstverzeichnis {

    /**
     * Kündigt einen Dienst im lokalen Netz an (T-068).
     *
     * @param rueckmeldung wird genau einmal aufgerufen.
     */
    fun registriere(dienst: Dienst, rueckmeldung: (Registrierung) -> Unit)

    /** Nimmt die Ankündigung zurück. Mehrfach aufrufbar. */
    fun beendeRegistrierung()

    /**
     * Sucht Dienste im lokalen Netz (T-069).
     *
     * @param hoerer wird für jedes Ereignis aufgerufen, bis [beendeSuche].
     */
    fun sucheAb(hoerer: (Suchereignis) -> Unit)

    /** Beendet die Suche. Mehrfach aufrufbar. */
    fun beendeSuche()
}
