package de.myhornets.rise1.projection

import de.myhornets.rise1.core.event.EventClass
import de.myhornets.rise1.core.event.EventType
import de.myhornets.rise1.core.event.MatchEvent

/**
 * T-025d — die Faltung des Event-Logs zum Anzeigezustand (TDD 5.1).
 *
 * ## Die eine Eigenschaft, auf die es ankommt
 *
 * **Determinismus.** Dieselben Events ergeben dieselbe Projektion — immer,
 * unabhängig von der Reihenfolge, in der sie eintreffen, und unabhängig davon,
 * wie oft gefaltet wird. Ohne diese Eigenschaft wäre der Neuaufbau aus `T-025f`
 * wertlos: Er könnte einen anderen Zustand liefern als der laufende Betrieb,
 * und niemand wüsste, welcher stimmt.
 *
 * Sichergestellt wird sie durch drei Entscheidungen:
 *
 * 1. Sortiert wird nach `seq`, der **vom Host vergebenen** Reihenfolge
 *    (TDD 6.2) — nicht nach `occurred_at`, das nur zur Anzeige taugt (TDD 6.6),
 *    und nicht nach der Reihenfolge des Eintreffens.
 * 2. Events ohne `seq` werden nicht gefaltet. Ihre Position steht noch nicht
 *    fest; sie jetzt einzurechnen hieße, sie später zurücknehmen zu müssen.
 * 3. Zwei Events mit derselben `seq` sind ein Fehler und werden gemeldet, nicht
 *    geordnet. Eine erfundene Ordnung wäre auf zwei Geräten verschieden.
 *
 * ## Was die Faltung nicht liest
 *
 * **Die Nutzdaten.** Kein `payload_json`, kein Chiffrat, kein JSON-Parser.
 * Alles, was hier gebraucht wird — Akteur, Ziel, Typ, Klasse, Sichtbarkeit —
 * steht nach TDD 5.2 als echte Spalte daneben. Das ist keine Sparsamkeit,
 * sondern die Grundlage dafür, dass diese Klasse auf jedem Gerät dasselbe
 * Ergebnis liefert, auch auf einem, das die Chiffrate nicht öffnen kann.
 *
 * ## Was heute ausgewertet wird
 *
 * `match_created`, `participant_joined`, `identity_revealed` — siehe
 * [EventType.angewandte]. Alles andere wird gezählt und übersprungen. Das ist
 * ausdrücklich kein Mangel: Ein unbekannter Typ darf eine Partie nicht
 * zerstören (TDD 5.5), und ein bekannter Typ ohne Auswertung macht den Schritt
 * von „Vokabular" zu „Wirkung" einzeln prüfbar.
 */
object MatchFold {

    /**
     * Faltet die Events einer Partie zum Anzeigezustand.
     *
     * @param matchUid Die Partie. Events anderer Partien sind ein Programmierfehler
     *   und keine Eingabe, die man stillschweigend wegfiltert.
     * @param events Beliebige Reihenfolge, Duplikate ausgeschlossen (die verhindert
     *   der eindeutige Index auf `(origin_device_uid, origin_seq)` in der Ablage).
     */
    fun falte(matchUid: String, events: Collection<MatchEvent>): MatchProjection {
        require(matchUid.isNotBlank()) { "match_uid fehlt." }

        val fremde = events.filter { it.matchUid != matchUid }
        require(fremde.isEmpty()) {
            "Es wurden Events einer anderen Partie übergeben (${fremde.size} Stück, " +
                "zuerst ${fremde.first().eventUid}). Eine Faltung, die das wegfiltert, " +
                "würde einen Programmierfehler in ein plausibles Ergebnis verwandeln."
        }

        val uebersprungen = mutableMapOf<Uebersprungsgrund, Int>()
        fun zaehle(grund: Uebersprungsgrund) {
            uebersprungen[grund] = (uebersprungen[grund] ?: 0) + 1
        }

        val bestaetigt = events.filter { it.seq != null }
        repeat(events.size - bestaetigt.size) { zaehle(Uebersprungsgrund.NICHT_BESTAETIGT) }

        val doppelt = bestaetigt.groupBy { it.seq }.filterValues { it.size > 1 }
        require(doppelt.isEmpty()) {
            "Zwei Events tragen dieselbe seq: " +
                doppelt.entries.joinToString("; ") { (seq, liste) ->
                    "seq=$seq → ${liste.map { it.eventUid }}"
                } +
                ". seq wird nur vom Host vergeben (TDD 5.2) und ist damit eindeutig. " +
                "Eine hier erfundene Ordnung wäre auf zwei Geräten verschieden."
        }

        var zustand = MatchState(matchUid = matchUid)
        val teilnehmer = linkedMapOf<String, ParticipantState>()
        val zaehler = linkedMapOf<String, ParticipantCounter>()
        var angelegt = false

        bestaetigt.sortedBy { it.seq }.forEach { event ->
            val seq = event.seq!!

            // `last_applied_seq` ist der Stand der Projektion (TDD 4.4) — sie
            // hat dieses Event gesehen. Ob es etwas verändert hat, ist eine
            // andere Frage; sonst würde ein Neuaufbau übersprungene Events
            // wieder und wieder betrachten.
            zustand = zustand.copy(lastAppliedSeq = seq)

            if (event.eventClass != EventClass.STATE) {
                zaehle(Uebersprungsgrund.KEINE_ZUSTANDSKLASSE); return@forEach
            }
            if (event.isUndone) {
                zaehle(Uebersprungsgrund.AUFGEHOBEN); return@forEach
            }

            val typ = event.typKennung
            if (typ == null) {
                zaehle(Uebersprungsgrund.UNBEKANNTER_TYP); return@forEach
            }
            if (!typ.angewandt) {
                zaehle(Uebersprungsgrund.NOCH_NICHT_ANGEWANDT); return@forEach
            }

            when (typ) {
                EventType.MATCH_CREATED -> {
                    angelegt = true
                }

                EventType.PARTICIPANT_JOINED -> {
                    val uid = event.actorParticipantUid
                    if (uid.isNullOrBlank()) {
                        zaehle(Uebersprungsgrund.UNVOLLSTAENDIG); return@forEach
                    }
                    // Ein zweites `participant_joined` für denselben Sitzplatz
                    // legt nichts neu an. Es zu überschreiben hieße, einen
                    // bereits gefalteten Zustand zu verlieren.
                    if (!teilnehmer.containsKey(uid)) {
                        teilnehmer[uid] = ParticipantState(participantUid = uid, lastAppliedSeq = seq)
                    }
                }

                // ── S3: Zugzählung (D-003) ───────────────────────────────────
                //
                // Gezählt wird, wie oft ein Zug **begonnen** wurde. Das ist
                // Aufzeichnung, keine Regelauswertung: Rise rechnet keine
                // Zugfolge aus und erzwingt keine. Ein Zug beginnt, weil jemand
                // am Tisch ihn beginnt.
                EventType.TURN_STARTED -> {
                    val uid = event.actorParticipantUid?.takeIf { it.isNotBlank() }
                    if (uid == null) {
                        zaehle(Uebersprungsgrund.UNVOLLSTAENDIG); return@forEach
                    }
                    if (!teilnehmer.containsKey(uid)) {
                        zaehle(Uebersprungsgrund.UNBEKANNTER_TEILNEHMER); return@forEach
                    }
                    zustand = zustand.copy(
                        turnNumber = zustand.turnNumber + 1,
                        activeParticipantUid = uid,
                    )
                }

                EventType.TURN_ENDED -> {
                    // Die Zugnummer bleibt stehen. Sie zählt begonnene Züge;
                    // sie beim Beenden zu verändern hieße, denselben Zug zweimal
                    // zu zählen oder gar nicht.
                    zustand = zustand.copy(activeParticipantUid = null)
                }

                // ── S3: Ausscheiden ──────────────────────────────────────────
                //
                // Zwei Wege, ein Ergebnis. TDD 5.4 unterscheidet sie bewusst:
                // `participant_eliminated` trägt einen Grund aus dem Spiel,
                // `participant_left` ist „endgültiges Ausscheiden — eine
                // Entscheidung, kein Timeout". Für die Projektion sind beide
                // dasselbe: dieser Sitzplatz spielt nicht mehr mit. Warum,
                // steht im Log und nicht im Zustand.
                EventType.PARTICIPANT_ELIMINATED, EventType.PARTICIPANT_LEFT -> {
                    val uid = event.actorParticipantUid?.takeIf { it.isNotBlank() }
                        ?: event.targetParticipantUid?.takeIf { it.isNotBlank() }
                    if (uid == null) {
                        zaehle(Uebersprungsgrund.UNVOLLSTAENDIG); return@forEach
                    }
                    val vorher = teilnehmer[uid]
                    if (vorher == null) {
                        zaehle(Uebersprungsgrund.UNBEKANNTER_TEILNEHMER); return@forEach
                    }
                    teilnehmer[uid] = vorher.copy(isEliminated = true, lastAppliedSeq = seq)
                    // Wer ausscheidet, ist nicht mehr am Zug. Das ist keine
                    // Regel über das Spiel, sondern eine über die Anzeige:
                    // Ein ausgeschiedener Sitzplatz als „am Zug" wäre schlicht
                    // falsch dargestellt.
                    if (zustand.activeParticipantUid == uid) {
                        zustand = zustand.copy(activeParticipantUid = null)
                    }
                }

                EventType.IDENTITY_REVEALED -> {
                    // Aufgedeckt wird die eigene Karte — der Akteur ist der
                    // Eigentümer. `target_participant_uid` dient als Rückfall,
                    // damit ein Event aus einer anderen Erzeugung nicht verloren
                    // geht; beides leer ist unvollständig.
                    val uid = event.actorParticipantUid?.takeIf { it.isNotBlank() }
                        ?: event.targetParticipantUid?.takeIf { it.isNotBlank() }
                    if (uid == null) {
                        zaehle(Uebersprungsgrund.UNVOLLSTAENDIG); return@forEach
                    }
                    val vorher = teilnehmer[uid]
                    if (vorher == null) {
                        // Bewusst kein stilles Anlegen: Ein Aufdecken ohne
                        // vorheriges Beitreten ist ein Riss in der Reihenfolge.
                        // Ihn zu glätten hieße, ihn unsichtbar zu machen.
                        zaehle(Uebersprungsgrund.UNBEKANNTER_TEILNEHMER); return@forEach
                    }
                    teilnehmer[uid] = vorher.copy(isRevealed = true, lastAppliedSeq = seq)
                }

                else -> zaehle(Uebersprungsgrund.NOCH_NICHT_ANGEWANDT)
            }
        }

        return MatchProjection(
            matchState = zustand,
            participants = teilnehmer.toMap(),
            counters = zaehler.toMap(),
            uebersprungen = uebersprungen.toMap(),
            partieAngelegt = angelegt,
        )
    }
}
