package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.EventClass
import de.myhornets.rise1.core.event.EventType
import de.myhornets.rise1.core.event.Payload
import de.myhornets.rise1.core.event.Visibility

/**
 * T-104 — die Sitzungs-Events (TDD 5.4, dritte Tabelle).
 *
 * ## Wozu
 *
 * [Verbindungsautomat] meldet Übergänge; ins Log gehören daraus drei Ereignisse
 * — `participant_disconnected`, `participant_reconnected`, `rejoin_rejected`.
 * Diese Datei ist die Übersetzung dazwischen, und sie ist **rein**: kein Log,
 * keine Datenbank, kein Android. Der Schritt „Meldung wird Event" ist damit
 * ohne Gerät prüfbar, und die Schicht darüber hängt nur noch an.
 *
 * ## Warum kein `MatchEvent`
 *
 * Weil hier drei der Pflichtfelder fehlen und fehlen **müssen**: `seq` vergibt
 * der Host bei der Bestätigung (TDD 6.2), `origin_seq` die Ablage beim Anhängen
 * (sie zählt je Gerät), und `event_uid` gehört zum erzeugenden Gerät. Ein
 * halbfertiges `MatchEvent` mit Platzhaltern wäre ein Wert, der aussieht, als
 * wäre er vollständig.
 *
 * [Ereignisentwurf] ist deshalb ausdrücklich ein **Entwurf**: alles, was die
 * Sitzungsschicht weiß, und nichts, was sie nicht weiß.
 *
 * ## Kein Ereignis verändert den Spielzustand
 *
 * Alle drei tragen `event_class = session` beziehungsweise `annotation` — und
 * nach TDD 5.2 verändert **nur** `state` die Projektion. Das ist die
 * technische Seite von TDD 9.2: *„Kein Übergang in diesem Automaten verändert
 * den Spielzustand."*
 */
data class Ereignisentwurf(
    val typ: String,
    val eventClass: EventClass,
    val visibility: Visibility,
    /** Wessen Sitzung — bei allen dreien der betroffene Sitzplatz. */
    val actorParticipantUid: String,
    val payload: Payload,
    /** Wanduhr des Erzeugers. Nur zur Anzeige (TDD 6.6). */
    val occurredAt: Long,
) {
    init {
        require(typ.isNotBlank()) { "Ein Entwurf ohne Typ ist keiner." }
        require(actorParticipantUid.isNotBlank()) { "Ein Sitzungsereignis gehört zu einem Sitzplatz." }
        require(eventClass != EventClass.STATE) {
            "Ein Sitzungsereignis ist nie `state`. Nur `state` verändert die Projektion (TDD 5.2), " +
                "und TDD 9.2 sagt ausdrücklich, dass kein Verbindungsübergang den Spielzustand ändert."
        }
        require(visibility == Visibility.PUBLIC) {
            "Die drei Sitzungs-Events sind PUBLIC (TDD 5.4). Ein verschlüsselter Verbindungshinweis " +
                "hätte keinen Empfänger, der ihn öffnen könnte — der Host sieht diese Metadaten ohnehin."
        }
        require(payload !is Payload.Chiffrat) { "Ein PUBLIC-Event trägt kein Chiffrat (TDD 5.2)." }
    }
}

/**
 * Übersetzt die Meldungen des Automaten in Entwürfe.
 *
 * Was **nicht** übersetzt wird, ist genauso wichtig:
 *
 * - [Verbindungsmeldung.Uebergang] — ein Zustandswechsel ist kein Ereignis. Der
 *   Wechsel nach `wackelig` erzeugt bewusst gar nichts (TDD 9.2: er existiert
 *   nur für die Anzeige); ein Verlauf, in dem jeder Funkschatten steht, wäre
 *   unlesbar.
 * - [Verbindungsmeldung.Vorschlag] — ein Vorschlag an den Host ist keine
 *   Handlung. Erst wenn ein Mensch ihn annimmt, entsteht ein Ereignis, und dann
 *   ist es `participant_left` und kommt von dort.
 */
object Sitzungsereignisse {

    /**
     * Genau die drei Typen, die [Verbindungsautomat] meldet.
     *
     * Eine geschlossene Liste, damit aus einer künftigen Meldung nicht
     * versehentlich ein Event einer anderen Sorte wird. `session_superseded`
     * etwa ist nach TDD 5.4 `PRIVATE` und gehört dem Host, der ablöst — nicht
     * dem Automaten eines Sitzplatzes.
     */
    private val AUS_DEM_AUTOMATEN = setOf(
        Sitzungsereignistypen.VERBINDUNG_VERLOREN,
        Sitzungsereignistypen.VERBINDUNG_ZURUECK,
        Sitzungsereignistypen.WIEDEREINSTIEG_ABGELEHNT,
    )

    /**
     * Der Entwurf zu einer Meldung, oder `null`, wenn daraus kein Event wird.
     *
     * Die Nutzdaten bleiben [Payload.Leer]: TDD 5.4 legt für diese drei Typen
     * **kein** Nutzdatenschema fest. Insbesondere trägt `rejoin_rejected`
     * **nicht** den Ablehnungsgrund — dafür müsste hier ein Format erfunden
     * werden, und das ist eine Entscheidung, keine Nebenwirkung. Vermerkt als
     * offener Punkt in [[T-105 T-108 Wiedereinstieg und Aufholen]].
     */
    fun entwurf(meldung: Verbindungsmeldung): Ereignisentwurf? {
        val ereignis = meldung as? Verbindungsmeldung.Sitzungsereignis ?: return null
        if (ereignis.typ !in AUS_DEM_AUTOMATEN) return null
        val typ = EventType.vonWert(ereignis.typ) ?: return null

        // Klasse und Sichtbarkeit kommen aus dem Vokabular in `:core` und nicht
        // aus einer zweiten Tabelle hier. Zwei Listen mit denselben Regeln
        // laufen auseinander — die Frage ist nur, wann.
        if (Visibility.PUBLIC !in typ.erlaubteSichtbarkeiten) return null

        return Ereignisentwurf(
            typ = typ.wert,
            eventClass = typ.eventClass,
            visibility = Visibility.PUBLIC,
            actorParticipantUid = ereignis.participantUid,
            payload = Payload.Leer,
            occurredAt = ereignis.bei,
        )
    }

    /** Alle Entwürfe zu einer Folge von Meldungen, in unveränderter Reihenfolge. */
    fun entwuerfe(meldungen: List<Verbindungsmeldung>): List<Ereignisentwurf> =
        meldungen.mapNotNull { entwurf(it) }
}
