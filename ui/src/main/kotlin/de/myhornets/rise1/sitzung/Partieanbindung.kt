package de.myhornets.rise1.sitzung

import de.myhornets.rise1.core.event.EventType
import de.myhornets.rise1.ereignis.EventAbbildung
import de.myhornets.rise1.session.Partiekontext
import de.myhornets.rise1.session.Sitzplatz
import de.myhornets.rise1.session.Sitzplatzzustand
import de.myhornets.rise1.session.Tischnachschlag
import de.myhornets.rise1.store.MatchEntity
import de.myhornets.rise1.store.MatchParticipantEntity
import de.myhornets.rise1.store.Geraeteanmeldung
import de.myhornets.rise1.store.MatchEventLog
import de.myhornets.rise1.store.RiseDatabase
import de.myhornets.rise1.store.Sitzungsverwaltung
import java.util.UUID

/**
 * T-100 / T-101 — Partieanlage und Beitritt an der Ablage.
 *
 * Zweite Naht neben `Sitzungsanbindung`, aus demselben Grund: `:ui` ist das
 * einzige Modul, das `:session` und `:store` sieht.
 *
 * Die **Entscheidungen** stehen in `:session` und laufen dort ohne Android:
 * welcher Platz vergeben wird, wie das Geheimnis entsteht, was der Fingerabdruck
 * ist. Hier steht, was daraus in `rise.db` und ins Event-Log wandert.
 */

/**
 * Schreibt eine angelegte Partie fest (T-100).
 *
 * Die Reihenfolge ist die aus `Partieeroeffnung.SCHRITTE`, und sie ist keine
 * Geschmacksfrage: Erst die Zeile in `match`, dann `match_created`. Andersherum
 * verwiese ein Event auf eine Partie, die es in der Ablage noch nicht gibt —
 * und die Fortschreibung der Projektion liefe ins Leere.
 */
class Partieschreiber(
    private val datenbank: RiseDatabase,
    private val log: MatchEventLog,
) {

    /**
     * Legt Zeile und Event an.
     *
     * @return die `event_uid` des `match_created`, oder `null`, wenn es das
     *   Event schon gab — dann war diese Partie bereits angelegt, und ein
     *   zweiter Aufruf ändert nichts (Idempotenz über den Dedup des Logs).
     */
    fun schreibe(kontext: Partiekontext, modeUid: String, catalogVersion: String, setCode: String): String? {
        datenbank.matchDao().einfuegen(
            MatchEntity(
                matchUid = kontext.matchUid,
                modeUid = modeUid,
                hostDeviceUid = kontext.hostDeviceUid,
                status = kontext.status,
                hostHeartbeatAt = null,
                playerCount = kontext.plaetze,
                catalogVersionUsed = catalogVersion,
                setCodeUsed = setCode,
                dealTranscriptDigest = null,
                moderatorMode = false,
                startedAt = null,
                endedAt = null,
                lastAppliedSeq = 0L,
                createdAt = kontext.angelegtAm,
                updatedAt = kontext.angelegtAm,
                deletedAt = null,
                originDeviceUid = kontext.hostDeviceUid,
            ),
        )

        val event = EventAbbildung.oeffentlichesZustandsEvent(
            matchUid = kontext.matchUid,
            typ = EventType.MATCH_CREATED,
            deviceUid = kontext.hostDeviceUid,
            jetzt = kontext.angelegtAm,
        )
        return log.anhaengenLokal(event)?.eventUid
    }
}

/**
 * [Tischnachschlag] über `rise.db` (T-101 / T-111).
 *
 * ## Warum der Sitzplatz eine Sitzung eröffnet
 *
 * `match_participant` führt keine Gerätespalte (TDD 4.4) — die Zuordnung
 * Sitzplatz ⇄ Gerät **ist** die Sitzung (TDD 4.5). Ohne sie könnte [belegung]
 * nicht sagen, welches Gerät schon am Tisch sitzt, und ein zweimal gedrückter
 * Knopf verbrauchte einen Platz. Deshalb legt [legeSitzplatzAn] beides an, über
 * `Sitzungsverwaltung` aus `T-102`.
 */
class RaumTischnachschlag(
    private val datenbank: RiseDatabase,
    private val kontext: Partiekontext,
    private val uhr: () -> Long = System::currentTimeMillis,
) : Tischnachschlag {

    private val sitzungen = Sitzungsverwaltung(datenbank)

    override fun status(matchUid: String): String? = datenbank.matchDao().nachUid(matchUid)?.status

    override fun plaetze(matchUid: String): Int =
        datenbank.matchDao().nachUid(matchUid)?.playerCount ?: 0

    override fun belegung(matchUid: String): List<Sitzplatz> =
        datenbank.matchParticipantDao().sitzordnung(matchUid).map { zeile ->
            Sitzplatz(
                participantUid = zeile.participantUid,
                sitzplatz = zeile.seatIndex,
                zustand = zeile.seatState,
                geraeteUid = sitzungen.laufende(zeile.participantUid)?.deviceUid,
            )
        }

    override fun legeSitzplatzAn(
        matchUid: String,
        sitzplatz: Int,
        anzeigename: String,
        tokenHash: String,
        deviceUid: String,
    ): String {
        val jetzt = uhr()
        val participantUid = UUID.randomUUID().toString()

        datenbank.matchParticipantDao().einfuegen(
            MatchParticipantEntity(
                participantUid = participantUid,
                matchUid = matchUid,
                playerUid = null,
                seatIndex = sitzplatz,
                displayNameSnapshot = anzeigename,
                identityCommitment = null,
                isRevealed = false,
                revealedAtSeq = null,
                revealedIdentityUid = null,
                identityNameSnapshot = null,
                identityRoleSnapshot = null,
                finalStatus = null,
                placement = null,
                // Nur der gesalzene Hash. Der Klartext lebt auf dem Gerät des
                // Spielers und kommt hier nie an (TDD 4.4 / 9.1).
                rejoinTokenHash = tokenHash,
                seatState = Sitzplatzzustand.AKTIV,
                lastSeenAt = jetzt,
                createdAt = jetzt,
                updatedAt = jetzt,
                deletedAt = null,
                originDeviceUid = kontext.hostDeviceUid,
            ),
        )

        // `participant_session.device_uid` zeigt auf `device`. Das Gerät des
        // Gasts kennt diese Datenbank noch nicht — ohne diese Zeile scheitert
        // die Sitzungseröffnung mit demselben FOREIGN KEY 787 wie die
        // Partieanlage ohne eigenes Gerät. Erst das Gerät, dann die Sitzung.
        Geraeteanmeldung(datenbank, uhr).merkeFremdes(
            deviceUid = deviceUid,
            anzeigename = anzeigename,
            durchGeraeteUid = kontext.hostDeviceUid,
        )

        sitzungen.eroeffne(
            sitzungsUid = UUID.randomUUID().toString(),
            participantUid = participantUid,
            deviceUid = deviceUid,
            jetzt = jetzt,
        )
        return participantUid
    }

    override fun ersetzeTokenHash(participantUid: String, tokenHash: String) {
        val zeile = datenbank.matchParticipantDao().nachUid(participantUid)
            ?: error("Sitzplatz $participantUid gibt es nicht — nichts wiederzuzulassen (TDD 9.6).")
        datenbank.matchParticipantDao().aktualisieren(
            zeile.copy(rejoinTokenHash = tokenHash, updatedAt = uhr()),
        )
    }
}
