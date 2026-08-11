package de.myhornets.rise1.berechtigung

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-076 — der Berechtigungszustand.
 *
 * ## Der wichtigste Test
 *
 * [ungefragtUndEndgueltigAbgelehntSindFuerDiePlattformNichtZuUnterscheiden].
 * Vor der ersten Frage und nach der endgültigen Ablehnung antwortet Android
 * **identisch**: nicht erteilt, keine Begründung nötig. Wer die beiden Fälle
 * zusammenwirft, baut entweder eine App, die nie fragt, oder eine, die ewig
 * gegen eine Tür läuft, die nicht mehr aufgeht.
 *
 * ## Der zweitwichtigste
 *
 * [dasLokaleNetzIstBeiZielSdk36AufKeinemGeraetNoetig]. Er hält den Befund fest,
 * der über den Umfang von T-076 entschieden hat: Die Sperre aus Android 17
 * hängt am **Ziel-SDK**, nicht an der Geräteversion. Solange `targetSdk = 36`
 * gilt (D-001, D-001A), gibt es nichts zu erfragen — auch auf einem Gerät mit
 * Android 17 nicht.
 *
 * ## Warum alle SDK-Stände hier Zahlen sind
 *
 * Damit sich auch die Kombination von morgen prüfen lässt, ohne sie zu bauen.
 * `Build.VERSION.SDK_INT` kommt genau einmal vor, in `berechtigungslage()`.
 */
class BerechtigungslageTest {

    private val android12 = 32
    private val android13 = 33
    private val android16 = 36
    private val android17 = 37

    private fun lage(
        rolle: Rolle = Rolle.GAST,
        geraeteSdk: Int = android16,
        zielSdk: Int = 36,
        schonGefragt: Set<Berechtigungsart> = emptySet(),
    ) = Berechtigungslage(rolle, geraeteSdk, zielSdk, schonGefragt)

    // ── Was überhaupt gebraucht wird ────────────────────────────────────────

    @Test
    fun vorAndroid13GibtEsDieBenachrichtigungsberechtigungNicht() {
        val lage = lage(geraeteSdk = android12)

        assertEquals(Berechtigungsstand.NICHT_NOETIG, lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
        assertTrue(lage.benoetigt.isEmpty())
        assertTrue(lage.vollstaendig, "Was nicht gebraucht wird, fehlt auch nicht.")
    }

    @Test
    fun abAndroid13BrauchenBeideRollenDieBenachrichtigung() {
        // Der Vordergrunddienst aus T-075 läuft auf jedem Gerät am Tisch.
        listOf(Rolle.HOST, Rolle.GAST).forEach { rolle ->
            val lage = lage(rolle = rolle, geraeteSdk = android13)
            assertEquals(
                Berechtigungsstand.UNGEFRAGT,
                lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN),
                "Rolle $rolle",
            )
        }
    }

    @Test
    fun dasLokaleNetzIstBeiZielSdk36AufKeinemGeraetNoetig() {
        // Der Befund, der den Umfang von T-076 bestimmt hat: „Beginning with
        // Android 17, enforcement is mandatory for apps that target Android 17
        // (API level 37) or higher." Rise zielt auf 36.
        val hostAufAndroid17 = lage(rolle = Rolle.HOST, geraeteSdk = android17, zielSdk = 36)

        assertEquals(Berechtigungsstand.NICHT_NOETIG, hostAufAndroid17.stand(Berechtigungsart.LOKALES_NETZ))
        assertFalse(Berechtigungsart.LOKALES_NETZ in hostAufAndroid17.benoetigt)
    }

    @Test
    fun abZielSdk37BrauchtNurDerHostDasLokaleNetz() {
        // ADR-001: „Nur das Host-Gerät braucht die breite Berechtigung … Alle
        // übrigen Spieler kommen mit dem System-Dialog aus."
        val host = lage(rolle = Rolle.HOST, geraeteSdk = android17, zielSdk = 37)
        val gast = lage(rolle = Rolle.GAST, geraeteSdk = android17, zielSdk = 37)

        assertEquals(Berechtigungsstand.UNGEFRAGT, host.stand(Berechtigungsart.LOKALES_NETZ))
        assertEquals(Berechtigungsstand.NICHT_NOETIG, gast.stand(Berechtigungsart.LOKALES_NETZ))
    }

    @Test
    fun aufEinemGeraetVorAndroid17GibtEsDasLokaleNetzAuchBeiZielSdk37Nicht() {
        // Ein Ziel-SDK ist keine Geräteversion. Eine Berechtigung, die die
        // laufende Plattform nicht kennt, wäre nur abzulehnen.
        val lage = lage(rolle = Rolle.HOST, geraeteSdk = android16, zielSdk = 37)

        assertEquals(Berechtigungsstand.NICHT_NOETIG, lage.stand(Berechtigungsart.LOKALES_NETZ))
    }

    @Test
    fun dieBerechtigungsnamenSindDieDerPlattform() {
        // Sie stehen als Zeichenketten im Quelltext, weil die Konstante für das
        // lokale Netz erst in SDK 37 existiert. Tippfehler fielen sonst erst auf
        // einem Gerät auf.
        assertEquals("android.permission.POST_NOTIFICATIONS", Berechtigungsart.BENACHRICHTIGUNGEN.systemname)
        assertEquals("android.permission.ACCESS_LOCAL_NETWORK", Berechtigungsart.LOKALES_NETZ.systemname)
        assertEquals(33, Berechtigungsart.BENACHRICHTIGUNGEN.abSdk)
        assertEquals(37, Berechtigungsart.LOKALES_NETZ.abSdk)
        assertEquals(37, Berechtigungsbedarf.SPERRE_AB_ZIELSDK)
    }

    // ── Die Zustände ────────────────────────────────────────────────────────

    @Test
    fun ungefragtUndEndgueltigAbgelehntSindFuerDiePlattformNichtZuUnterscheiden() {
        // Beide Male sagt Android dasselbe: nicht erteilt, keine Begründung.
        val nieGefragt = lage(geraeteSdk = android13)
        val schonAbgelehnt = lage(geraeteSdk = android13, schonGefragt = setOf(Berechtigungsart.BENACHRICHTIGUNGEN))

        nieGefragt.uebernimm(Berechtigungsart.BENACHRICHTIGUNGEN, erteilt = false, begruendungZeigen = false)
        schonAbgelehnt.uebernimm(Berechtigungsart.BENACHRICHTIGUNGEN, erteilt = false, begruendungZeigen = false)

        assertEquals(Berechtigungsstand.UNGEFRAGT, nieGefragt.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
        assertEquals(
            Berechtigungsstand.ENDGUELTIG_ABGELEHNT,
            schonAbgelehnt.stand(Berechtigungsart.BENACHRICHTIGUNGEN),
            "Der Unterschied kommt allein aus dem eigenen Wissen, dass schon gefragt wurde.",
        )
        assertEquals(Schritt.Fragen(listOf(Berechtigungsart.BENACHRICHTIGUNGEN)), nieGefragt.naechsterSchritt())
        assertEquals(
            Schritt.NurNochEinstellungen(listOf(Berechtigungsart.BENACHRICHTIGUNGEN)),
            schonAbgelehnt.naechsterSchritt(),
        )
    }

    @Test
    fun eineAblehnungMitBegruendungspflichtIstNichtEndgueltig() {
        val lage = lage(geraeteSdk = android13)

        lage.uebernimm(Berechtigungsart.BENACHRICHTIGUNGEN, erteilt = false, begruendungZeigen = true)

        assertEquals(Berechtigungsstand.ABGELEHNT, lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
        assertEquals(Schritt.Begruenden(listOf(Berechtigungsart.BENACHRICHTIGUNGEN)), lage.naechsterSchritt())
    }

    @Test
    fun dieBegruendungspflichtVerraetDassSchonGefragtWurde() {
        // Das System verlangt eine Begründung nur nach einer Ablehnung. Also
        // wurde gefragt — auch wenn der Aufrufer seinen Vermerk verloren hat.
        val lage = lage(geraeteSdk = android13, schonGefragt = emptySet())

        lage.uebernimm(Berechtigungsart.BENACHRICHTIGUNGEN, erteilt = false, begruendungZeigen = true)

        assertEquals(setOf(Berechtigungsart.BENACHRICHTIGUNGEN), lage.gefragte)
    }

    @Test
    fun eineErteilungHebtEineFruehereAblehnungAuf() {
        val lage = lage(geraeteSdk = android13)
        lage.uebernimm(Berechtigungsart.BENACHRICHTIGUNGEN, erteilt = false, begruendungZeigen = true)
        assertEquals(Berechtigungsstand.ABGELEHNT, lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))

        // Der Nutzer war in den Einstellungen.
        lage.uebernimm(Berechtigungsart.BENACHRICHTIGUNGEN, erteilt = true, begruendungZeigen = false)

        assertEquals(Berechtigungsstand.ERTEILT, lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
        assertTrue(lage.vollstaendig)
        assertEquals(Schritt.NichtsZuTun, lage.naechsterSchritt())
    }

    @Test
    fun einEntzugInDenEinstellungenFaelltAuf() {
        // Andersherum: Was einmal erteilt war, kann wieder weg sein.
        val lage = lage(geraeteSdk = android13, schonGefragt = setOf(Berechtigungsart.BENACHRICHTIGUNGEN))
        lage.uebernimm(Berechtigungsart.BENACHRICHTIGUNGEN, erteilt = true, begruendungZeigen = false)
        assertTrue(lage.vollstaendig)

        lage.uebernimm(Berechtigungsart.BENACHRICHTIGUNGEN, erteilt = false, begruendungZeigen = true)

        assertFalse(lage.vollstaendig)
        assertEquals(Berechtigungsstand.ABGELEHNT, lage.stand(Berechtigungsart.BENACHRICHTIGUNGEN))
    }

    @Test
    fun einNichtBenoetigterVermerkVerschiebtNichts() {
        // Ein Gast kann für das lokale Netz nichts vermerken, was er nie braucht.
        val gast = lage(rolle = Rolle.GAST, geraeteSdk = android17, zielSdk = 37)

        gast.merkeAnfrage(listOf(Berechtigungsart.LOKALES_NETZ))

        assertTrue(gast.gefragte.isEmpty())
        assertEquals(Berechtigungsstand.NICHT_NOETIG, gast.stand(Berechtigungsart.LOKALES_NETZ))
    }

    // ── Die Reihenfolge der Schritte ────────────────────────────────────────

    @Test
    fun begruendenKommtVorFragenUndFragenVorDenEinstellungen() {
        val lage = Berechtigungslage(
            rolle = Rolle.HOST,
            geraeteSdk = android17,
            zielSdk = 37,
            schonGefragt = setOf(Berechtigungsart.BENACHRICHTIGUNGEN),
        )
        // Benachrichtigungen: endgültig abgelehnt. Lokales Netz: noch nie gefragt.
        lage.uebernimm(Berechtigungsart.BENACHRICHTIGUNGEN, erteilt = false, begruendungZeigen = false)
        lage.uebernimm(Berechtigungsart.LOKALES_NETZ, erteilt = false, begruendungZeigen = false)

        // Erst das Offene fragen — nicht den Nutzer aus der App schicken.
        assertEquals(Schritt.Fragen(listOf(Berechtigungsart.LOKALES_NETZ)), lage.naechsterSchritt())

        // Jetzt zusätzlich eine Ablehnung mit Begründungspflicht: Sie geht vor.
        lage.uebernimm(Berechtigungsart.LOKALES_NETZ, erteilt = false, begruendungZeigen = true)
        assertEquals(Schritt.Begruenden(listOf(Berechtigungsart.LOKALES_NETZ)), lage.naechsterSchritt())

        // Und wenn nichts mehr zu fragen ist, bleiben die Einstellungen.
        lage.uebernimm(Berechtigungsart.LOKALES_NETZ, erteilt = true, begruendungZeigen = false)
        assertEquals(
            Schritt.NurNochEinstellungen(listOf(Berechtigungsart.BENACHRICHTIGUNGEN)),
            lage.naechsterSchritt(),
        )
    }

    @Test
    fun ohneBedarfGibtEsNichtsZuTun() {
        val lage = lage(geraeteSdk = android12)

        assertEquals(Schritt.NichtsZuTun, lage.naechsterSchritt())
        assertTrue(lage.vollstaendig)
    }

    @Test
    fun alleStaendeNennenAuchDieNichtBenoetigten() {
        val lage = lage(geraeteSdk = android16)
        val staende = lage.alleStaende()

        assertEquals(Berechtigungsart.entries.size, staende.size)
        assertEquals(Berechtigungsstand.UNGEFRAGT, staende[Berechtigungsart.BENACHRICHTIGUNGEN])
        assertEquals(Berechtigungsstand.NICHT_NOETIG, staende[Berechtigungsart.LOKALES_NETZ])
    }

    // ── Was die Oberfläche sagt ─────────────────────────────────────────────

    @Test
    fun dieErklaerungZumLokalenNetzNenntDenAusweg() {
        // ADR-001: „Wird sie verweigert, kann dieses Gerät nicht hosten — ein
        // anderes Gerät kann es." Genau das muss dastehen, statt einer
        // Fehlermeldung, die nur den Zustand nennt.
        val text = lage(rolle = Rolle.HOST).erklaerung(Berechtigungsart.LOKALES_NETZ)

        assertTrue(text.contains("anderes Gerät"), "Der Ausweg fehlt: $text")
        assertTrue(text.contains("beitreten", ignoreCase = true), "Dass Beitreten weiter geht, fehlt: $text")
    }

    @Test
    fun jedeBerechtigungHatEineErklaerung() {
        val lage = lage()
        Berechtigungsart.entries.forEach { art ->
            assertTrue(lage.erklaerung(art).length > 20, "Zu $art steht nichts Brauchbares.")
        }
    }
}
