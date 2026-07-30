package de.myhornets.rise1.tools.catalog

/**
 * Liest den Unveil-Cost aus `text_raw`. T-011, TDD 4.1.
 *
 * Die Quellfelder `cost` und `cmc` sind ungepflegt — leer beziehungsweise 0,
 * obwohl die Karte Kosten hat. Der tatsächliche Wert steht im Regeltext.
 *
 * **Zwei Eigenschaften der echten Daten bestimmen dieses Verfahren:**
 *
 * 1. **Leader haben keinen Unveil-Cost.** Sie liegen von Beginn an offen. Das
 *    Ergebnis ist dann `null` — kein Fehler, kein leerer String.
 *
 * 2. **Der Cost ist oft keine Mana-Angabe.** Belegt sind unter anderem
 *    `{3}`, `{X}{X}{2}`, `Discard a nonland card.`, `{5}, Pay 5 life.` und
 *    `{9}. This cost is reduced by {1} for each creature controlled by
 *    non-Leader players.` Der Ausdruck wird deshalb **roh** übernommen und
 *    nicht in Mana-Symbole zerlegt.
 *
 * **Die Falle, gegen die hier ausdrücklich verankert wird:** Das Wort `Unveil`
 * kommt auch mitten im Text vor, etwa in `Undercover (Unveil only if another
 * identity has been revealed …)`. Gesucht wird deshalb ein Abschnitt, der mit
 * `Unveil` **beginnt** — nicht eine Fundstelle irgendwo im Text.
 */
public object UnveilCost {

    private const val TRENNER = '|'
    private const val SCHLUESSELWORT = "Unveil"

    /** Die Zeichen, die in der Quelle zwischen `Unveil` und den Kosten stehen. */
    private val ABSTAND = charArrayOf(' ', '—', '–', '-', ':')

    public fun of(textRaw: String): String? {
        val abschnitt = textRaw.split(TRENNER)
            .map { it.trim() }
            .firstOrNull { it.startsWith(SCHLUESSELWORT) && istWortgrenze(it) }
            ?: return null

        val rest = abschnitt.removePrefix(SCHLUESSELWORT).trimStart(*ABSTAND).trim()
        return rest.ifEmpty { null }
    }

    /**
     * Verhindert, dass ein Abschnitt wie `Unveiling the truth …` als Kostenzeile
     * durchgeht. Nach `Unveil` muss ein Abstands- oder Satzzeichen folgen, kein
     * weiterer Buchstabe.
     */
    private fun istWortgrenze(abschnitt: String): Boolean {
        val naechstes = abschnitt.getOrNull(SCHLUESSELWORT.length) ?: return false
        return !naechstes.isLetter()
    }

    /**
     * Ob der Ausdruck ausschließlich aus Mana-Symbolen besteht. Nicht für die
     * Speicherung gedacht, sondern für die Auswertung beim Import: Sie sagt,
     * wie viele der 62 Karten eine reine Mana-Angabe haben und wie viele nicht.
     */
    public fun istReineManaAngabe(ausdruck: String): Boolean =
        ausdruck.isNotEmpty() && Regex("^(\\{[^}]+\\})+$").matches(ausdruck)
}
