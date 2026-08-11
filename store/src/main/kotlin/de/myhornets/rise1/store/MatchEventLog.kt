package de.myhornets.rise1.store

/**
 * T-025e/f — der einzige Schreibweg in den Spielzustand.
 *
 * ## Der Grundsatz
 *
 * TDD 5.1: Das Log ist die Wahrheit, der Zustand ist eine Projektion. Daraus
 * folgt eine Regel für diese Klasse, die wichtiger ist als alles andere hier:
 *
 * > **Es gibt keinen Weg, der die Projektion verändert, ohne ein Event
 * > anzuhängen.**
 *
 * [anhaengen] ist eine Transaktion: Entweder liegt das Event im Log **und** die
 * Projektion ist fortgeschrieben, oder es ist nichts passiert. Ein
 * Zwischenzustand — Event ohne Wirkung, Wirkung ohne Event — wäre genau das,
 * was der Neuaufbau später als Widerspruch fände, ohne sagen zu können, welche
 * Seite recht hat.
 *
 * ## Die zwei Wege und warum es beide gibt
 *
 * [anhaengen] schreibt **inkrementell** fort: ein Event, ein Schritt. Das ist
 * der Betriebsfall und kostet nichts.
 *
 * [neuAufbauen] verwirft die Projektion und faltet sie aus dem Log neu. Das ist
 * der Nachweis — und zugleich der Ausweg für den Fall, den der inkrementelle
 * Weg nicht kann: Trifft ein Event **verspätet** mit kleinerer `seq` ein
 * (TDD 6.5), müsste die Fortschreibung rückwärts rechnen. Sie tut es nicht; sie
 * erkennt den Fall und baut neu auf. Rückwärts zu rechnen wäre eine zweite,
 * schwierigere Umsetzung derselben Regeln — und jede Abweichung zwischen beiden
 * wäre ein Fehler, den niemand sähe.
 *
 * ## Die Regeln stehen genau einmal
 *
 * [wendeAn] wird von beiden Wegen benutzt. Zwei Umsetzungen derselben
 * Faltregeln wären zwei Gelegenheiten, sie verschieden zu verstehen.
 *
 * Eine **unabhängige** zweite Umsetzung gibt es trotzdem: `MatchFold` in
 * `:projection`, rein JVM und ohne Datenbank. Sie hier gegenzuprüfen ginge nur
 * mit einer Modulkante `:store → :projection`, die es nicht gibt und die eine
 * Ablage zum Mitspieler machen würde (ADR-003). Der Vergleich beider Seiten
 * gehört deshalb in `:ui` und ist als eigener Punkt vermerkt.
 *
 * ## Was hier nicht passiert
 *
 * Keine Regelauslegung. `identity_revealed` setzt ein Kennzeichen; was das
 * Aufdecken für den Tisch bedeutet, entscheidet der Tisch. Keine Kosten, keine
 * Fähigkeiten, keine Siegbedingung.
 */
class MatchEventLog(private val datenbank: RiseDatabase) {

    private val log get() = datenbank.eventLogDao()
    private val projektion get() = datenbank.projectionDao()

    /**
     * Hängt ein Event an und schreibt die Projektion fort — in einer Transaktion.
     *
     * @return `true`, wenn das Event neu war. `false` heißt: schon vorhanden,
     *   erkannt am eindeutigen Index auf `(origin_device_uid, origin_seq)`.
     *   Dann ist **nichts** geschehen, insbesondere wurde die Projektion nicht
     *   ein zweites Mal fortgeschrieben.
     */
    fun anhaengen(event: MatchEventEntity): Boolean {
        var neu = false
        datenbank.runInTransaction {
            neu = log.anhaengen(event) != -1L
            if (neu) fortschreiben(event)
        }
        return neu
    }

    /**
     * Hängt ein Event an und vergibt `seq` und `origin_seq` selbst — S2.
     *
     * ## Warum das hier steht und nicht in `:host`
     *
     * Die Sequenzvergabe ist Sache des Hosts (TDD 6.2), und `:host` wird sie
     * später übernehmen. Im lokalen Prototyp-Modus ([[ADR-004]]) **ist** das
     * eine Gerät der Host: Es gibt niemanden, mit dem eine Reihenfolge
     * auszuhandeln wäre. Diese Methode vergibt deshalb den nächsten freien
     * Wert innerhalb derselben Transaktion, in der das Event entsteht — ohne
     * Transaktion wäre zwischen „höchste lesen" und „einfügen" eine Lücke.
     *
     * Sie ist **kein Ersatz** für die Host-Koordination und heißt deshalb
     * `Lokal`. Mit S6 tritt die echte an ihre Stelle; diese Methode bleibt für
     * den Einzelgerätefall.
     *
     * `origin_seq` zählt **je Gerät über alle Partien**, nicht je Partie:
     * `(origin_device_uid, origin_seq)` ist der Dedup-Schlüssel und muss über
     * die ganze Tabelle eindeutig sein.
     *
     * @param entwurf ein Event, dessen `seq` und `origin_seq` **ignoriert**
     *   werden. Alles andere wird übernommen.
     * @return das tatsächlich gespeicherte Event mit den vergebenen Nummern,
     *   oder `null`, wenn es bereits vorhanden war.
     */
    fun anhaengenLokal(entwurf: MatchEventEntity): MatchEventEntity? {
        var ergebnis: MatchEventEntity? = null
        datenbank.runInTransaction {
            val fertig = mitNummern(entwurf)
            if (log.anhaengen(fertig) == -1L) return@runInTransaction
            fortschreiben(fertig)
            ergebnis = fertig
        }
        return ergebnis
    }

    /**
     * Hebt ein Event auf (TDD 5.3) — S2.
     *
     * Drei Schritte in **einer** Transaktion:
     *
     * 1. Ein `event_undone` wird angehängt. Es ist selbst ein Event; das Undo
     *    ist damit Teil der Geschichte und nicht ihre Korrektur.
     * 2. Das Ziel wird markiert. Nicht gelöscht — die Zeile bleibt vollständig
     *    stehen, nur die Faltung überspringt sie ab jetzt.
     * 3. Die Projektion wird **neu aufgebaut**. Das muss sie: Ein Undo wirkt
     *    rückwärts, und die Fortschreibung kann nur vorwärts. Genau dafür gibt
     *    es [neuAufbauen] — der Nachweis aus `T-025f` ist hier kein Test mehr,
     *    sondern Betrieb.
     *
     * @return `false`, wenn das Ziel fehlt oder schon aufgehoben war. Dann ist
     *   nichts geschehen, insbesondere wurde kein `event_undone` angehängt.
     */
    fun hebeAuf(zielEventUid: String, aufhebendesEvent: MatchEventEntity): Boolean {
        var erfolg = false
        datenbank.runInTransaction {
            val ziel = log.nachUid(zielEventUid) ?: return@runInTransaction
            if (ziel.isUndone) return@runInTransaction

            val aufheber = mitNummern(aufhebendesEvent)
            if (log.anhaengen(aufheber) == -1L) return@runInTransaction
            if (log.markiereAufgehoben(zielEventUid, aufheber.eventUid) != 1) return@runInTransaction

            neuAufbauenIntern(ziel.matchUid)
            erfolg = true
        }
        return erfolg
    }

    private fun mitNummern(entwurf: MatchEventEntity): MatchEventEntity {
        val seq = (log.hoechsteSeq(entwurf.matchUid) ?: -1L) + 1L
        val originSeq = (log.hoechsteOriginSeq(entwurf.originDeviceUid) ?: -1L) + 1L
        return entwurf.copy(seq = seq, originSeq = originSeq)
    }

    /** Fortschreiben oder — bei verspätetem Eintreffen — neu aufbauen. */
    private fun fortschreiben(event: MatchEventEntity) {
        val stand = projektion.partie(event.matchUid)?.lastAppliedSeq
        val seq = event.seq
        if (seq != null && stand != null && seq <= stand) {
            // Verspätet eingetroffen (TDD 6.5). Der inkrementelle Weg käme hier
            // nur mit Rückwärtsrechnen weiter — also gar nicht.
            neuAufbauenIntern(event.matchUid)
        } else {
            wendeAn(event)
        }
    }

    /**
     * Verwirft die Projektion einer Partie und baut sie aus dem Log neu auf.
     *
     * Das ist der Nachweis aus `T-025f`: Wenn das Log die Wahrheit ist, muss
     * dieser Weg denselben Zustand ergeben wie die Fortschreibung. Ergibt er
     * einen anderen, ist eine der beiden Seiten falsch — und das ist ein Befund,
     * kein Rundungsfehler.
     */
    fun neuAufbauen(matchUid: String) {
        datenbank.runInTransaction { neuAufbauenIntern(matchUid) }
    }

    private fun neuAufbauenIntern(matchUid: String) {
        projektion.verwirfTeilnehmer(matchUid)
        projektion.verwirfZaehler(matchUid)
        projektion.verwirfPartie(matchUid)
        // `bestaetigte` liefert nach `seq` sortiert — die kanonische Ordnung.
        log.bestaetigte(matchUid).forEach { wendeAn(it) }
    }

    // ── Die Faltregeln (TDD 5.2 / 5.5) ──────────────────────────────────────

    /**
     * Wendet genau ein Event auf die Projektion an.
     *
     * Wortgleich mit `MatchFold.falte` in `:projection`, Regel für Regel — nur
     * gegen Zeilen statt gegen Werte.
     */
    private fun wendeAn(event: MatchEventEntity) {
        val seq = event.seq ?: return // Ohne Bestätigung steht die Position nicht fest (TDD 6.2).

        // `last_applied_seq` ist der Stand der Projektion (TDD 4.4): Sie hat
        // dieses Event gesehen. Auch dann, wenn es nichts verändert — sonst
        // betrachtete ein Neuaufbau übersprungene Events wieder und wieder.
        val vorhanden = projektion.partie(event.matchUid)
        var zustand = vorhanden?.copy(lastAppliedSeq = maxOf(vorhanden.lastAppliedSeq, seq))
            ?: MatchStateEntity(
                matchUid = event.matchUid,
                // D-003: Die Zugzählung ist Bestandteil von `match_state`.
                turnNumber = 0,
                activeParticipantUid = null,
                lastAppliedSeq = seq,
            )

        // Erst schreiben, dann auswerten: Auch ein übersprungenes Event ist
        // gesehen worden. Die Auswertung unten schreibt bei Bedarf erneut.
        projektion.setzePartie(zustand)

        if (event.eventClass != EventClasses.STATE) return // TDD 5.2
        if (event.isUndone) return // TDD 5.3 — aufgehoben, nicht gelöscht

        when (event.type) {
            EventTypen.MATCH_CREATED -> Unit // Die Zeile ist oben bereits entstanden.

            // ── S3: Zugzählung (D-003) ───────────────────────────────────────
            //
            // Gezählt werden **begonnene** Züge. Aufzeichnung, keine
            // Regelauswertung: Rise rechnet keine Zugfolge aus.
            EventTypen.TURN_STARTED -> {
                val uid = event.actorParticipantUid?.takeIf { it.isNotBlank() } ?: return
                if (projektion.teilnehmer(uid) == null) return
                zustand = zustand.copy(turnNumber = zustand.turnNumber + 1, activeParticipantUid = uid)
                projektion.setzePartie(zustand)
            }

            EventTypen.TURN_ENDED -> {
                // Die Zugnummer bleibt stehen — sie zählt begonnene Züge.
                zustand = zustand.copy(activeParticipantUid = null)
                projektion.setzePartie(zustand)
            }

            // ── S3: Ausscheiden ──────────────────────────────────────────────
            //
            // TDD 5.4 unterscheidet die beiden Typen bewusst; für die Projektion
            // sind sie dasselbe: Dieser Sitzplatz spielt nicht mehr mit. Warum,
            // steht im Log und nicht im Zustand.
            EventTypen.PARTICIPANT_ELIMINATED, EventTypen.PARTICIPANT_LEFT -> {
                val uid = event.actorParticipantUid?.takeIf { it.isNotBlank() }
                    ?: event.targetParticipantUid?.takeIf { it.isNotBlank() }
                    ?: return
                val vorher = projektion.teilnehmer(uid) ?: return
                projektion.setzeTeilnehmer(vorher.copy(isEliminated = true, lastAppliedSeq = seq))
                if (zustand.activeParticipantUid == uid) {
                    zustand = zustand.copy(activeParticipantUid = null)
                    projektion.setzePartie(zustand)
                }
            }

            EventTypen.PARTICIPANT_JOINED -> {
                val uid = event.actorParticipantUid
                if (uid.isNullOrBlank()) return
                // Ein zweites `participant_joined` legt nichts neu an — es würde
                // einen bereits gefalteten Zustand überschreiben.
                if (projektion.teilnehmer(uid) != null) return
                projektion.setzeTeilnehmer(
                    ParticipantStateEntity(
                        participantUid = uid,
                        matchUid = event.matchUid,
                        // Startwert 0. Der wirkliche Startwert ist eine Angabe
                        // des Modus und kommt später durch ein Event — ihn hier
                        // anzunehmen wäre der erste Schritt zu einer Regelengine.
                        life = 0,
                        isRevealed = false,
                        isEliminated = false,
                        lastAppliedSeq = seq,
                    ),
                )
            }

            EventTypen.IDENTITY_REVEALED -> {
                val uid = event.actorParticipantUid?.takeIf { it.isNotBlank() }
                    ?: event.targetParticipantUid?.takeIf { it.isNotBlank() }
                    ?: return
                // Bewusst kein stilles Anlegen: Ein Aufdecken ohne vorheriges
                // Beitreten ist ein Riss in der Reihenfolge. Ihn zu glätten
                // hieße, ihn unsichtbar zu machen.
                val vorher = projektion.teilnehmer(uid) ?: return
                projektion.setzeTeilnehmer(
                    vorher.copy(isRevealed = true, lastAppliedSeq = seq),
                )
            }

            // Unbekannter Typ oder bekannter ohne Auswertung: gespeichert,
            // übersprungen (TDD 5.5). Eine Partie darf an einem Event aus einer
            // neueren App nicht zerbrechen.
            else -> Unit
        }
    }
}

/**
 * Die Typwerte, die dieser Schreibweg auswertet.
 *
 * Bewusst **nur diese drei** und nicht das ganze Vokabular: `:store` soll nicht
 * die zweite Stelle werden, an der die vollständige Typliste gepflegt wird. Sie
 * steht in `:core`. Was hier steht, sind die Werte, auf die dieser Code
 * tatsächlich reagiert — und die Doppelung ist damit so klein wie möglich, aber
 * weiterhin eine. Siehe Dateikommentar in [EventEntities].
 */
object EventTypen {
    const val MATCH_CREATED = "match_created"
    const val PARTICIPANT_JOINED = "participant_joined"
    const val IDENTITY_REVEALED = "identity_revealed"

    // S3 — Zugzählung (D-003) und Ausscheiden.
    const val TURN_STARTED = "turn_started"
    const val TURN_ENDED = "turn_ended"
    const val PARTICIPANT_ELIMINATED = "participant_eliminated"
    const val PARTICIPANT_LEFT = "participant_left"

    val ANGEWANDT = listOf(
        MATCH_CREATED,
        PARTICIPANT_JOINED,
        IDENTITY_REVEALED,
        TURN_STARTED,
        TURN_ENDED,
        PARTICIPANT_ELIMINATED,
        PARTICIPANT_LEFT,
    )
}
