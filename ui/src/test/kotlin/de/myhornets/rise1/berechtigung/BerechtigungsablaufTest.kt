package de.myhornets.rise1.berechtigung

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-076 — der Ablauf gegen eine Plattform-Attrappe.
 *
 * ## Was hier geprüft wird, das auf einem Gerät niemand prüft
 *
 * Die drei Nicht-Ergebnisse: ein **abgebrochener** Dialog, ein **fremdes**
 * Ergebnis und ungleich lange Felder. Auf einem Gerät stellt man sie kaum her —
 * und genau sie sind die Fälle, in denen Apps eine Ablehnung erfinden, die es
 * nie gab, und den Nutzer danach in die Systemeinstellungen schicken.
 *
 * ## Der wichtigste
 *
 * [einAbgebrochenerDialogIstKeineAblehnung]. Android dokumentiert diesen Fall
 * ausdrücklich mit leeren Feldern. Wer ihn als Ablehnung wertet, verwandelt ein
 * versehentliches Antippen daneben in eine endgültige Verweigerung.
 */
class BerechtigungsablaufTest {

    private val benachrichtigung = Berechtigungsart.BENACHRICHTIGUNGEN.systemname
    private val lokalesNetz = Berechtigungsart.LOKALES_NETZ.systemname

    /** Die Plattform, aber ohne Plattform. */
    private class Systemattrappe : Berechtigungssystem {

        val erteilte = mutableSetOf<String>()
        val begruendungNoetig = mutableSetOf<String>()

        val anfragen = mutableListOf<Pair<List<String>, Int>>()
        val begruendungGefragt = mutableListOf<String>()
        var einstellungenGeoeffnet = 0

        override fun istErteilt(systemname: String): Boolean = systemname in erteilte

        override fun darfBegruendungZeigen(systemname: String): Boolean {
            begruendungGefragt += systemname
            return systemname in begruendungNoetig
        }

        override fun frage(systemnamen: List<String>, kennung: Int) {
            anfragen += systemnamen to kennung
        }

        override fun oeffneEinstellungen() {
            einstellungenGeoeffnet++
        }
    }

    private fun ablauf(
        system: Systemattrappe = Systemattrappe(),
        rolle: Rolle = Rolle.GAST,
        geraeteSdk: Int = 36,
        zielSdk: Int = 36,
        schonGefragt: Set<Berechtigungsart> = emptySet(),
    ) = Berechtigungsablauf(system, Berechtigungslage(rolle, geraeteSdk, zielSdk, schonGefragt))

    // ── Systemstand lesen ───────────────────────────────────────────────────

    @Test
    fun derSystemstandWirdUebernommen() {
        val system = Systemattrappe().apply { erteilte += benachrichtigung }
        val ablauf = ablauf(system)

        ablauf.leseSystemstand()

        assertEquals(Berechtigungsstand.ERTEILT, ablauf.lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
        assertTrue(ablauf.lage.vollstaendig)
        assertEquals(Schritt.NichtsZuTun, ablauf.naechsterSchritt())
    }

    @Test
    fun fuerEineErteilteBerechtigungWirdDieBegruendungGarNichtErstErfragt() {
        // Ein Aufruf ohne Aussage. `shouldShowRequestPermissionRationale` ist
        // für eine erteilte Berechtigung bedeutungslos.
        val system = Systemattrappe().apply { erteilte += benachrichtigung }

        ablauf(system).leseSystemstand()

        assertTrue(system.begruendungGefragt.isEmpty(), "Gefragt wurde: ${system.begruendungGefragt}")
    }

    @Test
    fun derSystemstandWirdBeiJedemLesenNeuGeholt() {
        // Ein Nutzer kann die Berechtigung entziehen, während die App im
        // Hintergrund steht. Ein Ablauf, der sich seinen Stand merkt, meldete
        // danach „alles da".
        val system = Systemattrappe().apply { erteilte += benachrichtigung }
        val ablauf = ablauf(system)
        ablauf.leseSystemstand()
        assertTrue(ablauf.lage.vollstaendig)

        system.erteilte -= benachrichtigung
        system.begruendungNoetig += benachrichtigung
        ablauf.leseSystemstand()

        assertFalse(ablauf.lage.vollstaendig)
        assertEquals(Berechtigungsstand.ABGELEHNT, ablauf.lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
    }

    @Test
    fun nichtBenoetigteBerechtigungenWerdenDasSystemGarNichtGefragt() {
        val system = Systemattrappe()

        // Ziel-SDK 36: Das lokale Netz ist gegenstandslos.
        ablauf(system, rolle = Rolle.HOST, geraeteSdk = 37, zielSdk = 36).leseSystemstand()

        assertFalse(lokalesNetz in system.begruendungGefragt)
        assertTrue(system.anfragen.isEmpty())
    }

    // ── Fragen ──────────────────────────────────────────────────────────────

    @Test
    fun dieFrageOeffnetDenDialogMitDerEigenenKennung() {
        val system = Systemattrappe()
        val ablauf = ablauf(system)
        ablauf.leseSystemstand()

        assertTrue(ablauf.frageOffene())

        assertEquals(1, system.anfragen.size)
        assertEquals(listOf(benachrichtigung), system.anfragen.single().first)
        assertEquals(Berechtigungsablauf.ANFRAGEKENNUNG, system.anfragen.single().second)
    }

    @Test
    fun eineAnfrageOhneOffenePunkteOeffnetKeinenDialog() {
        // Eine Anfrage über eine leere Liste ist auf der Plattform ein Rückruf
        // mit leeren Feldern — und damit von einem Abbruch nicht zu
        // unterscheiden. Solche Zweideutigkeit wird nicht erzeugt.
        val system = Systemattrappe().apply { erteilte += benachrichtigung }
        val ablauf = ablauf(system)
        ablauf.leseSystemstand()

        assertFalse(ablauf.frageOffene())
        assertFalse(ablauf.frage(listOf(Berechtigungsart.BENACHRICHTIGUNGEN)))
        assertTrue(system.anfragen.isEmpty())
    }

    @Test
    fun dasFragenAlleinIstNochKeinVermerk() {
        // Ein geöffneter Dialog ist keine Antwort. Würde hier schon vermerkt,
        // machte ein Abbruch daraus eine endgültige Ablehnung.
        val ablauf = ablauf()
        ablauf.leseSystemstand()
        ablauf.frageOffene()

        assertTrue(ablauf.lage.gefragte.isEmpty())
        assertEquals(Berechtigungsstand.UNGEFRAGT, ablauf.lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
    }

    // ── Ergebnisse ──────────────────────────────────────────────────────────

    @Test
    fun einErteiltesErgebnisWirdUebernommen() {
        val system = Systemattrappe()
        val ablauf = ablauf(system)
        ablauf.leseSystemstand()
        ablauf.frageOffene()
        system.erteilte += benachrichtigung

        assertTrue(
            ablauf.nimmErgebnis(
                Berechtigungsablauf.ANFRAGEKENNUNG,
                arrayOf(benachrichtigung),
                intArrayOf(Berechtigungsablauf.ERTEILT_VOM_SYSTEM),
            ),
        )

        assertEquals(Berechtigungsstand.ERTEILT, ablauf.lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
        assertEquals(Schritt.NichtsZuTun, ablauf.naechsterSchritt())
    }

    @Test
    fun eineAblehnungMitBegruendungspflichtIstNochNichtEndgueltig() {
        val system = Systemattrappe().apply { begruendungNoetig += benachrichtigung }
        val ablauf = ablauf(system)
        ablauf.leseSystemstand()

        ablauf.nimmErgebnis(Berechtigungsablauf.ANFRAGEKENNUNG, arrayOf(benachrichtigung), intArrayOf(-1))

        assertEquals(Berechtigungsstand.ABGELEHNT, ablauf.lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
        assertEquals(Schritt.Begruenden(listOf(Berechtigungsart.BENACHRICHTIGUNGEN)), ablauf.naechsterSchritt())
    }

    @Test
    fun eineAblehnungOhneBegruendungspflichtIstEndgueltig() {
        val ablauf = ablauf()
        ablauf.leseSystemstand()
        ablauf.frageOffene()

        ablauf.nimmErgebnis(Berechtigungsablauf.ANFRAGEKENNUNG, arrayOf(benachrichtigung), intArrayOf(-1))

        assertEquals(Berechtigungsstand.ENDGUELTIG_ABGELEHNT, ablauf.lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
        assertEquals(
            Schritt.NurNochEinstellungen(listOf(Berechtigungsart.BENACHRICHTIGUNGEN)),
            ablauf.naechsterSchritt(),
        )
    }

    @Test
    fun eineWiederholteAnfrageNachEndgueltigerAblehnungFindetNichtStatt() {
        val system = Systemattrappe()
        val ablauf = ablauf(system)
        ablauf.leseSystemstand()
        ablauf.frageOffene()
        ablauf.nimmErgebnis(Berechtigungsablauf.ANFRAGEKENNUNG, arrayOf(benachrichtigung), intArrayOf(-1))
        val bisher = system.anfragen.size

        assertFalse(ablauf.frageOffene(), "Es wird nicht gegen eine Tür gefragt, die nicht mehr aufgeht.")

        assertEquals(bisher, system.anfragen.size)
        ablauf.oeffneEinstellungen()
        assertEquals(1, system.einstellungenGeoeffnet)
    }

    @Test
    fun nachEinerBegruendungDarfErneutGefragtWerden() {
        val system = Systemattrappe().apply { begruendungNoetig += benachrichtigung }
        val ablauf = ablauf(system)
        ablauf.leseSystemstand()
        assertEquals(Schritt.Begruenden(listOf(Berechtigungsart.BENACHRICHTIGUNGEN)), ablauf.naechsterSchritt())

        assertTrue(ablauf.frageOffene(), "Nach der Begründung geht der Dialog wieder auf.")
        assertEquals(listOf(benachrichtigung), system.anfragen.single().first)
    }

    // ── Die drei Nicht-Ergebnisse ───────────────────────────────────────────

    @Test
    fun einAbgebrochenerDialogIstKeineAblehnung() {
        // Android: „It is possible that the permissions request interaction with
        // the user is interrupted. In this case you will receive empty
        // permissions and results arrays."
        val ablauf = ablauf()
        ablauf.leseSystemstand()
        ablauf.frageOffene()

        assertFalse(ablauf.nimmErgebnis(Berechtigungsablauf.ANFRAGEKENNUNG, emptyArray(), intArrayOf()))

        assertEquals(
            Berechtigungsstand.UNGEFRAGT,
            ablauf.lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN),
            "Ein Abbruch darf nicht zu ENDGUELTIG_ABGELEHNT werden.",
        )
        assertTrue(ablauf.lage.gefragte.isEmpty())
    }

    @Test
    fun einFremdesErgebnisWirdIgnoriert() {
        val ablauf = ablauf()
        ablauf.leseSystemstand()

        assertFalse(ablauf.nimmErgebnis(4242, arrayOf(benachrichtigung), intArrayOf(-1)))

        assertEquals(Berechtigungsstand.UNGEFRAGT, ablauf.lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
    }

    @Test
    fun ungleichLangeFelderWerdenNichtGeraten() {
        val ablauf = ablauf()
        ablauf.leseSystemstand()

        assertFalse(
            ablauf.nimmErgebnis(
                Berechtigungsablauf.ANFRAGEKENNUNG,
                arrayOf(benachrichtigung, lokalesNetz),
                intArrayOf(0),
            ),
        )

        assertEquals(Berechtigungsstand.UNGEFRAGT, ablauf.lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
    }

    @Test
    fun einFremderBerechtigungsnameWirdUebersprungen() {
        val system = Systemattrappe().apply { erteilte += benachrichtigung }
        val ablauf = ablauf(system)
        ablauf.leseSystemstand()

        val uebernommen = ablauf.nimmErgebnis(
            Berechtigungsablauf.ANFRAGEKENNUNG,
            arrayOf("android.permission.CAMERA", benachrichtigung),
            intArrayOf(0, 0),
        )

        assertTrue(uebernommen, "Der bekannte Name wurde übernommen.")
        assertEquals(Berechtigungsstand.ERTEILT, ablauf.lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
    }

    @Test
    fun einErgebnisNurZuFremdenNamenAendertNichts() {
        val ablauf = ablauf()
        ablauf.leseSystemstand()

        assertFalse(
            ablauf.nimmErgebnis(
                Berechtigungsablauf.ANFRAGEKENNUNG,
                arrayOf("android.permission.CAMERA"),
                intArrayOf(-1),
            ),
        )

        assertEquals(Berechtigungsstand.UNGEFRAGT, ablauf.lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
    }

    // ── Der Host bei Ziel-SDK 37 ────────────────────────────────────────────

    @Test
    fun derHostFragtBeiZielSdk37BeideBerechtigungenInEinemDialog() {
        // Vorgerechnet für den Tag, an dem `targetSdk` auf 37 geht: Der Ablauf
        // steht dann schon, es ändert sich eine Zahl im Build.
        val system = Systemattrappe()
        val ablauf = ablauf(system, rolle = Rolle.HOST, geraeteSdk = 37, zielSdk = 37)
        ablauf.leseSystemstand()

        assertTrue(ablauf.frageOffene())

        assertEquals(listOf(benachrichtigung, lokalesNetz), system.anfragen.single().first)
    }

    @Test
    fun derGastFragtAuchBeiZielSdk37NichtNachDemLokalenNetz() {
        val system = Systemattrappe()
        val ablauf = ablauf(system, rolle = Rolle.GAST, geraeteSdk = 37, zielSdk = 37)
        ablauf.leseSystemstand()

        ablauf.frageOffene()

        assertEquals(listOf(benachrichtigung), system.anfragen.single().first)
    }
}
