package de.myhornets.rise1.core.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-025a — Wächter über das Event-Vokabular.
 *
 * Der wichtigste Test dieser Datei ist [keinEventTypVerraetEineRolle]. Er
 * schützt keine Funktion, sondern die Kernzusage des Projekts: Der Host sieht
 * alle Metadaten (TDD 7.4), also auch jeden Typnamen. Ein Typ, der eine Rolle
 * nennt, macht die gesamte Verschlüsselung wirkungslos (TDD 5.5).
 */
class EventVocabularyTest {

    @Test
    fun keinEventTypVerraetEineRolle() {
        val verstoesse = EventType.keinTypVerraetEineRolle()
        assertTrue(
            actual = verstoesse.isEmpty(),
            message = "Ein Event-Typ nennt eine Rolle. Das umgeht die Verschlüsselung vollständig, " +
                "weil der Host alle Metadaten sieht (TDD 5.5 / 7.4). Der Typ muss so heißen, " +
                "dass er die Handlung beschreibt und nicht, wer sie hatte:\n" +
                verstoesse.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    fun dieVerbotslisteIstNichtLeer() {
        // Sonst wäre der Test darüber grün, ohne etwas geprüft zu haben —
        // dieselbe Familie von Fehlern wie `NO-SOURCE`.
        assertTrue(EventType.VERBOTENE_NAMENSTEILE.size >= 4)
    }

    @Test
    fun dieVerbotslisteWuerdeEinenVerstossFinden() {
        // Der Wächter selbst wird hier gegen einen erfundenen Namen geführt.
        // Ohne diesen Test wäre nur belegt, dass er nichts findet — nicht, dass
        // er etwas finden könnte.
        val erfunden = "traitor_ability_used"
        assertTrue(
            EventType.VERBOTENE_NAMENSTEILE.any { erfunden.contains(it, ignoreCase = true) },
        )
    }

    @Test
    fun dealRolesDrawnIstKeinVerstoss() {
        // Dort werden Packer und Verteiler ausgelost, nicht Treachery-Rollen.
        // Festgehalten, damit niemand die Verbotsliste um `role` erweitert und
        // sich wundert.
        assertTrue(EventType.DEAL_ROLES_DRAWN.wert.contains("roles"))
        assertTrue(
            EventType.VERBOTENE_NAMENSTEILE.none {
                EventType.DEAL_ROLES_DRAWN.wert.contains(it, ignoreCase = true)
            },
        )
    }

    @Test
    fun typwerteSindEindeutig() {
        val werte = EventType.entries.map { it.wert }
        assertEquals(werte.size, werte.toSet().size, "Zwei Event-Typen tragen denselben Wert.")
    }

    @Test
    fun jederTypNenntMindestensEineErlaubteSichtbarkeit() {
        EventType.entries.forEach {
            assertTrue(it.erlaubteSichtbarkeiten.isNotEmpty(), "${it.wert} erlaubt keine Sichtbarkeit.")
        }
    }

    @Test
    fun genauDieseTypenSindHeuteAngewandt() {
        // Hält den Umfang fest. Steigt diese Menge, ist das eine Erweiterung
        // der Faltung und gehört in einen eigenen Task — nicht nebenbei.
        //
        // S1: die ersten drei. S3 (D-003): Zugzählung und Ausscheiden.
        assertEquals(
            setOf(
                EventType.MATCH_CREATED,
                EventType.PARTICIPANT_JOINED,
                EventType.IDENTITY_REVEALED,
                EventType.TURN_STARTED,
                EventType.TURN_ENDED,
                EventType.PARTICIPANT_ELIMINATED,
                EventType.PARTICIPANT_LEFT,
            ),
            EventType.angewandte(),
        )
    }

    @Test
    fun keinAngewandterTypBrauchtNutzdaten() {
        // Die Faltung liest keine Nutzdaten (siehe MatchFold). Ein Typ, dessen
        // Wirkung nach TDD 5.4 im Payload steckt — `life_changed` mit Delta und
        // Absolutwert, `counter_changed` mit Zählertyp —, darf deshalb nicht in
        // `angewandte()` stehen, solange das so ist. Sonst würde die Faltung
        // behaupten, ihn auszuwerten, und dabei nichts tun.
        listOf(EventType.LIFE_CHANGED, EventType.COUNTER_CHANGED, EventType.NOTE_ADDED)
            .forEach {
                assertTrue(
                    actual = !it.angewandt,
                    message = "${it.wert} ist als angewandt markiert, seine Wirkung steckt aber in den Nutzdaten.",
                )
            }
    }

    @Test
    fun verteilEventsMitGeheimnisSindNichtOeffentlich() {
        // TDD 8.3: Die Schlüsselmatrix geht verschlossen an den Verteiler, ein
        // Schlüsselpaket verschlossen an genau einen Spieler. Wären sie PUBLIC,
        // könnte der Host die Verteilung mitlesen.
        listOf(EventType.DEAL_MATRIX_DELIVERED, EventType.DEAL_KEY_PACKET).forEach {
            assertEquals(setOf(Visibility.PRIVATE), it.erlaubteSichtbarkeiten, it.wert)
        }
    }

    @Test
    fun unbekannteWerteLiefernNull() {
        // Grundlage von TDD 5.5 — ein fremdes Event wird gespeichert, nicht gedeutet.
        assertNull(EventType.vonWert("etwas_aus_version_2"))
        assertNull(EventClass.vonWert("irgendwas"))
        assertNull(Visibility.vonWert("SEMI_PUBLIC"))
    }

    @Test
    fun bekannteWerteWerdenErkannt() {
        assertEquals(EventType.IDENTITY_REVEALED, EventType.vonWert("identity_revealed"))
        assertEquals(EventClass.STATE, EventClass.vonWert("state"))
        assertEquals(Visibility.PLAYER_ONLY, Visibility.vonWert("PLAYER_ONLY"))
    }

    @Test
    fun nurPublicIstOeffentlich() {
        assertTrue(Visibility.PUBLIC.istOeffentlich)
        assertTrue(!Visibility.PLAYER_ONLY.istOeffentlich)
        assertTrue(!Visibility.PRIVATE.istOeffentlich)
    }
}
