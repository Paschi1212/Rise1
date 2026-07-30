package de.myhornets.rise1.tools.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-011 — Unveil-Cost. **Alle Eingaben sind wörtliche Ausschnitte aus der
 * echten Quelle**, nicht ausgedacht. Genau darin liegt der Wert dieser Tests:
 * Die Formen sind vielfältiger, als das TDD annahm.
 */
class UnveilCostTest {

    @Test
    fun `einfache Manakosten`() {
        assertEquals("{3}", UnveilCost.of("Undercover|Unveil {3}|When The Ætherist is unveiled, counter target spell."))
    }

    @Test
    fun `variable Kosten`() {
        assertEquals("{X}", UnveilCost.of("Undercover|Unveil {X}|When The Augur is unveiled, target player shuffles."))
    }

    @Test
    fun `zusammengesetzte Kosten`() {
        assertEquals("{X}{X}{2}", UnveilCost.of("Unveil {X}{X}{2}|Wirkung."))
        assertEquals("{X}{4}", UnveilCost.of("Unveil {X}{4}|Wirkung."))
    }

    @Test
    fun `ohne Undercover-Abschnitt davor`() {
        assertEquals("{7}", UnveilCost.of("Unveil {7}|Wirkung."))
        assertEquals("{0}", UnveilCost.of("Unveil {0}|Wirkung."))
    }

    @Test
    fun `Kosten ohne Mana, mit Gedankenstrich`() {
        assertEquals(
            "Discard a nonland card.",
            UnveilCost.of("Undercover|Unveil—Discard a nonland card.|Wirkung."),
        )
        assertEquals(
            "Sacrifice a nontoken permanent.",
            UnveilCost.of("Unveil—Sacrifice a nontoken permanent.|Wirkung."),
        )
    }

    @Test
    fun `gemischte Kosten`() {
        assertEquals(
            "{5}, Pay 5 life.",
            UnveilCost.of(
                "Undercover (Unveil only if another identity has been revealed or if another " +
                    "player attacked the Leader this game.)|Unveil—{5}, Pay 5 life.|Wirkung."
            ),
        )
    }

    @Test
    fun `Kosten mit angehaengtem Satz bleiben vollstaendig`() {
        assertEquals(
            "{9}. This cost is reduced by {1} for each creature controlled by non-Leader players.",
            UnveilCost.of(
                "Unveil {9}. This cost is reduced by {1} for each creature controlled by " +
                    "non-Leader players.|Wirkung."
            ),
        )
    }

    @Test
    fun `das Wort Unveil im Klammertext ist keine Kostenzeile`() {
        // Die Falle: Der erste Abschnitt enthält "Unveil", beginnt aber nicht damit.
        assertEquals(
            "{4}",
            UnveilCost.of(
                "Undercover (Unveil only if another identity has been revealed or if another " +
                    "player attacked the Leader this game.)|Unveil {4}|Wirkung."
            ),
        )
    }

    @Test
    fun `Leader haben keinen Unveil-Cost`() {
        assertNull(
            UnveilCost.of(
                "(Start the game with this identity face up in the command zone. You are the " +
                    "starting player.)|At the beginning of your draw step, you may draw an " +
                    "additional card."
            )
        )
        assertNull(
            UnveilCost.of(
                "At the beginning of your end step, roll a six-sided die, then roll again for " +
                    "each player who has lost the game.|1 — Create a 0/1 white Goat creature token."
            )
        )
    }

    @Test
    fun `ein Wort das nur mit Unveil beginnt zaehlt nicht`() {
        assertNull(UnveilCost.of("Unveiling the truth costs nothing.|Wirkung."))
    }

    @Test
    fun `reine Manaangabe wird erkannt`() {
        assertTrue(UnveilCost.istReineManaAngabe("{3}"))
        assertTrue(UnveilCost.istReineManaAngabe("{X}{X}{2}"))
        assertFalse(UnveilCost.istReineManaAngabe("Discard a nonland card."))
        assertFalse(UnveilCost.istReineManaAngabe("{5}, Pay 5 life."))
        assertFalse(UnveilCost.istReineManaAngabe(""))
    }
}
