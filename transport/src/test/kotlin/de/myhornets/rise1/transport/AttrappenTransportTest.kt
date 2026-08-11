package de.myhornets.rise1.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-066 — die Attrappe selbst wird geprüft.
 *
 * ## Warum das kein Selbstzweck ist
 *
 * Ab hier wird jeder Fehlerfall des Transports gegen diese Klasse entwickelt:
 * Wiederverbindung, Herzschlag, der Zustandsautomat aus TDD 9.2. Wenn die
 * Attrappe selbst nicht tut, was sie behauptet, sind alle Tests darüber wertlos
 * — und zwar unauffällig, weil sie grün wären.
 *
 * Die wichtigste Eigenschaft ist [derselbeAblaufErgibtZweimalDasselbe]: ohne
 * sie wäre ein roter Test in E06 nie eindeutig einem Fehler zuzuordnen.
 */
class AttrappenTransportTest {

    private val host = Gegenstelle("d-host", "Tisch")
    private val gast = Gegenstelle("d-gast", "Bert")

    private fun mitschnitt(t: Transport): MutableList<TransportEreignis> =
        mutableListOf<TransportEreignis>().also { liste -> t.beobachte { liste += it } }

    // ── Zeit ────────────────────────────────────────────────────────────────

    @Test
    fun ohneUhrLaufZuStellenPassiertNichts() {
        // Der Kern des Determinismus: Der Test bestimmt den Zeitpunkt, nicht
        // die Attrappe.
        val t = AttrappenTransport(verzoegerungMillis = 50)
        val gesehen = mitschnitt(t)
        t.verbinde(host)
        assertTrue(gesehen.isEmpty())
    }

    @Test
    fun eineVerzoegerungWirdEingehalten() {
        val t = AttrappenTransport(verzoegerungMillis = 50)
        val gesehen = mitschnitt(t)
        t.verbinde(host)
        t.laufeBis(49)
        assertTrue(gesehen.isEmpty(), "Zu früh zugestellt.")
        t.laufeBis(50)
        assertEquals<List<TransportEreignis>>(listOf(TransportEreignis.Verbunden(host)), gesehen)
    }

    @Test
    fun dieUhrLaeuftNichtRueckwaerts() {
        val t = AttrappenTransport()
        t.laufeBis(100)
        assertFailsWith<IllegalArgumentException> { t.laufeBis(99) }
    }

    @Test
    fun zustellungErfolgtInReihenfolgeDerFaelligkeit() {
        val t = AttrappenTransport(verzoegerungMillis = 10)
        val gesehen = mitschnitt(t)
        t.verbinde(host)
        t.laufeBis(10)
        t.antworteMit(host) { "A".toByteArray() }
        t.sende(host, "1".toByteArray())
        t.laufeBis(20)
        t.antworteMit(host) { "B".toByteArray() }
        t.sende(host, "2".toByteArray())
        t.laufeBis(30)

        val texte = gesehen.filterIsInstance<TransportEreignis.Empfangen>()
            .map { String(it.rahmen) }
        assertEquals(listOf("A", "B"), texte)
    }

    @Test
    fun derselbeAblaufErgibtZweimalDasselbe() {
        fun lauf(): List<String> {
            val t = AttrappenTransport(verzoegerungMillis = 5)
            val gesehen = mitschnitt(t)
            t.antworteMit(host) { r -> ("echo:" + String(r)).toByteArray() }
            t.verwirfNaechste(2)
            t.verbinde(host)
            t.laufeBis(5)
            listOf("a", "b", "c").forEach { t.sende(host, it.toByteArray()); t.laufeWeiter() }
            t.trenne(host, "fertig")
            t.laufeWeiter()
            return gesehen.map { it.toString() }
        }
        assertEquals(lauf(), lauf())
    }

    // ── Verbinden und Trennen ───────────────────────────────────────────────

    @Test
    fun nachDemVerbindenIstDieGegenstelleVerbunden() {
        val t = AttrappenTransport()
        t.verbinde(host)
        assertEquals(setOf(host), t.verbundene)
    }

    @Test
    fun zweimalVerbindenIstEinmalVerbinden() {
        val t = AttrappenTransport()
        val gesehen = mitschnitt(t)
        t.verbinde(host)
        t.verbinde(host)
        t.laufeBis(1)
        assertEquals(1, gesehen.count { it is TransportEreignis.Verbunden })
    }

    @Test
    fun trennenMeldetUndEntferntDieVerbindung() {
        val t = AttrappenTransport()
        val gesehen = mitschnitt(t)
        t.verbinde(host)
        t.trenne(host, "Nutzer hat abgebrochen")
        t.laufeBis(1)
        assertTrue(t.verbundene.isEmpty())
        assertEquals<TransportEreignis>(
            TransportEreignis.Getrennt(host, "Nutzer hat abgebrochen"),
            gesehen.last(),
        )
    }

    @Test
    fun trennenEinerUnbekanntenGegenstelleMeldetNichts() {
        val t = AttrappenTransport()
        val gesehen = mitschnitt(t)
        t.trenne(gast, "gibt es gar nicht")
        t.laufeBis(1)
        assertTrue(gesehen.isEmpty())
    }

    // ── Senden ──────────────────────────────────────────────────────────────

    @Test
    fun sendenOhneVerbindungIstFalschAberKeinFehler() {
        // TDD 9: Dass eine Verbindung weg ist, ist der Normalfall. Eine
        // Ausnahme daraus zu machen hieße, den Regelfall als Störung zu führen.
        val t = AttrappenTransport()
        assertFalse(t.sende(host, "x".toByteArray()))
    }

    @Test
    fun eineAntwortKommtAlsEmpfangenZurueck() {
        val t = AttrappenTransport()
        val gesehen = mitschnitt(t)
        t.antworteMit(host) { r -> ("echo:" + String(r)).toByteArray() }
        t.verbinde(host)
        t.sende(host, "hallo".toByteArray())
        t.laufeBis(1)
        assertEquals(
            "echo:hallo",
            String(gesehen.filterIsInstance<TransportEreignis.Empfangen>().single().rahmen),
        )
    }

    @Test
    fun schweigenIstEinEigenerFall() {
        // Ein Gerät, das nicht antwortet, ist nicht dasselbe wie eines, das die
        // Verbindung abbricht — der Herzschlag muss beides unterscheiden können.
        val t = AttrappenTransport()
        val gesehen = mitschnitt(t)
        t.antworteMit(host) { null }
        t.verbinde(host)
        t.sende(host, "hallo".toByteArray())
        t.laufeBis(100)
        assertTrue(gesehen.none { it is TransportEreignis.Empfangen })
        assertTrue(t.verbundene.contains(host), "Schweigen trennt nicht.")
    }

    @Test
    fun einVerworfenerRahmenGiltAlsGesendetUndKommtNichtAn() {
        val t = AttrappenTransport()
        val gesehen = mitschnitt(t)
        t.antworteMit(host) { "da".toByteArray() }
        t.verbinde(host)
        t.verwirfNaechste(1)
        t.sende(host, "eins".toByteArray())
        t.sende(host, "zwei".toByteArray())
        t.laufeBis(1)

        assertEquals(2, t.gesendet.size, "Verworfen heißt gesendet, nicht ungesendet.")
        assertEquals(1, gesehen.count { it is TransportEreignis.Empfangen })
    }

    @Test
    fun derMitschnittDerSendungenIstEineKopie() {
        // Sonst zeigte er, was der Aufrufer nachträglich verändert hat.
        val t = AttrappenTransport()
        t.verbinde(host)
        val rahmen = "abc".toByteArray()
        t.sende(host, rahmen)
        rahmen[0] = 'z'.code.toByte()
        assertEquals("abc", String(t.gesendet.single().second))
    }

    // ── Abbruch und Fehlerfälle ─────────────────────────────────────────────

    @Test
    fun einAbbruchTrenntNachDerVereinbartenSendung() {
        val t = AttrappenTransport()
        val gesehen = mitschnitt(t)
        t.verbinde(host)
        t.brichAbNach(2)
        t.sende(host, "eins".toByteArray())
        t.sende(host, "zwei".toByteArray())
        t.laufeBis(1)

        assertTrue(t.verbundene.isEmpty())
        assertTrue(gesehen.any { it is TransportEreignis.Getrennt })
        assertFalse(t.sende(host, "drei".toByteArray()), "Nach dem Abbruch geht nichts mehr.")
    }

    @Test
    fun eineScheiterndeVerbindungMeldetDenGrund() {
        val t = AttrappenTransport()
        val gesehen = mitschnitt(t)
        t.lassVerbindungScheitern(TransportFehler.KeinGemeinsamesNetz)
        t.verbinde(host)
        t.laufeBis(1)

        assertEquals<TransportEreignis>(
            TransportEreignis.Fehlgeschlagen(host, TransportFehler.KeinGemeinsamesNetz),
            gesehen.single(),
        )
        assertTrue(t.verbundene.isEmpty())
    }

    @Test
    fun einFalscherFingerabdruckIstEinEigenerFall() {
        // Sicherheitsrelevant: Ein Angriff darf nicht wie eine Störung aussehen
        // (ADR-001 / ADR-002A).
        val t = AttrappenTransport()
        val gesehen = mitschnitt(t)
        t.lassVerbindungScheitern(TransportFehler.FingerabdruckPasstNicht("AB-CD", "EF-GH"))
        t.verbinde(host)
        t.laufeBis(1)

        val fehler = (gesehen.single() as TransportEreignis.Fehlgeschlagen).fehler
        assertTrue(fehler is TransportFehler.FingerabdruckPasstNicht)
        assertEquals("AB-CD", fehler.erwartet)
        assertEquals("EF-GH", fehler.gesehen)
    }

    @Test
    fun nurDerNaechsteVersuchScheitert() {
        val t = AttrappenTransport()
        val gesehen = mitschnitt(t)
        t.lassVerbindungScheitern(TransportFehler.Zeitueberschreitung(3_000))
        t.verbinde(host)
        t.laufeBis(1)
        t.verbinde(host)
        t.laufeBis(2)

        assertEquals(1, gesehen.count { it is TransportEreignis.Fehlgeschlagen })
        assertEquals(1, gesehen.count { it is TransportEreignis.Verbunden })
    }

    // ── Mehrere Gegenstellen und Hörer ──────────────────────────────────────

    @Test
    fun mehrereGegenstellenStoerenSichNicht() {
        val t = AttrappenTransport()
        t.verbinde(host)
        t.verbinde(gast)
        t.trenne(host, "weg")
        assertEquals(setOf(gast), t.verbundene)
    }

    @Test
    fun alleHoererBekommenAlles() {
        val t = AttrappenTransport()
        val a = mitschnitt(t)
        val b = mitschnitt(t)
        t.verbinde(host)
        t.laufeBis(1)
        assertEquals(a, b)
        assertEquals(1, a.size)
    }

    @Test
    fun nachDemSchliessenGehtNichtsMehr() {
        val t = AttrappenTransport()
        t.verbinde(host)
        t.schliesse()
        assertFailsWith<IllegalStateException> { t.verbinde(gast) }
        assertFailsWith<IllegalStateException> { t.sende(host, "x".toByteArray()) }
    }

    @Test
    fun zweimalSchliessenIstErlaubt() {
        val t = AttrappenTransport()
        t.schliesse()
        t.schliesse()
    }

    // ── Der Werttyp ─────────────────────────────────────────────────────────

    @Test
    fun eineGegenstelleOhneKennungGibtEsNicht() {
        assertFailsWith<IllegalArgumentException> { Gegenstelle("", "Namenlos") }
    }

    @Test
    fun zweiGleicheEmpfangsereignisseSindGleich() {
        // Ohne eigenes equals verglichen sich die ByteArrays über die Referenz —
        // und jeder Vergleich zweier Mitschnitte wäre still falsch.
        assertEquals(
            TransportEreignis.Empfangen(host, byteArrayOf(1, 2, 3)),
            TransportEreignis.Empfangen(host, byteArrayOf(1, 2, 3)),
        )
    }

    @Test
    fun einEmpfangsereignisZeigtSeineBytesNichtImText() {
        assertFalse(TransportEreignis.Empfangen(host, byteArrayOf(42)).toString().contains("42"))
    }
}
