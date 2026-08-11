package de.myhornets.rise1.sitzung

import de.myhornets.rise1.core.event.Payload
import de.myhornets.rise1.session.Ereignisentwurf
import de.myhornets.rise1.session.Partienachschlag
import de.myhornets.rise1.session.Sitzplatznachweis
import de.myhornets.rise1.store.MatchEventEntity
import de.myhornets.rise1.store.MatchEventLog
import de.myhornets.rise1.store.RiseDatabase
import de.myhornets.rise1.store.Sitzungsverwaltung
import java.util.UUID

/**
 * T-102 / T-104 — die Naht zwischen `:session` und `:store`.
 *
 * ## Warum hier
 *
 * Aus demselben Grund wie `EventAbbildung`: `:ui` ist das einzige Modul, das
 * `:session` **und** `:store` sieht. `allowedModuleEdges` gibt `:session` nur
 * `{core, crypto, transport}` — eine Kante auf `:store` wäre eine
 * Architekturänderung und bräuchte eine ADR. Deshalb sind [Partienachschlag]
 * und `Ereignisquelle` dort Schnittstellen und werden hier verdrahtet.
 *
 * ## Was das kostet und warum es sich lohnt
 *
 * Die Entscheidungen — wer zurückdarf, was er zu sehen bekommt, was ins Log
 * gehört — stehen in `:session` und laufen ohne Android in jedem JVM-Test.
 * Hier steht nur noch Übersetzung. Das ist der Grund, warum die
 * sicherheitsrelevanten Regeln geprüft sind, obwohl kein Gerät angeschlossen ist.
 */

/**
 * [Partienachschlag] über `rise.db` (TDD 9.3).
 *
 * Liest den Sitzplatz aus `match_participant` und die laufende Sitzung aus
 * `participant_session`. Den **Klartext** des `rejoin_token` sieht diese Klasse
 * nie: In der Datenbank steht nur der gesalzene Hash (TDD 4.4), und verglichen
 * wird in `:session`, in konstanter Zeit.
 */
class RaumPartienachschlag(
    private val datenbank: RiseDatabase,
    /**
     * Das Salz dieser Partie.
     *
     * Es gehört nicht in dieselbe Zeile wie der Hash — sonst wäre mit einer
     * kopierten Tabelle beides zugleich weg. Woher es kommt, entscheidet die
     * Schlüsselverwaltung (`E05`); bis dahin reicht der Aufrufer es herein.
     */
    private val salz: String,
    private val uhr: () -> Long = System::currentTimeMillis,
) : Partienachschlag {

    private val sitzungen: Sitzungsverwaltung = Sitzungsverwaltung(datenbank)

    override fun status(matchUid: String): String? =
        datenbank.matchDao().nachUid(matchUid)?.status

    override fun sitzplatz(matchUid: String, participantUid: String): Sitzplatznachweis? {
        val zeile = datenbank.matchParticipantDao().nachUid(participantUid) ?: return null
        // Ein Sitzplatz aus einer **anderen** Partie ist kein Sitzplatz dieser.
        // Ohne diese Zeile könnte eine gültige participant_uid aus Partie A den
        // Handshake für Partie B beginnen.
        if (zeile.matchUid != matchUid) return null
        val hash = zeile.rejoinTokenHash ?: return null

        val laufend = sitzungen.laufende(participantUid)
        return Sitzplatznachweis(
            participantUid = zeile.participantUid,
            rejoinTokenHash = hash,
            salz = salz,
            offeneSitzung = laufend?.sessionUid,
            offenesGeraet = laufend?.deviceUid,
        )
    }

    /**
     * Legt die Sitzung an und löst die frühere ab (TDD 9.3).
     *
     * Die Kennung entsteht hier und nicht in `:session`: Eine UUID zu ziehen ist
     * eine Nebenwirkung, und die Prüfung aus TDD 9.3 soll keine haben — sie
     * läuft im Test hundertmal.
     */
    override fun eroeffneSitzung(matchUid: String, participantUid: String, deviceUid: String): String =
        sitzungen.eroeffne(
            sitzungsUid = UUID.randomUUID().toString(),
            participantUid = participantUid,
            deviceUid = deviceUid,
            jetzt = uhr(),
        ).laufend.sessionUid

    override fun hoechsteSeq(matchUid: String): Long =
        datenbank.eventLogDao().hoechsteSeq(matchUid) ?: -1L
}

/**
 * Schreibt die Entwürfe aus `Sitzungsereignisse` ins Log (T-104).
 *
 * Es ist bewusst **derselbe** Weg wie für jedes andere Event: [MatchEventLog].
 * Ein zweiter Schreibweg für Sitzungsereignisse wäre eine zweite Stelle, an der
 * `seq` und `origin_seq` vergeben werden — und damit die zweite Quelle der
 * Wahrheit, die es nach TDD 5.1 nicht geben darf.
 */
class Sitzungsschreiber(
    private val log: MatchEventLog,
    private val matchUid: String,
    private val eigenesGeraeteUid: String,
) {

    /**
     * Hängt einen Entwurf an.
     *
     * @return das geschriebene Event, oder `null`, wenn es schon vorhanden war.
     */
    fun schreibe(entwurf: Ereignisentwurf, eventUid: String = UUID.randomUUID().toString()): MatchEventEntity? =
        log.anhaengenLokal(zuZeile(entwurf, eventUid))

    fun schreibeAlle(entwuerfe: List<Ereignisentwurf>): List<MatchEventEntity> =
        entwuerfe.mapNotNull { schreibe(it) }

    private fun zuZeile(entwurf: Ereignisentwurf, eventUid: String): MatchEventEntity {
        // Die Trennung aus TDD 5.2 noch einmal an der Grenze zur Datenbank: Der
        // Entwurf lässt gar kein Chiffrat zu, hier wird daraus ausdrücklich
        // `payloadCiphertext = null`. Zwei nullbare Spalten können beides
        // zugleich tragen — der versiegelte Typ in `:core` kann es nicht.
        val json = (entwurf.payload as? Payload.Klartext)?.json
        return MatchEventEntity(
            eventUid = eventUid,
            matchUid = matchUid,
            seq = null,
            originDeviceUid = eigenesGeraeteUid,
            originSeq = 0L,
            lamportClock = 0L,
            occurredAt = entwurf.occurredAt,
            recordedAt = entwurf.occurredAt,
            type = entwurf.typ,
            eventClass = entwurf.eventClass.wert,
            actorParticipantUid = entwurf.actorParticipantUid,
            targetParticipantUid = null,
            payloadJson = json,
            payloadCiphertext = null,
            encScheme = null,
            payloadSchemaVersion = 1,
            visibility = entwurf.visibility.wert,
            recipientParticipantUid = null,
            isUndone = false,
            undoneByEventUid = null,
            hasConflict = false,
        )
    }
}
