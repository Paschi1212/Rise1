package de.myhornets.rise1.berechtigung

/**
 * T-076 — der Berechtigungsablauf, ohne eine Zeile Android.
 *
 * ## Warum das hier ohne Android steht
 *
 * Weil an Berechtigungen genau eine Sache schwierig ist, und die hat mit
 * Android nichts zu tun: **zu wissen, in welchem der fünf Zustände man ist.**
 * Der Rest — `checkSelfPermission`, `requestPermissions`, der Systemdialog — ist
 * drei Aufrufe. Die Zustände sind es, an denen Apps scheitern: Sie fragen ein
 * zweites Mal, wo das System nicht mehr fragt, und stehen dann still.
 *
 * Dieselbe Aufteilung wie überall im Projekt: `Dienstverzeichnis` /
 * `NsdDienstverzeichnis`, `Socketquelle` / `TlsSocketquelle`,
 * `Sitzungslaufzeit` / `Sitzungsdienst`. Was ein Verhalten hat, ist ohne Gerät
 * prüfbar; was Android ist, ist eine dünne Naht.
 *
 * ## Warum das in `:ui` liegt
 *
 * Weil es sonst nirgends hindarf. `:transport` hat keine Modulkante und darf
 * von Berechtigungen nichts wissen — `Transport` weiß nicht einmal, dass es TLS
 * gibt. `:session` sieht nur `{core, crypto, transport}` und hat mit dem
 * Android-Lebenszyklus nichts zu schaffen: Eine Berechtigungsanfrage braucht
 * eine `Activity`, und eine Sitzung darf nie eine haben.
 *
 * **Aus Transport oder Session heraus wird nie gefragt.** Wer eine Verbindung
 * aufbaut, hat die Berechtigung längst — oder er hätte gar nicht so weit kommen
 * dürfen. Ein `Transport`, der mitten im Verbinden einen Dialog öffnet, wäre
 * eine Oberfläche im Netzwerkstapel.
 */

/**
 * Die Berechtigungen, die Rise überhaupt kennt.
 *
 * Zwei, und beide sind begründet. Was hier nicht steht, wird nicht erfragt.
 */
enum class Berechtigungsart(
    /** Der Name auf der Plattform. */
    val systemname: String,
    /** Ab welcher Android-Fassung es sie gibt. Darunter ist sie gegenstandslos. */
    val abSdk: Int,
) {

    /**
     * `POST_NOTIFICATIONS` — seit Android 13 (API 33).
     *
     * Sie gehört zu `T-075`: Der Vordergrunddienst läuft auch ohne sie, aber
     * **seine Benachrichtigung ist dann unsichtbar**. Genau die ist die Zusage
     * an den Nutzer — wer sie sieht, weiß, dass die Leitung steht. Ein
     * Vordergrunddienst, den niemand sieht, ist die Sorte Hintergrundarbeit,
     * gegen die Android diese Berechtigung eingeführt hat.
     */
    BENACHRICHTIGUNGEN("android.permission.POST_NOTIFICATIONS", 33),

    /**
     * `ACCESS_LOCAL_NETWORK` — seit Android 17 (API 37).
     *
     * ADR-001: *„Das Host-Gerät braucht ab Android 17 die Berechtigung
     * `ACCESS_LOCAL_NETWORK`. Wird sie verweigert, kann dieses Gerät nicht
     * hosten — ein anderes Gerät kann es. Clients kommen ohne aus, solange der
     * Beitritt über den System-Dialog läuft."*
     *
     * **Der Name steht hier als Zeichenkette und nicht als
     * `Manifest.permission.ACCESS_LOCAL_NETWORK`.** Die Konstante gibt es erst
     * in SDK 37; die Zeichenkette ist dieselbe und bindet den Build nicht an
     * eine Werkzeugkette, über die nicht entschieden ist (D-001, D-001A).
     */
    LOKALES_NETZ("android.permission.ACCESS_LOCAL_NETWORK", 37),
    ;

    companion object {
        fun vonSystemname(name: String): Berechtigungsart? = entries.firstOrNull { it.systemname == name }
    }
}

/**
 * Wer man am Tisch ist.
 *
 * Der Unterschied ist keine Kosmetik, sondern die **Asymmetrie aus ADR-001**:
 * *„Nur das **Host-Gerät** braucht die breite Berechtigung
 * `ACCESS_LOCAL_NETWORK`, weil es eingehende Verbindungen annimmt. Genau dieses
 * Gerät gehört der Person, die bewusst eine Partie eröffnet — der Moment, in
 * dem eine Berechtigungsabfrage erklärbar ist. Alle übrigen Spieler kommen mit
 * dem System-Dialog aus."*
 */
enum class Rolle { HOST, GAST }

/**
 * Der Zustand einer einzelnen Berechtigung.
 *
 * Fünf, nicht drei. Die beiden zusätzlichen sind die, an denen es in der Praxis
 * hängt:
 *
 * [UNGEFRAGT] und [ENDGUELTIG_ABGELEHNT] sehen für die Plattform **gleich aus**
 * — `checkSelfPermission` sagt „nein" und `shouldShowRequestPermissionRationale`
 * sagt „keine Begründung nötig". Wer sie nicht auseinanderhält, fragt entweder
 * nie oder fragt ewig ins Leere. Auseinandergehalten werden sie nur durch das
 * eigene Wissen, ob schon einmal gefragt wurde — siehe [Berechtigungslage].
 */
enum class Berechtigungsstand {

    /** Auf diesem Gerät gibt es sie nicht, oder diese Rolle braucht sie nicht. */
    NICHT_NOETIG,

    /** Gebraucht, noch nie gefragt. Der Normalfall beim ersten Start. */
    UNGEFRAGT,

    /** Erteilt. */
    ERTEILT,

    /** Abgelehnt, aber das System fragt noch einmal — mit vorheriger Begründung. */
    ABGELEHNT,

    /**
     * Abgelehnt, und das System fragt **nicht mehr**.
     *
     * Ein Dialog käme hier nicht mehr auf den Bildschirm; er würde sofort mit
     * „abgelehnt" zurückkommen. Der einzige verbleibende Weg führt über die
     * Systemeinstellungen der App.
     */
    ENDGUELTIG_ABGELEHNT,
}

/**
 * Was als Nächstes zu tun ist.
 *
 * Versiegelt, damit die Oberfläche gezwungen ist, jeden Fall zu behandeln —
 * insbesondere [NurNochEinstellungen]. Eine Oberfläche, die diesen Fall
 * vergisst, zeigt einen Knopf, der nichts tut.
 */
sealed interface Schritt {

    /** Alles da, oder nichts nötig. */
    data object NichtsZuTun : Schritt

    /**
     * Erst erklären, dann fragen.
     *
     * Das System sagt, eine Begründung sei angebracht — der Nutzer hat schon
     * einmal abgelehnt. Ein zweiter Dialog ohne ein Wort dazwischen ist
     * Drängeln.
     */
    data class Begruenden(val arten: List<Berechtigungsart>) : Schritt

    /** Fragen. */
    data class Fragen(val arten: List<Berechtigungsart>) : Schritt

    /**
     * Nur noch die Systemeinstellungen.
     *
     * ADR-001 für den Host-Fall: *„Wird sie verweigert, kann dieses Gerät nicht
     * hosten — ein anderes Gerät kann es."* Das ist die Auskunft, die die
     * Oberfläche hier geben muss, statt einen Dialog anzubieten, der nicht mehr
     * erscheint.
     */
    data class NurNochEinstellungen(val arten: List<Berechtigungsart>) : Schritt
}

/**
 * Was diese Rolle auf diesem Gerät überhaupt braucht.
 *
 * ## Warum `zielSdk` hier steht und nicht nur `geraeteSdk`
 *
 * Weil die Sperre am **Ziel-SDK** hängt, nicht an der Android-Fassung des
 * Geräts. Aus der Android-Dokumentation zur lokalen Netzberechtigung: *„In
 * Android 16, apps could opt in to local network permissions. Beginning with
 * Android 17, enforcement is mandatory for apps that target Android 17 (API
 * level 37) or higher."*
 *
 * Rise steht auf `targetSdk = 36` (D-001; der Schritt auf 37 ist in D-001A
 * zurückgenommen worden). Damit gilt die Sperre **auf keinem Gerät**, auch
 * nicht auf einem mit Android 17 — und [Berechtigungsart.LOKALES_NETZ] ist
 * heute [Berechtigungsstand.NICHT_NOETIG]. Das ist kein Versäumnis, das ist die
 * Regel der Plattform.
 *
 * Diese Funktion ist trotzdem vollständig geschrieben und geprüft. Am Tag, an
 * dem `targetSdk` auf 37 geht, ändert sich hier **eine Zahl im Aufrufer** — und
 * der Ablauf steht.
 */
object Berechtigungsbedarf {

    fun fuer(rolle: Rolle, geraeteSdk: Int, zielSdk: Int): List<Berechtigungsart> =
        Berechtigungsart.entries.filter { istNoetig(it, rolle, geraeteSdk, zielSdk) }

    fun istNoetig(art: Berechtigungsart, rolle: Rolle, geraeteSdk: Int, zielSdk: Int): Boolean {
        // Eine Berechtigung, die es auf diesem Gerät nicht gibt, ist keine.
        if (geraeteSdk < art.abSdk) return false
        return when (art) {
            // Der Vordergrunddienst läuft für beide Rollen (T-075).
            Berechtigungsart.BENACHRICHTIGUNGEN -> true

            // ADR-001: nur der Host. Ein Gast kommt über den System-Dialog
            // hinein und braucht die breite Berechtigung nie.
            Berechtigungsart.LOKALES_NETZ -> rolle == Rolle.HOST && zielSdk >= SPERRE_AB_ZIELSDK
        }
    }

    /**
     * Ab diesem Ziel-SDK sperrt Android das lokale Netz.
     *
     * Android 17 (API 37). Solange `targetSdk` darunter liegt, gibt es nichts
     * zu erfragen.
     */
    const val SPERRE_AB_ZIELSDK = 37
}

/**
 * Der Zustand aller Berechtigungen dieser Rolle — und der einzige Ort, an dem
 * er steht.
 *
 * ## Die Unterscheidung, um die es geht
 *
 * Android beantwortet zwei Fragen: *ist sie erteilt?* und *soll ich eine
 * Begründung zeigen?* Aus beiden allein lässt sich [Berechtigungsstand.UNGEFRAGT]
 * nicht von [Berechtigungsstand.ENDGUELTIG_ABGELEHNT] unterscheiden — vor der
 * ersten Frage und nach der endgültigen Ablehnung antwortet die Plattform
 * identisch. Die dritte Angabe kommt von hier: **haben wir schon gefragt?**
 *
 * ## Warum [gefragte] herausgereicht wird
 *
 * Weil dieses Wissen einen Prozessneustart überleben muss. Wer es verliert,
 * hält eine endgültige Ablehnung wieder für einen ersten Start und schickt den
 * Nutzer gegen eine Tür, die nicht mehr aufgeht. Die Ablage ist Sache des
 * Aufrufers — diese Klasse merkt sich nichts über ihre Lebensdauer hinaus und
 * nimmt den Stand beim Bauen entgegen.
 */
class Berechtigungslage(
    val rolle: Rolle,
    val geraeteSdk: Int,
    val zielSdk: Int,
    /** Wonach schon einmal gefragt wurde — aus der Ablage des Aufrufers. */
    schonGefragt: Set<Berechtigungsart> = emptySet(),
) {

    private val gefragt = schonGefragt.toMutableSet()
    private val erteilt = mutableSetOf<Berechtigungsart>()
    private val begruendungNoetig = mutableSetOf<Berechtigungsart>()

    /** Was diese Rolle auf diesem Gerät braucht. */
    val benoetigt: List<Berechtigungsart> = Berechtigungsbedarf.fuer(rolle, geraeteSdk, zielSdk)

    /** Wonach schon gefragt wurde. Gehört in die Ablage des Aufrufers. */
    val gefragte: Set<Berechtigungsart> get() = gefragt.toSet()

    /**
     * Übernimmt, was die Plattform sagt.
     *
     * @param erteilt `checkSelfPermission(...) == PERMISSION_GRANTED`
     * @param begruendungZeigen `shouldShowRequestPermissionRationale(...)`
     */
    fun uebernimm(art: Berechtigungsart, erteilt: Boolean, begruendungZeigen: Boolean) {
        if (erteilt) {
            this.erteilt += art
            begruendungNoetig -= art
            return
        }
        this.erteilt -= art
        if (begruendungZeigen) {
            begruendungNoetig += art
            // Das System zeigt nur dann eine Begründung an, wenn schon einmal
            // abgelehnt wurde. Also wurde gefragt — auch wenn der Aufrufer es
            // vergessen hat.
            gefragt += art
        } else {
            begruendungNoetig -= art
        }
    }

    /**
     * Merkt, dass eine **Antwort** vorlag.
     *
     * Nicht, dass ein Dialog aufging: Ein abgebrochener Dialog zeichnet auf der
     * Plattform nichts auf, und ein Vermerk dafür machte aus dem Abbruch eine
     * endgültige Ablehnung.
     *
     * ## Wenn dieser Vermerk verlorengeht
     *
     * Dann sieht eine endgültige Ablehnung nach dem nächsten Prozessstart aus
     * wie [Berechtigungsstand.UNGEFRAGT], und es wird einmal ins Leere gefragt:
     * Das System antwortet sofort mit „abgelehnt", ohne einen Dialog zu zeigen.
     * Das Ergebnis landet wieder hier, und danach steht der Zustand richtig.
     * Ein verlorener Vermerk kostet also **eine** wirkungslose Anfrage und
     * nicht die Richtigkeit — deshalb ist die Ablage Sache des Aufrufers und
     * keine Abhängigkeit dieser Klasse.
     */
    fun merkeAnfrage(arten: Collection<Berechtigungsart>) {
        gefragt += arten.filter { it in benoetigt }
    }

    fun stand(art: Berechtigungsart): Berechtigungsstand = when {
        art !in benoetigt -> Berechtigungsstand.NICHT_NOETIG
        art in erteilt -> Berechtigungsstand.ERTEILT
        art in begruendungNoetig -> Berechtigungsstand.ABGELEHNT
        art !in gefragt -> Berechtigungsstand.UNGEFRAGT
        else -> Berechtigungsstand.ENDGUELTIG_ABGELEHNT
    }

    /** Der Stand aller bekannten Berechtigungen — auch der nicht benötigten. */
    fun alleStaende(): Map<Berechtigungsart, Berechtigungsstand> =
        Berechtigungsart.entries.associateWith { stand(it) }

    /** Ist alles da, was diese Rolle braucht? */
    val vollstaendig: Boolean get() = benoetigt.all { stand(it) == Berechtigungsstand.ERTEILT }

    /**
     * Was als Nächstes zu tun ist.
     *
     * Die Reihenfolge ist festgelegt und nicht beliebig:
     *
     * 1. **Begründen**, wenn das System es verlangt. Sonst fragt man jemanden
     *    ein zweites Mal, der schon nein gesagt hat, ohne ein Wort dazwischen.
     * 2. **Fragen**, was noch nie gefragt wurde.
     * 3. **Einstellungen**, wenn nichts mehr zu fragen ist.
     *
     * Erst danach [Schritt.NichtsZuTun]. Ein Ablauf, der die Einstellungen vor
     * die offene Frage stellte, schickte den Nutzer aus der App heraus, bevor er
     * überhaupt gefragt wurde.
     */
    fun naechsterSchritt(): Schritt {
        val nachStand = { gesucht: Berechtigungsstand ->
            benoetigt.filter { stand(it) == gesucht }
        }

        nachStand(Berechtigungsstand.ABGELEHNT).let { if (it.isNotEmpty()) return Schritt.Begruenden(it) }
        nachStand(Berechtigungsstand.UNGEFRAGT).let { if (it.isNotEmpty()) return Schritt.Fragen(it) }
        nachStand(Berechtigungsstand.ENDGUELTIG_ABGELEHNT).let {
            if (it.isNotEmpty()) return Schritt.NurNochEinstellungen(it)
        }
        return Schritt.NichtsZuTun
    }

    /**
     * Was die Oberfläche sagen soll, wenn es nicht weitergeht.
     *
     * ADR-001 verlangt, dass der Fehlerfall **erklärt** wird *„statt eine
     * kryptische Zeitüberschreitung zu zeigen"*. Für eine verweigerte
     * Host-Berechtigung steht die Erklärung dort sogar wörtlich.
     */
    fun erklaerung(art: Berechtigungsart): String = when (art) {
        Berechtigungsart.BENACHRICHTIGUNGEN ->
            "Ohne die Benachrichtigung läuft die Partie weiter, aber du siehst nicht mehr, " +
                "dass die Verbindung zum Tisch steht."

        Berechtigungsart.LOKALES_NETZ ->
            "Ohne den Zugriff auf das lokale Netz kann dieses Gerät keine Partie eröffnen. " +
                "Ein anderes Gerät am Tisch kann den Tisch aufmachen — beitreten kannst du weiterhin."
    }
}
