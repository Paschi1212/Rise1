package de.myhornets.rise1.projection

import de.myhornets.rise1.core.event.MatchEvent
import de.myhornets.rise1.core.event.Partiestand
import de.myhornets.rise1.core.event.Schnappschuss
import de.myhornets.rise1.core.event.Sitzplatzstand
import de.myhornets.rise1.core.event.Visibility

/**
 * T-109 — den personalisierten Schnappschuss bauen (TDD 9.5).
 *
 * ## Warum hier
 *
 * Weil hier die Faltung liegt. Ein Schnappschuss ist der **gefaltete** Zustand
 * bis zu einem festen Stand plus die Teile, die nur einen Empfänger angehen —
 * und die Faltung ist [MatchFold], nicht eine zweite Auswertung derselben
 * Events. Zwei Stellen, die aus Events einen Zustand machen, laufen
 * auseinander; die Frage ist nur, wann.
 *
 * ## Der feste Stand
 *
 * TDD 9.5: Der Schnappschuss wird *„auf einen festen Stand `up_to_seq`
 * gebaut; parallel eintreffende Events puffert der Host und schickt sie
 * unmittelbar hinterher."* Deshalb nimmt [baue] den Stand entgegen, statt ihn
 * selbst aus dem Log zu bestimmen: Wer ihn selbst bestimmte, bekäme bei jedem
 * Aufruf einen anderen — und genau daraus entsteht das Rennen, das TDD 9.5
 * verhindern will.
 *
 * ## Was nicht hineingerät
 *
 * **Keine fremde verdeckte Identität** — der Host besitzt sie nicht (TDD 7.4).
 * **Keine Position im Kartenstapel** — sie steht nicht einmal als Feld zur
 * Verfügung (TDD 8.4). **Kein fremdes `PRIVATE`** — die Filterung ist dieselbe
 * Regel wie beim Delta und wird hier zusätzlich vom Werttyp erzwungen.
 */
object Schnappschussbau {

    /** Typen, die zum öffentlichen Deal-Transkript gehören (TDD 5.4, zweite Tabelle). */
    private const val TRANSKRIPT_PRAEFIX = "deal_"

    /**
     * Baut den Schnappschuss für einen Empfänger.
     *
     * @param events alle Events der Partie, die dieses Gerät kennt. Was darüber
     *   hinausgeht, wird ignoriert: gefiltert wird auf `seq <= bisSeq`.
     * @param bisSeq der feste Stand. `-1` heißt: Es gibt noch nichts.
     */
    fun baue(
        matchUid: String,
        empfaenger: String,
        events: Collection<MatchEvent>,
        bisSeq: Long,
    ): Schnappschuss {
        require(matchUid.isNotBlank()) { "Ein Schnappschuss ohne Partie ist keiner." }
        require(empfaenger.isNotBlank()) { "Ein Schnappschuss ohne Empfänger hat keine Zustellung." }
        require(bisSeq >= -1) { "up_to_seq ist -1 (nichts) oder größer, war $bisSeq." }

        val bisHierher = events
            .filter { it.matchUid == matchUid }
            .filter { es -> es.seq?.let { it <= bisSeq } == true }

        val projektion = MatchFold.falte(matchUid, bisHierher)

        // Reihenfolge: nach `participant_uid`. Nicht die Einfügereihenfolge —
        // die hinge davon ab, wer wann beigetreten ist, und wäre damit für jeden
        // Empfänger eine kleine Zusatzinformation über den Ablauf.
        val sitzplaetze = projektion.participants.values
            .sortedBy { it.participantUid }
            .map { zustand ->
                Sitzplatzstand(
                    participantUid = zustand.participantUid,
                    leben = zustand.life,
                    istAufgedeckt = zustand.isRevealed,
                    istAusgeschieden = zustand.isEliminated,
                    // Die Faltung führt heute keine aufgedeckte Identität mit.
                    // `null` ist deshalb die Wahrheit und kein Platzhalter —
                    // sobald `identity_revealed` seine Nutzdaten auswertet
                    // (E09), steht der Wert hier und die Feldstruktur bleibt.
                    aufgedeckteIdentitaet = null,
                )
            }

        return Schnappschuss(
            matchUid = matchUid,
            empfaenger = empfaenger,
            bisSeq = bisSeq,
            partie = Partiestand(
                matchUid = matchUid,
                zugnummer = projektion.matchState.turnNumber,
                amZug = projektion.matchState.activeParticipantUid,
                letzteAngewandteSeq = projektion.matchState.lastAppliedSeq,
            ),
            sitzplaetze = sitzplaetze,
            transkript = bisHierher
                .filter { it.visibility == Visibility.PUBLIC }
                .filter { it.type.startsWith(TRANSKRIPT_PRAEFIX) }
                .sortedBy { it.seq },
            eigenePrivate = bisHierher
                .filter { it.visibility == Visibility.PRIVATE }
                .filter { it.recipientParticipantUid == empfaenger }
                .sortedBy { it.seq },
        )
    }
}
