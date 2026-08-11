package de.myhornets.rise1.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T-101 / T-111 / T-110 — Beitritt, Sitzplatz, Wiederzulassung.
 *
 * ## Die Tests, um die es hier geht
 *
 * [derTokenIstLangGenugUndKommtNichtZweimalVor] und
 * [derHostBehaeltNurDenHash]. Der Wiedereinstiegs-Token ist das einzige, was
 * einen Sitzplatz schützt (TDD 9.1). Wäre er kurz, vorhersagbar oder beim Host
 * im Klartext gespeichert, wäre die gesamte Prüfung aus `T-105` eine Formalität.
 *
 * [einVerlorenesGeheimnisBrauchtEinenMenschen] hält TDD 9.6 fest: *„Diese
 * Bestätigung darf nicht wegkonfigurierbar sein."* Ein Standardargument oder
 * ein `Boolean` an dieser Stelle wäre genau die Konfigurierbarkeit, die das TDD
 * ausschließt.
 */
class BeitrittTest {

    private val salz = "salz-m1"

    private class Tisch(
        var status: String? = Partiestatus.SETUP,
        val plaetze: Int = 4,
        val belegt: MutableList<Sitzplatz> = mutableListOf(),
    ) : Tischnachschlag {

        val angelegt = mutableListOf<Triple<Int, String, String>>()
        val ersetzt = mutableListOf<Pair<String, String>>()

        override fun status(matchUid: String): String? = status
        override fun plaetze(matchUid: String): Int = plaetze
        override fun belegung(matchUid: String): List<Sitzplatz> = belegt.toList()

        override fun legeSitzplatzAn(
            matchUid: String,
            sitzplatz: Int,
            anzeigename: String,
            tokenHash: String,
            deviceUid: String,
        ): String {
            angelegt += Triple(sitzplatz, anzeigename, tokenHash)
            val uid = "p-$sitzplatz"
            // Wie in `:ui`: Der Sitzplatz entsteht **und** die erste Sitzung
            // wird eröffnet (T-102). Erst dadurch weiß die Belegung, welches
            // Gerät wo sitzt.
            belegt += Sitzplatz(uid, sitzplatz, Sitzplatzzustand.AKTIV, deviceUid)
            return uid
        }

        override fun ersetzeTokenHash(participantUid: String, tokenHash: String) {
            ersetzt += participantUid to tokenHash
        }
    }

    private fun gesuch(geraet: String = "d-a", platz: Int? = null) =
        Beitrittsgesuch("m-1", geraet, "Paschi", platz)

    // ── Der gute Weg ────────────────────────────────────────────────────────

    @Test
    fun einBeitrittBekommtSitzplatzUndGeheimnis() {
        val tisch = Tisch()
        val antwort = Beitrittsstelle(tisch, salz).beitreten(gesuch()) as Beitrittsantwort.Angenommen

        assertEquals(0, antwort.sitzplatz, "Der erste freie Platz.")
        assertEquals("p-0", antwort.participantUid)
        assertEquals(1, tisch.angelegt.size)
    }

    @Test
    fun dieNaechstenBekommenDieNaechstenPlaetze() {
        val tisch = Tisch()
        val stelle = Beitrittsstelle(tisch, salz)
        val plaetze = listOf("d-a", "d-b", "d-c").map {
            (stelle.beitreten(gesuch(geraet = it)) as Beitrittsantwort.Angenommen).sitzplatz
        }
        assertEquals(listOf(0, 1, 2), plaetze)
    }

    @Test
    fun einWunschplatzWirdBeachtet() {
        val tisch = Tisch()
        val antwort = Beitrittsstelle(tisch, salz)
            .beitreten(gesuch(platz = 2)) as Beitrittsantwort.Angenommen
        assertEquals(2, antwort.sitzplatz)
    }

    @Test
    fun einFreigegebenerPlatzWirdWiederVergeben() {
        // TDD 9.6: Freigegeben wird durch eine bewusste Handlung — danach ist
        // der Platz wirklich frei, sonst schrumpfte der Tisch mit jeder Runde.
        val tisch = Tisch()
        tisch.belegt += Sitzplatz("p-alt", 0, Sitzplatzzustand.FREIGEGEBEN, null)
        val antwort = Beitrittsstelle(tisch, salz).beitreten(gesuch()) as Beitrittsantwort.Angenommen
        assertEquals(0, antwort.sitzplatz)
    }

    @Test
    fun einReservierterPlatzWirdNichtVergeben() {
        // TDD 6.4: Der abwesende Spieler behält seinen Platz. Ihn neu zu
        // vergeben, wäre der Timeout, den TDD 9.1 ausschließt — nur mit
        // zusätzlichen Schritten.
        val tisch = Tisch()
        tisch.belegt += Sitzplatz("p-weg", 0, Sitzplatzzustand.RESERVIERT, null)
        val antwort = Beitrittsstelle(tisch, salz).beitreten(gesuch()) as Beitrittsantwort.Angenommen
        assertNotEquals(0, antwort.sitzplatz)
    }

    // ── Der Token ───────────────────────────────────────────────────────────

    @Test
    fun derTokenIstLangGenugUndKommtNichtZweimalVor() {
        val stelle = Beitrittsstelle(Tisch(plaetze = 64), salz)
        val tokens = (0 until 50).map {
            (stelle.beitreten(gesuch(geraet = "d-$it")) as Beitrittsantwort.Angenommen).rejoinToken
        }

        tokens.forEach {
            assertTrue(
                it.toByteArray(Charsets.UTF_8).size >= RejoinPruefer.MINDESTLAENGE_BYTES,
                "Ein Token mit ${it.length} Zeichen erfüllt TDD 9.1 nicht.",
            )
        }
        assertEquals(tokens.size, tokens.toSet().size, "Zwei gleiche Tokens aus SecureRandom.")
    }

    @Test
    fun derHostBehaeltNurDenHash() {
        val tisch = Tisch()
        val antwort = Beitrittsstelle(tisch, salz).beitreten(gesuch()) as Beitrittsantwort.Angenommen
        val gespeichert = tisch.angelegt.single().third

        assertNotEquals(antwort.rejoinToken, gespeichert)
        assertEquals(RejoinPruefer.tokenHash(antwort.rejoinToken, salz), gespeichert)
    }

    @Test
    fun derFrischeTokenBestehtDenWiedereinstieg() {
        // Der eigentliche Zweck des Beitritts: Was hier ausgegeben wird, muss in
        // `T-105` durchkommen. Beide Seiten benutzen dieselbe Ableitung — dieser
        // Test würde rot, wenn eine von beiden sich änderte.
        val tisch = Tisch()
        val beitritt = Beitrittsstelle(tisch, salz).beitreten(gesuch()) as Beitrittsantwort.Angenommen
        val hash = tisch.angelegt.single().third

        val pruefer = RejoinPruefer(
            object : Partienachschlag {
                override fun status(matchUid: String) = Partiestatus.ACTIVE
                override fun sitzplatz(matchUid: String, participantUid: String) =
                    Sitzplatznachweis(beitritt.participantUid, hash, salz)

                override fun eroeffneSitzung(m: String, p: String, d: String) = "s-1"
                override fun hoechsteSeq(matchUid: String) = 0L
            },
            { 0L },
        )

        val antwort = pruefer.pruefe(
            Wiedereinstiegsgesuch("m-1", beitritt.participantUid, beitritt.rejoinToken, "d-a", -1),
        )
        assertTrue(antwort is Wiedereinstiegsantwort.Angenommen)
    }

    @Test
    fun einFremderTokenBestehtDenWiedereinstiegNicht() {
        val tisch = Tisch()
        val stelle = Beitrittsstelle(tisch, salz)
        val a = stelle.beitreten(gesuch(geraet = "d-a")) as Beitrittsantwort.Angenommen
        stelle.beitreten(gesuch(geraet = "d-b"))
        val hashVonA = tisch.angelegt.first().third
        val tokenVonB = (stelle.beitreten(gesuch(geraet = "d-c")) as Beitrittsantwort.Angenommen).rejoinToken

        val pruefer = RejoinPruefer(
            object : Partienachschlag {
                override fun status(matchUid: String) = Partiestatus.ACTIVE
                override fun sitzplatz(matchUid: String, participantUid: String) =
                    Sitzplatznachweis(a.participantUid, hashVonA, salz)

                override fun eroeffneSitzung(m: String, p: String, d: String) = "s-1"
                override fun hoechsteSeq(matchUid: String) = 0L
            },
            { 0L },
        )

        assertEquals<Wiedereinstiegsantwort>(
            Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.NACHWEIS_FALSCH),
            pruefer.pruefe(Wiedereinstiegsgesuch("m-1", a.participantUid, tokenVonB, "d-c", -1)),
        )
    }

    @Test
    fun derTokenStehtInKeinerAntwortAlsText() {
        val antwort = Beitrittsstelle(Tisch(), salz).beitreten(gesuch()) as Beitrittsantwort.Angenommen
        assertFalse(antwort.toString().contains(antwort.rejoinToken))
    }

    @Test
    fun einZuKurzerZufallWirdBemerkt() {
        // Falls je eine Quelle eingesetzt wird, die weniger liefert: lieber ein
        // Fehler als ein kurzes Geheimnis.
        val stelle = Beitrittsstelle(Tisch(), salz, zufall = { ByteArray(4) })
        assertFailsWith<IllegalArgumentException> { stelle.beitreten(gesuch()) }
    }

    // ── Ablehnungen ─────────────────────────────────────────────────────────

    @Test
    fun eineUnbekanntePartieNimmtNiemandenAuf() {
        assertEquals<Beitrittsantwort>(
            Beitrittsantwort.Abgelehnt(Beitrittsablehnung.PARTIE_UNBEKANNT),
            Beitrittsstelle(Tisch(status = null), salz).beitreten(gesuch()),
        )
    }

    @Test
    fun nachDemStartKommtNiemandMehrNeuHinzu() {
        listOf(Partiestatus.DEALING, Partiestatus.ACTIVE, Partiestatus.PAUSED, Partiestatus.FINISHED)
            .forEach { status ->
                assertEquals<Beitrittsantwort>(
                    Beitrittsantwort.Abgelehnt(Beitrittsablehnung.PARTIE_NIMMT_NICHT_MEHR_AUF),
                    Beitrittsstelle(Tisch(status = status), salz).beitreten(gesuch()),
                    "Status $status",
                )
            }
    }

    @Test
    fun einVollerTischNimmtNiemandenMehr() {
        val tisch = Tisch(plaetze = 2)
        val stelle = Beitrittsstelle(tisch, salz)
        stelle.beitreten(gesuch(geraet = "d-a"))
        stelle.beitreten(gesuch(geraet = "d-b"))

        assertEquals<Beitrittsantwort>(
            Beitrittsantwort.Abgelehnt(Beitrittsablehnung.TISCH_VOLL),
            stelle.beitreten(gesuch(geraet = "d-c")),
        )
    }

    @Test
    fun einBesetzterWunschplatzWirdAbgelehnt() {
        val tisch = Tisch()
        val stelle = Beitrittsstelle(tisch, salz)
        stelle.beitreten(gesuch(geraet = "d-a", platz = 1))

        assertEquals<Beitrittsantwort>(
            Beitrittsantwort.Abgelehnt(Beitrittsablehnung.PLATZ_BESETZT),
            stelle.beitreten(gesuch(geraet = "d-b", platz = 1)),
        )
    }

    @Test
    fun einPlatzJenseitsDesTischesWirdAbgelehnt() {
        assertEquals<Beitrittsantwort>(
            Beitrittsantwort.Abgelehnt(Beitrittsablehnung.PLATZ_BESETZT),
            Beitrittsstelle(Tisch(plaetze = 4), salz).beitreten(gesuch(platz = 9)),
        )
    }

    @Test
    fun dasselbeGeraetBekommtKeinenZweitenSitzplatz() {
        // Sonst besetzte ein Gerät den halben Tisch — und ein doppelt gedrückter
        // Knopf verbrauchte einen Platz.
        val tisch = Tisch()
        val stelle = Beitrittsstelle(tisch, salz)
        stelle.beitreten(gesuch(geraet = "d-a"))

        assertEquals<Beitrittsantwort>(
            Beitrittsantwort.Abgelehnt(Beitrittsablehnung.GERAET_SITZT_SCHON),
            stelle.beitreten(gesuch(geraet = "d-a")),
        )
    }

    @Test
    fun einZweiterBeitrittGibtKeinenAltenSitzplatzZurueck() {
        // Der Unterschied zum Wiedereinstieg: Der bringt einen Nachweis mit,
        // der Beitritt nicht. Wiedererkennung reicht hier nicht (TDD 9.1).
        val tisch = Tisch()
        tisch.belegt += Sitzplatz("p-0", 0, Sitzplatzzustand.RESERVIERT, "d-a")
        val antwort = Beitrittsstelle(tisch, salz).beitreten(gesuch(geraet = "d-a"))
        assertTrue(antwort is Beitrittsantwort.Abgelehnt)
    }

    // ── T-110 Wiederzulassung (TDD 9.6) ─────────────────────────────────────

    @Test
    fun einVerlorenesGeheimnisBrauchtEinenMenschen() {
        val tisch = Tisch()
        val neu = Wiederzulassung(tisch, salz).stelleNeuAus(
            "p-3",
            Wiederzulassung.Bestaetigung(durch = "Gastgeber", bei = 1_000),
        )

        assertEquals("p-3", neu.participantUid)
        assertEquals(listOf("p-3" to RejoinPruefer.tokenHash(neu.rejoinToken, salz)), tisch.ersetzt)
        assertEquals("participant_readmitted", neu.ereignis.typ)
        assertEquals(1_000L, neu.ereignis.occurredAt)
    }

    @Test
    fun eineBestaetigungOhneBestaetigendenGibtEsNicht() {
        // „Nicht wegkonfigurierbar" (TDD 9.6) heißt auch: nicht durch einen
        // leeren Namen zu umgehen.
        assertFailsWith<IllegalArgumentException> {
            Wiederzulassung.Bestaetigung(durch = "   ", bei = 1)
        }
    }

    @Test
    fun dasNeueGeheimnisErsetztDasAlteUndDasAlteGiltNichtMehr() {
        val tisch = Tisch()
        val beitritt = Beitrittsstelle(tisch, salz).beitreten(gesuch()) as Beitrittsantwort.Angenommen
        val alterToken = beitritt.rejoinToken

        val neu = Wiederzulassung(tisch, salz)
            .stelleNeuAus(beitritt.participantUid, Wiederzulassung.Bestaetigung("Gastgeber", 1))
        val neuerHash = tisch.ersetzt.single().second

        val pruefer = RejoinPruefer(
            object : Partienachschlag {
                override fun status(matchUid: String) = Partiestatus.ACTIVE
                override fun sitzplatz(matchUid: String, participantUid: String) =
                    Sitzplatznachweis(beitritt.participantUid, neuerHash, salz)

                override fun eroeffneSitzung(m: String, p: String, d: String) = "s-1"
                override fun hoechsteSeq(matchUid: String) = 0L
            },
            { 0L },
        )

        assertTrue(
            pruefer.pruefe(
                Wiedereinstiegsgesuch("m-1", beitritt.participantUid, neu.rejoinToken, "d-neu", -1),
            ) is Wiedereinstiegsantwort.Angenommen,
            "Das neue Geheimnis muss gelten.",
        )
        assertEquals<Wiedereinstiegsantwort>(
            Wiedereinstiegsantwort.Abgelehnt(Ablehnungsgrund.NACHWEIS_FALSCH),
            pruefer.pruefe(
                Wiedereinstiegsgesuch("m-1", beitritt.participantUid, alterToken, "d-alt", -1),
            ),
            "Zwei gültige Geheimnisse wären zwei Schlüssel für dieselbe Tür.",
        )
    }

    @Test
    fun dasWiederzulassungsereignisIstOeffentlichUndKeinSpielzustand() {
        val neu = Wiederzulassung(Tisch(), salz)
            .stelleNeuAus("p-1", Wiederzulassung.Bestaetigung("Gastgeber", 1))

        // PUBLIC: Dass jemand ohne Nachweis hereingelassen wurde, geht den
        // ganzen Tisch an. Und `session`, nicht `state` — es ändert nichts am Spiel.
        assertEquals(de.myhornets.rise1.core.event.Visibility.PUBLIC, neu.ereignis.visibility)
        assertEquals(de.myhornets.rise1.core.event.EventClass.SESSION, neu.ereignis.eventClass)
    }

    @Test
    fun dieNeuausstellungZeigtDenTokenNichtImText() {
        val neu = Wiederzulassung(Tisch(), salz)
            .stelleNeuAus("p-1", Wiederzulassung.Bestaetigung("Gastgeber", 1))
        assertFalse(neu.toString().contains(neu.rejoinToken))
    }
}
