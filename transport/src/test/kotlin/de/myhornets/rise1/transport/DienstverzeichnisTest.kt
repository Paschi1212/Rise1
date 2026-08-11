package de.myhornets.rise1.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-068 / T-069 — Dienstregistrierung und Dienstsuche.
 *
 * Der wichtigste Test dieser Datei ist [einDienstDarfKeinenFingerabdruckAnkuendigen].
 * Er schützt keine Funktion, sondern den Grundsatz aus [[ADR-002A Key Verification]]:
 * **Wer geprüft wird, darf nicht die Prüfgrundlage liefern.**
 *
 * Der zweitwichtigste ist [derVollstaendigeAblaufFindenVerbindenVerifizieren] —
 * er ist der Grund, aus dem Verzeichnis und Netz an derselben Uhr hängen.
 */
class DienstverzeichnisTest {

    private val hostAdresse = Gegenstelle("d-host", "Paschis Tisch")
    private val zweiterHost = Gegenstelle("d-host-2", "Anderer Tisch")

    private fun dienst(g: Gegenstelle = hostAdresse, port: Int = 8443) =
        Dienst(g, port, mapOf(Dienst.MERKMAL_PARTIENAME to "Freitagsrunde"))

    // ── T-068 Registrierung ─────────────────────────────────────────────────

    @Test
    fun eineRegistrierungGelingt() {
        val netz = Attrappennetz(10)
        val verzeichnis = netz.verzeichnisFuer("d-host")
        var ergebnis: Registrierung? = null
        verzeichnis.registriere(dienst()) { ergebnis = it }

        assertNull(ergebnis, "Ohne Uhrlauf passiert nichts.")
        netz.laufeBis(10)
        assertEquals<Registrierung?>(Registrierung.Erfolgreich(dienst()), ergebnis)
        assertEquals(dienst(), verzeichnis.angekuendigt)
    }

    @Test
    fun eineFehlendeBerechtigungIstEinEigenerFall() {
        // ADR-001: „Wird sie verweigert, kann dieses Gerät nicht hosten — ein
        // anderes Gerät kann es." Das ist eine Auskunft, die die Oberfläche
        // geben können muss, und kein allgemeiner Fehler.
        val netz = Attrappennetz()
        val verzeichnis = netz.verzeichnisFuer("d-host")
        verzeichnis.lassRegistrierungScheitern(Registrierungsfehler.BerechtigungFehlt)
        var ergebnis: Registrierung? = null
        verzeichnis.registriere(dienst()) { ergebnis = it }
        netz.laufeBis(1)

        assertEquals<Registrierung?>(
            Registrierung.Gescheitert(Registrierungsfehler.BerechtigungFehlt),
            ergebnis,
        )
        assertNull(verzeichnis.angekuendigt, "Gescheitert heißt: nichts angekündigt.")
    }

    @Test
    fun einVergebenerNameWirdGemeldetUndSchlaegtEinenNeuenVor() {
        val netz = Attrappennetz()
        netz.verzeichnisFuer("d-host").registriere(dienst()) {}
        netz.laufeBis(1)

        var ergebnis: Registrierung? = null
        netz.verzeichnisFuer("d-host-2")
            .registriere(Dienst(Gegenstelle("d-host-2", "Paschis Tisch"), 8443)) { ergebnis = it }
        netz.laufeBis(2)

        val grund = (ergebnis as Registrierung.Gescheitert).grund as Registrierungsfehler.NameVergeben
        assertEquals("Paschis Tisch (2)", grund.vorgeschlagen)
    }

    @Test
    fun nachDemBeendenIstNichtsMehrAngekuendigt() {
        val netz = Attrappennetz()
        val verzeichnis = netz.verzeichnisFuer("d-host")
        verzeichnis.registriere(dienst()) {}
        netz.laufeBis(1)
        verzeichnis.beendeRegistrierung()
        assertNull(verzeichnis.angekuendigt)

        val gefunden = mutableListOf<Suchereignis>()
        netz.verzeichnisFuer("d-client").sucheAb { gefunden += it }
        netz.laufeBis(2)
        assertTrue(gefunden.isEmpty())
    }

    // ── Der Grundsatz aus ADR-002A 3.2 ──────────────────────────────────────

    @Test
    fun einDienstDarfKeinenFingerabdruckAnkuendigen() {
        // Wer den Dienst vortäuschen kann, kann auch das Merkmal setzen. Ein
        // Fingerabdruck aus der Ankündigung ist so viel wert wie die
        // Ankündigung selbst — also nichts.
        Dienst.VERBOTENE_MERKMALE.forEach { schluessel ->
            assertFailsWith<IllegalArgumentException>("Merkmal `$schluessel` wurde angenommen.") {
                Dienst(hostAdresse, 8443, mapOf(schluessel to "AB-CD"))
            }
        }
    }

    @Test
    fun dieVerbotslisteGreiftAuchBeiGrossschreibung() {
        assertFailsWith<IllegalArgumentException> {
            Dienst(hostAdresse, 8443, mapOf("Fingerabdruck" to "AB-CD"))
        }
    }

    @Test
    fun harmloseMerkmaleGehenDurch() {
        // Sonst prüfte der Test darüber nur, dass überhaupt etwas abgewiesen wird.
        val d = Dienst(
            hostAdresse,
            8443,
            mapOf(Dienst.MERKMAL_PARTIENAME to "Freitagsrunde", Dienst.MERKMAL_SITZPLAETZE_FREI to "3"),
        )
        assertEquals("Freitagsrunde", d.merkmale[Dienst.MERKMAL_PARTIENAME])
    }

    @Test
    fun einUngueltigerPortWirdAbgewiesen() {
        assertFailsWith<IllegalArgumentException> { Dienst(hostAdresse, 0) }
        assertFailsWith<IllegalArgumentException> { Dienst(hostAdresse, 70_000) }
    }

    // ── T-069 Suche ─────────────────────────────────────────────────────────

    @Test
    fun eineSucheFindetWasSchonDaIst() {
        // Sonst fände ein Client nur Hosts, die nach ihm gekommen sind.
        val netz = Attrappennetz(10)
        netz.verzeichnisFuer("d-host").registriere(dienst()) {}
        netz.laufeBis(10)

        val gesehen = mutableListOf<Suchereignis>()
        netz.verzeichnisFuer("d-client").sucheAb { gesehen += it }
        netz.laufeBis(20)

        assertEquals<List<Suchereignis>>(listOf(Suchereignis.Gefunden(dienst())), gesehen)
    }

    @Test
    fun eineSucheFindetWasSpaeterDazukommt() {
        val netz = Attrappennetz(10)
        val gesehen = mutableListOf<Suchereignis>()
        netz.verzeichnisFuer("d-client").sucheAb { gesehen += it }
        netz.laufeBis(10)
        assertTrue(gesehen.isEmpty())

        netz.verzeichnisFuer("d-host").registriere(dienst()) {}
        netz.laufeBis(30)
        assertEquals<List<Suchereignis>>(listOf(Suchereignis.Gefunden(dienst())), gesehen)
    }

    @Test
    fun eineSucheOhneHostFindetNichtsUndScheitertNicht() {
        // „Nichts gefunden" ist kein Fehler. Die Oberfläche muss beides
        // unterscheiden können (ADR-001, T-077).
        val netz = Attrappennetz()
        val gesehen = mutableListOf<Suchereignis>()
        netz.verzeichnisFuer("d-client").sucheAb { gesehen += it }
        netz.laufeBis(1_000)
        assertTrue(gesehen.isEmpty())
    }

    @Test
    fun einGescheiterteSucheMeldetSichAlsSolche() {
        val netz = Attrappennetz()
        val verzeichnis = netz.verzeichnisFuer("d-client")
        verzeichnis.lassSucheScheitern("Mehrfachsendung im Netz gesperrt")
        val gesehen = mutableListOf<Suchereignis>()
        verzeichnis.sucheAb { gesehen += it }
        netz.laufeBis(1)

        assertEquals<Suchereignis>(Suchereignis.Gescheitert("Mehrfachsendung im Netz gesperrt"), gesehen.single())
        assertTrue(!verzeichnis.suchtGerade)
    }

    @Test
    fun derEigeneDienstTauchtInDerEigenenSucheNichtAuf() {
        val netz = Attrappennetz()
        val verzeichnis = netz.verzeichnisFuer("d-host")
        verzeichnis.registriere(dienst()) {}
        netz.laufeBis(1)

        val gesehen = mutableListOf<Suchereignis>()
        verzeichnis.sucheAb { gesehen += it }
        netz.laufeBis(2)
        assertTrue(gesehen.isEmpty(), "Sich selbst beizutreten ergibt keinen Sinn.")
    }

    @Test
    fun einVerschwundenerDienstWirdGemeldet() {
        val netz = Attrappennetz()
        val hostVerzeichnis = netz.verzeichnisFuer("d-host")
        hostVerzeichnis.registriere(dienst()) {}
        netz.laufeBis(1)

        val gesehen = mutableListOf<Suchereignis>()
        netz.verzeichnisFuer("d-client").sucheAb { gesehen += it }
        netz.laufeBis(2)
        hostVerzeichnis.beendeRegistrierung()
        netz.laufeBis(3)

        assertEquals<List<Suchereignis>>(
            listOf(Suchereignis.Gefunden(dienst()), Suchereignis.Verschwunden(hostAdresse)),
            gesehen,
        )
    }

    @Test
    fun einVerschwundenKommtNurFuerVorherGefundenes() {
        // Eine Meldung über etwas, das der Client nie gesehen hat, wäre eine
        // Meldung über nichts.
        val netz = Attrappennetz()
        val hostVerzeichnis = netz.verzeichnisFuer("d-host")
        hostVerzeichnis.registriere(dienst()) {}
        netz.laufeBis(1)
        hostVerzeichnis.beendeRegistrierung()

        val gesehen = mutableListOf<Suchereignis>()
        netz.verzeichnisFuer("d-client").sucheAb { gesehen += it }
        netz.laufeBis(5)
        assertTrue(gesehen.isEmpty())
    }

    @Test
    fun nachBeendeSucheKommtNichtsMehr() {
        val netz = Attrappennetz()
        val verzeichnis = netz.verzeichnisFuer("d-client")
        val gesehen = mutableListOf<Suchereignis>()
        verzeichnis.sucheAb { gesehen += it }
        netz.laufeBis(1)
        verzeichnis.beendeSuche()

        netz.verzeichnisFuer("d-host").registriere(dienst()) {}
        netz.laufeBis(5)
        assertTrue(gesehen.isEmpty())
        assertTrue(!verzeichnis.suchtGerade)
    }

    @Test
    fun zweiHostsWerdenBeideGefunden() {
        val netz = Attrappennetz()
        netz.verzeichnisFuer("d-host").registriere(dienst()) {}
        netz.verzeichnisFuer("d-host-2").registriere(dienst(zweiterHost, 8444)) {}
        netz.laufeBis(1)

        val gesehen = mutableListOf<Suchereignis>()
        netz.verzeichnisFuer("d-client").sucheAb { gesehen += it }
        netz.laufeBis(2)
        assertEquals(2, gesehen.count { it is Suchereignis.Gefunden })
    }

    // ── Die Kopplung an das Netz ────────────────────────────────────────────

    @Test
    fun einUnerreichbarerHostWirdNichtGefunden() {
        // Ohne diese Kopplung könnte ein Test einen Dienst finden, zu dem keine
        // Verbindung zustande kommt — und niemand wüsste, ob das ein Fehler ist
        // oder Absicht.
        val netz = Attrappennetz()
        val host = netz.melde(Hostattrappe(hostAdresse))
        netz.verzeichnisFuer("d-host").registriere(dienst()) {}
        netz.laufeBis(1)
        host.erreichbar = false

        val gesehen = mutableListOf<Suchereignis>()
        netz.verzeichnisFuer("d-client").sucheAb { gesehen += it }
        netz.laufeBis(5)
        assertTrue(gesehen.isEmpty())
    }

    @Test
    fun derVollstaendigeAblaufFindenVerbindenVerifizieren() {
        // Der Ablauf, für den das ganze Fundament gebaut ist. Der Fingerabdruck
        // kommt hier **nicht** aus dem gefundenen Dienst, sondern von außerhalb
        // — er stünde in der App auf dem Bildschirm des Hosts.
        val netz = Attrappennetz(10)
        val host = netz.melde(Hostattrappe(hostAdresse, "AB-CD").spiegle("echo:"))
        netz.verzeichnisFuer("d-host").registriere(dienst()) {}
        netz.laufeBis(10)

        var gefunden: Dienst? = null
        netz.verzeichnisFuer("d-client").sucheAb {
            if (it is Suchereignis.Gefunden) gefunden = it.dienst
        }
        netz.laufeBis(20)
        val ziel = requireNotNull(gefunden) { "Nichts gefunden — der Rest des Tests prüfte nichts." }

        val client = netz.transportFuer("d-client")
        val gesehen = mutableListOf<TransportEreignis>()
        client.beobachte { gesehen += it }
        client.erwarteFingerabdruck(ziel.gegenstelle, "AB-CD") // vom Bildschirm abgelesen
        client.verbinde(ziel.gegenstelle)
        netz.laufeBis(40)
        assertEquals(setOf(hostAdresse), client.verbundene)

        client.sende(ziel.gegenstelle, "hallo".toByteArray())
        netz.laufeBis(80)
        assertEquals(listOf("hallo"), host.empfangenAlsText)
        assertEquals(
            "echo:hallo",
            String(gesehen.filterIsInstance<TransportEreignis.Empfangen>().single().rahmen),
        )
    }

    @Test
    fun einGefundenerDienstMitFalschemFingerabdruckKommtNichtDurch() {
        val netz = Attrappennetz(10)
        netz.melde(Hostattrappe(hostAdresse, "AB-CD"))
        netz.verzeichnisFuer("d-host").registriere(dienst()) {}
        netz.laufeBis(10)

        val client = netz.transportFuer("d-client")
        val gesehen = mutableListOf<TransportEreignis>()
        client.beobachte { gesehen += it }
        client.erwarteFingerabdruck(hostAdresse, "ZZ-ZZ")
        client.verbinde(hostAdresse)
        netz.laufeBis(30)

        assertTrue(
            (gesehen.single() as TransportEreignis.Fehlgeschlagen).fehler
                is TransportFehler.FingerabdruckPasstNicht,
        )
        assertTrue(client.verbundene.isEmpty(), "Gefunden heißt nicht vertraut.")
    }

    @Test
    fun einDienstOhneKennungGibtEsNicht() {
        val netz = Attrappennetz()
        assertFailsWith<IllegalArgumentException> { netz.verzeichnisFuer(" ") }
    }
}
