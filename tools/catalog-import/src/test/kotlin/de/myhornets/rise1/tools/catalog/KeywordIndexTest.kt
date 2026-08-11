package de.myhornets.rise1.tools.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T-015 — die Erkennungsregel für Schlüsselwortzeilen.
 *
 * Alle Fälle stammen wörtlich aus `catalog-source/treachery-cards.json`. Die
 * beiden interessanten sind die Negativfälle: Sie halten genau die Fehler fest,
 * die bei der Vorbereitung tatsächlich gemacht wurden.
 */
class KeywordIndexTest {

    @Test
    fun einSchluesselwortAlleinIstEinesTest() {
        assertEquals("Undercover", KeywordIndex.fuehrendesWort("Undercover"))
    }

    @Test
    fun schluesselwortMitErinnerungstextIstEines() {
        assertEquals(
            "Undercover",
            KeywordIndex.fuehrendesWort(
                "Undercover (Unveil only if another identity has been revealed or if another " +
                    "player attacked the Leader this game.)",
            ),
        )
    }

    @Test
    fun schluesselwortMitKostenIstEines() {
        assertEquals("Unveil", KeywordIndex.fuehrendesWort("Unveil {4}"))
    }

    @Test
    fun einGanzerSatzHinterDenKostenAendertNichts() {
        // Der Fall, an dem eine strengere Regel zwei Guardians verloren hat:
        // The Quellmaster und The Summoner.
        assertEquals(
            "Unveil",
            KeywordIndex.fuehrendesWort(
                "Unveil {9}. This cost is reduced by {1} for each creature controlled by " +
                    "non-Leader players.",
            ),
        )
        assertEquals("Unveil", KeywordIndex.fuehrendesWort("Unveil {X}{Y}. X and Y can’t be 0."))
    }

    @Test
    fun schluesselwortMitAlternativkostenIstEines() {
        assertEquals("Unveil", KeywordIndex.fuehrendesWort("Unveil—Pay 8 life, Discard a card."))
        assertEquals("Unveil", KeywordIndex.fuehrendesWort("Unveil—Sacrifice a nontoken permanent."))
    }

    @Test
    fun einKleinbuchstabeDirektDahinterIstKeinSchluesselwort() {
        // `Unveiling` darf nicht als `Unveil` durchgehen. Die Zurückweisung
        // passiert am Vokabularvergleich, nicht an der Wortgrenze — deshalb
        // steht die Prüfung seit T-019 an `schluesselwortVon` und nicht mehr
        // an `fuehrendesWort`. Dort behauptete sie eine Regel, die es im Code
        // nie gab, und war folgerichtig rot.
        assertNull(KeywordIndex.schluesselwortVon("Unveiling the truth costs nothing."))
        assertEquals("unveil", KeywordIndex.schluesselwortVon("Unveil {4}"))
        assertEquals("undercover", KeywordIndex.schluesselwortVon("Undercover"))
    }

    @Test
    fun fuehrendesWortLiefertDasGanzeWortAuchWennEsLaengerIst() {
        // Hält fest, was die Funktion wirklich tut. Ohne diesen Test hing die
        // einzige Aussage über ihr Verhalten in einem KDoc-Kommentar — und der
        // war falsch.
        assertEquals("Unveiling", KeywordIndex.fuehrendesWort("Unveiling the truth costs nothing."))
    }

    @Test
    fun einNormalerSatzLiefertSeinErstesWort() {
        // Der Aufrufer verwirft es, weil `When` nicht im Vokabular steht.
        assertEquals("When", KeywordIndex.fuehrendesWort("When The Augur is unveiled, target player shuffles."))
    }

    @Test
    fun aufzaehlungszeichenSindKeineSchluesselwoerter() {
        assertNull(KeywordIndex.fuehrendesWort("• Create a 0/1 black Thrull creature token."))
    }

    @Test
    fun einAbschnittAusReinemErinnerungstextIstKeinerTest() {
        assertNull(
            KeywordIndex.fuehrendesWort(
                "(Start the game with this identity face up in the command zone. " +
                    "You are the starting player.)",
            ),
        )
    }

    @Test
    fun leererAbschnittLiefertNichts() {
        assertNull(KeywordIndex.fuehrendesWort(""))
    }
}
