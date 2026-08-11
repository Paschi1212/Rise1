package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.Payload
import de.myhornets.rise1.core.event.Visibility
import de.myhornets.rise1.transport.Rahmen
import de.myhornets.rise1.transport.Rahmencodec
import de.myhornets.rise1.transport.Rahmenleser
import de.myhornets.rise1.transport.Rahmentyp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [[ADR-007 Nutzlastformat der Sitzungsrahmen]] — das Format auf der Leitung.
 *
 * ## Die Tests, um die es hier geht
 *
 * [derTokenStehtInKeinerFehlermeldung]. Der `rejoin_token` muss über die
 * Leitung, sonst gäbe es keinen Nachweis (TDD 9.3) — und genau deshalb ist
 * jede Fehlermeldung, die gelesene Bytes mitliefert, eine Zeitbombe. Ein
 * Protokollfehler landet im Log, das Log landet im Fehlerbericht.
 *
 * [eineRiesigeAnzahlWirdNichtGeglaubt] und [eineRiesigeTextlaengeWirdNichtGeglaubt]:
 * derselbe Angriff wie in `T-072`, eine Ebene höher. Acht Bytes schicken, eine
 * Milliarde behaupten, schweigen.
 *
 * [einPublicEventMitChiffratKommtNichtDurch] prüft, dass die Trennung aus
 * TDD 5.2 auch für **fremde Bytes** gilt und nicht nur für Werte, die dieses
 * Gerät selbst gebaut hat.
 *
 * ## Woher die Byte-Erwartungen kommen
 *
 * Die feindlichen Nachrichten werden mit [nutzlast] **unabhängig** vom
 * Kodierer zusammengesetzt — sonst prüfte der Test den Kodierer gegen sich
 * selbst und ein gemeinsamer Denkfehler bliebe unsichtbar.
 */
class SitzungsprotokollTest {

    private val token = "0123456789abcdef-geheimer-token"

    private fun gesuch() = Wiedereinstiegsgesuch("m-1", "p-1", token, "d-a", 7)

    // ── Ein eigener, kleiner Schreiber für feindliche Eingaben ──────────────

    private class Bau {
        val bytes = mutableListOf<Byte>()
        fun b(wert: Int) = apply { bytes += wert.toByte() }
        fun lang(wert: Long) = apply { for (i in 7 downTo 0) bytes += ((wert ushr (i * 8)) and 0xFF).toByte() }
        fun vier(wert: Long) = apply { for (i in 3 downTo 0) bytes += ((wert ushr (i * 8)) and 0xFF).toByte() }
        fun text(wert: String) = apply {
            val roh = wert.toByteArray(Charsets.UTF_8)
            bytes += ((roh.size ushr 8) and 0xFF).toByte()
            bytes += (roh.size and 0xFF).toByte()
            roh.forEach { bytes += it }
        }

        fun nichts() = b(0)
        fun etwas() = b(1)
        fun fertig(): ByteArray = bytes.toByteArray()
    }

    /** Baut eine Nutzlast mit dem Fassungsbyte davor. */
    private fun nutzlast(fassung: Int = 1, bau: Bau.() -> Unit): ByteArray =
        Bau().b(fassung).apply(bau).fertig()

    /** Ein Event, wie es der Leser erwartet — hier von Hand, damit man es verbiegen kann. */
    private fun Bau.event(
        visibility: Int,
        empfaenger: String?,
        payloadArt: Int,
        payload: Bau.() -> Unit = {},
    ) = apply {
        text("e-1")
        text("m-1")
        etwas().lang(1)          // seq
        text("d-host")
        lang(1)                  // origin_seq
        lang(1)                  // lamport_clock
        lang(1_000)              // occurred_at
        etwas().lang(1_000)      // recorded_at
        text("turn_started")
        b(1)                     // event_class = state
        nichts()                 // actor
        nichts()                 // target
        b(visibility)
        if (empfaenger == null) nichts() else etwas().text(empfaenger)
        vier(1)                  // payload_schema_version
        b(payloadArt)
        payload()
        nichts()                 // is_undone
        nichts()                 // undone_by
        nichts()                 // has_conflict
    }

    // ── Rundläufe ───────────────────────────────────────────────────────────

    @Test
    fun einGesuchUeberstehtDenRundlauf() {
        val rahmen = Sitzungsprotokoll.kodiere(gesuch())
        assertEquals(Rahmentyp.HANDSHAKE, rahmen.typ)
        assertEquals(gesuch(), Sitzungsprotokoll.liesGesuch(rahmen))
    }

    @Test
    fun eineAngenommeneAntwortUeberstehtDenRundlauf() {
        val antwort = Wiedereinstiegsantwort.Angenommen("s-1", "s-alt", 42)
        val rahmen = Sitzungsprotokoll.kodiere(antwort)
        assertEquals(Rahmentyp.HANDSHAKE_ANTWORT, rahmen.typ)
        assertEquals<Wiedereinstiegsantwort>(antwort, Sitzungsprotokoll.liesAntwort(rahmen))
    }

    @Test
    fun eineAntwortOhneAbgeloesteSitzungUeberstehtDenRundlauf() {
        val antwort = Wiedereinstiegsantwort.Angenommen("s-1", null, 0)
        assertEquals<Wiedereinstiegsantwort>(
            antwort,
            Sitzungsprotokoll.liesAntwort(Sitzungsprotokoll.kodiere(antwort)),
        )
    }

    @Test
    fun jederAblehnungsgrundUeberstehtDenRundlauf() {
        // Alle fünf, nicht einer als Stellvertreter: Die Kennungen stehen auf
        // der Leitung, und eine vertauschte machte aus [Partie ist vorbei] ein
        // [Zugriff verweigert].
        Ablehnungsgrund.entries.forEach { grund ->
            val antwort = Wiedereinstiegsantwort.Abgelehnt(grund)
            val rahmen = Sitzungsprotokoll.kodiere(antwort)
            assertEquals(Rahmentyp.ABLEHNUNG, rahmen.typ, "Eine Ablehnung ist ein eigener Rahmentyp.")
            assertEquals<Wiedereinstiegsantwort>(antwort, Sitzungsprotokoll.liesAntwort(rahmen))
        }
    }

    @Test
    fun einDeltaMitAllenSichtbarkeitenUeberstehtDenRundlauf() {
        val delta = Aufholung.Delta(
            "m-1", "p-1", 0, 4,
            listOf(
                ereignis(1),
                ereignis(2, typ = "note_added", visibility = Visibility.PLAYER_ONLY),
                ereignis(3, typ = "deal_key_packet", visibility = Visibility.PRIVATE, empfaenger = "p-1"),
                ereignis(4),
            ),
        )
        val rahmen = Sitzungsprotokoll.kodiere(delta)
        assertEquals(Rahmentyp.DELTA, rahmen.typ)
        assertEquals(delta, Sitzungsprotokoll.liesDelta(rahmen))
    }

    @Test
    fun einLeeresDeltaIstEineGueltigeNachricht() {
        val delta = Aufholung.Delta("m-1", "p-1", 5, 9, emptyList())
        assertEquals(delta, Sitzungsprotokoll.liesDelta(Sitzungsprotokoll.kodiere(delta)))
    }

    @Test
    fun einChiffratKommtByteGleichAn() {
        // Der Kern der Sichtbarkeit: Der Weg über die Leitung darf ein Chiffrat
        // nicht anfassen — kein Base64, kein Umkodieren, kein Abschneiden.
        val geheim = ByteArray(500) { (it % 256 - 128).toByte() }
        val original = ereignis(1, visibility = Visibility.PRIVATE, empfaenger = "p-1")
            .copy(payload = Payload.Chiffrat(geheim, "aes-gcm-256"))
        val delta = Aufholung.Delta("m-1", "p-1", 0, 1, listOf(original))

        val gelesen = Sitzungsprotokoll.liesDelta(Sitzungsprotokoll.kodiere(delta))
        val zurueck = gelesen.events.single().payload as Payload.Chiffrat
        assertTrue(geheim.contentEquals(zurueck.bytes))
        assertEquals("aes-gcm-256", zurueck.encScheme)
    }

    @Test
    fun einUnbekannterEventTypUeberlebtDieLeitung() {
        // TDD 5.5: Ein Gerät mit neuerer App darf einen Typ schicken, den dieses
        // nicht kennt. Eine Kennung statt eines Strings hätte genau das verhindert.
        val fremd = ereignis(1, typ = "quantum_flux_applied")
        val delta = Aufholung.Delta("m-1", "p-1", 0, 1, listOf(fremd))
        val gelesen = Sitzungsprotokoll.liesDelta(Sitzungsprotokoll.kodiere(delta))
        assertEquals("quantum_flux_applied", gelesen.events.single().type)
        assertEquals(null, gelesen.events.single().typKennung)
    }

    @Test
    fun derWegDurchDieRahmungFunktioniertAuch() {
        // T-072 und ADR-007 zusammen: kodieren, durch den Strom schicken,
        // wieder herauslesen. Beide sind einzeln geprüft; das hier ist die Naht.
        val delta = Aufholung.Delta("m-1", "p-1", 0, 2, listOf(ereignis(1), ereignis(2)))
        val strom = Rahmencodec.kodiere(Sitzungsprotokoll.kodiere(delta))

        val leser = Rahmenleser()
        val rahmen = leser.fuettere(strom).filterIsInstance<Rahmen>().single()
        assertEquals(delta, Sitzungsprotokoll.liesDelta(rahmen))
    }

    // ── Das Geheimnis ───────────────────────────────────────────────────────

    @Test
    fun derTokenStehtInKeinerFehlermeldung() {
        // Eine abgeschnittene Nachricht, die den Token noch enthält: Der Fehler
        // darf ihn nicht wiederholen.
        val vollstaendig = Sitzungsprotokoll.kodiere(gesuch()).nutzlast
        val abgeschnitten = vollstaendig.copyOfRange(0, vollstaendig.size - 4)
        val fehler = assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesGesuch(Rahmen(Rahmentyp.HANDSHAKE, abgeschnitten))
        }
        assertFalse(fehler.message!!.contains(token), "Ein Geheimnis gehört in keine Meldung.")
        assertTrue(fehler.message!!.contains("device_uid") || fehler.message!!.contains("last_seq_seen"))
    }

    @Test
    fun einGesuchZeigtSeinenTokenAuchNichtAlsRahmen() {
        val text = Sitzungsprotokoll.kodiere(gesuch()).toString()
        assertFalse(text.contains(token))
    }

    // ── Feindliche Eingaben ─────────────────────────────────────────────────

    @Test
    fun eineFremdeFassungWirdNichtGeraten() {
        val bytes = nutzlast(fassung = 9) { text("m-1") }
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesGesuch(Rahmen(Rahmentyp.HANDSHAKE, bytes))
        }
    }

    @Test
    fun eineLeereNutzlastIstKeineNachricht() {
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesGesuch(Rahmen(Rahmentyp.HANDSHAKE, ByteArray(0)))
        }
    }

    @Test
    fun ueberzaehligeBytesAmEndeWerdenAbgelehnt() {
        // Sonst wären sich zwei Geräte über den Inhalt einig und über die Länge
        // nicht — die Stelle, an der Protokolle auseinanderlaufen.
        val gut = Sitzungsprotokoll.kodiere(gesuch()).nutzlast
        val zuviel = gut + byteArrayOf(0, 0)
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesGesuch(Rahmen(Rahmentyp.HANDSHAKE, zuviel))
        }
    }

    @Test
    fun eineRiesigeAnzahlWirdNichtGeglaubt() {
        // Zwanzig Bytes schicken, zwei Milliarden Events behaupten, schweigen.
        val boese = nutzlast {
            text("m-1"); text("p-1")
            lang(0); lang(9)
            vier(2_000_000_000)
        }
        val fehler = assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesDelta(Rahmen(Rahmentyp.DELTA, boese))
        }
        assertTrue(fehler.message!!.contains("event_count"))
    }

    @Test
    fun eineRiesigeTextlaengeWirdNichtGeglaubt() {
        // 0xFFFF angekündigt, zwei Bytes geliefert.
        val boese = nutzlast { b(0xFF); b(0xFF); b(1); b(2) }
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesGesuch(Rahmen(Rahmentyp.HANDSHAKE, boese))
        }
    }

    @Test
    fun eineAbgeschnitteneZahlWirdBemerkt() {
        val boese = nutzlast {
            text("m-1"); text("p-1"); text("token-lang-genug-1234"); text("d-a")
            b(0); b(0); b(0) // von acht Bytes nur drei
        }
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesGesuch(Rahmen(Rahmentyp.HANDSHAKE, boese))
        }
    }

    @Test
    fun einFalscherRahmentypWirdNichtGedeutet() {
        val rahmen = Rahmen(Rahmentyp.HERZSCHLAG, ByteArray(0))
        assertFailsWith<Protokollfehler> { Sitzungsprotokoll.liesGesuch(rahmen) }
        assertFailsWith<Protokollfehler> { Sitzungsprotokoll.liesAntwort(rahmen) }
        assertFailsWith<Protokollfehler> { Sitzungsprotokoll.liesDelta(rahmen) }
    }

    @Test
    fun eineUnbekannteSichtbarkeitWirdAbgelehnt() {
        val boese = nutzlast {
            text("m-1"); text("p-1"); lang(0); lang(1); vier(1)
            event(visibility = 9, empfaenger = null, payloadArt = 0)
        }
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesDelta(Rahmen(Rahmentyp.DELTA, boese))
        }
    }

    @Test
    fun eineUnbekannteNutzdatenartWirdAbgelehnt() {
        val boese = nutzlast {
            text("m-1"); text("p-1"); lang(0); lang(1); vier(1)
            event(visibility = 1, empfaenger = null, payloadArt = 7)
        }
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesDelta(Rahmen(Rahmentyp.DELTA, boese))
        }
    }

    @Test
    fun einPrivatesEventOhneEmpfaengerKommtNichtDurch() {
        // Die Regel aus TDD 5.2 gilt auch für Bytes von außen — nicht nur für
        // Werte, die dieses Gerät selbst gebaut hat.
        val boese = nutzlast {
            text("m-1"); text("p-1"); lang(0); lang(1); vier(1)
            event(visibility = 3, empfaenger = null, payloadArt = 2) {
                text("aes-gcm-256"); vier(2); b(1); b(2)
            }
        }
        val fehler = assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesDelta(Rahmen(Rahmentyp.DELTA, boese))
        }
        assertTrue(fehler.message!!.contains("unzulässig"))
    }

    @Test
    fun einPublicEventMitChiffratKommtNichtDurch() {
        val boese = nutzlast {
            text("m-1"); text("p-1"); lang(0); lang(1); vier(1)
            event(visibility = 1, empfaenger = null, payloadArt = 2) {
                text("aes-gcm-256"); vier(2); b(1); b(2)
            }
        }
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesDelta(Rahmen(Rahmentyp.DELTA, boese))
        }
    }

    @Test
    fun einNichtOeffentlichesEventMitKlartextKommtNichtDurch() {
        val boese = nutzlast {
            text("m-1"); text("p-1"); lang(0); lang(1); vier(1)
            event(visibility = 3, empfaenger = "p-1", payloadArt = 1) {
                vier(2); b('{'.code); b('}'.code)
            }
        }
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesDelta(Rahmen(Rahmentyp.DELTA, boese))
        }
    }

    @Test
    fun einGesuchOhneSitzplatzKommtNichtDurch() {
        // Der Werttyp prüft es ohnehin — hier zählt, dass daraus ein
        // Protokollfehler wird und keine Zusicherungsverletzung. Ein feindliches
        // Paket darf nicht aussehen wie ein Programmierfehler.
        val boese = nutzlast {
            text("m-1"); text(""); text("token-lang-genug-1234"); text("d-a"); lang(0)
        }
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesGesuch(Rahmen(Rahmentyp.HANDSHAKE, boese))
        }
    }

    @Test
    fun eineFlaggeMitFremdemWertWirdAbgelehnt() {
        // Kein `!= 0`: Ein drittes Byte heißt, dass der Absender etwas anderes
        // meint als dieses Gerät versteht.
        val boese = nutzlast { text("s-1"); b(2) }
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesAntwort(Rahmen(Rahmentyp.HANDSHAKE_ANTWORT, boese))
        }
    }

    @Test
    fun einUnbekannterAblehnungsgrundWirdAbgelehnt() {
        val boese = nutzlast { b(99) }
        assertFailsWith<Protokollfehler> {
            Sitzungsprotokoll.liesAntwort(Rahmen(Rahmentyp.ABLEHNUNG, boese))
        }
    }

    // ── Größe (Vorarbeit zu T-078) ──────────────────────────────────────────

    @Test
    fun einDeltaAnDerSchwelleBleibtWeitUnterDerRahmenobergrenze() {
        // TDD 6.1 verlangt Nachrichtengrößen im zweistelligen Kilobyte-Bereich.
        // Das ist die Aussage, an der `resume_delta_threshold` hängt — und sie
        // wird hier gerechnet und nicht behauptet.
        val schwelle = Aufholschwelle().resumeDeltaSchwelle.toInt()
        val events = (1..schwelle).map { ereignis(it.toLong()) }
        val bytes = Sitzungsprotokoll.kodiere(
            Aufholung.Delta("m-1", "p-1", 0, schwelle.toLong(), events),
        ).nutzlast.size

        assertTrue(
            bytes < 100 * 1024,
            "Ein volles Delta an der Schwelle wäre $bytes Bytes — TDD 6.1 sagt zweistellige Kilobyte.",
        )
        assertTrue(bytes < Rahmencodec.MAX_NUTZLAST / 4, "Reserve zur Rahmenobergrenze: $bytes Bytes.")
    }
}
