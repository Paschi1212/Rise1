package de.myhornets.rise1.transport

/**
 * T-065 — die Transportschnittstelle nach [[ADR-001 Transport]].
 *
 * ## Warum es diese Schnittstelle gibt
 *
 * ADR-001, Abschnitt „Was gebaut werden muss": *„Der Transport liegt hinter
 * einer eigenen Schnittstelle — **nicht als Ausweichmöglichkeit**, sondern damit
 * im Test ein Attrappen-Transport eingesetzt werden kann, der Abbrüche und
 * Verzögerungen deterministisch erzeugt."*
 *
 * Das ist der Grund, aus dem ADR-001 überhaupt so entschieden wurde: *„Ein
 * Protokoll mit Sitzungen, Wiedereinstieg, Zeremonien und Verschlüsselung wird
 * nicht im ersten Anlauf richtig."* Die Schnittstelle ist deshalb kein
 * Architekturschmuck, sondern die Voraussetzung dafür, dass alles darüber
 * fertig werden kann.
 *
 * ## Was hier bewusst nicht steht
 *
 * **Nichts über TLS, Zertifikate oder `NsdManager`.** Wer diese Schnittstelle
 * benutzt, soll nicht wissen können, worüber gesprochen wird — sonst wäre die
 * Attrappe keine Attrappe, sondern eine zweite Umsetzung derselben Annahmen.
 *
 * **Nichts über Nutzdaten.** Ein Rahmen ist ein `ByteArray`. Der Transport
 * kennt weder Events noch Sitzungen; er befördert Bytes. ADR-001: *„Der
 * Transport trägt keine Geheimnisse"* — alles Vertrauliche ist bereits
 * Ende-zu-Ende verschlüsselt, bevor es hier ankommt.
 *
 * **Keine Verschlüsselungszusage.** TLS schützt gegen Mitlesen im Netz,
 * **nicht gegen den Host** (ADR-001, „Was ausdrücklich nicht gilt"). Wer die
 * Nutzdatenverschlüsselung aus TDD Kapitel 7 mit Hinweis auf diese Schicht
 * wegkürzt, hebt das gesamte Sicherheitsmodell auf.
 */

/** Eine Gegenstelle — ein anderes Gerät am selben Tisch. */
data class Gegenstelle(
    /** Stabile Kennung des Geräts. Entspricht `device.device_uid` (TDD 4.3). */
    val geraeteUid: String,
    /** Was der Nutzer sieht, wenn er einen Host auswählt. */
    val anzeigename: String,
) {
    init {
        require(geraeteUid.isNotBlank()) { "Eine Gegenstelle ohne Kennung ist keine." }
    }
}

/**
 * Was schiefgehen kann.
 *
 * Geschlossene Menge, damit die Oberfläche jeden Fall **erklären** kann.
 * ADR-001 verlangt das ausdrücklich: Bei fehlendem gemeinsamen Netz *„muss die
 * App das erklären und den Hotspot-Weg anbieten, statt eine kryptische
 * Zeitüberschreitung zu zeigen"*. Ein `Exception` mit freiem Text würde genau
 * diese Erklärung unmöglich machen.
 */
sealed interface TransportFehler {

    /** Kein gemeinsames Netz. Der Fall, den ADR-001 als Preis der Entscheidung benennt. */
    data object KeinGemeinsamesNetz : TransportFehler

    /** Die Gegenstelle war da und ist weg. Der Normalfall, nicht der Ausnahmefall (TDD 9). */
    data class VerbindungAbgebrochen(val grund: String) : TransportFehler

    /** Nichts gehört. Trägt die Wartezeit mit, damit die Anzeige sie nennen kann. */
    data class Zeitueberschreitung(val nachMillis: Long) : TransportFehler

    /**
     * Der Fingerabdruck des Hosts stimmt nicht.
     *
     * **Sicherheitsrelevant und deshalb ein eigener Fall.** ADR-001 und
     * [[ADR-002A Key Verification]]: Der Fingerabdruck ist die Prüfung, dass am
     * anderen Ende der erwartete Host sitzt. Ihn mit einem gewöhnlichen
     * Verbindungsfehler zu vermengen hieße, einen Angriff wie eine Störung
     * aussehen zu lassen.
     */
    data class FingerabdruckPasstNicht(val erwartet: String, val gesehen: String) : TransportFehler

    /** Die Gegenstelle hat abgelehnt — etwa, weil die Partie voll ist. */
    data class Abgelehnt(val grund: String) : TransportFehler
}

/** Was der Transport meldet. */
sealed interface TransportEreignis {
    data class Verbunden(val gegenstelle: Gegenstelle) : TransportEreignis
    data class Empfangen(val von: Gegenstelle, val rahmen: ByteArray) : TransportEreignis {
        // ByteArray in einer data class vergleicht sich sonst über die Referenz.
        override fun equals(other: Any?): Boolean =
            this === other || (other is Empfangen && von == other.von && rahmen.contentEquals(other.rahmen))

        override fun hashCode(): Int = 31 * von.hashCode() + rahmen.contentHashCode()
        override fun toString(): String = "Empfangen(von=${von.geraeteUid}, ${rahmen.size} Bytes)"
    }

    data class Getrennt(val gegenstelle: Gegenstelle, val grund: String) : TransportEreignis
    data class Fehlgeschlagen(val gegenstelle: Gegenstelle?, val fehler: TransportFehler) : TransportEreignis
}

/**
 * Ein Transport befördert Rahmen zwischen Geräten.
 *
 * Bewusst **ohne Coroutinen und ohne Flow** in der Signatur: Die Attrappe soll
 * ohne Scheduler und ohne Zeit auskommen, damit ihre Tests deterministisch
 * sind. Wer einen Strom braucht, baut ihn eine Schicht darüber.
 */
interface Transport {

    /** Meldungen entgegennehmen. Mehrfach aufrufbar; alle Hörer bekommen alles. */
    fun beobachte(hoerer: (TransportEreignis) -> Unit)

    /** Verbindung aufbauen. Das Ergebnis kommt als Ereignis, nicht als Rückgabewert. */
    fun verbinde(gegenstelle: Gegenstelle)

    /**
     * Einen Rahmen senden.
     *
     * @return `false`, wenn zu dieser Gegenstelle gerade keine Verbindung
     *   besteht. Kein Werfen: Dass eine Verbindung weg ist, ist in TDD 9 der
     *   Normalfall und keine Ausnahme.
     */
    fun sende(an: Gegenstelle, rahmen: ByteArray): Boolean

    /** Verbindung bewusst beenden. Erzeugt [TransportEreignis.Getrennt]. */
    fun trenne(gegenstelle: Gegenstelle, grund: String)

    /** Alles beenden. Danach ist dieser Transport nicht mehr benutzbar. */
    fun schliesse()

    /** Die derzeit verbundenen Gegenstellen. */
    val verbundene: Set<Gegenstelle>
}
