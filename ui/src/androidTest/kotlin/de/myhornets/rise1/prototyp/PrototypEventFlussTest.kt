package de.myhornets.rise1.prototyp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.myhornets.rise1.core.event.EventType
import de.myhornets.rise1.ereignis.EventAbbildung
import de.myhornets.rise1.projection.MatchFold
import de.myhornets.rise1.store.RiseDatabase
import de.myhornets.rise1.store.RiseStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S2 — der Prototyp läuft über das Event-Log.
 *
 * ## Was diese Klasse beweisen soll
 *
 * Nicht, dass die Oberfläche hübsch ist. Sondern dass es **genau einen Weg**
 * gibt, auf dem sich der Spielzustand ändert. Die schärfste Prüfung dafür ist
 * [matchParticipantTraegtKeinenAufdeckzustandMehr]: Sie schaut in die Tabelle,
 * in die der Prototyp vor S2 geschrieben hat, und verlangt, dass dort nichts
 * mehr ankommt — auch wenn die Oberfläche sich unverändert verhält.
 *
 * Die zweite ist [dieFaltungErgibtDasselbeWieDieFortschreibung]: Sie führt die
 * beiden **unabhängigen** Umsetzungen der Faltregeln gegeneinander — die in
 * SQL (`MatchEventLog`) und die in reinem JVM (`MatchFold`). Diese Prüfung war
 * in `T-025` als offener Punkt vermerkt, weil sie nur hier möglich ist:
 * `:store` darf `:projection` nicht kennen.
 */
@RunWith(AndroidJUnit4::class)
class PrototypEventFlussTest {

    private lateinit var kontext: Context
    private lateinit var steuerung: PrototypSteuerung
    private lateinit var db: RiseDatabase

    private val namen = listOf("Anna", "Bert", "Cem", "Dora")

    @Before
    fun aufraeumen() {
        kontext = ApplicationProvider.getApplicationContext()
        kontext.deleteDatabase(RiseStore.DATABASE_NAME)
        // Eine Datenbank, zwei Nutzer. Zwei `RiseStore.open`-Aufrufe wären zwei
        // Verbindungen auf dieselbe Datei — dann prüfte dieser Test am Ende die
        // Sperrverwaltung von SQLite und nicht den eigenen Code.
        db = RiseStore.open(kontext)
        steuerung = PrototypSteuerung(kontext, db)
    }

    @After
    fun schliessen() {
        db.close()
        kontext.deleteDatabase(RiseStore.DATABASE_NAME)
    }

    // ── S2.1 Match-Erstellung ───────────────────────────────────────────────

    @Test
    fun neueRundeErzeugtMatchCreatedUndJeSitzplatzEinParticipantJoined() {
        val partie = steuerung.neueRunde(namen)
        val typen = db.eventLogDao().alle(partie).map { it.type }
        assertEquals(
            listOf(EventType.MATCH_CREATED.wert) + List(namen.size) { EventType.PARTICIPANT_JOINED.wert },
            typen,
        )
    }

    @Test
    fun matchCreatedTraegtSeqNull() {
        // TDD 5.4: `match_created` ist immer seq = 0. Hier ergibt es sich, weil
        // es das erste Event der Partie ist — festgehalten, damit es auffällt,
        // wenn jemand vorher etwas anderes anhängt.
        val partie = steuerung.neueRunde(namen)
        val erstes = db.eventLogDao().bestaetigte(partie).first()
        assertEquals(EventType.MATCH_CREATED.wert, erstes.type)
        assertEquals(0L, erstes.seq)
    }

    @Test
    fun keinEventTraegtNutzdaten() {
        // S2 kommt ohne JSON im Log aus. Sollte das je nötig werden, ist es
        // eine Entscheidung und kein Nebenprodukt — dieser Test macht sie
        // sichtbar.
        val partie = steuerung.neueRunde(namen)
        db.eventLogDao().alle(partie).forEach {
            assertNull("payload_json bei ${it.type}", it.payloadJson)
            assertNull("payload_ciphertext bei ${it.type}", it.payloadCiphertext)
        }
    }

    // ── S2.2 Teilnehmer ─────────────────────────────────────────────────────

    @Test
    fun teilnehmerEntstehenAusDemEventUndNichtAusDerEinfuegung() {
        val partie = steuerung.neueRunde(namen)
        val ausProjektion = db.projectionDao().teilnehmerDerPartie(partie)
        assertEquals(namen.size, ausProjektion.size)
        assertTrue("Kein Teilnehmer darf am Anfang aufgedeckt sein.", ausProjektion.none { it.isRevealed })
        assertTrue("Startwert für life ist 0 — kein Modus, keine Regel.", ausProjektion.all { it.life == 0 })
    }

    @Test
    fun dieProjektionKenntDiePartie() {
        val partie = steuerung.neueRunde(namen)
        val zustand = db.projectionDao().partie(partie)
        assertNotNull("match_state fehlt — die Partie hätte keinen Stand.", zustand)
        assertEquals(namen.size.toLong(), zustand!!.lastAppliedSeq)
        assertEquals(0, zustand.turnNumber)
    }

    // ── S2.3 Aufdecken ──────────────────────────────────────────────────────

    @Test
    fun aufdeckenErzeugtEinIdentityRevealed() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitz = nichtAufgedeckterSitz(partie)

        val vorher = anzahl(partie, EventType.IDENTITY_REVEALED)
        steuerung.decke(sitz)
        assertEquals(vorher + 1, anzahl(partie, EventType.IDENTITY_REVEALED))
    }

    @Test
    fun nachDemAufdeckenIstDerZustandKorrekt() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitz = nichtAufgedeckterSitz(partie)
        steuerung.decke(sitz)

        assertTrue(db.projectionDao().teilnehmer(sitz)!!.isRevealed)
        val stand = steuerung.stand(partie)
        val angezeigt = stand.sitzplaetze.first { it.participantUid == sitz }
        assertTrue("Die Oberfläche zeigt den Sitzplatz nicht als aufgedeckt.", angezeigt.istAufgedeckt)
        assertNotNull("Die aufgedeckte Karte fehlt.", angezeigt.aufgedeckteIdentitaet)
    }

    @Test
    fun zweimalAufdeckenErzeugtKeinZweitesEvent() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitz = nichtAufgedeckterSitz(partie)

        steuerung.decke(sitz)
        val nachEinmal = anzahl(partie, EventType.IDENTITY_REVEALED)
        steuerung.decke(sitz)
        steuerung.decke(sitz)
        assertEquals(
            "Wiederholtes Aufdecken hat weitere Events erzeugt. Das Log würde ein " +
                "Ereignis behaupten, das es nicht gab.",
            nachEinmal,
            anzahl(partie, EventType.IDENTITY_REVEALED),
        )
        assertTrue(db.projectionDao().teilnehmer(sitz)!!.isRevealed)
    }

    // ── S2.4 Keine zweite Wahrheitsquelle ───────────────────────────────────

    @Test
    fun matchParticipantTraegtKeinenAufdeckzustandMehr() {
        // Der Kern von S2. Vor der Umstellung standen hier `is_revealed = 1`
        // und die Snapshots. Bleiben sie leer, während die Projektion den
        // Aufdeckzustand führt, gibt es die zweite Wahrheit nicht mehr.
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        steuerung.decke(nichtAufgedeckterSitz(partie))

        db.matchParticipantDao().sitzordnung(partie).forEach { sitz ->
            assertFalse(
                "match_participant.is_revealed wurde wieder beschrieben — " +
                    "damit gäbe es zwei Wahrheiten über denselben Zustand.",
                sitz.isRevealed,
            )
            assertNull(sitz.revealedIdentityUid)
            assertNull(sitz.identityNameSnapshot)
            assertNull(sitz.identityRoleSnapshot)
        }

        assertTrue(
            "Die Projektion führt keinen aufgedeckten Sitzplatz — dann prüft der " +
                "Test oben nichts.",
            db.projectionDao().teilnehmerDerPartie(partie).any { it.isRevealed },
        )
    }

    // ── S2.5 Fortschreibung gegen Faltung ───────────────────────────────────

    @Test
    fun neuaufbauErgibtDenselbenZustand() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        steuerung.decke(nichtAufgedeckterSitz(partie))
        val vorher = abbild(partie)

        RiseStore.eventLog(db).neuAufbauen(partie)
        assertEquals(vorher, abbild(partie))
    }

    @Test
    fun dieFaltungErgibtDasselbeWieDieFortschreibung() {
        // Zwei unabhängige Umsetzungen derselben Regeln: `MatchEventLog.wendeAn`
        // in SQL und `MatchFold.falte` in reinem JVM. Weichen sie ab, ist eine
        // von beiden falsch — und ohne diesen Vergleich fiele es niemandem auf.
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        steuerung.decke(nichtAufgedeckterSitz(partie))

        val ausDemLog = MatchFold.falte(
            partie,
            db.eventLogDao().alle(partie).mapNotNull { EventAbbildung.zuDomaene(it) },
        )
        val ausDerProjektion = db.projectionDao().teilnehmerDerPartie(partie)

        assertEquals(
            "Die Faltung kennt andere Teilnehmer als die Projektionstabelle.",
            ausDerProjektion.map { it.participantUid }.sorted(),
            ausDemLog.participants.keys.sorted(),
        )
        ausDerProjektion.forEach { zeile ->
            val gefaltet = ausDemLog.participants.getValue(zeile.participantUid)
            assertEquals(
                "is_revealed weicht ab für ${zeile.participantUid}",
                zeile.isRevealed,
                gefaltet.isRevealed,
            )
            assertEquals(zeile.lastAppliedSeq, gefaltet.lastAppliedSeq)
            assertEquals(zeile.life, gefaltet.life)
        }
        assertEquals(
            db.projectionDao().partie(partie)!!.lastAppliedSeq,
            ausDemLog.matchState.lastAppliedSeq,
        )
        assertTrue("Ohne match_created wäre die Faltung haltlos.", ausDemLog.partieAngelegt)
    }

    @Test
    fun keinEventGehtBeiDerUebersetzungVerloren() {
        // Sonst wäre der Vergleich darüber grün, weil beide Seiten wenig sehen.
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val zeilen = db.eventLogDao().alle(partie)
        assertEquals(
            zeilen.size,
            zeilen.mapNotNull { EventAbbildung.zuDomaene(it) }.size,
        )
    }

    // ── Zurücksetzen ist ein Undo, kein Löschen ─────────────────────────────

    @Test
    fun zuruecksetzenHebtAufUndLoeschtNichts() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        steuerung.decke(nichtAufgedeckterSitz(partie))
        val eventsVorher = db.eventLogDao().anzahl(partie)

        steuerung.setzeZurueck(partie)

        assertTrue(
            "Das Log ist geschrumpft. Es ist append-only (TDD 5.1); ein Undo " +
                "markiert, es entfernt nicht (TDD 5.3).",
            db.eventLogDao().anzahl(partie) >= eventsVorher,
        )
        assertTrue(
            "Nach dem Zurücknehmen ist noch ein Sitzplatz aufgedeckt.",
            db.projectionDao().teilnehmerDerPartie(partie).none { it.isRevealed },
        )
        assertTrue(
            "Es wurde kein event_undone angehängt — dann war es kein Undo.",
            db.eventLogDao().alle(partie).any { it.type == EventType.EVENT_UNDONE.wert },
        )
        assertTrue(
            "Kein identity_revealed ist als aufgehoben markiert.",
            db.eventLogDao().alle(partie)
                .any { it.type == EventType.IDENTITY_REVEALED.wert && it.isUndone },
        )
    }

    @Test
    fun nachDemZuruecksetzenLaesstSichNeuVerteilen() {
        // Regression: der Ablauf, für den die Schaltfläche da ist.
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        steuerung.setzeZurueck(partie)
        assertFalse("Die Zuordnung wurde nicht weggeworfen.", steuerung.stand(partie).verteilt)

        steuerung.verteile(partie)
        val stand = steuerung.stand(partie)
        assertTrue(stand.verteilt)
        assertNotNull("Nach dem Neuverteilen fehlt der Leader.", stand.leader)
    }

    // ── S2.6 Regression ─────────────────────────────────────────────────────

    @Test
    fun verteilenDecktDenLeaderVonSelbstAuf() {
        // TDD 8.5 — und seit S2 auf demselben Weg wie jedes andere Aufdecken.
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val stand = steuerung.stand(partie)
        assertNotNull("Der Leader liegt nicht offen.", stand.leader)
        assertEquals(1, stand.sitzplaetze.count { it.istAufgedeckt })
        assertEquals(1, anzahl(partie, EventType.IDENTITY_REVEALED))
    }

    @Test
    fun derZustandKommtAusDerAblageUndNichtAusDemSpeicher() {
        // Eine zweite Steuerung ohne eigenes Gedächtnis: Sie findet die Runde
        // und ihren Aufdeckzustand nur, wenn beides wirklich abgelegt ist.
        // (Bewusst mit derselben Verbindung — siehe Kommentar in `aufraeumen`.)
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        steuerung.decke(nichtAufgedeckterSitz(partie))

        val neue = PrototypSteuerung(kontext, db)
        assertEquals(partie, neue.letzteRunde())
        assertEquals(2, neue.stand(partie).sitzplaetze.count { it.istAufgedeckt })
    }

    @Test
    fun karteAnsehenFunktioniertWeiterhinOhneAufzudecken() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitz = nichtAufgedeckterSitz(partie)
        assertNotNull(steuerung.karteVon(partie, sitz))
        assertFalse(
            "Ansehen hat aufgedeckt — der Pass-around wäre damit kaputt.",
            db.projectionDao().teilnehmer(sitz)!!.isRevealed,
        )
    }

    @Test
    fun verwerfenBeendetDieRunde() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verwirf(partie)
        assertNull(steuerung.letzteRunde())
    }

    // ── S3: Zugzählung und Ausscheiden (D-003) ──────────────────────────────

    @Test
    fun einZugErzeugtEinEventUndZaehlt() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitz = steuerung.stand(partie).sitzplaetze.first()

        steuerung.starteZug(partie, sitz.participantUid)

        val stand = steuerung.stand(partie)
        assertEquals(1, stand.zugnummer)
        assertEquals(sitz.participantUid, stand.amZug?.participantUid)
        assertEquals(1, anzahl(partie, EventType.TURN_STARTED))
    }

    @Test
    fun einZugwechselBeendetDenLaufendenZugZuerst() {
        // Sonst zählte die Projektion zwei begonnene Züge, von denen nur einer
        // gespielt wurde.
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitze = steuerung.stand(partie).sitzplaetze

        steuerung.starteZug(partie, sitze[0].participantUid)
        steuerung.starteZug(partie, sitze[1].participantUid)

        assertEquals(2, steuerung.stand(partie).zugnummer)
        assertEquals(1, anzahl(partie, EventType.TURN_ENDED))
        assertEquals(sitze[1].participantUid, steuerung.stand(partie).amZug?.participantUid)
    }

    @Test
    fun derselbeSitzplatzZweimalAmZugErzeugtKeinZweitesEvent() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitz = steuerung.stand(partie).sitzplaetze.first()

        steuerung.starteZug(partie, sitz.participantUid)
        steuerung.starteZug(partie, sitz.participantUid)
        assertEquals(1, steuerung.stand(partie).zugnummer)
    }

    @Test
    fun zugBeendenLaesstDieZugnummerStehen() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        steuerung.starteZug(partie, steuerung.stand(partie).sitzplaetze.first().participantUid)
        steuerung.beendeZug(partie)

        val stand = steuerung.stand(partie)
        assertEquals(1, stand.zugnummer)
        assertNull(stand.amZug)
    }

    @Test
    fun beendenOhneLaufendenZugErzeugtNichts() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        steuerung.beendeZug(partie)
        assertEquals(0, anzahl(partie, EventType.TURN_ENDED))
    }

    @Test
    fun ausscheidenWirktUndBeendetDenZug() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitz = steuerung.stand(partie).sitzplaetze.first()

        steuerung.starteZug(partie, sitz.participantUid)
        steuerung.scheideAus(partie, sitz.participantUid)

        val stand = steuerung.stand(partie)
        assertTrue(stand.sitzplaetze.first { it.participantUid == sitz.participantUid }.istAusgeschieden)
        assertNull("Ein ausgeschiedener Sitzplatz ist nicht mehr am Zug.", stand.amZug)
        assertEquals(1, stand.zugnummer)
    }

    @Test
    fun einAusgeschiedenerSitzplatzKommtNichtMehrAnDenZug() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitz = steuerung.stand(partie).sitzplaetze.first()
        steuerung.scheideAus(partie, sitz.participantUid)
        steuerung.starteZug(partie, sitz.participantUid)
        assertEquals(0, steuerung.stand(partie).zugnummer)
    }

    @Test
    fun zuruecksetzenNimmtAuchDieZugzaehlungZurueck() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitz = steuerung.stand(partie).sitzplaetze.first()
        steuerung.starteZug(partie, sitz.participantUid)
        steuerung.scheideAus(partie, sitz.participantUid)

        steuerung.setzeZurueck(partie)

        val stand = steuerung.stand(partie)
        assertEquals("Die Zugnummer stammt aus einer Partie, die es nicht mehr gibt.", 0, stand.zugnummer)
        assertNull(stand.amZug)
        assertTrue(stand.sitzplaetze.none { it.istAusgeschieden })
    }

    @Test
    fun dieZugzaehlungUeberstehtDenNeuaufbau() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitze = steuerung.stand(partie).sitzplaetze
        steuerung.starteZug(partie, sitze[0].participantUid)
        steuerung.beendeZug(partie)
        steuerung.starteZug(partie, sitze[1].participantUid)
        steuerung.scheideAus(partie, sitze[2].participantUid)
        val vorher = abbild(partie)

        RiseStore.eventLog(db).neuAufbauen(partie)
        assertEquals(vorher, abbild(partie))
        assertEquals(2, steuerung.stand(partie).zugnummer)
    }

    @Test
    fun dieFaltungKenntDieZugzaehlungEbenfalls() {
        val partie = steuerung.neueRunde(namen)
        steuerung.verteile(partie)
        val sitze = steuerung.stand(partie).sitzplaetze
        steuerung.starteZug(partie, sitze[0].participantUid)
        steuerung.scheideAus(partie, sitze[1].participantUid)

        val gefaltet = MatchFold.falte(
            partie,
            db.eventLogDao().alle(partie).mapNotNull { EventAbbildung.zuDomaene(it) },
        )
        val zeile = db.projectionDao().partie(partie)!!
        assertEquals(zeile.turnNumber, gefaltet.matchState.turnNumber)
        assertEquals(zeile.activeParticipantUid, gefaltet.matchState.activeParticipantUid)
        assertEquals(
            db.projectionDao().teilnehmerDerPartie(partie).filter { it.isEliminated }.map { it.participantUid },
            gefaltet.participants.values.filter { it.isEliminated }.map { it.participantUid },
        )
    }

    // ── Hilfen ──────────────────────────────────────────────────────────────

    private fun anzahl(partie: String, typ: EventType): Int =
        db.eventLogDao().alle(partie).count { it.type == typ.wert && !it.isUndone }

    private fun nichtAufgedeckterSitz(partie: String): String =
        steuerung.stand(partie).sitzplaetze.first { !it.istAufgedeckt }.participantUid

    private fun abbild(partie: String): String = buildString {
        appendLine(db.projectionDao().partie(partie).toString())
        db.projectionDao().teilnehmerDerPartie(partie).forEach { appendLine(it.toString()) }
    }
}
