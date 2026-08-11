package de.myhornets.rise1.catalog

// T-016 — Regeltext-Renderer.
//
// `text_raw` steht so in der Datenbank, wie die Quelle ihn liefert: Zeilen mit
// `|` getrennt, Symbole in geschweiften Klammern, Erinnerungstext in runden.
// Diese Datei zerlegt ihn in eine Form, die sich anzeigen lässt.
//
// Was hier NICHT passiert, und zwar mit Absicht:
//
//   `{3}` wird zu einem Symbol mit dem Code `3`. Es wird nicht zu einer Zahl,
//   nicht zu Mana, nicht zu Kosten. Was `{a}` bedeutet, weiß diese Datei nicht
//   und soll es nicht wissen — auf den Karten steht dazu ein Erinnerungstext
//   („six nut counters"), und der wird angezeigt, nicht ausgewertet.
//
//   Es gibt keine Funktion, die zwei Symbole addiert, vergleicht oder auf
//   Bezahlbarkeit prüft. Wer eine braucht, hat die Grenze aus dem README
//   überschritten.
//
// Das Ergebnis ist bewusst **toolkit-frei** — kein Compose, kein Android. Es
// beschreibt, was dasteht; wie es aussieht, entscheidet `:ui`. Dieselbe
// Trennung wie zwischen `:projection` und `:ui` (Modules.md).

/** Ein Baustein einer Regeltextzeile. */
sealed interface RulesToken {

    /** Gewöhnlicher Text. Enthält nie ein `|`, `{` oder `}`. */
    data class Text(val text: String) : RulesToken

    /**
     * Ein Symbol, roh wie es in der Quelle steht: `{3}` → `code = "3"`,
     * `{X}` → `"X"`, `{a}` → `"a"`.
     *
     * Der Code wird nicht gedeutet. `:ui` stellt ihn dar.
     */
    data class Symbol(val code: String) : RulesToken

    /**
     * Erinnerungstext — der Teil in runden Klammern, den Magic kursiv setzt.
     *
     * Er kann selbst Symbole enthalten und wird deshalb wieder zerlegt. Die
     * Klammern gehören nicht zum Inhalt; wer sie anzeigen will, setzt sie
     * beim Rendern.
     */
    data class Reminder(val tokens: List<RulesToken>) : RulesToken
}

/**
 * Eine Zeile des Regeltexts.
 *
 * `isBullet` hält fest, dass die Zeile in der Quelle mit `•` beginnt — die
 * Karten mit „choose two" führen ihre Optionen so auf. Das Zeichen selbst ist
 * dann aus den Tokens entfernt; die Anzeige setzt es neu, damit Einrückung und
 * Zeichen zusammenpassen.
 */
data class RulesLine(
    val isBullet: Boolean,
    val tokens: List<RulesToken>,
)

/** Ein vollständig zerlegter Regeltext. */
data class RulesText(val lines: List<RulesLine>) {

    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * Der Text ohne Auszeichnung, für Vorschauen und für Zusicherungen in Tests.
     *
     * Symbole erscheinen wieder als `{code}`, Erinnerungstext wieder in
     * Klammern, Zeilen mit `\n` getrennt. Was hier **nicht** mehr vorkommt, ist
     * das Pipe-Zeichen — das ist der Punkt des ganzen Tasks.
     */
    val plain: String
        get() = lines.joinToString("\n") { zeile ->
            val vorn = if (zeile.isBullet) "• " else ""
            vorn + zeile.tokens.joinToString("") { it.plain() }
        }

    private fun RulesToken.plain(): String = when (this) {
        is RulesToken.Text -> text
        is RulesToken.Symbol -> "{$code}"
        is RulesToken.Reminder -> "(" + tokens.joinToString("") { it.plain() } + ")"
    }
}

/**
 * Zerlegt `text_raw` und verwandte Felder.
 *
 * Der Zerleger wirft nie. Ein unvollständiges `{` oder eine nicht geschlossene
 * Klammer wird als gewöhnlicher Text übernommen — bei 62 Karten, die aus einer
 * fremden Quelle stammen, ist eine Ausnahme beim Anzeigen der schlechtere
 * Ausgang als ein Zeichen zu viel auf dem Bildschirm.
 */
object RulesTextParser {

    /** Die Quelle trennt Zeilen mit diesem Zeichen statt mit `\n`. */
    private const val ZEILENTRENNER = '|'

    private const val AUFZAEHLUNG = '•'

    /**
     * Zerlegt einen vollständigen `text_raw`.
     *
     * Leere Abschnitte werden übergangen — sie tragen nichts und ergäben eine
     * Leerzeile ohne Inhalt.
     */
    fun parse(textRaw: String): RulesText {
        val zeilen = textRaw.split(ZEILENTRENNER)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { zeile ->
                val aufzaehlung = zeile.startsWith(AUFZAEHLUNG)
                val rest = if (aufzaehlung) zeile.drop(1).trimStart() else zeile
                RulesLine(isBullet = aufzaehlung, tokens = parseInline(rest))
            }
            .filter { it.tokens.isNotEmpty() }
        return RulesText(zeilen)
    }

    /**
     * Zerlegt einen einzelnen Ausdruck ohne Zeilentrennung — etwa
     * `unveil_cost`, der ebenfalls Symbole enthält (`{5}, Pay 5 life.`).
     */
    fun parseInline(text: String): List<RulesToken> {
        val tokens = mutableListOf<RulesToken>()
        val puffer = StringBuilder()

        fun pufferAbgeben() {
            if (puffer.isNotEmpty()) {
                tokens += RulesToken.Text(puffer.toString())
                puffer.clear()
            }
        }

        var i = 0
        while (i < text.length) {
            when (val zeichen = text[i]) {
                '{' -> {
                    val ende = text.indexOf('}', startIndex = i + 1)
                    if (ende < 0) {
                        // Keine schließende Klammer — als Text übernehmen.
                        puffer.append(zeichen)
                        i++
                    } else {
                        pufferAbgeben()
                        tokens += RulesToken.Symbol(text.substring(i + 1, ende))
                        i = ende + 1
                    }
                }

                '(' -> {
                    val ende = passendeKlammer(text, i)
                    if (ende < 0) {
                        puffer.append(zeichen)
                        i++
                    } else {
                        pufferAbgeben()
                        tokens += RulesToken.Reminder(parseInline(text.substring(i + 1, ende)))
                        i = ende + 1
                    }
                }

                else -> {
                    puffer.append(zeichen)
                    i++
                }
            }
        }
        pufferAbgeben()
        return tokens
    }

    /**
     * Index der schließenden Klammer zu der an [start], oder -1.
     *
     * Zählt die Tiefe mit, damit `(… (…) …)` nicht bei der inneren Klammer
     * endet. Im Bestand TRD-2025 kommt das nicht vor — aber eine Quelle, die
     * `|` für Zeilenumbrüche verwendet, hat sich schon einmal anders verhalten
     * als erwartet.
     */
    private fun passendeKlammer(text: String, start: Int): Int {
        var tiefe = 0
        var i = start
        while (i < text.length) {
            when (text[i]) {
                '(' -> tiefe++
                ')' -> {
                    tiefe--
                    if (tiefe == 0) return i
                }
            }
            i++
        }
        return -1
    }
}
