package de.myhornets.rise1.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-016 — der Regeltext-Zerleger.
 *
 * Reine JVM-Tests: `RulesTextParser` kennt weder Android noch Compose noch die
 * Datenbank. Sie laufen deshalb in `./gradlew checkAll` mit — anders als der
 * Durchlauf über alle 62 Karten, der die ausgelieferte Datenbank braucht und in
 * `CatalogAssetTest` steht.
 *
 * Die Beispieltexte stammen wörtlich aus `catalog-source/treachery-cards.json`.
 */
class RulesTextParserTest {

    @Test
    fun dasPipeZeichenTrenntZeilen() {
        val text = RulesTextParser.parse("Undercover|Unveil {3}|When it is unveiled, draw a card.")
        assertEquals(3, text.lines.size)
        assertFalse("Im Ergebnis darf kein Pipe mehr stehen", text.plain.contains('|'))
    }

    @Test
    fun einSymbolBehaeltSeinenRohenCode() {
        val tokens = RulesTextParser.parseInline("Unveil {X}{Y}")
        assertEquals(
            listOf(
                RulesToken.Text("Unveil "),
                RulesToken.Symbol("X"),
                RulesToken.Symbol("Y"),
            ),
            tokens,
        )
    }

    @Test
    fun zahlenSymboleBleibenZeichenketten() {
        // Aus `{3}` wird der Code "3" — keine Zahl. Es wird nichts gerechnet.
        val tokens = RulesTextParser.parseInline("{10}")
        assertEquals(listOf(RulesToken.Symbol("10")), tokens)
    }

    @Test
    fun erinnerungstextWirdEigenerBausteinOhneKlammern() {
        val tokens = RulesTextParser.parseInline("Undercover (Unveil only if another identity has been revealed.)")
        assertEquals(2, tokens.size)
        assertEquals(RulesToken.Text("Undercover "), tokens[0])
        val reminder = tokens[1] as RulesToken.Reminder
        assertEquals(
            listOf(RulesToken.Text("Unveil only if another identity has been revealed.")),
            reminder.tokens,
        )
    }

    @Test
    fun erinnerungstextKannSelbstSymboleEnthalten() {
        val tokens = RulesTextParser.parseInline("you get {m}{m} (two gem counters)")
        val reminder = tokens.last() as RulesToken.Reminder
        assertEquals(listOf(RulesToken.Text("two gem counters")), reminder.tokens)
        assertEquals(2, tokens.filterIsInstance<RulesToken.Symbol>().size)
    }

    @Test
    fun verschachtelteKlammernEndenAnDerRichtigenStelle() {
        val tokens = RulesTextParser.parseInline("a (b (c) d) e")
        assertEquals(3, tokens.size)
        val reminder = tokens[1] as RulesToken.Reminder
        assertEquals("(b (c) d)", RulesText(listOf(RulesLine(false, listOf(reminder)))).plain)
    }

    @Test
    fun einAufzaehlungszeichenWirdErkanntUndEntfernt() {
        val text = RulesTextParser.parse("choose two —|• Create a Blood token.|• Draw a card.")
        assertEquals(listOf(false, true, true), text.lines.map { it.isBullet })
        assertEquals(
            RulesToken.Text("Create a Blood token."),
            text.lines[1].tokens.single(),
        )
    }

    @Test
    fun eineNichtGeschlosseneKlammerBleibtText() {
        // Lieber ein Zeichen zu viel auf dem Bildschirm als eine Ausnahme
        // beim Anzeigen einer Karte.
        assertEquals(listOf(RulesToken.Text("Pay {3")), RulesTextParser.parseInline("Pay {3"))
        assertEquals(listOf(RulesToken.Text("Pay (3")), RulesTextParser.parseInline("Pay (3"))
    }

    @Test
    fun leereAbschnitteWerdenUebergangen() {
        val text = RulesTextParser.parse("Undercover||  |Unveil {2}")
        assertEquals(2, text.lines.size)
    }

    @Test
    fun einLeererTextErgibtEinLeeresErgebnis() {
        assertTrue(RulesTextParser.parse("").isEmpty)
        assertTrue(RulesTextParser.parse("|  |").isEmpty)
    }

    @Test
    fun dieZerlegungIstVerlustfrei() {
        // Der Maßstab: `plain` gibt genau das zurück, was in der Quelle steht —
        // nur mit Zeilenumbruch statt Pipe. Ohne diese Zusicherung könnte der
        // Zerleger stillschweigend Text verlieren, und bei 62 Karten fiele das
        // niemandem auf.
        val roh = "Undercover|Unveil {9}. This cost is reduced by {1} for each creature " +
            "controlled by non-Leader players.|When The Quellmaster is unveiled, tap all " +
            "creatures. (If a permanent with a stun counter would become untapped, remove " +
            "one from it instead.)"
        assertEquals(roh.replace('|', '\n'), RulesTextParser.parse(roh).plain)
    }

    @Test
    fun unveilCostLaeuftDurchDenselbenZerleger() {
        val tokens = RulesTextParser.parseInline("{5}, Pay 5 life.")
        assertEquals(RulesToken.Symbol("5"), tokens.first())
        assertEquals(RulesToken.Text(", Pay 5 life."), tokens.last())
    }

    @Test
    fun einKostenausdruckOhneSymbolBleibtEinfacherText() {
        // 24 der 49 Unveil-Kosten sind keine Mana-Angaben (T-011).
        assertEquals(
            listOf(RulesToken.Text("Discard a nonland card.")),
            RulesTextParser.parseInline("Discard a nonland card."),
        )
    }
}
