package de.myhornets.rise1.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-067 — die Host-Attrappe und das Netz.
 *
 * ## Was diese Tests belegen sollen
 *
 * Nicht, dass die Attrappe „funktioniert" — sondern dass sie **belastbar** ist:
 * dass die Fehlerfälle, gegen die E06 und E08 entwickelt werden, sich hier
 * tatsächlich auf Knopf erzeugen lassen und beim zweiten Lauf dasselbe ergeben.
 *
 * Jeder Test hier ist deshalb der Stellvertreter einer späteren Aufgabe:
 * Fingerabdruck → `T-071`, Schweigen → `T-073`, Wegbrechen und Wiederkommen →
 * `T-074`, Ablehnung → `T-077`, Laufzeiten → `T-078`.
 */
class AttrappennetzTest {

    private val hostAdresse = Gegenstelle("d-host", "Tisch")
    private val zweiterHost = Gegenstelle("d-host-2", "Anderer Tisch")

    private fun aufbau(
        verzoegerung: Long = 10,
        fingerabdruck: String = "AB-CD",
    ): Triple<Attrappennetz, Hostattrappe, Attrappennetz.AttrappennetzTransport> {
        val netz = Attrappennetz(verzoegerung)
        val host = netz.melde(Hostattrappe(hostAdresse, fingerabdruck).spiegle("echo:"))
        return Triple(netz, host, netz.transportFuer("d-client"))
    }

    private fun mitschnitt(t: Transport): MutableList<TransportEreignis> =
        mutableListOf<TransportEreignis>().also { liste -> t.beobachte { liste += it } }

    // ── Der vollständige Weg ────────────────────────────────────────────────

    @Test
    fun einRahmenGehtHinUndDieAntwortKommtZurueck() {
        val (netz, host, client) = aufbau()
        val gesehen = mitschnitt(client)

        client.verbinde(hostAdresse)
        netz.laufeBis(10)
        assertEquals<List<TransportEreignis>>(listOf(TransportEreignis.Verbunden(hostAdresse)), gesehen)

        client.sende(hostAdresse, "hallo".toByteArray())
        netz.laufeBis(30)

        assertEquals(listOf("hallo"), host.empfangenAlsText)
        assertEquals(
            "echo:hallo",
            String(gesehen.filterIsInstance<TransportEreignis.Empfangen>().single().rahmen),
        )
    }

    @Test
    fun hinUndRueckwegKostenJeDieVerzoegerung() {
        // Die Zusage, auf der alle späteren Laufzeit- und Herzschlagtests stehen.
        val (netz, host, client) = aufbau(verzoegerung = 20)
        val gesehen = mitschnitt(client)
        client.verbinde(hostAdresse)
        netz.laufeBis(20)
        client.sende(hostAdresse, "x".toByteArray())

        netz.laufeBis(39)
        assertTrue(host.empfangen.isEmpty(), "Zu früh beim Host.")
        netz.laufeBis(40)
        assertEquals(1, host.empfangen.size, "Hinweg dauert genau eine Verzögerung.")
        assertTrue(gesehen.none { it is TransportEreignis.Empfangen }, "Rückweg schon fertig?")
        netz.laufeBis(60)
        assertEquals(1, gesehen.count { it is TransportEreignis.Empfangen })
    }

    @Test
    fun derselbeAblaufErgibtZweimalDasselbe() {
        fun lauf(): List<String> {
            val (netz, host, client) = aufbau()
            val gesehen = mitschnitt(client)
            host.schweigeAb(3)
            client.verbinde(hostAdresse)
            netz.laufeBis(10)
            listOf("a", "b", "c", "d").forEach {
                client.sende(hostAdresse, it.toByteArray())
                netz.laufeEinenUmlauf()
            }
            client.trenne(hostAdresse, "fertig")
            netz.laufeEinenUmlauf()
            return gesehen.map { it.toString() } + host.empfangenAlsText
        }
        assertEquals(lauf(), lauf())
    }

    @Test
    fun dieUhrLaeuftNichtRueckwaerts() {
        val (netz, _, _) = aufbau()
        netz.laufeBis(100)
        assertFailsWith<IllegalArgumentException> { netz.laufeBis(99) }
    }

    // ── Fingerabdruck — Stellvertreter für T-071 ────────────────────────────

    @Test
    fun einPassenderFingerabdruckLaesstDurch() {
        val (netz, _, client) = aufbau(fingerabdruck = "AB-CD")
        val gesehen = mitschnitt(client)
        client.erwarteFingerabdruck(hostAdresse, "AB-CD")
        client.verbinde(hostAdresse)
        netz.laufeBis(10)
        assertEquals(setOf(hostAdresse), client.verbundene)
        assertTrue(gesehen.single() is TransportEreignis.Verbunden)
    }

    @Test
    fun einFalscherFingerabdruckVerhindertDieVerbindung() {
        val (netz, _, client) = aufbau(fingerabdruck = "AB-CD")
        val gesehen = mitschnitt(client)
        client.erwarteFingerabdruck(hostAdresse, "ZZ-ZZ")
        client.verbinde(hostAdresse)
        netz.laufeBis(10)

        val fehler = (gesehen.single() as TransportEreignis.Fehlgeschlagen).fehler
        assertTrue(
            fehler is TransportFehler.FingerabdruckPasstNicht,
            "Ein falscher Fingerabdruck darf nicht als gewöhnlicher Verbindungsfehler ankommen.",
        )
        assertEquals("ZZ-ZZ", fehler.erwartet)
        assertEquals("AB-CD", fehler.gesehen)
        assertTrue(client.verbundene.isEmpty())
    }

    @Test
    fun ohneErwartungWirdNichtGeprueft() {
        // Der Erstbeitritt: Der Nutzer liest den Code erst vom Bildschirm des
        // Hosts ab. Eine Prüfung ohne Vergleichswert wäre keine.
        val (netz, _, client) = aufbau()
        client.verbinde(hostAdresse)
        netz.laufeBis(10)
        assertEquals(setOf(hostAdresse), client.verbundene)
    }

    // ── Erreichbarkeit — Stellvertreter für T-074 ───────────────────────────

    @Test
    fun einNichtErreichbarerHostErgibtKeinGemeinsamesNetz() {
        val (netz, host, client) = aufbau()
        val gesehen = mitschnitt(client)
        host.erreichbar = false
        client.verbinde(hostAdresse)
        netz.laufeBis(10)
        assertEquals<TransportEreignis>(
            TransportEreignis.Fehlgeschlagen(hostAdresse, TransportFehler.KeinGemeinsamesNetz),
            gesehen.single(),
        )
    }

    @Test
    fun einUnbekannterHostVerhaeltSichWieEinNichtErreichbarer() {
        val (netz, _, client) = aufbau()
        val gesehen = mitschnitt(client)
        client.verbinde(zweiterHost)
        netz.laufeBis(10)
        assertTrue(gesehen.single() is TransportEreignis.Fehlgeschlagen)
    }

    @Test
    fun wegbrechenWaehrendDerRahmenUnterwegsIstTrennt() {
        val (netz, host, client) = aufbau()
        val gesehen = mitschnitt(client)
        client.verbinde(hostAdresse)
        netz.laufeBis(10)

        client.sende(hostAdresse, "unterwegs".toByteArray())
        host.erreichbar = false // genau jetzt, mitten im Flug
        netz.laufeBis(30)

        assertTrue(client.verbundene.isEmpty())
        assertTrue(gesehen.last() is TransportEreignis.Getrennt)
        assertTrue(host.empfangen.isEmpty(), "Der Rahmen darf nicht angekommen sein.")
    }

    @Test
    fun nachDemWiederkommenLaesstSichNeuVerbinden() {
        // Der Ablauf, gegen den T-074 entwickelt wird.
        val (netz, host, client) = aufbau()
        val gesehen = mitschnitt(client)
        host.erreichbar = false
        client.verbinde(hostAdresse)
        netz.laufeBis(10)
        assertTrue(gesehen.single() is TransportEreignis.Fehlgeschlagen)

        host.erreichbar = true
        client.verbinde(hostAdresse)
        netz.laufeBis(20)
        assertEquals(setOf(hostAdresse), client.verbundene)

        client.sende(hostAdresse, "wieder da".toByteArray())
        netz.laufeBis(40)
        assertEquals(listOf("wieder da"), host.empfangenAlsText)
    }

    // ── Schweigen und Abbruch — Stellvertreter für T-073 ────────────────────

    @Test
    fun schweigenTrenntNicht() {
        // Der Unterschied, den ein Herzschlag erkennen muss: Ein Host, der
        // nachdenkt, ist nicht derselbe wie einer, der weg ist.
        val (netz, host, client) = aufbau()
        val gesehen = mitschnitt(client)
        host.schweigeAb(1)
        client.verbinde(hostAdresse)
        netz.laufeBis(10)
        client.sende(hostAdresse, "hörst du?".toByteArray())
        netz.laufeBis(1_000)

        assertTrue(gesehen.none { it is TransportEreignis.Empfangen })
        assertTrue(gesehen.none { it is TransportEreignis.Getrennt })
        assertEquals(setOf(hostAdresse), client.verbundene)
        assertEquals(1, host.empfangen.size, "Angekommen ist er trotzdem.")
    }

    @Test
    fun derHostKannGezieltBeimNtenRahmenTrennen() {
        val (netz, host, client) = aufbau()
        val gesehen = mitschnitt(client)
        host.trenneBei(2, "Attrappe: Schluss")
        client.verbinde(hostAdresse)
        netz.laufeBis(10)

        client.sende(hostAdresse, "eins".toByteArray())
        netz.laufeEinenUmlauf()
        client.sende(hostAdresse, "zwei".toByteArray())
        netz.laufeEinenUmlauf()

        assertTrue(client.verbundene.isEmpty())
        assertEquals<TransportEreignis>(
            TransportEreignis.Getrennt(hostAdresse, "Attrappe: Schluss"),
            gesehen.last(),
        )
        assertEquals(listOf("eins", "zwei"), host.empfangenAlsText)
    }

    @Test
    fun einAusloesenderRahmenBleibtImMitschnitt() {
        // Sonst wäre nach einem Abbruch nicht mehr feststellbar, was ihn
        // ausgelöst hat — und genau das will man dann wissen.
        val (netz, host, client) = aufbau()
        host.trenneBei(1)
        client.verbinde(hostAdresse)
        netz.laufeBis(10)
        client.sende(hostAdresse, "der schlimme Rahmen".toByteArray())
        netz.laufeEinenUmlauf()
        assertEquals(listOf("der schlimme Rahmen"), host.empfangenAlsText)
    }

    @Test
    fun ablehnenIstEtwasAnderesAlsTrennen() {
        // Der Host ist da und sagt nein. Das muss die Oberfläche anders
        // erklären als einen Abbruch (T-077).
        val (netz, host, client) = aufbau()
        val gesehen = mitschnitt(client)
        host.antworteMit { Hostattrappe.Antwort.LehneAb("Die Partie ist voll.") }
        client.verbinde(hostAdresse)
        netz.laufeBis(10)
        client.sende(hostAdresse, "beitreten".toByteArray())
        netz.laufeEinenUmlauf()

        val fehler = (gesehen.last() as TransportEreignis.Fehlgeschlagen).fehler
        assertEquals(TransportFehler.Abgelehnt("Die Partie ist voll."), fehler)
        assertEquals(
            setOf(hostAdresse),
            client.verbundene,
            "Eine Ablehnung ist keine Trennung — die Verbindung steht noch.",
        )
    }

    // ── Verhalten nach Inhalt — Voraussetzung für E08 ───────────────────────

    @Test
    fun derHostKannAufDenInhaltReagieren() {
        // Ein Beitrittsprotokoll antwortet auf den Inhalt, nicht auf die Nummer.
        // Ohne diese Fähigkeit wäre T-101 hier nicht abbildbar.
        val (netz, host, client) = aufbau()
        val gesehen = mitschnitt(client)
        host.antworteMit { rahmen ->
            when (String(rahmen)) {
                "HALLO" -> Hostattrappe.Antwort.Sende("WILLKOMMEN".toByteArray())
                "TSCHUESS" -> Hostattrappe.Antwort.Trenne("auf Wunsch")
                else -> Hostattrappe.Antwort.Schweige
            }
        }
        client.verbinde(hostAdresse)
        netz.laufeBis(10)

        client.sende(hostAdresse, "HALLO".toByteArray())
        netz.laufeEinenUmlauf()
        assertEquals(
            "WILLKOMMEN",
            String(gesehen.filterIsInstance<TransportEreignis.Empfangen>().single().rahmen),
        )

        client.sende(hostAdresse, "EGAL".toByteArray())
        netz.laufeEinenUmlauf()
        assertEquals(1, gesehen.count { it is TransportEreignis.Empfangen })

        client.sende(hostAdresse, "TSCHUESS".toByteArray())
        netz.laufeEinenUmlauf()
        assertTrue(client.verbundene.isEmpty())
    }

    // ── Mehrere Teilnehmer ──────────────────────────────────────────────────

    @Test
    fun zweiClientsSprechenMitDemselbenHost() {
        val netz = Attrappennetz(5)
        val host = netz.melde(Hostattrappe(hostAdresse).spiegle())
        val a = netz.transportFuer("d-a")
        val b = netz.transportFuer("d-b")
        a.verbinde(hostAdresse)
        b.verbinde(hostAdresse)
        netz.laufeBis(5)
        a.sende(hostAdresse, "von a".toByteArray())
        b.sende(hostAdresse, "von b".toByteArray())
        netz.laufeBis(20)

        assertEquals(listOf("von a", "von b"), host.empfangenAlsText)
    }

    @Test
    fun zweiHostsMitDerselbenKennungGibtEsNicht() {
        val netz = Attrappennetz()
        netz.melde(Hostattrappe(hostAdresse))
        assertFailsWith<IllegalArgumentException> { netz.melde(Hostattrappe(hostAdresse)) }
    }

    // ── Grundzusagen der Schnittstelle ──────────────────────────────────────

    @Test
    fun sendenOhneVerbindungIstFalschUndErreichtNiemanden() {
        val (netz, host, client) = aufbau()
        assertFalse(client.sende(hostAdresse, "x".toByteArray()))
        netz.laufeBis(100)
        assertTrue(host.empfangen.isEmpty())
    }

    @Test
    fun derMitschnittDesHostsIstEineKopie() {
        val (netz, host, client) = aufbau()
        client.verbinde(hostAdresse)
        netz.laufeBis(10)
        val rahmen = "abc".toByteArray()
        client.sende(hostAdresse, rahmen)
        rahmen[0] = 'z'.code.toByte()
        netz.laufeBis(30)
        assertEquals(listOf("abc"), host.empfangenAlsText)
    }

    @Test
    fun nachDemSchliessenGehtNichtsMehr() {
        val (_, _, client) = aufbau()
        client.schliesse()
        assertFailsWith<IllegalStateException> { client.verbinde(hostAdresse) }
    }

    @Test
    fun einClientOhneKennungIstKeiner() {
        val netz = Attrappennetz()
        assertFailsWith<IllegalArgumentException> { netz.transportFuer("  ") }
    }
}
