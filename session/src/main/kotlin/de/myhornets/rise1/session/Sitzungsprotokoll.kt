package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.EventClass
import de.myhornets.rise1.core.event.MatchEvent
import de.myhornets.rise1.core.event.Partiestand
import de.myhornets.rise1.core.event.Payload
import de.myhornets.rise1.core.event.Schnappschuss
import de.myhornets.rise1.core.event.Sitzplatzstand
import de.myhornets.rise1.core.event.Visibility
import de.myhornets.rise1.transport.Rahmen
import de.myhornets.rise1.transport.Rahmentyp

/**
 * Das Nutzlastformat der Sitzungsrahmen — [[ADR-007 Nutzlastformat der Sitzungsrahmen]].
 *
 * ## Wozu
 *
 * `T-072` hat die Rahmung entschieden und offen gelassen, was darin steht.
 * `T-105` und `T-108` sind fertig und ohne diese Datei unerreichbar: Ein
 * Wiedereinstiegsgesuch, das nur als Kotlin-Wert im selben Prozess existiert,
 * ist kein Wiedereinstieg.
 *
 * ## Der Grundsatz des Lesers
 *
 * **Keine Länge wird geglaubt.** Jede kommt vom Gegenüber und wird gegen den
 * vorhandenen Rest geprüft, bevor irgendetwas alloziert wird — derselbe
 * Grundsatz, aus dem `Rahmencodec.MAX_NUTZLAST` entstanden ist. Ein Gerät, das
 * eine Milliarde behauptet und dann schweigt, bekommt einen [Protokollfehler]
 * und keinen Speicher.
 *
 * **Nichts wird halb herausgegeben.** Der Leser liefert eine vollständige
 * Nachricht oder wirft. Eine halb gelesene Antwort wäre ein Zustand, den
 * niemand mehr erklären kann.
 *
 * ## Das Geheimnis in der Nutzlast
 *
 * Der `rejoin_token` steht im Handshake — er muss dorthin, sonst gäbe es keinen
 * Nachweis (TDD 9.3). Deshalb nennen die Fehlermeldungen hier **Feldnamen und
 * Längen, niemals Inhalte**. Ein Protokollfehler, der die gelesenen Bytes
 * mitliefert, schreibt früher oder später ein Geheimnis in ein Protokoll.
 */
object Sitzungsprotokoll {

    /** Fassung der Nutzlast, unabhängig von der Protokollversion der Rahmung. */
    const val FASSUNG: Byte = 1

    /**
     * Obergrenze für die Anzahl Events in einem Delta.
     *
     * Unabhängig von der Rahmenobergrenze: Ein Rahmen von einem Megabyte könnte
     * sonst hunderttausende winzige Events behaupten. Der Wert liegt bewusst
     * über jeder sinnvollen [Aufholschwelle] — er ist eine Notbremse, keine
     * Fachregel.
     */
    const val MAX_EVENTS = 10_000

    /** Obergrenze für eine einzelne Zeichenkette. UUIDs und Typnamen sind kurz. */
    const val MAX_TEXT = 4_096

    /**
     * Obergrenze für Sitzplätze in einem Schnappschuss.
     *
     * TDD 6.1 rechnet mit acht Spielern; 64 lässt jede denkbare Runde zu und
     * verhindert trotzdem, dass ein Rahmen Millionen Sitzplätze behauptet.
     */
    const val MAX_SITZPLAETZE = 64

    // ── Handshake (TDD 9.3) ─────────────────────────────────────────────────

    fun kodiere(gesuch: Wiedereinstiegsgesuch): Rahmen {
        val s = Schreiber()
        s.text(gesuch.matchUid)
        s.text(gesuch.participantUid)
        s.text(gesuch.rejoinToken)
        s.text(gesuch.deviceUid)
        s.zahl(gesuch.lastSeqSeen)
        return Rahmen(Rahmentyp.HANDSHAKE, s.fertig())
    }

    fun liesGesuch(rahmen: Rahmen): Wiedereinstiegsgesuch {
        erwarte(rahmen, Rahmentyp.HANDSHAKE)
        val l = Leser(rahmen.nutzlast)
        val gesuch = try {
            Wiedereinstiegsgesuch(
                matchUid = l.text("match_uid"),
                participantUid = l.text("participant_uid"),
                rejoinToken = l.text("rejoin_token"),
                deviceUid = l.text("device_uid"),
                lastSeqSeen = l.zahl("last_seq_seen"),
            )
        } catch (fehler: IllegalArgumentException) {
            // Die Werttypen prüfen selbst (leere Kennungen, last_seq_seen < -1).
            // Ihre Meldung ist hier aber ein **Eingabefehler** und keine
            // Zusicherungsverletzung — sonst sähe ein feindliches Paket aus wie
            // ein Programmierfehler.
            throw Protokollfehler("Das Gesuch ist inhaltlich unzulässig: ${fehler.message}")
        }
        l.mussLeerSein()
        return gesuch
    }

    fun kodiere(antwort: Wiedereinstiegsantwort): Rahmen = when (antwort) {
        is Wiedereinstiegsantwort.Angenommen -> {
            val s = Schreiber()
            s.text(antwort.sitzungsUid)
            s.textOderNichts(antwort.abgeloesteSitzung)
            s.zahl(antwort.bisSeq)
            Rahmen(Rahmentyp.HANDSHAKE_ANTWORT, s.fertig())
        }

        is Wiedereinstiegsantwort.Abgelehnt -> {
            // Eigener Rahmentyp. Eine Ablehnung ist keine Antwort mit einem
            // Flag darin: Wer sie als solche liest, kann sie übersehen.
            val s = Schreiber()
            s.byteWert(kennungVon(antwort.grund))
            Rahmen(Rahmentyp.ABLEHNUNG, s.fertig())
        }
    }

    fun liesAntwort(rahmen: Rahmen): Wiedereinstiegsantwort = when (rahmen.typ) {
        Rahmentyp.HANDSHAKE_ANTWORT -> {
            val l = Leser(rahmen.nutzlast)
            val antwort = Wiedereinstiegsantwort.Angenommen(
                sitzungsUid = l.text("session_uid"),
                abgeloesteSitzung = l.textOderNichts("superseded_session_uid"),
                bisSeq = l.zahl("up_to_seq"),
            )
            l.mussLeerSein()
            antwort
        }

        Rahmentyp.ABLEHNUNG -> {
            val l = Leser(rahmen.nutzlast)
            val grund = grundVon(l.byteWert("reason"))
            l.mussLeerSein()
            Wiedereinstiegsantwort.Abgelehnt(grund)
        }

        else -> throw Protokollfehler(
            "Rahmentyp ${rahmen.typ} ist keine Antwort auf einen Handshake.",
        )
    }

    // ── Beitritt (TDD 9.3, T-101) ───────────────────────────────────────────

    /**
     * Das Gesuch eines Geräts, das noch keinen Sitzplatz hat.
     *
     * Anders als beim Wiedereinstieg steht hier **kein Geheimnis** drin: Der
     * `rejoin_token` entsteht erst durch die Antwort. Ein Beitritt weist nichts
     * nach — wer am Tisch sitzt, entscheidet der Host (`Beitrittsstelle`), und
     * dass es der richtige Host ist, hat der Gast schon vor dem ersten Byte
     * geprüft: über den Fingerabdruck des Zertifikats (ADR-001, ADR-006).
     */
    fun kodiere(gesuch: Beitrittsgesuch): Rahmen {
        val s = Schreiber()
        s.text(gesuch.matchUid)
        s.text(gesuch.deviceUid)
        s.text(gesuch.anzeigename)
        s.zahlOderNichts(gesuch.wunschplatz?.toLong())
        return Rahmen(Rahmentyp.BEITRITT, s.fertig())
    }

    fun liesBeitrittsgesuch(rahmen: Rahmen): Beitrittsgesuch {
        erwarte(rahmen, Rahmentyp.BEITRITT)
        val l = Leser(rahmen.nutzlast)
        val matchUid = l.text("match_uid")
        val deviceUid = l.text("device_uid")
        val anzeigename = l.text("display_name")
        val wunschplatz = l.zahlOderNichts("seat_index")
        l.mussLeerSein()

        if (wunschplatz != null && (wunschplatz < 0 || wunschplatz > MAX_SITZPLAETZE)) {
            // Die Länge kam vom Gegenüber, die Zahl auch. Ein Wunschplatz von
            // drei Milliarden ist kein Wunsch.
            throw Protokollfehler(
                "Feld `seat_index` liegt außerhalb von 0..$MAX_SITZPLAETZE.",
            )
        }
        return try {
            Beitrittsgesuch(
                matchUid = matchUid,
                deviceUid = deviceUid,
                anzeigename = anzeigename,
                wunschplatz = wunschplatz?.toInt(),
            )
        } catch (fehler: IllegalArgumentException) {
            throw Protokollfehler("Das Beitrittsgesuch ist inhaltlich unzulässig: ${fehler.message}")
        }
    }

    /**
     * Die Antwort des Hosts.
     *
     * **Der `rejoin_token` geht genau hier einmal über die Leitung** — er muss,
     * sonst hätte der Gast keinen Nachweis für den Wiedereinstieg (TDD 9.3).
     * Er darf deshalb in keiner Fehlermeldung und in keinem `toString`
     * auftauchen; dafür sorgen die Meldungen dieses Lesers, die Feldnamen und
     * Längen nennen und nie Inhalte.
     */
    fun kodiere(antwort: Beitrittsantwort): Rahmen = when (antwort) {
        is Beitrittsantwort.Angenommen -> {
            val s = Schreiber()
            s.text(antwort.participantUid)
            s.anzahl(antwort.sitzplatz)
            s.text(antwort.rejoinToken)
            Rahmen(Rahmentyp.BEITRITT_ANTWORT, s.fertig())
        }

        is Beitrittsantwort.Abgelehnt -> {
            val s = Schreiber()
            s.byteWert(kennungVon(antwort.grund))
            Rahmen(Rahmentyp.BEITRITT_ABLEHNUNG, s.fertig())
        }
    }

    fun liesBeitrittsantwort(rahmen: Rahmen): Beitrittsantwort = when (rahmen.typ) {
        Rahmentyp.BEITRITT_ANTWORT -> {
            val l = Leser(rahmen.nutzlast)
            val antwort = try {
                Beitrittsantwort.Angenommen(
                    participantUid = l.text("participant_uid"),
                    sitzplatz = l.anzahl("seat_index", MAX_SITZPLAETZE),
                    rejoinToken = l.text("rejoin_token"),
                )
            } catch (fehler: IllegalArgumentException) {
                throw Protokollfehler("Die Beitrittsantwort ist inhaltlich unzulässig: ${fehler.message}")
            }
            l.mussLeerSein()
            antwort
        }

        Rahmentyp.BEITRITT_ABLEHNUNG -> {
            val l = Leser(rahmen.nutzlast)
            val grund = beitrittsgrundVon(l.byteWert("reason"))
            l.mussLeerSein()
            Beitrittsantwort.Abgelehnt(grund)
        }

        else -> throw Protokollfehler(
            "Rahmentyp ${rahmen.typ} ist keine Antwort auf ein Beitrittsgesuch.",
        )
    }

    /** Feste Kennungen — sie stehen auf der Leitung und dürfen sich nie verschieben. */
    internal fun kennungVon(grund: Beitrittsablehnung): Byte = when (grund) {
        Beitrittsablehnung.PARTIE_UNBEKANNT -> 1
        Beitrittsablehnung.PARTIE_NIMMT_NICHT_MEHR_AUF -> 2
        Beitrittsablehnung.TISCH_VOLL -> 3
        Beitrittsablehnung.PLATZ_BESETZT -> 4
        Beitrittsablehnung.GERAET_SITZT_SCHON -> 5
    }

    internal fun beitrittsgrundVon(kennung: Byte): Beitrittsablehnung = when (kennung.toInt()) {
        1 -> Beitrittsablehnung.PARTIE_UNBEKANNT
        2 -> Beitrittsablehnung.PARTIE_NIMMT_NICHT_MEHR_AUF
        3 -> Beitrittsablehnung.TISCH_VOLL
        4 -> Beitrittsablehnung.PLATZ_BESETZT
        5 -> Beitrittsablehnung.GERAET_SITZT_SCHON
        else -> throw Protokollfehler("Unbekannte Kennung $kennung im Feld `reason` einer Beitrittsablehnung.")
    }

    // ── Aufholen (TDD 9.5) ──────────────────────────────────────────────────

    fun kodiere(delta: Aufholung.Delta): Rahmen {
        val s = Schreiber()
        s.text(delta.matchUid)
        s.text(delta.empfaenger)
        s.zahl(delta.abSeqExklusiv)
        s.zahl(delta.bisSeq)
        s.anzahl(delta.events.size)
        delta.events.forEach { schreibeEvent(s, it) }
        return Rahmen(Rahmentyp.DELTA, s.fertig())
    }

    fun liesDelta(rahmen: Rahmen): Aufholung.Delta {
        erwarte(rahmen, Rahmentyp.DELTA)
        val l = Leser(rahmen.nutzlast)
        val matchUid = l.text("match_uid")
        val empfaenger = l.text("recipient_participant_uid")
        val ab = l.zahl("ab_seq_exklusiv")
        val bis = l.zahl("bis_seq")
        val anzahl = l.anzahl("event_count", MAX_EVENTS)

        val events = ArrayList<MatchEvent>(minOf(anzahl, 64))
        repeat(anzahl) { events += liesEvent(l) }
        l.mussLeerSein()

        return try {
            Aufholung.Delta(matchUid, empfaenger, ab, bis, events)
        } catch (fehler: IllegalArgumentException) {
            throw Protokollfehler("Der Bereich des Deltas ist unzulässig: ${fehler.message}")
        }
    }

    // ── Schnappschuss (TDD 9.5) ─────────────────────────────────────────────

    fun kodiere(schnappschuss: Schnappschuss): Rahmen {
        val s = Schreiber()
        s.text(schnappschuss.matchUid)
        s.text(schnappschuss.empfaenger)
        s.zahl(schnappschuss.bisSeq)

        s.anzahl(schnappschuss.partie.zugnummer)
        s.textOderNichts(schnappschuss.partie.amZug)
        s.zahl(schnappschuss.partie.letzteAngewandteSeq)

        // Die Feldstruktur ist für alle Empfänger dieselbe (TDD 9.5). Deshalb
        // wird hier nichts ausgelassen, wenn es leer ist — ein weggelassenes
        // Feld wäre selbst eine Information.
        s.anzahl(schnappschuss.sitzplaetze.size)
        schnappschuss.sitzplaetze.forEach { platz ->
            s.text(platz.participantUid)
            s.zahl(platz.leben.toLong())
            s.flagge(platz.istAufgedeckt)
            s.flagge(platz.istAusgeschieden)
            s.textOderNichts(platz.aufgedeckteIdentitaet)
        }

        s.anzahl(schnappschuss.transkript.size)
        schnappschuss.transkript.forEach { schreibeEvent(s, it) }
        s.anzahl(schnappschuss.eigenePrivate.size)
        schnappschuss.eigenePrivate.forEach { schreibeEvent(s, it) }

        return Rahmen(Rahmentyp.SCHNAPPSCHUSS, s.fertig())
    }

    fun liesSchnappschuss(rahmen: Rahmen): Schnappschuss {
        erwarte(rahmen, Rahmentyp.SCHNAPPSCHUSS)
        val l = Leser(rahmen.nutzlast)
        val matchUid = l.text("match_uid")
        val empfaenger = l.text("recipient_participant_uid")
        val bisSeq = l.zahl("up_to_seq")

        val zugnummer = l.anzahl("turn_number")
        val amZug = l.textOderNichts("active_participant_uid")
        val letzteSeq = l.zahl("last_applied_seq")

        val anzahlPlaetze = l.anzahl("seat_count", MAX_SITZPLAETZE)
        val sitzplaetze = ArrayList<Sitzplatzstand>(anzahlPlaetze)
        repeat(anzahlPlaetze) {
            val uid = l.text("participant_uid")
            val leben = l.zahl("life")
            val aufgedeckt = l.flagge("is_revealed")
            val ausgeschieden = l.flagge("is_eliminated")
            val identitaet = l.textOderNichts("revealed_identity_uid")
            sitzplaetze += try {
                Sitzplatzstand(uid, leben.toInt(), aufgedeckt, ausgeschieden, identitaet)
            } catch (fehler: IllegalArgumentException) {
                throw Protokollfehler("Ein Sitzplatz ist inhaltlich unzulässig: ${'$'}{fehler.message}")
            }
        }

        val transkript = ArrayList<MatchEvent>()
        repeat(l.anzahl("transcript_count", MAX_EVENTS)) { transkript += liesEvent(l) }
        val eigene = ArrayList<MatchEvent>()
        repeat(l.anzahl("private_count", MAX_EVENTS)) { eigene += liesEvent(l) }
        l.mussLeerSein()

        return try {
            Schnappschuss(
                matchUid = matchUid,
                empfaenger = empfaenger,
                bisSeq = bisSeq,
                partie = Partiestand(matchUid, zugnummer, amZug, letzteSeq),
                sitzplaetze = sitzplaetze,
                transkript = transkript,
                eigenePrivate = eigene,
            )
        } catch (fehler: IllegalArgumentException) {
            // Auch hier prüft der Werttyp mit: ein fremdes PRIVATE im
            // Schnappschuss kommt nicht durch, egal wer die Bytes geschickt hat.
            throw Protokollfehler("Der Schnappschuss ist inhaltlich unzulässig: ${'$'}{fehler.message}")
        }
    }

    // ── Ein einzelnes Event (TDD 5.2) ───────────────────────────────────────

    private fun schreibeEvent(s: Schreiber, e: MatchEvent) {
        s.text(e.eventUid)
        s.text(e.matchUid)
        s.zahlOderNichts(e.seq)
        s.text(e.originDeviceUid)
        s.zahl(e.originSeq)
        s.zahl(e.lamportClock)
        s.zahl(e.occurredAt)
        s.zahlOderNichts(e.recordedAt)
        // `type` bleibt ein String — ein Gerät mit neuerer App darf einen Typ
        // schicken, den dieses nicht kennt (TDD 5.5). Eine Kennung hier würde
        // genau das verhindern.
        s.text(e.type)
        s.byteWert(kennungVon(e.eventClass))
        s.textOderNichts(e.actorParticipantUid)
        s.textOderNichts(e.targetParticipantUid)
        s.byteWert(kennungVon(e.visibility))
        s.textOderNichts(e.recipientParticipantUid)
        s.anzahl(e.payloadSchemaVersion)
        schreibePayload(s, e.payload)
        s.flagge(e.isUndone)
        s.textOderNichts(e.undoneByEventUid)
        s.flagge(e.hasConflict)
    }

    private fun liesEvent(l: Leser): MatchEvent {
        val eventUid = l.text("event_uid")
        val matchUid = l.text("match_uid")
        val seq = l.zahlOderNichts("seq")
        val originDeviceUid = l.text("origin_device_uid")
        val originSeq = l.zahl("origin_seq")
        val lamport = l.zahl("lamport_clock")
        val occurredAt = l.zahl("occurred_at")
        val recordedAt = l.zahlOderNichts("recorded_at")
        val type = l.text("type")
        val eventClass = eventClassVon(l.byteWert("event_class"))
        val actor = l.textOderNichts("actor_participant_uid")
        val target = l.textOderNichts("target_participant_uid")
        val visibility = visibilityVon(l.byteWert("visibility"))
        val recipient = l.textOderNichts("recipient_participant_uid")
        val schemaVersion = l.anzahl("payload_schema_version")
        val payload = liesPayload(l)
        val undone = l.flagge("is_undone")
        val undoneBy = l.textOderNichts("undone_by_event_uid")
        val konflikt = l.flagge("has_conflict")

        return try {
            MatchEvent(
                eventUid = eventUid,
                matchUid = matchUid,
                seq = seq,
                originDeviceUid = originDeviceUid,
                originSeq = originSeq,
                lamportClock = lamport,
                occurredAt = occurredAt,
                recordedAt = recordedAt,
                type = type,
                eventClass = eventClass,
                actorParticipantUid = actor,
                targetParticipantUid = target,
                payload = payload,
                payloadSchemaVersion = schemaVersion,
                visibility = visibility,
                recipientParticipantUid = recipient,
                isUndone = undone,
                undoneByEventUid = undoneBy,
                hasConflict = konflikt,
            )
        } catch (fehler: IllegalArgumentException) {
            // Hier zahlt sich der Werttyp aus: Ein Gegenüber, das ein
            // PUBLIC-Event mit Chiffrat oder ein PRIVATE-Event ohne Empfänger
            // schickt, kommt nicht durch. Die Regel steht an genau einer Stelle
            // und gilt auch für Bytes, die von außen kommen.
            throw Protokollfehler("Ein Event ist inhaltlich unzulässig: ${fehler.message}")
        }
    }

    private fun schreibePayload(s: Schreiber, payload: Payload) {
        when (payload) {
            is Payload.Leer -> s.byteWert(PAYLOAD_LEER)
            is Payload.Klartext -> {
                s.byteWert(PAYLOAD_KLARTEXT)
                s.langerText(payload.json)
            }

            is Payload.Chiffrat -> {
                s.byteWert(PAYLOAD_CHIFFRAT)
                s.text(payload.encScheme)
                s.bytes(payload.bytes)
            }
        }
    }

    private fun liesPayload(l: Leser): Payload = when (val art = l.byteWert("payload_kind")) {
        PAYLOAD_LEER -> Payload.Leer
        PAYLOAD_KLARTEXT -> Payload.Klartext(l.langerText("payload_json"))
        PAYLOAD_CHIFFRAT -> {
            val schema = l.text("enc_scheme")
            Payload.Chiffrat(l.bytes("payload_ciphertext"), schema)
        }

        else -> throw Protokollfehler("Unbekannte Nutzdatenart $art.")
    }

    // ── Kennungen auf der Leitung ───────────────────────────────────────────
    //
    // Eigene Zahlen, nicht `ordinal`: Ein Umsortieren des `enum` darf die
    // Bedeutung auf der Leitung nicht verschieben. Dieselbe Regel wie bei
    // `Rahmentyp.kennung`.

    private const val PAYLOAD_LEER: Byte = 0
    private const val PAYLOAD_KLARTEXT: Byte = 1
    private const val PAYLOAD_CHIFFRAT: Byte = 2

    internal fun kennungVon(wert: EventClass): Byte = when (wert) {
        EventClass.STATE -> 1
        EventClass.SESSION -> 2
        EventClass.ANNOTATION -> 3
    }

    internal fun eventClassVon(kennung: Byte): EventClass = when (kennung) {
        1.toByte() -> EventClass.STATE
        2.toByte() -> EventClass.SESSION
        3.toByte() -> EventClass.ANNOTATION
        else -> throw Protokollfehler("Unbekannte event_class $kennung.")
    }

    internal fun kennungVon(wert: Visibility): Byte = when (wert) {
        Visibility.PUBLIC -> 1
        Visibility.PLAYER_ONLY -> 2
        Visibility.PRIVATE -> 3
    }

    internal fun visibilityVon(kennung: Byte): Visibility = when (kennung) {
        1.toByte() -> Visibility.PUBLIC
        2.toByte() -> Visibility.PLAYER_ONLY
        3.toByte() -> Visibility.PRIVATE
        else -> throw Protokollfehler("Unbekannte visibility $kennung.")
    }

    internal fun kennungVon(grund: Ablehnungsgrund): Byte = when (grund) {
        Ablehnungsgrund.PARTIE_UNBEKANNT -> 1
        Ablehnungsgrund.PARTIE_BEENDET -> 2
        Ablehnungsgrund.SITZPLATZ_UNBEKANNT -> 3
        Ablehnungsgrund.NACHWEIS_FALSCH -> 4
        Ablehnungsgrund.ZU_VIELE_VERSUCHE -> 5
    }

    internal fun grundVon(kennung: Byte): Ablehnungsgrund = when (kennung) {
        1.toByte() -> Ablehnungsgrund.PARTIE_UNBEKANNT
        2.toByte() -> Ablehnungsgrund.PARTIE_BEENDET
        3.toByte() -> Ablehnungsgrund.SITZPLATZ_UNBEKANNT
        4.toByte() -> Ablehnungsgrund.NACHWEIS_FALSCH
        5.toByte() -> Ablehnungsgrund.ZU_VIELE_VERSUCHE
        else -> throw Protokollfehler("Unbekannter Ablehnungsgrund $kennung.")
    }

    private fun erwarte(rahmen: Rahmen, typ: Rahmentyp) {
        if (rahmen.typ != typ) {
            throw Protokollfehler("Rahmentyp ${rahmen.typ} gelesen, erwartet war $typ.")
        }
    }
}

/**
 * Ein Fehler im Nutzlastformat.
 *
 * Getrennt von `Rahmenfehler`: Der eine sagt, dass die **Rahmung** nicht stimmt
 * — dann ist die Leitung unbrauchbar. Dieser sagt, dass ein einzelner Rahmen
 * unsinnigen Inhalt hatte. Der Unterschied entscheidet, ob die Verbindung
 * fällt oder nur eine Nachricht verworfen wird.
 *
 * Die Meldung nennt **nie** gelesene Inhalte — siehe [Sitzungsprotokoll].
 */
class Protokollfehler(meldung: String) : IllegalStateException(meldung)

/** Baut eine Nutzlast. Wächst mit; die Obergrenze prüft der Rahmen selbst. */
private class Schreiber {

    private val bytes = ArrayList<Byte>(256)

    init {
        bytes += Sitzungsprotokoll.FASSUNG
    }

    fun byteWert(wert: Byte) {
        bytes += wert
    }

    fun flagge(wert: Boolean) = byteWert(if (wert) 1 else 0)

    /** Vorzeichenbehaftet, 8 Byte, big-endian. `seq` und Zeitstempel sind `Long`. */
    fun zahl(wert: Long) {
        for (schritt in 7 downTo 0) bytes += ((wert ushr (schritt * 8)) and 0xFF).toByte()
    }

    fun zahlOderNichts(wert: Long?) {
        if (wert == null) {
            flagge(false)
        } else {
            flagge(true)
            zahl(wert)
        }
    }

    /** Nichtnegative Anzahl, 4 Byte. */
    fun anzahl(wert: Int) {
        require(wert >= 0) { "Eine negative Anzahl gibt es nicht: $wert." }
        for (schritt in 3 downTo 0) bytes += ((wert ushr (schritt * 8)) and 0xFF).toByte()
    }

    fun text(wert: String) {
        val roh = wert.toByteArray(Charsets.UTF_8)
        require(roh.size <= Sitzungsprotokoll.MAX_TEXT) {
            "Zeichenkette mit ${roh.size} Bytes überschreitet ${Sitzungsprotokoll.MAX_TEXT}."
        }
        bytes += ((roh.size ushr 8) and 0xFF).toByte()
        bytes += (roh.size and 0xFF).toByte()
        roh.forEach { bytes += it }
    }

    fun textOderNichts(wert: String?) {
        if (wert == null) {
            flagge(false)
        } else {
            flagge(true)
            text(wert)
        }
    }

    /** Für `payload_json`: darf länger sein als eine Kennung. */
    fun langerText(wert: String) = bytes(wert.toByteArray(Charsets.UTF_8))

    fun bytes(wert: ByteArray) {
        anzahl(wert.size)
        wert.forEach { bytes += it }
    }

    fun fertig(): ByteArray = bytes.toByteArray()
}

/** Liest eine Nutzlast. Prüft jede Länge, bevor sie etwas anlegt. */
private class Leser(private val quelle: ByteArray) {

    private var stelle = 0

    init {
        if (quelle.isEmpty()) throw Protokollfehler("Leere Nutzlast — kein Fassungsbyte.")
        val fassung = quelle[0]
        if (fassung != Sitzungsprotokoll.FASSUNG) {
            throw Protokollfehler(
                "Nutzlastfassung $fassung, erwartet ${Sitzungsprotokoll.FASSUNG}. Eine andere " +
                    "Fassung ist eine andere Sprache und wird nicht geraten.",
            )
        }
        stelle = 1
    }

    private fun brauche(anzahl: Int, feld: String) {
        if (anzahl < 0 || quelle.size - stelle < anzahl) {
            throw Protokollfehler(
                "Feld `$feld` verlangt $anzahl Bytes, vorhanden sind ${quelle.size - stelle}.",
            )
        }
    }

    fun byteWert(feld: String): Byte {
        brauche(1, feld)
        return quelle[stelle++]
    }

    fun flagge(feld: String): Boolean = when (val wert = byteWert(feld)) {
        0.toByte() -> false
        1.toByte() -> true
        // Kein `!= 0`: Ein drittes Byte bedeutet, dass der Absender etwas
        // anderes meint als dieses Gerät versteht.
        else -> throw Protokollfehler("Feld `$feld` ist eine Flagge, hat aber den Wert $wert.")
    }

    fun zahl(feld: String): Long {
        brauche(8, feld)
        var wert = 0L
        repeat(8) { wert = (wert shl 8) or (quelle[stelle++].toLong() and 0xFF) }
        return wert
    }

    fun zahlOderNichts(feld: String): Long? = if (flagge("${feld}_gesetzt")) zahl(feld) else null

    fun anzahl(feld: String, hoechstens: Int = Int.MAX_VALUE): Int {
        brauche(4, feld)
        var wert = 0L
        repeat(4) { wert = (wert shl 8) or (quelle[stelle++].toLong() and 0xFF) }
        if (wert > hoechstens) {
            throw Protokollfehler(
                "Feld `$feld` behauptet $wert, erlaubt sind höchstens $hoechstens. Eine " +
                    "Anzahl vom Gegenüber wird nicht geglaubt.",
            )
        }
        return wert.toInt()
    }

    fun text(feld: String): String {
        brauche(2, feld)
        val laenge = ((quelle[stelle].toInt() and 0xFF) shl 8) or (quelle[stelle + 1].toInt() and 0xFF)
        stelle += 2
        brauche(laenge, feld)
        val wert = String(quelle, stelle, laenge, Charsets.UTF_8)
        stelle += laenge
        return wert
    }

    fun textOderNichts(feld: String): String? = if (flagge("${feld}_gesetzt")) text(feld) else null

    fun langerText(feld: String): String = String(bytes(feld), Charsets.UTF_8)

    fun bytes(feld: String): ByteArray {
        val laenge = anzahl(feld)
        brauche(laenge, feld)
        val wert = quelle.copyOfRange(stelle, stelle + laenge)
        stelle += laenge
        return wert
    }

    /**
     * Überzählige Bytes sind ein Fehler.
     *
     * Wer sie durchgehen ließe, könnte eine Nachricht mit angehängtem Unsinn
     * annehmen — und zwei Geräte wären sich über den Inhalt einig, über die
     * Länge aber nicht. Das ist die Stelle, an der Protokolle auseinanderlaufen.
     */
    fun mussLeerSein() {
        if (stelle != quelle.size) {
            throw Protokollfehler(
                "Nach der Nachricht stehen noch ${quelle.size - stelle} Bytes.",
            )
        }
    }
}
