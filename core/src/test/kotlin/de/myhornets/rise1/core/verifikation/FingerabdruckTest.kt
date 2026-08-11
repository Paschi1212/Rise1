package de.myhornets.rise1.core.verifikation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-071 — der Fingerabdruck aus [[ADR-002A Key Verification]].
 *
 * ## Woher die erwarteten Werte kommen
 *
 * Die fünf Festwerte unten wurden mit einer **unabhängigen Umsetzung** derselben
 * Vorschrift berechnet (Python, `hashlib.sha256` plus eigene Crockford-Kodierung)
 * und hier eingetragen. Das ist der Unterschied zwischen einem Test und einer
 * Tautologie: Ein Test, der die Erwartung aus derselben Funktion holt, die er
 * prüft, ist immer grün — auch dann, wenn die Ableitung falsch ist.
 *
 * Ändert sich einer dieser Werte, ist entweder die Ableitung kaputt oder sie
 * wurde absichtlich geändert. Im zweiten Fall gehört eine ADR dazu: Ein
 * geänderter Fingerabdruck bedeutet, dass eine Partie mit gemischten
 * App-Versionen die Zeremonie nicht mehr besteht.
 */
class FingerabdruckTest {

    private val schluessel = byteArrayOf(1, 2, 3, 4)

    // ── Feste Werte gegen eine unabhängige Umsetzung ─────────────────────────

    @Test
    fun einTeilnehmerFingerabdruckStimmtMitDerUnabhaengigenBerechnung() {
        assertEquals(
            "7QZE-M8XY-XPC0",
            Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-1", schluessel).lesbar,
        )
    }

    @Test
    fun einHostFingerabdruckStimmtMitDerUnabhaengigenBerechnung() {
        assertEquals(
            "DZVX-9SYJ-TW95",
            Fingerabdruck.vonHostzertifikat("m-1", schluessel).lesbar,
        )
    }

    @Test
    fun derTischcodeStimmtMitDerUnabhaengigenBerechnung() {
        assertEquals(
            "CQTN-12AK-J433",
            Fingerabdruck.tischcode(
                "m-1",
                listOf(byteArrayOf(1, 1), byteArrayOf(2, 2), byteArrayOf(3, 3)),
            ).lesbar,
        )
    }

    // ── Die Bindungen aus ADR-002A 3.1 ───────────────────────────────────────

    @Test
    fun einAndererSitzplatzErgibtEinenAnderenFingerabdruck() {
        // Sonst ließe sich ein Fingerabdruck von einem Sitzplatz auf einen
        // anderen übertragen — und die Zeremonie wäre umgehbar.
        assertEquals(
            "BFC5-ZA7Y-40F8",
            Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-2", schluessel).lesbar,
        )
    }

    @Test
    fun eineAnderePartieErgibtEinenAnderenFingerabdruck() {
        // Sonst wäre ein Fingerabdruck aus einer früheren Partie wiederverwendbar.
        assertEquals(
            "SZ3M-BEWV-3V1N",
            Fingerabdruck.vonTeilnehmerschluessel("m-2", "p-1", schluessel).lesbar,
        )
    }

    @Test
    fun hostUndTeilnehmerSindNichtVerwechselbar() {
        // Getrennte Verwendungszwecke. Ohne eigene Präfixe wären zwei
        // Zeremonien mit demselben Zeichenvorrat gegeneinander austauschbar.
        assertFalse(
            Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-1", schluessel)
                .stimmtUeberein(Fingerabdruck.vonHostzertifikat("m-1", schluessel)),
        )
    }

    @Test
    fun einAndererSchluesselErgibtEinenAnderenFingerabdruck() {
        assertFalse(
            Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-1", byteArrayOf(1, 2, 3, 4))
                .stimmtUeberein(Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-1", byteArrayOf(1, 2, 3, 5))),
        )
    }

    // ── Der Tischcode ────────────────────────────────────────────────────────

    @Test
    fun derTischcodeHaengtNichtAnDerEintreffreihenfolge() {
        // ADR-002A 3.3: „Alle Geräte zeigen dieselben zwölf Zeichen." Sie haben
        // die Schlüssel in verschiedener Reihenfolge empfangen — wäre der Code
        // davon abhängig, zeigte jedes Gerät einen anderen.
        val a = listOf(byteArrayOf(3, 3), byteArrayOf(1, 1), byteArrayOf(2, 2))
        val b = listOf(byteArrayOf(1, 1), byteArrayOf(2, 2), byteArrayOf(3, 3))
        assertEquals(Fingerabdruck.tischcode("m-1", a), Fingerabdruck.tischcode("m-1", b))
    }

    @Test
    fun einFehlenderSchluesselAendertDenTischcode() {
        val alle = listOf(byteArrayOf(1, 1), byteArrayOf(2, 2), byteArrayOf(3, 3))
        assertFalse(
            Fingerabdruck.tischcode("m-1", alle)
                .stimmtUeberein(Fingerabdruck.tischcode("m-1", alle.dropLast(1))),
        )
    }

    // ── Form ─────────────────────────────────────────────────────────────────

    @Test
    fun einFingerabdruckHatZwoelfZeichenInDreiGruppen() {
        val fp = Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-1", schluessel)
        assertEquals(12, fp.zeichen.length)
        assertEquals(14, fp.lesbar.length)
        assertEquals(3, fp.lesbar.split("-").size)
        assertTrue(fp.lesbar.split("-").all { it.length == 4 })
    }

    @Test
    fun dasAlphabetLaesstDieVerwechselbarenZeichenWeg() {
        assertEquals(32, Fingerabdruck.ALPHABET.length)
        listOf('I', 'L', 'O', 'U').forEach {
            assertFalse(it in Fingerabdruck.ALPHABET, "$it steht im Alphabet, gehört aber nicht hinein.")
        }
    }

    @Test
    fun jedesZeichenStammtAusDemAlphabet() {
        // Über viele Eingaben, damit nicht nur ein glücklicher Fall geprüft wird.
        (1..500).forEach { i ->
            val fp = Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-$i", schluessel)
            assertTrue(
                fp.zeichen.all { it in Fingerabdruck.ALPHABET },
                "Fremdes Zeichen in ${fp.lesbar}",
            )
        }
    }

    // ── Eingabe ──────────────────────────────────────────────────────────────

    @Test
    fun derLesbareCodeLaesstSichWiederEinlesen() {
        val fp = Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-1", schluessel)
        assertEquals(fp, Fingerabdruck.ausEingabe(fp.lesbar))
        assertEquals(fp, Fingerabdruck.ausEingabe(fp.zeichen))
    }

    @Test
    fun beimAbtippenVerwechselteZeichenWerdenGeradegezogen() {
        // Genau dafür ist Crockford gemacht: `KLF2` statt `K1F2` soll
        // durchkommen, nicht an der Zeremonie scheitern.
        val original = Fingerabdruck.ausEingabe("7QZE-M8XY-XPC0")
        assertEquals(original, Fingerabdruck.ausEingabe("7qze m8xy xpc0"))
        // `I` und `L` werden beide zu `1` — deshalb ist die Entsprechung von
        // `IL34` gerade `1134` und nicht `1234`.
        assertEquals(
            Fingerabdruck.ausEingabe("1134-5678-9ABC"),
            Fingerabdruck.ausEingabe("IL34-5678-9ABC"),
        )
        assertEquals(
            Fingerabdruck.ausEingabe("0234-5678-9ABC"),
            Fingerabdruck.ausEingabe("O234-5678-9ABC"),
        )
    }

    @Test
    fun eineUneindeutigeEingabeWirdAbgewiesen() {
        assertFailsWith<IllegalArgumentException> { Fingerabdruck.ausEingabe("zu kurz") }
        assertFailsWith<IllegalArgumentException> { Fingerabdruck.ausEingabe("7QZE-M8XY-XPC0-ZUVIEL") }
        // `U` gibt es in Crockford nicht und wird auch nicht ersetzt.
        assertFailsWith<IllegalArgumentException> { Fingerabdruck.ausEingabe("7QZE-M8XY-XPCU") }
    }

    // ── Vergleich ────────────────────────────────────────────────────────────

    @Test
    fun gleichIstGleichUndUngleichIstUngleich() {
        val a = Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-1", schluessel)
        val b = Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-1", schluessel)
        assertTrue(a.stimmtUeberein(b))
        assertEquals(a, b)
        assertFalse(a.stimmtUeberein(Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-9", schluessel)))
    }

    @Test
    fun einPraefixReichtNicht() {
        // Der wichtigste Test dieser Datei. Verglichen werden alle zwölf
        // Zeichen; ein Vergleich über ein Präfix machte aus 60 Bit zwanzig,
        // ohne dass es jemand merkte.
        val echt = Fingerabdruck.ausEingabe("7QZE-M8XY-XPC0")
        val fastGleich = Fingerabdruck.ausEingabe("7QZE-M8XY-XPC1")
        assertFalse(echt.stimmtUeberein(fastGleich))
        assertFalse(echt == fastGleich)
    }

    // ── Pflichtangaben ───────────────────────────────────────────────────────

    @Test
    fun ohneBindungGibtEsKeinenFingerabdruck() {
        assertFailsWith<IllegalArgumentException> {
            Fingerabdruck.vonTeilnehmerschluessel("", "p-1", schluessel)
        }
        assertFailsWith<IllegalArgumentException> {
            Fingerabdruck.vonTeilnehmerschluessel("m-1", "", schluessel)
        }
        assertFailsWith<IllegalArgumentException> {
            Fingerabdruck.vonHostzertifikat("", schluessel)
        }
        assertFailsWith<IllegalArgumentException> {
            Fingerabdruck.tischcode("m-1", emptyList())
        }
    }

    @Test
    fun ohneSchluesselGibtEsKeinenFingerabdruck() {
        assertFailsWith<IllegalArgumentException> {
            Fingerabdruck.vonTeilnehmerschluessel("m-1", "p-1", ByteArray(0))
        }
        assertFailsWith<IllegalArgumentException> {
            Fingerabdruck.vonHostzertifikat("m-1", ByteArray(0))
        }
    }
}
