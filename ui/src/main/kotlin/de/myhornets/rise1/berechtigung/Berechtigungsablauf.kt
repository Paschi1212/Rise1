package de.myhornets.rise1.berechtigung

/**
 * T-076 — der Ablauf zwischen [Berechtigungslage] und der Plattform.
 *
 * ## Warum es diese Naht gibt
 *
 * Dieselbe Begründung wie bei `Dienstverzeichnis`: *„`NsdManager` verlangt einen
 * `Context`, arbeitet mit Rückrufen auf fremden Threads und lässt sich nicht
 * dazu bringen, einen bestimmten Fehler zu melden — gegen so etwas entwickelt
 * man nicht, man hofft."* Für `Activity.requestPermissions` gilt Wort für Wort
 * dasselbe, und obendrein braucht es einen Nutzer, der tippt.
 *
 * Hinter [Berechtigungssystem] sind es drei Fragen und zwei Handlungen. Davor
 * steht der ganze Ablauf — und der ist ohne Gerät prüfbar, einschließlich der
 * Fälle, die man auf einem Gerät kaum herstellt: abgebrochener Dialog,
 * fremdes Ergebnis, endgültige Ablehnung.
 *
 * ## Wer das benutzt — und wer nicht
 *
 * Eine `Activity`. Sonst niemand. `:transport` und `:session` sehen diese
 * Klasse nicht und dürfen sie nicht sehen: Eine Berechtigungsanfrage braucht
 * eine Oberfläche, und eine Sitzung darf nie eine haben (ADR-008: der
 * Sitzungsthread überlebt jede Activity).
 */

/**
 * Die drei Fragen und zwei Handlungen, die nur die Plattform beantworten kann.
 *
 * Bewusst über **Zeichenketten** und nicht über [Berechtigungsart]: Diese
 * Schnittstelle soll nichts über Rise wissen. Die Umsetzung ist damit ein
 * Durchreichen ohne jede Entscheidung — und Entscheidungen sind das, was hier
 * geprüft werden soll.
 */
interface Berechtigungssystem {

    /** `checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED` */
    fun istErteilt(systemname: String): Boolean

    /**
     * `shouldShowRequestPermissionRationale(name)`
     *
     * `true` heißt: schon einmal abgelehnt, das System fragt aber noch einmal.
     * `false` heißt **zweierlei** — nie gefragt, oder endgültig abgelehnt.
     * Welches von beidem, weiß nur [Berechtigungslage].
     */
    fun darfBegruendungZeigen(systemname: String): Boolean

    /** `requestPermissions(namen, kennung)` — öffnet den Systemdialog. */
    fun frage(systemnamen: List<String>, kennung: Int)

    /** Öffnet die Systemeinstellungen dieser App. Der letzte Weg nach [Schritt.NurNochEinstellungen]. */
    fun oeffneEinstellungen()
}

/**
 * Führt den Berechtigungsablauf.
 *
 * Zustandslos gegenüber der Plattform: Jeder Schritt liest den Systemstand neu.
 * Ein Nutzer kann eine Berechtigung in den Einstellungen entziehen, während die
 * App im Hintergrund steht — ein Ablauf, der sich seinen Stand merkt, meldete
 * danach fröhlich „alles da".
 */
class Berechtigungsablauf(
    private val system: Berechtigungssystem,
    /** Der Zustand. Wird von diesem Ablauf fortgeschrieben. */
    val lage: Berechtigungslage,
    private val anfragekennung: Int = ANFRAGEKENNUNG,
) {

    /**
     * Liest den Systemstand für alles, was gebraucht wird.
     *
     * Gehört in `onResume` — nicht in `onCreate`. Wer aus den Einstellungen
     * zurückkommt, hat den Stand dort geändert, ohne dass ein Rückruf käme.
     */
    fun leseSystemstand() {
        lage.benoetigt.forEach { art ->
            val erteilt = system.istErteilt(art.systemname)
            lage.uebernimm(
                art = art,
                erteilt = erteilt,
                // Eine erteilte Berechtigung braucht keine Begründung — und die
                // Plattform danach zu fragen wäre ein Aufruf ohne Aussage.
                begruendungZeigen = if (erteilt) false else system.darfBegruendungZeigen(art.systemname),
            )
        }
    }

    fun naechsterSchritt(): Schritt = lage.naechsterSchritt()

    /**
     * Öffnet den Systemdialog.
     *
     * @return `false`, wenn nichts zu fragen war — dann geht auch kein Dialog
     *   auf. Eine Anfrage über eine leere Liste ist auf der Plattform ein
     *   Rückruf mit leeren Feldern und sieht damit aus wie ein abgebrochener
     *   Dialog. Solche Zweideutigkeit wird hier nicht erzeugt.
     */
    fun frage(arten: List<Berechtigungsart>): Boolean {
        val offen = arten.filter { lage.stand(it) != Berechtigungsstand.NICHT_NOETIG }
            .filter { lage.stand(it) != Berechtigungsstand.ERTEILT }
        if (offen.isEmpty()) return false

        // Hier wird **nicht** „gefragt" vermerkt. Ein geöffneter Dialog ist
        // keine Antwort: Wird er abgebrochen, hat das System nichts
        // aufgezeichnet, und ein Vermerk hier machte aus dem Abbruch eine
        // endgültige Ablehnung. Vermerkt wird in [nimmErgebnis], wenn wirklich
        // eine Antwort da ist.
        system.frage(offen.map { it.systemname }, anfragekennung)
        return true
    }

    /** Bequemlichkeit: fragen, was gerade dran ist. */
    fun frageOffene(): Boolean = when (val schritt = naechsterSchritt()) {
        is Schritt.Fragen -> frage(schritt.arten)
        is Schritt.Begruenden -> frage(schritt.arten)
        else -> false
    }

    fun oeffneEinstellungen() = system.oeffneEinstellungen()

    /**
     * Nimmt das Ergebnis aus `onRequestPermissionsResult` entgegen.
     *
     * ## Die drei Fälle, die kein Ergebnis sind
     *
     * **Fremde Kennung.** Eine andere Stelle der App hat gefragt. Nicht unser
     * Ergebnis, also unberührt lassen — nicht als Ablehnung werten.
     *
     * **Leere Felder.** Android dokumentiert das ausdrücklich: Wird die
     * Interaktion unterbrochen, kommen leere Felder zurück. Das ist ein
     * **Abbruch**, keine Ablehnung. Wer ihn als Ablehnung führt, schiebt den
     * Nutzer nach einem versehentlichen Antippen daneben in Richtung
     * Systemeinstellungen.
     *
     * **Ungleich lange Felder.** Kommt auf einer heilen Plattform nicht vor.
     * Käme es vor, wäre jede Zuordnung geraten — und geratene Berechtigungen
     * sind schlimmer als gar keine.
     *
     * @return `true`, wenn mindestens ein Ergebnis übernommen wurde.
     */
    fun nimmErgebnis(kennung: Int, namen: Array<out String>, ergebnisse: IntArray): Boolean {
        if (kennung != anfragekennung) return false
        if (namen.isEmpty() || ergebnisse.isEmpty()) return false
        if (namen.size != ergebnisse.size) return false

        var uebernommen = false
        for (i in namen.indices) {
            // Ein Name, den Rise nicht kennt, wird übersprungen. Er gehört
            // jemand anderem; ihn zu deuten hieße, sich etwas auszudenken.
            val art = Berechtigungsart.vonSystemname(namen[i]) ?: continue
            val erteilt = ergebnisse[i] == ERTEILT_VOM_SYSTEM
            // Jetzt liegt eine echte Antwort vor — erst jetzt gilt „gefragt".
            lage.merkeAnfrage(listOf(art))
            lage.uebernimm(
                art = art,
                erteilt = erteilt,
                // Nach der Antwort steht die Begründungsfrage anders als davor:
                // Genau hier entscheidet sich ABGELEHNT gegen ENDGUELTIG_ABGELEHNT.
                begruendungZeigen = if (erteilt) false else system.darfBegruendungZeigen(art.systemname),
            )
            uebernommen = true
        }
        return uebernommen
    }

    companion object {

        /** Die Kennung dieser App für Berechtigungsanfragen. */
        const val ANFRAGEKENNUNG = 7601

        /**
         * `PackageManager.PERMISSION_GRANTED`.
         *
         * Als Zahl und nicht als Konstante, damit diese Datei ohne Android
         * übersetzt und geprüft werden kann. Der Wert ist seit API 1
         * unverändert und Teil der Plattform-ABI; er steht hier mit Namen,
         * damit niemand über eine nackte `0` stolpert.
         */
        const val ERTEILT_VOM_SYSTEM = 0
    }
}
