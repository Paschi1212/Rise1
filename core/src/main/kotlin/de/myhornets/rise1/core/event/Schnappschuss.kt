package de.myhornets.rise1.core.event

/**
 * T-109 — der personalisierte Schnappschuss, TDD 9.5.
 *
 * ## Die zwei Regeln, die die Form bestimmen
 *
 * TDD 9.5: *„Für alle Empfänger gilt **dieselbe Feldstruktur** mit Nullwerten —
 * ein weggelassenes Feld wäre selbst eine Information."* Und: *„Der Snapshot
 * geht an die Sitzung, die den Handshake bestanden hat, nicht an das Gerät und
 * nicht an die Person."*
 *
 * Aus der ersten folgt die ganze Bauart dieser Typen: **kein nullbares Feld
 * bedeutet „gibt es nicht"**, sondern immer nur „ist leer". Listen sind leer,
 * nicht abwesend; ein nicht aufgedeckter Sitzplatz trägt `null` in
 * [Sitzplatzstand.aufgedeckteIdentitaet] — genau wie ein aufgedeckter, dessen
 * Identität dieser Empfänger nicht kennen darf. Wer die Struktur vergleicht,
 * lernt nichts.
 *
 * ## Warum diese Typen in `:core` stehen und nicht in `:projection`
 *
 * Weil zwei Module sie brauchen und keines das andere sehen darf:
 * `:projection` **baut** den Schnappschuss (es hat die Faltung), `:session`
 * **verschickt** ihn (es hat den Transport). `allowedModuleEdges` gibt
 * `:session` kein `:projection` — eine Kante dorthin wäre eine
 * Architekturänderung und bräuchte eine ADR. Ein gemeinsamer Werttyp in `:core`
 * ist der Weg, den dieses Projekt schon bei `MatchEvent` gegangen ist.
 *
 * ## Warum das nicht `MatchProjection` ist
 *
 * `MatchProjection` ist der **Anzeigezustand dieses Geräts** — sie darf sich
 * ändern, wenn die Anzeige sich ändert. Ein Schnappschuss ist ein
 * **Nachrichtenformat**: Seine Feldstruktur ist eine Zusage an alle Empfänger
 * und ändert sich nur mit einer Fassung des Protokolls. Die beiden zu
 * verschmelzen hieße, dass eine Anzeigeänderung stillschweigend die Leitung
 * ändert.
 */
data class Schnappschuss(

    val matchUid: String,

    /**
     * Der Sitzplatz, für den dieser Schnappschuss gebaut wurde.
     *
     * **Der Sitzplatz, nicht das Gerät** (TDD 9.5). Ein Schnappschuss, der an
     * ein Gerät adressiert wäre, ginge nach einem Gerätewechsel an den Falschen.
     */
    val empfaenger: String,

    /**
     * Der feste Stand, auf den gebaut wurde (TDD 9.5, `up_to_seq`).
     *
     * Ohne ihn entstünde ein Rennen, bei dem Events doppelt oder gar nicht
     * ankommen: Der Host puffert, was währenddessen eintrifft, und schickt es
     * unmittelbar hinterher.
     */
    val bisSeq: Long,

    val partie: Partiestand,

    /** Alle Sitzplätze, in stabiler Reihenfolge. Für jeden dieselben Felder. */
    val sitzplaetze: List<Sitzplatzstand>,

    /**
     * Das öffentliche Deal-Transkript (TDD 9.5).
     *
     * Roh als Events, nicht als gedeuteter Zustand: Es ist ein **Nachweis**, und
     * ein Nachweis, der zusammengefasst wird, ist keiner mehr. Der Empfänger
     * muss die Bindungen selbst nachrechnen können (TDD 8.3).
     */
    val transkript: List<MatchEvent>,

    /**
     * Die `PRIVATE`-Events dieses Empfängers — und nur seine.
     *
     * Der Host kann sie nicht öffnen (TDD 7.4); er reicht Chiffrate weiter.
     * Fremde verdeckte Identitäten kann ein Schnappschuss nicht enthalten, weil
     * der Host sie nicht besitzt.
     */
    val eigenePrivate: List<MatchEvent>,
) {
    init {
        require(matchUid.isNotBlank()) { "Ein Schnappschuss ohne Partie ist keiner." }
        require(empfaenger.isNotBlank()) { "Ein Schnappschuss ohne Empfänger hat keine Zustellung." }
        require(bisSeq >= -1) { "up_to_seq ist -1 (nichts) oder größer, war $bisSeq." }
        require(partie.matchUid == matchUid) {
            "Der Partiestand gehört zu ${partie.matchUid}, der Schnappschuss zu $matchUid."
        }

        // Die Regel aus TDD 9.5, hier als Zusicherung und nicht als Absicht:
        // Was hier liegt, gehört diesem Empfänger. Ein Schnappschuss, der ein
        // fremdes Chiffrat trägt, ist ein Leck — auch wenn niemand es öffnen kann,
        // denn schon die Existenz verrät, wer etwas bekommen hat.
        eigenePrivate.forEach { event ->
            require(event.visibility == Visibility.PRIVATE) {
                "In `eigenePrivate` liegt ein Event mit visibility=${event.visibility.wert}."
            }
            require(event.recipientParticipantUid == empfaenger) {
                "In `eigenePrivate` liegt ein Event für einen anderen Sitzplatz (TDD 9.5)."
            }
        }
        transkript.forEach { event ->
            require(event.visibility == Visibility.PUBLIC) {
                "Das Transkript ist öffentlich (TDD 9.5); hier liegt ${event.visibility.wert}."
            }
        }
        require(sitzplaetze.map { it.participantUid }.toSet().size == sitzplaetze.size) {
            "Ein Sitzplatz kommt zweimal vor."
        }
    }

    /** Wie viele Events dieser Schnappschuss mitführt. Für Größenprüfungen. */
    val ereignisanzahl: Int get() = transkript.size + eigenePrivate.size
}

/**
 * Der öffentliche Partie-Zustand (TDD 4.4, Abschnitt „Projektionen").
 *
 * Dieselben Felder, die `MatchState` in `:projection` führt — aber als
 * Nachrichtenformat. Siehe [Schnappschuss].
 */
data class Partiestand(
    val matchUid: String,
    /** Zugnummer aus `D-003`. `0` heißt: noch kein Zug begonnen. */
    val zugnummer: Int,
    /** Wer am Zug ist, oder `null` zwischen zwei Zügen. */
    val amZug: String?,
    /** Bis wohin die Faltung gelaufen ist. */
    val letzteAngewandteSeq: Long,
) {
    init {
        require(matchUid.isNotBlank()) { "Ein Partiestand ohne Partie ist keiner." }
        require(zugnummer >= 0) { "Eine negative Zugnummer gibt es nicht." }
    }
}

/**
 * Ein Sitzplatz im Schnappschuss.
 *
 * **Was hier mit Absicht fehlt:** die Position im Kartenstapel. TDD 8.4 ist
 * unmissverständlich — sie lebt beim Verteiler und in `own_identity`, „sonst
 * nirgends, insbesondere nicht auf dem Host". Ein Schnappschuss kommt vom Host;
 * was er nicht hat, kann er nicht schicken, und was er nicht schicken darf,
 * steht hier gar nicht erst als Feld.
 */
data class Sitzplatzstand(
    val participantUid: String,
    val leben: Int,
    val istAufgedeckt: Boolean,
    val istAusgeschieden: Boolean,
    /**
     * Die aufgedeckte Identität — `null`, solange nicht aufgedeckt.
     *
     * Für **alle** Empfänger dasselbe Feld: Wer nicht aufgedeckt ist, trägt hier
     * `null`, und zwar in jedem Schnappschuss. Das Feld wegzulassen, wenn es
     * leer ist, wäre die Information, dass es etwas zu verbergen gibt.
     */
    val aufgedeckteIdentitaet: String?,
) {
    init {
        require(participantUid.isNotBlank()) { "Ein Sitzplatz ohne Kennung ist keiner." }
        require(istAufgedeckt || aufgedeckteIdentitaet == null) {
            "Eine Identität ohne Aufdeckung: Entweder ist `istAufgedeckt` falsch, oder hier " +
                "steht etwas, das niemand aufgedeckt hat (TDD 3.4)."
        }
    }
}
