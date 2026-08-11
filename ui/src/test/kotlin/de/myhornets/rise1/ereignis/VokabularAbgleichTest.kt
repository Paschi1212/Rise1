package de.myhornets.rise1.ereignis

import de.myhornets.rise1.core.event.EventClass
import de.myhornets.rise1.core.event.EventType
import de.myhornets.rise1.core.event.Visibility
import de.myhornets.rise1.store.EventClasses
import de.myhornets.rise1.store.EventTypen
import de.myhornets.rise1.store.Visibilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S2 — der Wächter über die bewusste Doppelung.
 *
 * ## Wogegen er schützt
 *
 * `:core` führt das Event-Vokabular als `enum`. `:store` führt dieselben
 * Zeichenketten noch einmal als `object`, weil es `:core` nicht kennen darf —
 * `allowedModuleEdges["store"] = emptySet()`, „eine Ablage ist kein Mitspieler"
 * ([[ADR-003 Persistenzmodul]]).
 *
 * Diese Doppelung ist in [[T-025 Event-Log-Fundament]] als NEEDS DECISION
 * vermerkt. Sie ist damit **erklärt**, aber nicht **abgesichert**: Ändert
 * jemand `Visibility.PLAYER_ONLY` und vergisst `Visibilities.PLAYER_ONLY`,
 * schreibt die App still Events, die der Trigger in der Datenbank abweist —
 * oder schlimmer, die er durchlässt, weil auch er den alten Wert kennt.
 *
 * `:ui` ist das einzige Modul, das beide Seiten sieht. Also steht der Wächter
 * hier. Er ersetzt die Entscheidung nicht, er macht das Warten darauf
 * ungefährlich.
 *
 * Reines JVM: Die verglichenen Werte sind Zeichenketten in gewöhnlichen
 * Kotlin-Objekten, kein Android wird angefasst. Damit läuft der Test in
 * `checkAll` mit.
 */
class VokabularAbgleichTest {

    @Test
    fun dieEventKlassenStimmenUeberein() {
        assertEquals(
            EventClass.entries.map { it.wert }.sorted(),
            EventClasses.ALLE.sorted(),
            "`:core`.EventClass und `:store`.EventClasses sind auseinandergelaufen. " +
                "Nur `state` verändert die Projektion (TDD 5.2) — wenn beide Seiten " +
                "verschiedene Zeichenketten dafür halten, verändert sie gar nichts.",
        )
    }

    @Test
    fun dieSichtbarkeitenStimmenUeberein() {
        assertEquals(
            Visibility.entries.map { it.wert }.sorted(),
            Visibilities.ALLE.sorted(),
            "`:core`.Visibility und `:store`.Visibilities sind auseinandergelaufen. " +
                "Der Trigger `match_event_sichtbarkeit` vergleicht auf 'PUBLIC' und " +
                "'PRIVATE' als Text — ein abweichender Wert umgeht ihn lautlos (TDD 5.2/7.3).",
        )
    }

    @Test
    fun dieAusgewertetenTypenStimmenUeberein() {
        assertEquals(
            EventType.angewandte().map { it.wert }.sorted(),
            EventTypen.ANGEWANDT.sorted(),
            "Die Faltung in `:projection` und die Fortschreibung in `:store` werten " +
                "verschiedene Typen aus. Damit ergäben Normalbetrieb und Neuaufbau " +
                "verschiedene Zustände — und keiner der beiden wäre nachweislich falsch.",
        )
    }

    @Test
    fun jederZeichenkettenwertAusStoreIstImVokabular() {
        EventTypen.ANGEWANDT.forEach { wert ->
            assertTrue(
                EventType.vonWert(wert) != null,
                "`$wert` steht in `:store`, aber in keinem Event-Typ von `:core`.",
            )
        }
    }

    @Test
    fun dieVerglichenenListenSindNichtLeer() {
        // Ohne diese Zusicherung wären die vier Tests darüber grün, sobald
        // beide Seiten leer sind. Dieselbe Familie von Fehlern wie `NO-SOURCE`.
        assertTrue(EventClasses.ALLE.size == 3)
        assertTrue(Visibilities.ALLE.size == 3)
        assertTrue(EventTypen.ANGEWANDT.size >= 7, "S3 wertet sieben Typen aus.")
    }
}
