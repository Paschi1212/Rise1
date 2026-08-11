package de.myhornets.rise1.core.event

/**
 * T-025a — das Vokabular des Event-Modells (TDD 5).
 *
 * ## Warum das hier steht und nicht in `:store`
 *
 * Die Faltlogik in `:projection` muss Events lesen können, ohne die Datenbank
 * zu kennen — sonst wäre sie nicht ohne Android testbar. Also braucht es einen
 * Werttyp, den beide Seiten verstehen, und der gehört ins Domänenmodul.
 * `:store` bildet seine Zeilen auf diese Typen ab, nicht umgekehrt.
 *
 * ## Was hier ausdrücklich nicht passiert
 *
 * Kein Typ dieser Datei deutet Spielmechanik. `unveil_cost`, Kosten, Effekte,
 * Fähigkeiten kommen nicht vor. Ein Event sagt, **dass** etwas geschehen ist —
 * was es bedeutet, entscheidet der Tisch.
 */

/**
 * Die Klasse eines Events (TDD 5.2).
 *
 * **Nur `state` verändert die Projektion.** Das ist keine Konvention, sondern
 * die Regel, an der die Faltung entlanggeht: Ein `session`- oder
 * `annotation`-Event kann den Spielstand nicht verändern, egal was in ihm steht.
 */
enum class EventClass(val wert: String) {
    STATE("state"),
    SESSION("session"),
    ANNOTATION("annotation"),
    ;

    companion object {
        /** `null` bei unbekanntem Wert — ein fremdes Event wird gespeichert, nicht gedeutet (TDD 5.5). */
        fun vonWert(wert: String): EventClass? = entries.firstOrNull { it.wert == wert }
    }
}

/**
 * Die Sichtbarkeit eines Events (TDD 5.2 und 7.3).
 *
 * Sichtbarkeit ist im Entwurf **eine Eigenschaft der Verschlüsselung**, keine
 * Anzeigeoption: Was nicht `PUBLIC` ist, liegt als Chiffrat vor und ist für den
 * Host nicht lesbar (TDD 7.4). Deshalb ist die Kopplung an die Nutzdaten in
 * [Payload] strukturell und nicht als Prüfregel gebaut.
 */
enum class Visibility(val wert: String) {
    PUBLIC("PUBLIC"),
    PLAYER_ONLY("PLAYER_ONLY"),
    PRIVATE("PRIVATE"),
    ;

    /** Genau dann darf Klartext im Log stehen. */
    val istOeffentlich: Boolean get() = this == PUBLIC

    companion object {
        fun vonWert(wert: String): Visibility? = entries.firstOrNull { it.wert == wert }
    }
}

// Abkürzungen für die Tabelle unten. Stehen bewusst **vor** dem `enum`: Sie
// werden in dessen Konstruktoraufrufen gelesen, und Lesbarkeit von oben nach
// unten ist hier wichtiger als Gruppierung nach Sichtbarkeit.
private val NUR_PUBLIC: Set<Visibility> = setOf(Visibility.PUBLIC)
private val NUR_PRIVATE: Set<Visibility> = setOf(Visibility.PRIVATE)
private val ALLE_SICHTBARKEITEN: Set<Visibility> = Visibility.entries.toSet()

/**
 * Die geschlossene Menge der Event-Typen aus TDD 5.4.
 *
 * ## Geschlossen, aber nicht abschließend
 *
 * Die Menge ist hier vollständig aufgeführt, weil ein Vokabular nur dann eines
 * ist, wenn es zählbar ist — nur so kann [keinTypVerraetEineRolle] überhaupt
 * etwas prüfen. In `match_event.type` steht trotzdem ein **String**: Ein Gerät
 * mit einer neueren App darf Events schicken, die dieses Gerät noch nicht kennt.
 * Sie werden gespeichert und von der Projektion übersprungen (TDD 5.5). Ein
 * `enum`-Feld in der Datenbank würde genau das verhindern.
 *
 * ## `angewandt`
 *
 * Markiert, welche Typen die Faltung in `:projection` **heute** auswertet.
 * Alles andere ist Vokabular ohne Wirkung — bewusst, damit der Schritt von
 * „Typ existiert" zu „Typ verändert Zustand" sichtbar und einzeln zu prüfen
 * bleibt.
 *
 * ## Die Namensregel
 *
 * TDD 5.5: Ereignistypen dürfen **nichts verraten**. Der Host sieht alle
 * Metadaten, also auch jeden Typnamen. Ein Typ `traitor_ability_used` würde die
 * gesamte Verschlüsselung umgehen — er heißt deshalb `unveil_ability_used`, und
 * welche Fähigkeit es war, steckt im Chiffrat. [VERBOTENE_NAMENSTEILE] hält das
 * als Prüfung fest, nicht als Vorsatz.
 */
enum class EventType(
    val wert: String,
    val eventClass: EventClass,
    val erlaubteSichtbarkeiten: Set<Visibility>,
    val angewandt: Boolean = false,
) {
    // ── Spielzustand (TDD 5.4, erste Tabelle) ────────────────────────────────
    MATCH_CREATED("match_created", EventClass.STATE, NUR_PUBLIC, angewandt = true),
    PARTICIPANT_JOINED("participant_joined", EventClass.STATE, NUR_PUBLIC, angewandt = true),
    IDENTITY_REVEALED("identity_revealed", EventClass.STATE, NUR_PUBLIC, angewandt = true),
    IDENTITY_DISCLOSED("identity_disclosed", EventClass.STATE, NUR_PUBLIC),
    LIFE_CHANGED("life_changed", EventClass.STATE, NUR_PUBLIC),
    COUNTER_CHANGED("counter_changed", EventClass.STATE, NUR_PUBLIC),
    PARTICIPANT_ELIMINATED("participant_eliminated", EventClass.STATE, NUR_PUBLIC, angewandt = true),
    TURN_STARTED("turn_started", EventClass.STATE, NUR_PUBLIC, angewandt = true),
    TURN_ENDED("turn_ended", EventClass.STATE, NUR_PUBLIC, angewandt = true),

    /**
     * Notiz am Tisch oder für sich.
     *
     * **Beobachtung, keine Änderung:** TDD 5.4 führt diesen Typ in der Tabelle
     * der Zustands-Events, obwohl eine Notiz keinen Spielstand verändert. Hier
     * steht deshalb wörtlich, was das TDD sagt — `STATE`. Ob das so gemeint war
     * oder ob `ANNOTATION` richtiger wäre, ist im Aufgabendokument zu `T-025a`
     * als offener Punkt vermerkt und **nicht** hier stillschweigend entschieden.
     */
    NOTE_ADDED("note_added", EventClass.STATE, setOf(Visibility.PLAYER_ONLY, Visibility.PRIVATE)),

    PARTICIPANT_LEFT("participant_left", EventClass.STATE, NUR_PUBLIC, angewandt = true),
    MATCH_PAUSED("match_paused", EventClass.STATE, NUR_PUBLIC),
    MATCH_RESUMED("match_resumed", EventClass.STATE, NUR_PUBLIC),
    MATCH_FINISHED("match_finished", EventClass.STATE, NUR_PUBLIC),

    /** Undo. Sichtbarkeit „wie das Ziel" (TDD 5.4) — deshalb alle drei erlaubt. */
    EVENT_UNDONE("event_undone", EventClass.STATE, ALLE_SICHTBARKEITEN),

    // ── Verteilung (TDD 5.4, zweite Tabelle; Verfahren in Kapitel 8) ─────────
    DEAL_ROLES_DRAWN("deal_roles_drawn", EventClass.STATE, NUR_PUBLIC),
    DEAL_ENVELOPES_PUBLISHED("deal_envelopes_published", EventClass.STATE, NUR_PUBLIC),
    DEAL_MATRIX_DELIVERED("deal_matrix_delivered", EventClass.STATE, NUR_PRIVATE),
    DEAL_ASSIGNMENT_COMMITTED("deal_assignment_committed", EventClass.STATE, NUR_PUBLIC),
    DEAL_KEY_PACKET("deal_key_packet", EventClass.STATE, NUR_PRIVATE),
    DEAL_IDENTITY_COMMITTED("deal_identity_committed", EventClass.STATE, NUR_PUBLIC),
    DEAL_ABORTED("deal_aborted", EventClass.STATE, NUR_PUBLIC),

    // ── Sitzung (TDD 5.4, dritte Tabelle) ───────────────────────────────────
    PARTICIPANT_DISCONNECTED("participant_disconnected", EventClass.SESSION, NUR_PUBLIC),
    PARTICIPANT_RECONNECTED("participant_reconnected", EventClass.SESSION, NUR_PUBLIC),
    SESSION_SUPERSEDED("session_superseded", EventClass.SESSION, NUR_PRIVATE),
    PARTICIPANT_READMITTED("participant_readmitted", EventClass.SESSION, NUR_PUBLIC),
    REJOIN_REJECTED("rejoin_rejected", EventClass.ANNOTATION, NUR_PUBLIC),
    HOST_CHANGED("host_changed", EventClass.STATE, NUR_PUBLIC),
    ;

    companion object {

        /** `null` bei unbekanntem Typ — Grundlage von TDD 5.5. */
        fun vonWert(wert: String): EventType? = entries.firstOrNull { it.wert == wert }

        /** Die Typen, die die Faltung heute auswertet. */
        fun angewandte(): Set<EventType> = entries.filter { it.angewandt }.toSet()

        /**
         * Namensteile, die in keinem Event-Typ vorkommen dürfen (TDD 5.5).
         *
         * Es sind die vier Rollen aus [[Roles]]. `deal_roles_drawn` ist bewusst
         * nicht betroffen: Dort werden Packer und Verteiler ausgelost, nicht
         * Treachery-Rollen.
         */
        val VERBOTENE_NAMENSTEILE: List<String> = listOf("leader", "guardian", "assassin", "traitor")

        /**
         * Prüft die Namensregel über die gesamte Menge.
         *
         * Gibt die Verstöße zurück, statt zu werfen — der Aufrufer ist ein Test,
         * und der soll alle Fundstellen auf einmal nennen können.
         */
        fun keinTypVerraetEineRolle(): List<String> = entries.flatMap { typ ->
            VERBOTENE_NAMENSTEILE
                .filter { verboten -> typ.wert.contains(verboten, ignoreCase = true) }
                .map { verboten -> "${typ.wert} enthält `$verboten`" }
        }
    }
}
