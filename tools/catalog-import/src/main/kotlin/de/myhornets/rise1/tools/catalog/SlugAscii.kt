package de.myhornets.rise1.tools.catalog

import java.text.Normalizer

/**
 * Erzeugt `identity.slug_ascii` aus dem Kartennamen. T-011, TDD 4.1.
 *
 * Warum überhaupt: Die Quelle liefert mit `name_anchor` bereits einen Slug —
 * aber der ist **nicht ASCII**. Karte 1 heißt dort `the-Ætherist`. Ein Slug mit
 * `Æ` taugt weder als Dateiname für die Bild-Pipeline (`T-012`) noch als
 * stabiler Schlüssel. Deshalb wird er hier neu gebildet und der Quell-Slug
 * verworfen.
 *
 * Das Verfahren ist bewusst zweistufig: erst die Sonderfälle, die Unicode
 * **nicht** zerlegt, dann die allgemeine Zerlegung.
 */
public object SlugAscii {

    /**
     * Buchstaben, die NFD nicht zerlegt, weil sie keine Kombination aus
     * Grundzeichen und Akzent sind, sondern eigene Buchstaben. Ohne diese
     * Tabelle fielen sie in Schritt 2 ersatzlos weg — aus `Ætherist` würde
     * `therist`, und der Fehler wäre still.
     */
    private val EIGENE_BUCHSTABEN: List<Pair<String, String>> = listOf(
        "Æ" to "ae", "æ" to "ae",
        "Œ" to "oe", "œ" to "oe",
        "Ø" to "o", "ø" to "o",
        "Þ" to "th", "þ" to "th",
        "Ð" to "d", "ð" to "d",
        "Đ" to "d", "đ" to "d",
        "Ł" to "l", "ł" to "l",
        "ß" to "ss",
        // Apostrophe verschwinden ersatzlos: "Death's" soll "deaths" ergeben,
        // nicht "death-s". Gerade und typografische Form, beide Richtungen.
        "'" to "", "‘" to "", "’" to "",
    )

    public fun of(name: String): String {
        var s = name.lowercase()
        EIGENE_BUCHSTABEN.forEach { (von, nach) -> s = s.replace(von.lowercase(), nach) }

        // NFD trennt Grundzeichen und Akzent; die Akzente (Unicode-Kategorie M)
        // fallen anschließend weg. Aus `é` wird `e`.
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

        // Alles, was kein a-z und keine Ziffer ist, wird zum Trenner.
        s = s.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return s
    }

    /**
     * Prüft eine Menge erzeugter Slugs auf Eindeutigkeit — TDD 4.1 verlangt
     * `unique`. Zwei Karten mit gleichem Slug würden sich in `T-012` das Bild
     * teilen und in `T-013` an der Datenbank scheitern.
     */
    public fun duplikate(slugs: List<String>): Map<String, Int> =
        slugs.groupingBy { it }.eachCount().filterValues { it > 1 }
}
