package de.myhornets.rise1.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-072 — die Rahmung.
 *
 * ## Der Test, um den es hier geht
 *
 * [dieAufteilungDerEingabeIstGleichgueltig]. TCP ist ein Strom; was als ein
 * `send` losgeht, kommt als zwei Stücke an. Ein Protokoll, das das nicht
 * aushält, funktioniert im WLAN des Entwicklers und am Küchentisch nicht — und
 * der Fehler sieht dann aus wie ein Netzproblem.
 *
 * Der zweitwichtigste ist [eineRiesigeLaengenangabeWirdNichtGeglaubt]: Die
 * Länge kommt vom Gegenüber, und wer sie glaubt, lässt sich den Speicher
 * volllaufen — von einem Gerät, das danach kein einziges Byte mehr schickt.
 *
 * ## Woher die Byte-Erwartungen kommen
 *
 * Das Kopf-Layout wurde mit einer unabhängigen Umsetzung des Formats
 * nachgerechnet (Python) und hier als feste Bytes eingetragen. Ein Format, das
 * nur gegen den eigenen Kodierer geprüft ist, ist kein Format, sondern eine
 * Absprache mit sich selbst.
 */
class RahmenTest {

    private fun rahmen(bytes: ByteArray) = Rahmenleser().fuettere(bytes)

    private fun nurRahmen(gelesen: List<Gelesenes>) = gelesen.filterIsInstance<Rahmen>()

    // ── Das Format auf der Leitung ──────────────────────────────────────────

    @Test
    fun derKopfSiehtGenauSoAusWieVereinbart() {
        val bytes = Rahmencodec.kodiere(Rahmen(Rahmentyp.EREIGNIS, "hallo".toByteArray()))
        assertEquals(13, bytes.size, "8 Byte Kopf plus 5 Byte Nutzlast.")
        assertEquals('R'.code.toByte(), bytes[0])
        assertEquals('1'.code.toByte(), bytes[1])
        assertEquals(1, bytes[2], "Protokollversion")
        assertEquals(3, bytes[3], "Kennung von EREIGNIS")
        // Länge 5, big-endian
        assertEquals(0, bytes[4])
        assertEquals(0, bytes[5])
        assertEquals(0, bytes[6])
        assertEquals(5, bytes[7])
        assertEquals("hallo", String(bytes.copyOfRange(8, 13)))
    }

    @Test
    fun dieKennungenLiegenFestUndSindEindeutig() {
        // Sie stehen auf der Leitung. Verschöben sie sich beim Umsortieren des
        // `enum`, spräche eine neue Fassung stillschweigend etwas anderes.
        assertEquals(1, Rahmentyp.HANDSHAKE.kennung)
        assertEquals(4, Rahmentyp.HERZSCHLAG.kennung)
        val kennungen = Rahmentyp.entries.map { it.kennung }
        assertEquals(kennungen.size, kennungen.toSet().size)
    }

    @Test
    fun einRundlaufErgibtDenselbenRahmen() {
        val original = Rahmen(Rahmentyp.SCHNAPPSCHUSS, byteArrayOf(1, 2, 3, 0, -1))
        assertEquals(listOf(original), nurRahmen(rahmen(Rahmencodec.kodiere(original))))
    }

    // ── Der Kern: Strom statt Nachrichten ───────────────────────────────────

    @Test
    fun dieAufteilungDerEingabeIstGleichgueltig() {
        val strom = Rahmencodec.kodiere(Rahmen(Rahmentyp.HANDSHAKE, ByteArray(100) { 'a'.code.toByte() })) +
            Rahmencodec.kodiere(Rahmen(Rahmentyp.HERZSCHLAG, ByteArray(0))) +
            Rahmencodec.kodiere(Rahmen(Rahmentyp.EREIGNIS, "xyz".toByteArray()))

        val erwartet = listOf(
            Rahmen(Rahmentyp.HANDSHAKE, ByteArray(100) { 'a'.code.toByte() }),
            Rahmen(Rahmentyp.HERZSCHLAG, ByteArray(0)),
            Rahmen(Rahmentyp.EREIGNIS, "xyz".toByteArray()),
        )

        assertEquals(erwartet, nurRahmen(rahmen(strom)), "am Stück")

        listOf(1, 2, 3, 7, 13, 64, 127).forEach { groesse ->
            val leser = Rahmenleser()
            val gesammelt = mutableListOf<Gelesenes>()
            var i = 0
            while (i < strom.size) {
                gesammelt += leser.fuettere(strom.copyOfRange(i, minOf(i + groesse, strom.size)))
                i += groesse
            }
            assertEquals(erwartet, nurRahmen(gesammelt), "in Brocken zu $groesse Bytes")
        }
    }

    @Test
    fun einUnvollstaendigerRahmenErgibtNochNichts() {
        val bytes = Rahmencodec.kodiere(Rahmen(Rahmentyp.EREIGNIS, "hallo".toByteArray()))
        val leser = Rahmenleser()
        assertTrue(leser.fuettere(bytes.copyOfRange(0, 4)).isEmpty(), "halber Kopf")
        assertTrue(leser.fuettere(bytes.copyOfRange(4, 10)).isEmpty(), "halbe Nutzlast")
        // 4 + 6 gefütterte Bytes liegen im Puffer — `angesammelt` ist der
        // ganze unvollständige Rest, nicht nur der Nutzlastanteil.
        assertEquals(10, leser.angesammelt)
        assertEquals(
            listOf(Rahmen(Rahmentyp.EREIGNIS, "hallo".toByteArray())),
            nurRahmen(leser.fuettere(bytes.copyOfRange(10, bytes.size))),
        )
        assertEquals(0, leser.angesammelt, "Nach der Auslieferung ist der Puffer leer.")
    }

    @Test
    fun einLeererRahmenIstEinRahmen() {
        // Ein Herzschlag trägt nichts. Er darf trotzdem nicht verschwinden.
        assertEquals(
            listOf(Rahmen(Rahmentyp.HERZSCHLAG, ByteArray(0))),
            nurRahmen(rahmen(Rahmencodec.kodiere(Rahmen(Rahmentyp.HERZSCHLAG, ByteArray(0))))),
        )
    }

    @Test
    fun dieObergrenzeSelbstGehtNochDurch() {
        val gross = ByteArray(Rahmencodec.MAX_NUTZLAST) { 'z'.code.toByte() }
        val gelesen = nurRahmen(rahmen(Rahmencodec.kodiere(Rahmen(Rahmentyp.SCHNAPPSCHUSS, gross))))
        assertEquals(1, gelesen.size)
        assertEquals(Rahmencodec.MAX_NUTZLAST, gelesen.single().nutzlast.size)
    }

    @Test
    fun eineZuGrosseNutzlastLaesstSichGarNichtErstBauen() {
        assertFailsWith<IllegalArgumentException> {
            Rahmen(Rahmentyp.SCHNAPPSCHUSS, ByteArray(Rahmencodec.MAX_NUTZLAST + 1))
        }
    }

    // ── Vorwärtskompatibilität ──────────────────────────────────────────────

    @Test
    fun einUnbekannterTypWirdUebersprungenUndNichtVerschluckt() {
        // Dasselbe Prinzip wie TDD 5.5: Ein älteres Gerät darf an einem
        // neueren nicht zerbrechen — aber der Rahmen danach muss ankommen.
        val fremd = Rahmencodec.kodiere(Rahmen(Rahmentyp.EREIGNIS, "aus der Zukunft".toByteArray()))
        fremd[3] = 99 // eine Kennung, die es nicht gibt
        val strom = fremd + Rahmencodec.kodiere(Rahmen(Rahmentyp.HERZSCHLAG, ByteArray(0)))

        val gelesen = rahmen(strom)
        assertEquals(2, gelesen.size)
        val unbekannt = gelesen[0] as UnbekannterRahmen
        assertEquals(99, unbekannt.kennung)
        assertEquals("aus der Zukunft".length, unbekannt.laenge)
        assertEquals(Rahmen(Rahmentyp.HERZSCHLAG, ByteArray(0)), gelesen[1])
    }

    @Test
    fun eineUnbekannteKennungErgibtKeinenTyp() {
        assertEquals(null, Rahmentyp.vonKennung(99))
        assertEquals(Rahmentyp.EREIGNIS, Rahmentyp.vonKennung(3))
    }

    // ── Protokollfehler: die Verbindung ist zu trennen ──────────────────────

    @Test
    fun eineFalscheKennmarkeIstEinProtokollfehler() {
        val bytes = Rahmencodec.kodiere(Rahmen(Rahmentyp.EREIGNIS, "x".toByteArray()))
        bytes[0] = 'X'.code.toByte()
        val fehler = assertFailsWith<Rahmenfehler> { rahmen(bytes) }
        assertTrue(fehler.message!!.contains("Kennmarke"))
    }

    @Test
    fun eineFremdeVersionIstEinProtokollfehler() {
        // Eine andere Version ist eine andere Sprache, kein unbekannter Rahmen.
        val bytes = Rahmencodec.kodiere(Rahmen(Rahmentyp.EREIGNIS, "x".toByteArray()))
        bytes[2] = 9
        assertFailsWith<Rahmenfehler> { rahmen(bytes) }
    }

    @Test
    fun eineRiesigeLaengenangabeWirdNichtGeglaubt() {
        // Der Angriff ohne Aufwand: acht Bytes schicken, dann schweigen, und
        // beim Gegenüber wächst der Puffer.
        val boese = byteArrayOf(
            'R'.code.toByte(), '1'.code.toByte(), 1, 3,
            0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        val fehler = assertFailsWith<Rahmenfehler> { rahmen(boese) }
        assertTrue(fehler.message!!.contains("Obergrenze"))
    }

    @Test
    fun einByteUeberDerObergrenzeReichtSchon() {
        val n = Rahmencodec.MAX_NUTZLAST + 1
        val boese = byteArrayOf(
            'R'.code.toByte(), '1'.code.toByte(), 1, 3,
            (n ushr 24 and 0xFF).toByte(), (n ushr 16 and 0xFF).toByte(),
            (n ushr 8 and 0xFF).toByte(), (n and 0xFF).toByte(),
        )
        assertFailsWith<Rahmenfehler> { rahmen(boese) }
    }

    @Test
    fun nachEinemProtokollfehlerIstDerLeserUnbrauchbar() {
        // Wer falsch gerahmte Bytes schickt, spricht ein anderes Protokoll oder
        // versucht etwas. Weiterzulesen hieße, ihm eine zweite Gelegenheit zu geben.
        val leser = Rahmenleser()
        assertFailsWith<Rahmenfehler> { leser.fuettere(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)) }
        assertFailsWith<IllegalStateException> { leser.fuettere(byteArrayOf(1)) }
    }

    // ── Kein Inhalt in Protokollen ──────────────────────────────────────────

    @Test
    fun einRahmenZeigtSeineNutzlastNichtImText() {
        val text = Rahmen(Rahmentyp.EREIGNIS, "GEHEIMNIS".toByteArray()).toString()
        assertFalse(text.contains("GEHEIMNIS"))
        assertTrue(text.contains("EREIGNIS") && text.contains("9"))
    }

    @Test
    fun zweiGleicheRahmenSindGleich() {
        assertEquals(
            Rahmen(Rahmentyp.EREIGNIS, byteArrayOf(1, 2, 3)),
            Rahmen(Rahmentyp.EREIGNIS, byteArrayOf(1, 2, 3)),
        )
        assertFalse(
            Rahmen(Rahmentyp.EREIGNIS, byteArrayOf(1, 2, 3)) ==
                Rahmen(Rahmentyp.HERZSCHLAG, byteArrayOf(1, 2, 3)),
        )
    }
}
