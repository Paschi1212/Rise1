package de.myhornets.rise1.tools.catalog

/**
 * Ergebnis einer Prüfung. T-010.
 *
 * Befunde sind nach Art unterschieden, damit die Meldung später sagen kann,
 * *was* nicht stimmt — und nicht nur, *dass* etwas nicht stimmt.
 */
public sealed interface Finding {
    public val message: String

    public data class Fehlend(override val message: String) : Finding
    public data class Struktur(override val message: String) : Finding
    public data class Anzahl(override val message: String) : Finding
    public data class Nummerierung(override val message: String) : Finding
    public data class Pool(override val message: String) : Finding
    public data class Wert(override val message: String) : Finding
}
// Kein Befund-Typ für die Prüfsumme: Sie wird VOR dem Parsen geprüft und bricht
// dort ab (siehe Main). Ein hier unbenutzter Fall wäre eine Zusage, die niemand
// einlöst.

/** Kopfdaten der Quelle, sofern die Struktur tragfähig war. */
public data class SourceHeader(
    val apiVersion: String,
    val apiAuthor: String,
    val setName: String,
    val setCode: String,
    val setLang: String,
    val cardsCount: Int,
)

public data class ValidationReport(
    val findings: List<Finding>,
    val header: SourceHeader?,
) {
    public val isValid: Boolean get() = findings.isEmpty()

    public fun render(): String = if (isValid) {
        "Quelle in Ordnung: ${header?.cardsCount} Karten, Set ${header?.setCode}, " +
            "API-Version ${header?.apiVersion}."
    } else {
        buildString {
            appendLine("Die Quelldatei ist nicht verwendbar. ${findings.size} Befund(e):")
            appendLine()
            findings.forEach { appendLine("  - [${it::class.simpleName}] ${it.message}") }
            appendLine()
            append("Der Import wird abgebrochen. Es wird nichts erzeugt und nichts überschrieben.")
        }
    }
}
