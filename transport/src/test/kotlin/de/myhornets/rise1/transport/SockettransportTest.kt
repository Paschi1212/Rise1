package de.myhornets.rise1.transport

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-070 — der echte Transport über Sockets.
 *
 * ## Was diese Tests prüfen — und was ausdrücklich nicht
 *
 * ADR-008 zieht die Grenze selbst: *„**Was ein JVM-Test hier prüfen kann:** die
 * Übergabestelle, das Beenden, die laufende Nummer gegen verspätete Rahmen —
 * über Schleifen-Sockets auf `localhost`. **Was er nicht kann:** TLS mit einem
 * echten Partie-Zertifikat … Dieser Teil ist ein **Gerätetest**."*
 *
 * Hier steht also die Socket-Mechanik, über Klartext auf `127.0.0.1`. Kein Test
 * in dieser Datei berührt TLS, und keiner könnte eine Fingerabdruckprüfung
 * abschwächen — es gibt in `:transport` keine.
 *
 * ## Die drei wichtigsten
 *
 * [verspaeteteRahmenEinerAbgeloestenVerbindungWerdenVerworfen] — die laufende
 * Nummer aus ADR-008. Ohne sie *„landete nach einem Wiederaufbau der letzte
 * Rahmen der alten Leitung im neuen Ablauf — und `T-108` prüfte ein Delta gegen
 * einen Stand, der zu einer anderen Sitzung gehört."*
 *
 * [alleRueckrufeLaufenAufDemSitzungsthread] — die Regel, aus der alles folgt.
 * Sie ist der Grund, warum `Sitzungsverbindung` und `Verbindungsautomat` nicht
 * threadsicher sein müssen. Wird sie gebrochen, fällt es dort auf — sporadisch,
 * Monate später und ohne Zusammenhang.
 *
 * [einRahmenInZweiStueckenWirdErstVollstaendigZugestellt] — TCP ist ein Strom.
 * Ein Transport, der das nicht aushält, funktioniert im WLAN des Entwicklers
 * und am Küchentisch nicht.
 *
 * ## Warum hier gewartet und nicht geschlafen wird
 *
 * Echte Threads lassen sich nicht wie das `Attrappennetz` an einer Uhr
 * vorbeischieben. Statt fester Pausen wartet [warteBis] auf **Bedingungen**,
 * und wo eine Reihenfolge erzwungen werden muss, wird der Sitzungsthread mit
 * einer Sperre angehalten. Ein Test, der ein Ergebnis „nach 50 ms" erwartet,
 * ist auf einem ausgelasteten Rechner rot und sagt nichts.
 */
class SockettransportTest {

    private val host = Gegenstelle("host-1", "Tisch am Fenster")

    private val sitzungen = mutableListOf<Sitzungsthread>()
    private val transporte = mutableListOf<Sockettransport>()
    private val schliessbares = mutableListOf<AutoCloseable>()

    private fun sitzungsthread(): Sitzungsthread = Sitzungsthread().also { sitzungen += it }

    private fun transport(
        sitzung: Sitzungsthread,
        quelle: Socketquelle = Socketquelle.NurAnnehmend,
        sitzplaetze: Int = Sockettransport.STANDARD_SITZPLAETZE,
    ): Sockettransport = Sockettransport(sitzung, quelle, sitzplaetze).also { transporte += it }

    private fun gegenserver(): Gegenserver = Gegenserver().also { schliessbares += it }

    @AfterTest
    fun raeumeAuf() {
        // Erst die Transporte: Sie schließen ihre Sockets und warten auf ihre
        // Threads. Ein Test, der das dem nächsten überlässt, macht dessen
        // Threadzählung wertlos.
        transporte.forEach { runCatching { it.schliesse() } }
        schliessbares.forEach { runCatching { it.close() } }
        sitzungen.forEach { runCatching { it.beende(1_000) } }
    }

    private fun ereignis(text: String): ByteArray =
        Rahmencodec.kodiere(Rahmen(Rahmentyp.EREIGNIS, text.toByteArray()))

    // ── Aufbau und Zustellung ───────────────────────────────────────────────

    @Test
    fun eineVerbindungKommtZustandeUndMeldetVerbunden() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        gegen.naechsteVerbindung()

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1), "Kein Verbunden gemeldet.")
        assertEquals(setOf(host), transport.verbundene)
        assertNotNull(transport.verbindungsnummer(host))
    }

    @Test
    fun mehrereRahmenHintereinanderKommenInReihenfolgeAn() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        val leitung = gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))

        // Alle drei in einem einzigen Schreibvorgang — TCP darf sie beliebig
        // zusammenfassen, der Rahmenleser muss sie wieder trennen.
        gegen.schreibe(leitung, ereignis("eins") + ereignis("zwei") + ereignis("drei"))

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Empfangen>(3), "Es kamen nicht drei Rahmen an.")
        assertEquals(listOf("eins", "zwei", "drei"), mitschnitt.empfangeneNutzlasten())
    }

    @Test
    fun einRahmenInZweiStueckenWirdErstVollstaendigZugestellt() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        val leitung = gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))

        val ganz = ereignis("zerschnitten")
        // Mitten im Kopf trennen: Selbst die Längenangabe kommt in Stücken.
        gegen.schreibe(leitung, ganz.copyOfRange(0, 5))

        assertFalse(
            mitschnitt.warteAuf<TransportEreignis.Empfangen>(1, millis = 250),
            "Ein halber Rahmen darf nicht zugestellt werden — sonst hätte die Rahmung keinen Zweck.",
        )

        gegen.schreibe(leitung, ganz.copyOfRange(5, ganz.size))

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Empfangen>(1), "Der vollständige Rahmen kam nie an.")
        assertEquals(listOf("zerschnitten"), mitschnitt.empfangeneNutzlasten())
    }

    @Test
    fun einUnbekannterRahmentypWirdUebersprungenUndDieLeitungBleibtStehen() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        val leitung = gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))

        // Kennung 99 kennt dieses Gerät nicht. T-072: gemeldet und übersprungen,
        // nicht als Angriff behandelt — ein älteres Gerät zerbricht an einem
        // neueren nicht.
        val fremd = byteArrayOf('R'.code.toByte(), '1'.code.toByte(), 1, 99, 0, 0, 0, 2, 7, 7)
        gegen.schreibe(leitung, fremd + ereignis("danach"))

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Empfangen>(1))
        assertEquals(listOf("danach"), mitschnitt.empfangeneNutzlasten())
        assertEquals(0, mitschnitt.von<TransportEreignis.Getrennt>().size, "Ein unbekannter Typ trennt nicht.")
    }

    @Test
    fun einProtokollfehlerTrenntDieVerbindung() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        val leitung = gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))

        // Keine Kennmarke: Das Gegenüber spricht ein anderes Protokoll. T-072
        // sagt, die Verbindung ist zu trennen und nicht weiterzulesen.
        gegen.schreibe(leitung, ByteArray(16) { 0x41 })

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Getrennt>(1), "Ein Protokollfehler muss trennen.")
        assertTrue(transport.verbundene.isEmpty())
    }

    // ── Senden ──────────────────────────────────────────────────────────────

    @Test
    fun dieSendewarteschlangeStelltAllesInReihenfolgeZu() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        val leitung = gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))

        val anzahl = 50
        repeat(anzahl) { nummer ->
            assertTrue(transport.sende(host, ereignis("rahmen-$nummer")), "Sendung $nummer wurde abgelehnt.")
        }

        val leser = Rahmenleser()
        val gelesen = mutableListOf<String>()
        val ein = leitung.getInputStream()
        val puffer = ByteArray(4096)
        val ende = System.currentTimeMillis() + 5_000
        while (gelesen.size < anzahl && System.currentTimeMillis() < ende) {
            val zahl = ein.read(puffer)
            if (zahl < 0) break
            gelesen += leser.fuettere(puffer.copyOf(zahl)).filterIsInstance<Rahmen>().map { String(it.nutzlast) }
        }

        assertEquals(List(anzahl) { "rahmen-$it" }, gelesen, "Reihenfolge oder Vollständigkeit verletzt.")
    }

    @Test
    fun sendenOhneVerbindungIstFalschUndKeinFehler() {
        val sitzung = sitzungsthread()
        val transport = transport(sitzung)

        // TDD 9: Dass eine Verbindung weg ist, ist der Normalfall. Kein Werfen.
        assertFalse(transport.sende(host, ereignis("ins Leere")))
    }

    // ── Verbindungsende ─────────────────────────────────────────────────────

    @Test
    fun dasVerbindungsendeMeldetGenauEinGetrennt() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        val leitung = gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))

        // Das Gegenüber legt auf. Lese- und Sendethread bemerken es gleichzeitig —
        // genau der Fall, für den ADR-008 das AtomicBoolean vorschreibt.
        leitung.close()

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Getrennt>(1))
        sitzung.warteAufLeerlauf(1_000)
        assertEquals(1, mitschnitt.von<TransportEreignis.Getrennt>().size, "Genau ein Getrennt (ADR-008).")
    }

    @Test
    fun zweimalTrennenMeldetKeinZweitesGetrennt() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))

        transport.trenne(host, "Erstes Trennen")
        transport.trenne(host, "Zweites Trennen")
        transport.schliesse()

        sitzung.warteAufLeerlauf(2_000)
        val getrennt = mitschnitt.von<TransportEreignis.Getrennt>()
        assertEquals(1, getrennt.size, "Wer das AtomicBoolean umlegt, meldet; alle anderen schweigen.")
        assertEquals("Erstes Trennen", getrennt.single().grund)
    }

    @Test
    fun einGescheiterterAufbauMeldetFehlgeschlagenUndNichtGetrennt() {
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(geschlossenerPort()))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Fehlgeschlagen>(1))
        assertEquals(
            TransportFehler.KeinGemeinsamesNetz,
            mitschnitt.von<TransportEreignis.Fehlgeschlagen>().single().fehler,
        )
        assertEquals(
            0,
            mitschnitt.von<TransportEreignis.Getrennt>().size,
            "Getrennt werden kann nur, was verbunden war.",
        )
        assertNull(transport.verbindungsnummer(host), "Ein gescheiterter Aufbau hinterlässt keine Verbindung.")
    }

    @Test
    fun einFingerabdruckfehlerBleibtEinFingerabdruckfehler() {
        val sitzung = sitzungsthread()
        // Was die TLS-Fabrik auf dem Gerät tut, wenn der Fingerabdruck nicht
        // stimmt: einen bereits eingeordneten Fehler werfen. Geprüft wird hier
        // nur, dass der Transport ihn **unverwaschen** durchreicht — T-065:
        // „Ein Angriff darf nicht wie eine Störung aussehen."
        val abweisend = object : Socketquelle {
            override fun verbinde(gegenstelle: Gegenstelle): Socket =
                throw Verbindungsfehler(TransportFehler.FingerabdruckPasstNicht("AAAA-BBBB-CCCC", "DDDD-EEEE-FFFF"))
        }
        val transport = transport(sitzung, abweisend)
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Fehlgeschlagen>(1))
        val fehler = mitschnitt.von<TransportEreignis.Fehlgeschlagen>().single().fehler
        assertEquals(TransportFehler.FingerabdruckPasstNicht("AAAA-BBBB-CCCC", "DDDD-EEEE-FFFF"), fehler)
    }

    // ── Die laufende Nummer ─────────────────────────────────────────────────

    @Test
    fun nachDemWiederaufbauGiltEineNeueVerbindungsnummer() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))
        val erste = assertNotNull(transport.verbindungsnummer(host))

        transport.trenne(host, "Wiederaufbau")
        transport.verbinde(host)
        gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(2))
        val zweite = assertNotNull(transport.verbindungsnummer(host))

        assertTrue(zweite > erste, "Eine neue Leitung ist eine neue Nummer: $erste → $zweite.")
    }

    @Test
    fun verspaeteteRahmenEinerAbgeloestenVerbindungWerdenVerworfen() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        val alteLeitung = gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))
        val alteNummer = assertNotNull(transport.verbindungsnummer(host))

        // Den Sitzungsthread anhalten, damit die Übergabe sichtbar stehen bleibt.
        val laeuft = CountDownLatch(1)
        val sperre = CountDownLatch(1)
        sitzung.fuehreAus {
            laeuft.countDown()
            sperre.await()
        }
        assertTrue(laeuft.await(5, TimeUnit.SECONDS), "Der Sitzungsthread ließ sich nicht anhalten.")

        // Der Rahmen der alten Leitung wird gelesen und eingereiht — aber nicht
        // ausgeführt, denn der Sitzungsthread hängt an der Sperre.
        gegen.schreibe(alteLeitung, ereignis("aus der alten Sitzung"))
        assertTrue(warteBis { sitzung.ausstehend >= 1 }, "Der Rahmen wurde nie übergeben.")

        // Jetzt wird die Leitung abgelöst.
        transport.trenne(host, "Abgelöst")
        transport.verbinde(host)
        gegen.naechsteVerbindung()
        val neueNummer = assertNotNull(transport.verbindungsnummer(host))
        assertTrue(neueNummer > alteNummer)

        sperre.countDown()
        assertTrue(sitzung.warteAufLeerlauf(5_000), "Der Sitzungsthread wurde nicht fertig.")

        assertEquals(
            emptyList(),
            mitschnitt.empfangeneNutzlasten(),
            "Ein Rahmen aus einer abgelösten Leitung darf nicht im neuen Ablauf landen (ADR-008).",
        )
    }

    // ── Der Host ────────────────────────────────────────────────────────────

    @Test
    fun derHostHatGenauEinenAnnahmethread() {
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)

        assertEquals(0, lebendeThreads(Sockettransport.ANNAHME_THREADNAME), "Vorher läuft keiner.")
        wirt.lausche(KlartextLauschposten())
        assertEquals(1, lebendeThreads(Sockettransport.ANNAHME_THREADNAME))

        // Ein zweiter wäre eine zweite Stelle, an der die Sitzplatzgrenze zu
        // prüfen wäre. ADR-008 kennt genau einen.
        assertFailsWith<IllegalStateException> { wirt.lausche(KlartextLauschposten()) }
        assertEquals(1, lebendeThreads(Sockettransport.ANNAHME_THREADNAME))
    }

    @Test
    fun mehrereClientsGleichzeitigWerdenAlsEigeneGegenstellenGefuehrt() {
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)
        val mitschnitt = Mitschnitt(sitzung)
        wirt.beobachte(mitschnitt.hoerer())
        val port = wirt.lausche(KlartextLauschposten())

        val gaeste = (1..3).map { nummer ->
            val gast = Socket(InetAddress.getLoopbackAddress(), port)
            schliessbares += gast
            gast.getOutputStream().write(ereignis("gast-$nummer"))
            gast.getOutputStream().flush()
            gast
        }

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(3), "Nicht alle drei wurden angenommen.")
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Empfangen>(3))

        assertEquals(3, wirt.verbundene.size)
        assertEquals(
            3,
            mitschnitt.von<TransportEreignis.Empfangen>().map { it.von.geraeteUid }.toSet().size,
            "Jede Leitung ist eine eigene Gegenstelle.",
        )
        assertTrue(
            wirt.verbundene.all { it.geraeteUid.startsWith(Sockettransport.VORLAEUFIG) },
            "Vor dem Handshake aus TDD 9.3 trägt niemand eine device_uid.",
        )
        assertEquals(setOf("gast-1", "gast-2", "gast-3"), mitschnitt.empfangeneNutzlasten().toSet())
        gaeste.forEach { runCatching { it.close() } }
    }

    @Test
    fun mehrVerbindungenAlsSitzplaetzeWerdenAbgelehntUndNichtGeparkt() {
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung, sitzplaetze = 2)
        val mitschnitt = Mitschnitt(sitzung)
        wirt.beobachte(mitschnitt.hoerer())
        val port = wirt.lausche(KlartextLauschposten())

        val gaeste = (1..3).map {
            Socket(InetAddress.getLoopbackAddress(), port).also { gast -> schliessbares += gast }
        }

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(2))
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Fehlgeschlagen>(1), "Der dritte hätte abgelehnt werden müssen.")

        val abgelehnt = mitschnitt.von<TransportEreignis.Fehlgeschlagen>().single()
        assertNull(abgelehnt.gegenstelle, "Wer keinen Platz bekommt, wird nie eine Gegenstelle.")
        assertTrue(abgelehnt.fehler is TransportFehler.Abgelehnt, "Der Tisch ist voll — das ist keine Störung.")
        assertEquals(2, wirt.verbundene.size)
        gaeste.forEach { runCatching { it.close() } }
    }

    @Test
    fun jeVerbindungGenauEinLeserUndEineSendeschleife() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))

        val nummer = assertNotNull(transport.verbindungsnummer(host))
        assertEquals(1, lebendeThreads(Sockettransport.LESER_PRAEFIX + nummer), "Ein Lesethread je Verbindung.")
        assertEquals(1, lebendeThreads(Sockettransport.SENDER_PRAEFIX + nummer), "Eine Sendeschleife je Verbindung.")
    }

    // ── Herunterfahren ──────────────────────────────────────────────────────

    @Test
    fun beimHerunterfahrenEndenAlleThreads() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val wirt = transport(sitzung)
        val gast = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        gast.beobachte(mitschnitt.hoerer())

        wirt.lausche(KlartextLauschposten())
        gast.verbinde(host)
        gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))
        val nummer = assertNotNull(gast.verbindungsnummer(host))

        wirt.schliesse()
        gast.schliesse()

        assertEquals(0, lebendeThreads(Sockettransport.ANNAHME_THREADNAME), "Der Annahmethread lebt weiter.")
        assertEquals(0, lebendeThreads(Sockettransport.LESER_PRAEFIX + nummer), "Der Lesethread lebt weiter.")
        assertEquals(0, lebendeThreads(Sockettransport.SENDER_PRAEFIX + nummer), "Der Sendethread lebt weiter.")
        assertTrue(gast.verbundene.isEmpty())
    }

    @Test
    fun nachDemHerunterfahrenIstDerTransportUnbrauchbar() {
        val sitzung = sitzungsthread()
        val transport = transport(sitzung)
        transport.schliesse()

        // Dieselbe Regel wie beim Rahmenleser nach einem Protokollfehler (ADR-008).
        assertFailsWith<IllegalStateException> { transport.verbinde(host) }
        assertFailsWith<IllegalStateException> { transport.sende(host, ereignis("zu spät")) }
        assertFailsWith<IllegalStateException> { transport.trenne(host, "zu spät") }
        assertFailsWith<IllegalStateException> { transport.lausche(KlartextLauschposten()) }

        // Ein zweites Herunterfahren ist kein Fehler.
        transport.schliesse()
    }

    @Test
    fun einTransportOhneSocketquelleNimmtNurAn() {
        val sitzung = sitzungsthread()
        val transport = transport(sitzung)
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        // Ein Programmfehler, kein Betriebsfall: Er darf nicht als
        // TransportFehler auftauchen. Sonst sähe er aus wie eine Störung im Netz
        // — und die Wiederverbindung aus T-074 würde ihn geduldig wiederholen.
        assertFailsWith<IllegalStateException> { transport.verbinde(host) }
        assertEquals(0, mitschnitt.von<TransportEreignis.Fehlgeschlagen>().size)
        assertTrue(transport.verbundene.isEmpty())
    }

    // ── Die Regel, aus der alles folgt ──────────────────────────────────────

    @Test
    fun alleRueckrufeLaufenAufDemSitzungsthread() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)
        transport.beobachte(mitschnitt.hoerer())

        transport.verbinde(host)
        val leitung = gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))
        gegen.schreibe(leitung, ereignis("eins") + ereignis("zwei"))
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Empfangen>(2))
        leitung.close()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Getrennt>(1))
        sitzung.warteAufLeerlauf(2_000)

        assertTrue(mitschnitt.ereignisse.size >= 4, "Zu wenige Ereignisse, um etwas zu behaupten.")
        assertEquals(
            0,
            mitschnitt.fremdeThreads.get(),
            "ADR-008: Alles oberhalb von Transport läuft auf dem Sitzungsthread — und nur dort. " +
                "Genau deshalb muss Sitzungsverbindung nicht threadsicher werden.",
        )
    }

    @Test
    fun einRueckrufMitAusnahmeReisstDenSitzungsthreadNichtMit() {
        val gegen = gegenserver()
        val sitzung = sitzungsthread()
        val transport = transport(sitzung, KlartextSocketquelle(gegen.port))
        val mitschnitt = Mitschnitt(sitzung)

        // Reihenfolge mit Absicht: Ein stolpernder Hörer reißt die **nach** ihm
        // eingetragenen mit — genau wie beim `AttrappenTransport`, der die
        // Ausnahme ebenfalls durchreicht. Dass beide sich gleich verhalten, ist
        // der Punkt (ADR-008: „Wer gegen die Attrappe testet, testet weiterhin
        // Reihenfolgen und keinen Scheduler"). Geprüft wird hier das, was der
        // echte Transport zusätzlich leisten muss: Der Sitzungsthread überlebt.
        transport.beobachte(mitschnitt.hoerer())
        transport.beobachte { throw IOException("Ein Hörer, der stolpert.") }

        transport.verbinde(host)
        val leitung = gegen.naechsteVerbindung()
        assertTrue(mitschnitt.warteAuf<TransportEreignis.Verbunden>(1))
        gegen.schreibe(leitung, ereignis("trotzdem"))

        assertTrue(mitschnitt.warteAuf<TransportEreignis.Empfangen>(1), "Nach einer Ausnahme lief nichts mehr.")
        assertEquals(0, mitschnitt.fremdeThreads.get(), "Der Sitzungsthread wurde nicht ausgetauscht.")
        assertTrue(sitzung.fehlerzahl > 0, "Die Ausnahme wurde spurlos verschluckt.")
    }
}
