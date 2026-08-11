package de.myhornets.rise1.prototyp

import android.content.Context
import de.myhornets.rise1.browser.KatalogZugriff
import de.myhornets.rise1.catalog.CatalogRole
import de.myhornets.rise1.catalog.IdentityEntity
import de.myhornets.rise1.core.event.EventType
import de.myhornets.rise1.ereignis.EventAbbildung
import de.myhornets.rise1.store.DeviceEntity
import de.myhornets.rise1.store.MatchEntity
import de.myhornets.rise1.store.MatchEventLog
import de.myhornets.rise1.store.MatchParticipantEntity
import de.myhornets.rise1.store.MatchStatus
import de.myhornets.rise1.store.PrototypeAssignmentEntity
import de.myhornets.rise1.store.RiseDatabase
import de.myhornets.rise1.store.RiseStore
import de.myhornets.rise1.store.SeatState
import java.util.UUID

// ADR-004 — der lokale Prototyp-Modus.
//
// ┌───────────────────────────────────────────────────────────────────────────┐
// │ Dieses Verzeichnis ist befristet. Es weicht bewusst von TDD Kapitel 8 ab:  │
// │ EIN Gerät würfelt die Zuordnung und speichert sie im Klartext. Damit weiß  │
// │ dieses Gerät alles — genau das, was TDD 8.1 ausschließt.                   │
// │                                                                            │
// │ Zweck ist, den ABLAUF am Tisch auszuprobieren, nicht eine Partie zu        │
// │ spielen, deren Geheimhaltung jemand ernst nimmt.                           │
// │                                                                            │
// │ Rückbau mit T-029/T-030: dieses Verzeichnis löschen, `prototype_assignment`│
// │ per Migration entfernen, den Manifest-Eintrag streichen.                   │
// └───────────────────────────────────────────────────────────────────────────┘
//
// Was hier NICHT passiert, auch im Prototyp nicht: keine Regelauswertung, keine
// Kostenberechnung, keine Aussage darüber, ob jemand aufdecken darf. Der
// Prototyp verwaltet — er spielt nicht mit.
//
// ─────────────────────────────────────────────────────────────────────────────
// S2 (2026-08-10) — umgestellt auf das Event-Log.
//
// Vorher schrieb diese Klasse den Spielzustand direkt: `match_participant`
// bekam `is_revealed = true` und die Snapshots, und `stand()` las sie von dort
// zurück. Damit gab es zwei Wahrheiten — das Log aus S1 und diese Felder — und
// keine Regel, welche im Zweifel gilt.
//
// Jetzt gilt genau ein Weg:
//
//     Bedienung → Event → match_event → Fortschreibung → participant_state → UI
//
// Was das konkret heißt:
//
//   * `neueRunde` hängt `match_created` an, dann je Sitzplatz
//     `participant_joined`.
//   * `decke` hängt `identity_revealed` an. Es schreibt **kein** Zustandsfeld.
//   * `setzeZurueck` hebt die Aufdeck-Events nach TDD 5.3 auf — es löscht
//     nichts und setzt nichts zurück.
//   * `stand()` liest `is_revealed` **nur noch** aus `participant_state`.
//
// Was bewusst **nicht** über Events läuft, mit Begründung:
//
//   * Die Zeilen in `match` und `match_participant`. Sie sind das Verzeichnis
//     der Partie — Sitzordnung und Namen —, nicht ihr Zustand. In der späteren
//     Architektur entstehen sie aus den Nutzdaten von `participant_joined`
//     (TDD 5.4: „Sitzplatz, Name"); dafür bräuchte die Faltung einen
//     JSON-Leser, und den hat sie aus gutem Grund nicht (siehe `MatchFold`).
//     Vermerkt als offener Punkt in [[S2 Prototyp auf Event-Log]].
//   * `match.status` von `setup` auf `active`. TDD 5.4 kennt **keinen**
//     Event-Typ für diesen Übergang. Einen zu erfinden wäre eine
//     Architekturentscheidung und keine Umsetzung — deshalb bleibt es ein
//     direkter Schreibvorgang auf dem Partie-Datensatz, und die Frage steht
//     als NEEDS DECISION im Aufgabendokument.
//   * `prototype_assignment`. Das ist das Ergebnis der Verteilung, nicht der
//     Spielzustand; in der echten Architektur lebt es in `own_identity` und in
//     den `deal_*`-Events (TDD 8.3). ADR-004 hält es befristet hier.
// ─────────────────────────────────────────────────────────────────────────────

/** Ein Sitzplatz, wie ihn die Oberfläche braucht. */
data class Sitzplatz(
    val participantUid: String,
    val seatIndex: Int,
    val name: String,
    val istAufgedeckt: Boolean,
    val aufgedeckteIdentitaet: IdentityEntity?,
    /** S3 — aus `participant_state.is_eliminated`, also aus dem Log. */
    val istAusgeschieden: Boolean = false,
)

/** Der ganze sichtbare Zustand einer Prototyp-Runde. */
data class Rundenstand(
    val matchUid: String?,
    val sitzplaetze: List<Sitzplatz>,
    val verteilt: Boolean,
    /** Nach TDD 8.5 liegt der Leader von Beginn an offen. */
    val leader: Pair<Sitzplatz, IdentityEntity>?,
    /** S3 / D-003 — Anzahl der **begonnenen** Züge, aus `match_state`. */
    val zugnummer: Int = 0,
    /** S3 / D-003 — wer gerade am Zug ist, oder `null` zwischen zwei Zügen. */
    val amZug: Sitzplatz? = null,
)

/**
 * Die offizielle Zusammensetzung nach Spielerzahl — TDD 8.2, geprüft gegen die
 * Regeln auf mtgtreachery.net.
 *
 * Das sind **Zahlen aus einer Tabelle**, keine Ableitung: Sie sind öffentlich,
 * jeder am Tisch kennt sie, und sie gehören ab `T-022` als Seed-Daten in
 * `role_distribution`. Bis dahin stehen sie hier.
 */
private val ZUSAMMENSETZUNG: Map<Int, Map<String, Int>> = mapOf(
    4 to mapOf(CatalogRole.LEADER to 1, CatalogRole.TRAITOR to 1, CatalogRole.ASSASSIN to 2, CatalogRole.GUARDIAN to 0),
    5 to mapOf(CatalogRole.LEADER to 1, CatalogRole.TRAITOR to 1, CatalogRole.ASSASSIN to 2, CatalogRole.GUARDIAN to 1),
    6 to mapOf(CatalogRole.LEADER to 1, CatalogRole.TRAITOR to 1, CatalogRole.ASSASSIN to 3, CatalogRole.GUARDIAN to 1),
    7 to mapOf(CatalogRole.LEADER to 1, CatalogRole.TRAITOR to 1, CatalogRole.ASSASSIN to 3, CatalogRole.GUARDIAN to 2),
    8 to mapOf(CatalogRole.LEADER to 1, CatalogRole.TRAITOR to 2, CatalogRole.ASSASSIN to 3, CatalogRole.GUARDIAN to 2),
)

val UNTERSTUETZTE_SPIELERZAHLEN: IntRange = 4..8

/**
 * Alle Schreib- und Lesevorgänge des Prototyps.
 *
 * Nur vom Hintergrund-Thread aufrufen — Room verweigert den Hauptthread, und
 * `allowMainThreadQueries()` steht auch hier nirgends.
 */
class PrototypSteuerung(
    private val context: Context,
    /**
     * Nur für Tests: eine bereits geöffnete Datenbank.
     *
     * `Room.databaseBuilder(...).build()` liefert bei jedem Aufruf eine **neue**
     * Instanz — es gibt keinen Zwischenspeicher. Zwei Instanzen auf derselben
     * Datei sind zwei Verbindungen, und ein Test, der die eine schreibt und die
     * andere liest, prüft am Ende die Sperrverwaltung von SQLite statt den
     * eigenen Code. Deshalb kann er hier dieselbe hereinreichen.
     *
     * Die Anwendung ruft weiterhin `PrototypSteuerung(context)` auf.
     */
    private val datenbank: RiseDatabase? = null,
) {

    private val db: RiseDatabase by lazy { datenbank ?: RiseStore.open(context) }
    private val eventLog: MatchEventLog by lazy { RiseStore.eventLog(db) }

    // ── 1. Neue Runde ────────────────────────────────────────────────────────

    fun neueRunde(namen: List<String>): String {
        val jetzt = System.currentTimeMillis()
        val geraet = eigenesGeraet(jetzt)
        val matchUid = UUID.randomUUID().toString()

        db.matchDao().einfuegen(
            MatchEntity(
                matchUid = matchUid,
                // `game_mode` entsteht erst mit T-022; bis dahin der stabile
                // Schlüssel aus TDD 4.2 als Wert ohne Fremdschlüssel.
                modeUid = "mode_standard",
                hostDeviceUid = geraet.deviceUid,
                status = MatchStatus.SETUP,
                hostHeartbeatAt = null,
                playerCount = namen.size,
                catalogVersionUsed = herkunft()?.catalogVersion ?: "1",
                setCodeUsed = herkunft()?.sourceSetCode ?: "TRD-2025",
                dealTranscriptDigest = null,
                moderatorMode = false,
                startedAt = null,
                endedAt = null,
                lastAppliedSeq = 0L,
                createdAt = jetzt,
                updatedAt = jetzt,
                deletedAt = null,
                originDeviceUid = geraet.deviceUid,
            ),
        )

        // Das erste Event der Partie. TDD 5.4: `match_created` trägt immer
        // seq = 0 — hier ergibt sich das von selbst, weil es das erste ist.
        eventLog.anhaengenLokal(
            EventAbbildung.oeffentlichesZustandsEvent(
                matchUid = matchUid,
                typ = EventType.MATCH_CREATED,
                deviceUid = geraet.deviceUid,
                jetzt = jetzt,
            ),
        ) ?: error("match_created ließ sich nicht anhängen — die Partie hätte keinen Anfang.")

        // ── 2./3. Spieler und Sitzordnung ────────────────────────────────────
        //
        // `seat_index` ist die Reihenfolge der Liste. Ein `player`-Datensatz
        // entsteht bewusst nicht: Im Prototyp sind alle namenlose Gäste, und
        // TDD 4.4 sieht für die `player_uid` genau dafür `null` vor.
        //
        // Die Zeile in `match_participant` ist das **Verzeichnis** — Name und
        // Sitzplatz. Der Teilnehmer als Träger von Spielzustand entsteht durch
        // `participant_joined`; ohne dieses Event gäbe es keine Zeile in
        // `participant_state`, und `stand()` würde ihn als nicht aufgedeckt
        // führen, weil es dort nichts zu lesen gäbe.
        namen.forEachIndexed { platz, name ->
            val participantUid = UUID.randomUUID().toString()
            db.matchParticipantDao().einfuegen(
                MatchParticipantEntity(
                    participantUid = participantUid,
                    matchUid = matchUid,
                    playerUid = null,
                    seatIndex = platz,
                    displayNameSnapshot = name,
                    identityCommitment = null,
                    // S2: Diese fünf Felder werden vom Prototyp **nicht mehr
                    // beschrieben**. Sie bleiben, wie TDD 4.4 sie vorsieht, für
                    // die Aufdeckphase (TDD 10) reserviert. Der Aufdeckzustand
                    // des Prototyps steht ausschließlich in `participant_state`.
                    isRevealed = false,
                    revealedAtSeq = null,
                    revealedIdentityUid = null,
                    identityNameSnapshot = null,
                    identityRoleSnapshot = null,
                    finalStatus = null,
                    placement = null,
                    rejoinTokenHash = null,
                    seatState = SeatState.ACTIVE,
                    lastSeenAt = null,
                    createdAt = jetzt,
                    updatedAt = jetzt,
                    deletedAt = null,
                    originDeviceUid = geraet.deviceUid,
                ),
            )
            eventLog.anhaengenLokal(
                EventAbbildung.oeffentlichesZustandsEvent(
                    matchUid = matchUid,
                    typ = EventType.PARTICIPANT_JOINED,
                    deviceUid = geraet.deviceUid,
                    jetzt = jetzt,
                    akteur = participantUid,
                ),
            ) ?: error("participant_joined für $name ließ sich nicht anhängen.")
        }
        return matchUid
    }

    /** Die zuletzt angelegte Runde, damit die App nach einem Neustart weitermacht. */
    fun letzteRunde(): String? = db.matchDao().alle().firstOrNull()?.matchUid

    // ── 4. Rollen verteilen ──────────────────────────────────────────────────

    /**
     * Würfelt die Zuordnung und speichert sie.
     *
     * **Das ist die Abweichung.** TDD 8.3 verteilt über zwei Parteien und
     * Umschläge; hier zieht ein Gerät Karten und schreibt eine Tabelle. Siehe
     * [[ADR-004 Lokaler Prototyp-Modus]].
     *
     * Die Zusammensetzung kommt aus [ZUSAMMENSETZUNG]; je Rolle werden so viele
     * **verschiedene** Karten aus dem Katalogpool gezogen, wie die Tabelle
     * nennt. Anschließend wird gemischt und auf die Sitzplätze gelegt.
     */
    fun verteile(matchUid: String) {
        val sitze = db.matchParticipantDao().sitzordnung(matchUid)
        val zusammensetzung = ZUSAMMENSETZUNG[sitze.size]
            ?: error("Für ${sitze.size} Spieler gibt es keine offizielle Zusammensetzung (TDD 8.2 deckt 4 bis 8 ab).")

        val katalog = KatalogZugriff.dao(context)
        val gezogen = zusammensetzung.flatMap { (rolle, anzahl) ->
            if (anzahl == 0) emptyList() else katalog.nachRolle(rolle).shuffled().take(anzahl)
        }
        check(gezogen.size == sitze.size) {
            "Zusammensetzung ergibt ${gezogen.size} Karten für ${sitze.size} Sitzplätze."
        }

        val jetzt = System.currentTimeMillis()
        db.prototypeAssignmentDao().einfuegen(
            sitze.zip(gezogen.shuffled()) { sitz, karte ->
                PrototypeAssignmentEntity(
                    matchUid = matchUid,
                    participantUid = sitz.participantUid,
                    identityUid = karte.identityUid,
                    assignedAt = jetzt,
                )
            },
        )

        // Der Statuswechsel auf `active` bleibt ein direkter Schreibvorgang auf
        // dem Partie-Datensatz. TDD 5.4 kennt keinen Event-Typ dafür; einen zu
        // erfinden wäre eine Architekturentscheidung. Er verändert auch keine
        // Projektionstabelle — `match_state` trägt Zugnummer und aktiven
        // Sitzplatz, keinen Status.
        db.matchDao().nachUid(matchUid)?.let { partie ->
            db.matchDao().aktualisieren(
                partie.copy(status = MatchStatus.ACTIVE, startedAt = jetzt, updatedAt = jetzt),
            )
        }

        // ── 6. Der Leader liegt von Beginn an offen (TDD 8.5) ────────────────
        //
        // Nicht abgeleitet, sondern eine Eigenschaft der Karte: Wer eine
        // Leader-Karte hat, deckt sie sofort auf. Im echten Verfahren tut das
        // das Gerät des Spielers automatisch; hier tut es dasselbe — und seit
        // S2 auf demselben Weg wie jedes andere Aufdecken, nämlich über ein
        // Event.
        db.prototypeAssignmentDao().fuerPartie(matchUid).forEach { zuordnung ->
            val karte = katalog.identitaet(zuordnung.identityUid) ?: return@forEach
            if (karte.role == CatalogRole.LEADER) decke(zuordnung.participantUid)
        }
    }

    // ── 5. Die eigene Karte ansehen (Pass-around) ────────────────────────────

    /**
     * Die Karte eines Sitzplatzes.
     *
     * Sie wird gelesen, wenn das Gerät weitergereicht wurde und der Spieler
     * bestätigt hat. Der Prototyp merkt sich nicht, wer schon geschaut hat —
     * das wäre Buchführung ohne Zweck.
     */
    fun karteVon(matchUid: String, participantUid: String): IdentityEntity? {
        val zuordnung = db.prototypeAssignmentDao().fuerSitzplatz(matchUid, participantUid) ?: return null
        return KatalogZugriff.dao(context).identitaet(zuordnung.identityUid)
    }

    // ── 7. Aufdecken ─────────────────────────────────────────────────────────

    /**
     * Deckt einen Sitzplatz auf — **durch ein Event**.
     *
     * Vor S2 schrieb diese Methode `is_revealed`, `revealed_identity_uid` und
     * die beiden Snapshots direkt nach `match_participant`. Jetzt hängt sie ein
     * `identity_revealed` an und rührt kein Zustandsfeld an. Die Wirkung
     * entsteht in der Fortschreibung, die daraus `participant_state.is_revealed`
     * setzt.
     *
     * **Doppeltes Aufdecken erzeugt kein zweites Event.** Der Vergleich läuft
     * gegen die Projektion, nicht gegen `match_participant` — also gegen die
     * Quelle, die seit S2 gilt.
     */
    fun decke(participantUid: String) {
        val sitz = db.matchParticipantDao().nachUid(participantUid) ?: return
        if (db.projectionDao().teilnehmer(participantUid)?.isRevealed == true) return

        // Ohne Zuordnung gibt es nichts aufzudecken. Der Prototyp kennt sie
        // lokal; im echten Verfahren käme sie aus dem eigenen Umschlag.
        db.prototypeAssignmentDao().fuerSitzplatz(sitz.matchUid, participantUid) ?: return

        val jetzt = System.currentTimeMillis()
        // `origin_device_uid` ist das Gerät, das das Event **erzeugt** — nicht
        // das, von dem der Sitzplatz stammt. Im Prototyp ist das dasselbe; die
        // Unterscheidung stehenzulassen kostet nichts und wäre später ein
        // stiller Fehler im Dedup-Schlüssel.
        val geraet = eigenesGeraet(jetzt)

        eventLog.anhaengenLokal(
            EventAbbildung.oeffentlichesZustandsEvent(
                matchUid = sitz.matchUid,
                typ = EventType.IDENTITY_REVEALED,
                deviceUid = geraet.deviceUid,
                jetzt = jetzt,
                akteur = participantUid,
            ),
        )
    }

    // ── S3: Zugzählung und Ausscheiden (D-003) ───────────────────────────────

    /**
     * Beginnt den Zug eines Sitzplatzes.
     *
     * **Aufzeichnung, keine Regel.** Rise rechnet keine Zugfolge aus und
     * erzwingt keine: Ein Zug beginnt, weil jemand am Tisch ihn beginnt und das
     * hier festhält. Wer wann dran ist, entscheidet der Tisch.
     *
     * Ein laufender Zug wird zuerst beendet — sonst zählte die Projektion zwei
     * begonnene Züge, von denen nur einer gespielt wurde.
     */
    fun starteZug(matchUid: String, participantUid: String) {
        val stand = db.projectionDao().partie(matchUid) ?: return
        if (db.projectionDao().teilnehmer(participantUid)?.isEliminated != false) return
        if (stand.activeParticipantUid == participantUid) return
        if (stand.activeParticipantUid != null) beendeZug(matchUid)
        haengeAn(matchUid, EventType.TURN_STARTED, akteur = participantUid)
    }

    /** Beendet den laufenden Zug. Die Zugnummer bleibt stehen — sie zählt begonnene Züge. */
    fun beendeZug(matchUid: String) {
        if (db.projectionDao().partie(matchUid)?.activeParticipantUid == null) return
        haengeAn(matchUid, EventType.TURN_ENDED)
    }

    /**
     * Ein Sitzplatz scheidet aus.
     *
     * `participant_eliminated` und nicht `participant_left`: TDD 5.4 nennt für
     * den ersten einen Grund aus dem Spiel, für den zweiten eine Entscheidung,
     * den Tisch zu verlassen. Am Prototyp-Tisch ist es das Erste.
     */
    fun scheideAus(matchUid: String, participantUid: String) {
        if (db.projectionDao().teilnehmer(participantUid)?.isEliminated != false) return
        haengeAn(matchUid, EventType.PARTICIPANT_ELIMINATED, akteur = participantUid)
    }

    private fun haengeAn(matchUid: String, typ: EventType, akteur: String? = null) {
        val jetzt = System.currentTimeMillis()
        eventLog.anhaengenLokal(
            EventAbbildung.oeffentlichesZustandsEvent(
                matchUid = matchUid,
                typ = typ,
                deviceUid = eigenesGeraet(jetzt).deviceUid,
                jetzt = jetzt,
                akteur = akteur,
            ),
        )
    }

    // ── 8. Zurücksetzen ──────────────────────────────────────────────────────

    /**
     * Wirft die Zuordnung weg und nimmt die Aufdeckungen zurück.
     *
     * ## Warum das ein Undo ist und kein Zurücksetzen
     *
     * Das Log ist append-only (TDD 5.1). „Zurücksetzen" gibt es darin nicht —
     * es gibt nur **Aufheben** (TDD 5.3): Ein `event_undone` wird angehängt,
     * das Ziel wird markiert, und die Projektion wird aus dem Log neu gefaltet.
     * Für den Nutzer sieht das genauso aus wie vorher; im Log bleibt sichtbar,
     * dass es eine erste Verteilung gab.
     *
     * Die Prototyp-Zuordnung wird dagegen **hart gelöscht**: Ein Prototypstand
     * soll restlos weg sein, wenn man ihn wegwirft. Der Soft Delete aus TDD 3.3
     * gilt für synchronisierte Tabellen — diese ist keine.
     */
    fun setzeZurueck(matchUid: String) {
        val jetzt = System.currentTimeMillis()
        val geraet = eigenesGeraet(jetzt)

        // S3: Auch Zug- und Ausscheide-Events gehören zur Runde. Bliebe die
        // Zugnummer stehen, während die Karten neu verteilt werden, zeigte der
        // Tisch eine Zählung aus einer Partie, die es nicht mehr gibt.
        val zuruecknehmen = listOf(
            EventType.IDENTITY_REVEALED,
            EventType.TURN_STARTED,
            EventType.TURN_ENDED,
            EventType.PARTICIPANT_ELIMINATED,
        ).flatMap { db.eventLogDao().nachTyp(matchUid, it.wert) }.sortedBy { it.seq }

        zuruecknehmen.forEach { aufdeckung ->
            eventLog.hebeAuf(
                zielEventUid = aufdeckung.eventUid,
                aufhebendesEvent = EventAbbildung.oeffentlichesZustandsEvent(
                    matchUid = matchUid,
                    typ = EventType.EVENT_UNDONE,
                    deviceUid = geraet.deviceUid,
                    jetzt = jetzt,
                    // Das Ziel steht als `undone_by_event_uid` am aufgehobenen
                    // Event. Es zusätzlich in die Nutzdaten zu schreiben wäre
                    // dieselbe Angabe an zwei Orten.
                    ziel = aufdeckung.actorParticipantUid,
                ),
            )
        }

        db.prototypeAssignmentDao().loeschen(matchUid)
        db.matchDao().nachUid(matchUid)?.let { partie ->
            db.matchDao().aktualisieren(
                partie.copy(status = MatchStatus.SETUP, startedAt = null, updatedAt = jetzt),
            )
        }
    }

    /** Löscht die Runde weich und beginnt von vorn. */
    fun verwirf(matchUid: String) {
        db.prototypeAssignmentDao().loeschen(matchUid)
        val jetzt = System.currentTimeMillis()
        db.matchParticipantDao().sitzordnung(matchUid).forEach {
            db.matchParticipantDao().aktualisieren(it.copy(deletedAt = jetzt, updatedAt = jetzt))
        }
        db.matchDao().nachUid(matchUid)?.let {
            db.matchDao().aktualisieren(it.copy(deletedAt = jetzt, updatedAt = jetzt))
        }
        // Das Log bleibt. Es ist append-only, und eine weich gelöschte Partie
        // ist kein Grund, ihre Geschichte zu entfernen (TDD 3.4). Die
        // Projektionszeilen bleiben ebenfalls stehen — sie sind aus dem Log
        // wiederherstellbar und werden von `stand()` nur über eine noch
        // vorhandene Partie erreicht.
    }

    // ── Zustand für die Anzeige ──────────────────────────────────────────────

    /**
     * Der Anzeigezustand.
     *
     * Seit S2 kommt `istAufgedeckt` **ausschließlich** aus `participant_state`,
     * also aus der Projektion des Logs. `match_participant` liefert nur noch
     * Name und Sitzplatz — das Verzeichnis, nicht den Zustand.
     */
    fun stand(matchUid: String?): Rundenstand {
        if (matchUid == null) return Rundenstand(null, emptyList(), false, null)
        val katalog = KatalogZugriff.dao(context)

        val projektion = db.projectionDao().teilnehmerDerPartie(matchUid)
            .associateBy { it.participantUid }
        val zuordnungen = db.prototypeAssignmentDao().fuerPartie(matchUid)
            .associate { it.participantUid to it.identityUid }

        val sitze = db.matchParticipantDao().sitzordnung(matchUid).map { sitz ->
            val aufgedeckt = projektion[sitz.participantUid]?.isRevealed == true
            Sitzplatz(
                participantUid = sitz.participantUid,
                seatIndex = sitz.seatIndex,
                name = sitz.displayNameSnapshot,
                istAufgedeckt = aufgedeckt,
                istAusgeschieden = projektion[sitz.participantUid]?.isEliminated == true,
                // Welche Karte es ist, weiß im Prototyp die Zuordnung. Im
                // echten Verfahren steht es in den Nutzdaten des
                // `identity_revealed` beziehungsweise in `own_identity`.
                aufgedeckteIdentitaet = if (aufgedeckt) {
                    zuordnungen[sitz.participantUid]?.let { katalog.identitaet(it) }
                } else {
                    null
                },
            )
        }

        val verteilt = zuordnungen.isNotEmpty()
        val leader = sitze.firstNotNullOfOrNull { sitz ->
            val karte = sitz.aufgedeckteIdentitaet
            if (karte != null && karte.role == CatalogRole.LEADER) sitz to karte else null
        }
        val partieZustand = db.projectionDao().partie(matchUid)
        return Rundenstand(
            matchUid = matchUid,
            sitzplaetze = sitze,
            verteilt = verteilt,
            leader = leader,
            zugnummer = partieZustand?.turnNumber ?: 0,
            amZug = partieZustand?.activeParticipantUid?.let { aktiv ->
                sitze.firstOrNull { it.participantUid == aktiv }
            },
        )
    }

    // ── Kleinkram ────────────────────────────────────────────────────────────

    private fun herkunft() = KatalogZugriff.dao(context).herkunft()

    private fun eigenesGeraet(jetzt: Long): DeviceEntity {
        db.deviceDao().eigenes()?.let { return it }
        val neu = DeviceEntity(
            deviceUid = UUID.randomUUID().toString(),
            displayName = android.os.Build.MODEL ?: "Dieses Gerät",
            platform = "android",
            isSelf = true,
            lastSeenAt = jetzt,
            createdAt = jetzt,
            updatedAt = jetzt,
            deletedAt = null,
            originDeviceUid = "",
        ).let { it.copy(originDeviceUid = it.deviceUid) }
        db.deviceDao().einfuegen(neu)
        return neu
    }
}
