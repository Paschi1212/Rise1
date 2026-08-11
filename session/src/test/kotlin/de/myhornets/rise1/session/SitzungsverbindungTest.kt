package de.myhornets.rise1.session

import de.myhornets.rise1.transport.Attrappennetz
import de.myhornets.rise1.transport.Gegenstelle
import de.myhornets.rise1.transport.Hostattrappe
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
 * T-073 / T-074 — Herzschlag und Wiederverbindung, gegen das Attrappennetz.
 *
 * Hier zahlt sich `T-067` aus: Ein Verbindungsverlust über zehn Minuten mit
 * sechs Wiederholungsversuchen läuft in Millisekunden und liefert bei jedem
 * Lauf dasselbe. Mit echtem WLAN wäre dieser Test nicht geschrieben worden.
 *
 * Der wichtigste ist [einWiederaufbauDerLeitungIstKeineWiederhergestellteSitzung].
 */
class SitzungsverbindungTest {

    private val hostAdresse = Gegenstelle("d-host", "Tisch")
    private val takt = 5_000L

    /** Ein Host, der auf jeden Herzschlag mit einem Herzschlag antwortet. */
    private fun antwortenderHost() = Hostattrappe(hostAdresse).antworteMit { rahmen ->
        val gelesen = Rahmenleser().fuettere(rahmen).filterIsInstance<Rahmen>()
        if (gelesen.any { it.typ == Rahmentyp.HERZSCHLAG }) {
            Hostattrappe.Antwort.Sende(Rahmencodec.kodiere(Rahmen(Rahmentyp.HERZSCHLAG, ByteArray(0))))
        } else {
            Hostattrappe.Antwort.Schweige
        }
    }

    private fun aufbau(
        plan: Wiederverbindungsplan = Wiederverbindungsplan(),
    ): Triple<Attrappennetz, Hostattrappe, Sitzungsverbindung> {
        val netz = Attrappennetz(verzoegerungMillis = 10)
        val host = netz.melde(antwortenderHost())
        val verbindung = Sitzungsverbindung(
            transport = netz.transportFuer("d-client"),
            host = hostAdresse,
            eigenerParticipantUid = "p-1",
            uhr = { netz.jetzt },
            plan = plan,
        )
        verbindung.starte()
        netz.laufeBis(10) // Verbindung steht
        return Triple(netz, host, verbindung)
    }

    // ── T-074 Der Plan für sich ─────────────────────────────────────────────

    @Test
    fun dieWartezeitenSteigenUndBleibenDannStehen() {
        val plan = Wiederverbindungsplan(grundwartezeitMillis = 1_000, faktor = 2.0, hoechstwartezeitMillis = 30_000)
        assertEquals(1_000, plan.wartezeitFuer(1))
        assertEquals(2_000, plan.wartezeitFuer(2))
        assertEquals(4_000, plan.wartezeitFuer(3))
        assertEquals(8_000, plan.wartezeitFuer(4))
        assertEquals(16_000, plan.wartezeitFuer(5))
        assertEquals(30_000, plan.wartezeitFuer(6), "Ab hier greift die Obergrenze.")
        assertEquals(30_000, plan.wartezeitFuer(7))
    }

    @Test
    fun auchDerVierzigsteVersuchLiefertEineSinnvolleZahl() {
        // Wiederholtes Multiplizieren ließe einen Long überlaufen, und die
        // Wartezeit wäre plötzlich negativ — also sofort.
        val plan = Wiederverbindungsplan()
        assertEquals(30_000, plan.wartezeitFuer(40))
        assertTrue(plan.wartezeitFuer(1_000) > 0)
    }

    @Test
    fun esGibtKeineObergrenzeFuerDieAnzahlDerVersuche() {
        // TDD 9.1: „Nur explizite Ereignisse beenden eine Partie." Ein Plan,
        // der nach n Versuchen aufhört, wäre ein Timeout mit anderem Namen.
        val plan = Wiederverbindungsplan()
        assertEquals(plan.wartezeitFuer(100), plan.wartezeitFuer(1_000))
    }

    @Test
    fun dieStreuungIstStandardmaessigAus() {
        // Ein Zufall im Standardweg machte jeden Test darüber unzuverlässig.
        val ohne = Wiederverbindungsplan()
        assertEquals(1_000, ohne.wartezeitFuer(1))
        val mit = Wiederverbindungsplan(streuung = { versuch -> versuch * 7L })
        assertEquals(1_007, mit.wartezeitFuer(1))
        assertEquals(2_014, mit.wartezeitFuer(2))
    }

    @Test
    fun unsinnigePlaeneWerdenAbgewiesen() {
        assertFailsWith<IllegalArgumentException> { Wiederverbindungsplan(grundwartezeitMillis = 0) }
        assertFailsWith<IllegalArgumentException> { Wiederverbindungsplan(faktor = 0.5) }
        assertFailsWith<IllegalArgumentException> {
            Wiederverbindungsplan(grundwartezeitMillis = 5_000, hoechstwartezeitMillis = 1_000)
        }
        assertFailsWith<IllegalArgumentException> { Wiederverbindungsplan().wartezeitFuer(0) }
    }

    // ── T-073 Herzschlag ────────────────────────────────────────────────────

    @Test
    fun nachDemStartStehtDieLeitung() {
        val (_, _, v) = aufbau()
        assertTrue(v.transportVerbunden)
        assertEquals(Verbindungszustand.VERBUNDEN, v.automat.zustand)
    }

    @Test
    fun einHerzschlagWirdErstNachDemIntervallGesendet() {
        val (netz, host, v) = aufbau() // Verbindung steht bei 10
        netz.laufeBis(10 + takt - 1)
        v.takt()
        netz.laufeBis(10 + takt + 100)
        assertTrue(host.empfangen.isEmpty(), "Vor Ablauf des Intervalls darf nichts gesendet werden.")

        // Jetzt liegt der letzte gesendete Herzschlag mehr als ein Intervall
        // zurück — dieser Takt sendet.
        v.takt()
        netz.laufeBis(10 + takt + 120)
        assertEquals(1, host.empfangen.size)
    }

    @Test
    fun dieAntwortDesHostsHaeltDenAutomatenVerbunden() {
        val (netz, _, v) = aufbau()
        // Zehn Intervalle lang im Takt bleiben.
        var t = 10L
        repeat(10) {
            t += takt
            netz.laufeBis(t)
            v.takt()
            netz.laufeBis(t + 25) // Umlauf: hin und zurück
        }
        assertEquals(
            Verbindungszustand.VERBUNDEN,
            v.automat.zustand,
            "Bei antwortendem Host darf nichts als verpasst gelten.",
        )
    }

    @Test
    fun jederRahmenGiltAlsLebenszeichenNichtNurEinHerzschlag() {
        // Wenn Ereignisse fließen, ist die Gegenstelle offensichtlich da.
        val netz = Attrappennetz(verzoegerungMillis = 10)
        val host = netz.melde(
            Hostattrappe(hostAdresse).antworteMit {
                Hostattrappe.Antwort.Sende(Rahmencodec.kodiere(Rahmen(Rahmentyp.EREIGNIS, "e".toByteArray())))
            },
        )
        val v = Sitzungsverbindung(
            netz.transportFuer("d-client"), hostAdresse, "p-1", { netz.jetzt },
        )
        v.starte()
        netz.laufeBis(10)

        netz.laufeBis(3 * takt)
        v.takt() // sendet Herzschlag
        netz.laufeBis(3 * takt + 25) // Antwort ist ein EREIGNIS, kein Herzschlag
        v.takt()

        assertEquals(Verbindungszustand.VERBUNDEN, v.automat.zustand)
        assertEquals(1, v.eingegangen.size)
        assertEquals(Rahmentyp.EREIGNIS, v.eingegangen.single().typ)
        assertTrue(host.empfangen.isNotEmpty())
    }

    @Test
    fun herzschlaegeLandenNichtInDenEingegangenenRahmen() {
        // Sie sind Verbindungssache, kein Inhalt für die Schicht darüber.
        val (netz, _, v) = aufbau()
        netz.laufeBis(3 * takt)
        v.takt()
        netz.laufeBis(3 * takt + 25)
        v.takt()
        assertTrue(v.eingegangen.isEmpty())
    }

    // ── T-074 Wiederverbindung ──────────────────────────────────────────────

    @Test
    fun einSchweigenderHostFuehrtUeberWackeligNachOffline() {
        val (netz, host, v) = aufbau()
        host.erreichbar = false

        netz.laufeBis(2 * takt + 10)
        v.takt()
        assertEquals(Verbindungszustand.WACKELIG, v.automat.zustand)

        netz.laufeBis(6 * takt + 10)
        v.takt()
        assertEquals(Verbindungszustand.OFFLINE, v.automat.zustand)
    }

    @Test
    fun nachEinemVerlustWirdNachDerGeplantenWartezeitNeuVersucht() {
        val (netz, host, v) = aufbau()
        host.erreichbar = false

        // Ein Sendeversuch trennt die Leitung im Attrappennetz.
        netz.laufeBis(takt + 10)
        v.takt()
        netz.laufeBis(takt + 30)
        assertFalse(v.transportVerbunden, "Der Verlust muss bemerkt worden sein.")
        assertEquals(0, v.versuchszaehler)

        // Vor Ablauf der ersten Wartezeit passiert nichts.
        netz.laufeBis(takt + 900)
        v.takt()
        assertEquals(0, v.versuchszaehler)

        netz.laufeBis(takt + 30 + 1_000)
        v.takt()
        assertEquals(1, v.versuchszaehler)
    }

    @Test
    fun dieAbstaendeZwischenDenVersuchenWachsen() {
        val (netz, host, v) = aufbau()
        host.erreichbar = false
        netz.laufeBis(takt + 10)
        v.takt()
        netz.laufeBis(takt + 30)

        val versucheBei = mutableListOf<Long>()
        var t = takt + 30
        var zuletzt = 0
        while (t < 200_000) {
            t += 250
            netz.laufeBis(t)
            v.takt()
            if (v.versuchszaehler > zuletzt) {
                zuletzt = v.versuchszaehler
                versucheBei += t
            }
        }
        assertTrue(versucheBei.size >= 5, "Zu wenige Versuche: ${versucheBei.size}")
        val abstaende = versucheBei.zipWithNext { a, b -> b - a }
        abstaende.zipWithNext { a, b ->
            assertTrue(b >= a, "Die Abstände schrumpfen: $abstaende")
        }
    }

    @Test
    fun einWiederaufbauDerLeitungIstKeineWiederhergestellteSitzung() {
        // Der wichtigste Test dieser Datei. Eine stehende TCP-Verbindung ist
        // keine Anmeldung. TDD 9.1: „Wiedereinstieg ist Authentifizierung,
        // nicht Wiedererkennung." Der Handshake aus TDD 9.3 ist T-105.
        val (netz, host, v) = aufbau()
        host.erreichbar = false
        netz.laufeBis(7 * takt)
        v.takt()
        netz.laufeBis(7 * takt + 30)
        assertEquals(Verbindungszustand.OFFLINE, v.automat.zustand)

        host.erreichbar = true
        var t = 7 * takt + 30
        while (!v.transportVerbunden && t < 300_000) {
            t += 500
            netz.laufeBis(t)
            v.takt()
        }

        assertTrue(v.transportVerbunden, "Die Leitung sollte wieder stehen.")
        assertEquals(
            Verbindungszustand.OFFLINE,
            v.automat.zustand,
            "Die Leitung steht — die Sitzung nicht. Sonst wäre aus Wiedererkennung " +
                "eine Anmeldung geworden.",
        )
        assertTrue(v.bereitFuerHandshake, "Genau jetzt ist der Handshake aus TDD 9.3 fällig.")
    }

    @Test
    fun derVersuchszaehlerBeginntNachErfolgVonVorn() {
        val (netz, host, v) = aufbau()
        host.erreichbar = false
        netz.laufeBis(takt + 10)
        v.takt()
        netz.laufeBis(takt + 5_000)
        v.takt()
        assertTrue(v.versuchszaehler >= 1)

        host.erreichbar = true
        var t = takt + 5_000
        while (!v.transportVerbunden && t < 300_000) {
            t += 500
            netz.laufeBis(t)
            v.takt()
        }
        assertEquals(0, v.versuchszaehler)
    }

    @Test
    fun nachDemVerlassenWirdNichtWeiterVersucht() {
        // `participant_left` ist eine Entscheidung. Danach klopft das Gerät
        // nicht weiter an.
        val (netz, host, v) = aufbau()
        host.erreichbar = false
        netz.laufeBis(takt + 10)
        v.takt()
        netz.laufeBis(takt + 30)
        v.automat.verlassen(netz.jetzt)

        netz.laufeBis(200_000)
        v.takt()
        assertEquals(0, v.versuchszaehler)
        assertEquals(Verbindungszustand.GEGANGEN, v.automat.zustand)
    }

    @Test
    fun sendenOhneLeitungIstFalschUndKeinFehler() {
        val (netz, host, v) = aufbau()
        host.erreichbar = false
        netz.laufeBis(takt + 10)
        v.takt()
        netz.laufeBis(takt + 30)
        assertFalse(v.sende(Rahmen(Rahmentyp.EREIGNIS, "x".toByteArray())))
    }
}
