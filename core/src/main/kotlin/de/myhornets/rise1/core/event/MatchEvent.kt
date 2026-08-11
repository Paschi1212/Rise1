package de.myhornets.rise1.core.event

/**
 * T-025a — ein Eintrag im Event-Log (TDD 5.2).
 *
 * ## Der Grundsatz, aus dem alles folgt
 *
 * `match_event` ist append-only und unveränderlich (TDD 5.1). Daraus folgt,
 * dass der Spielzustand **nicht die Wahrheit ist, sondern eine Projektion** —
 * und dass dieser Typ unveränderlich sein muss. Es gibt hier deshalb keine
 * `var` und keine Setter.
 *
 * ## Was hier geprüft wird und was nicht
 *
 * Geprüft werden die Zusagen aus TDD 5.2, die man an einem einzelnen Event
 * sehen kann: die Kopplung von Sichtbarkeit und Nutzdatenform, der Empfänger
 * nur bei `PRIVATE`, die Vollständigkeit des Dedup-Schlüssels.
 *
 * **Nicht** geprüft wird alles, was mehrere Events braucht: Lückenlosigkeit der
 * `seq`, Dedup, Kausalität. Das gehört in die Ablage und in die Faltung, nicht
 * in einen Werttyp — ein Event weiß nichts von seinen Nachbarn.
 *
 * ## `type` ist ein String
 *
 * Bewusst, nicht aus Bequemlichkeit: Ein Gerät mit neuerer App darf Events
 * schicken, deren Typ dieses Gerät nicht kennt. Sie werden gespeichert und von
 * der Projektion übersprungen (TDD 5.5). Mit einem `enum`-Feld wäre ein solches
 * Event nicht einmal darstellbar — die Partie ginge daran kaputt statt
 * weiterzulaufen. [typKennung] liefert den bekannten Typ oder `null`.
 */
data class MatchEvent(

    /** UUID, **vom erzeugenden Gerät** vergeben. */
    val eventUid: String,

    val matchUid: String,

    /**
     * Kanonische Reihenfolge, **nur vom Host vergeben**.
     *
     * `null` heißt: noch nicht bestätigt. Solche Events sind gültig und werden
     * gespeichert — die Faltung lässt sie aber liegen, weil ihre Position in
     * der Reihenfolge noch nicht feststeht (TDD 6.2).
     */
    val seq: Long?,

    /** Zusammen mit [originSeq] der Dedup-Schlüssel (TDD 5.2). */
    val originDeviceUid: String,
    val originSeq: Long,

    /** Kausale Ordnung ohne Verbindung. */
    val lamportClock: Long,

    /** Wanduhr des Erzeugers — **nur zur Anzeige**, nie zum Sortieren (TDD 6.6). */
    val occurredAt: Long,

    /** Wanduhr des Hosts bei Bestätigung. `null`, solange [seq] `null` ist. */
    val recordedAt: Long?,

    /** Roher Typwert. Siehe Klassenkommentar. */
    val type: String,

    val eventClass: EventClass,

    val actorParticipantUid: String?,
    val targetParticipantUid: String?,

    val payload: Payload,

    /** Vorwärtskompatibilität der Nutzdatenform (TDD 5.2). */
    val payloadSchemaVersion: Int,

    val visibility: Visibility,

    /** Nur bei [Visibility.PRIVATE] gesetzt. */
    val recipientParticipantUid: String?,

    /** Undo löscht nicht, es markiert (TDD 5.3). */
    val isUndone: Boolean = false,
    val undoneByEventUid: String? = null,

    /** Nachträglich eingetroffenes Event mit nicht mehr passendem Absolutwert (TDD 6.5). */
    val hasConflict: Boolean = false,
) {

    init {
        require(eventUid.isNotBlank()) { "event_uid fehlt." }
        require(matchUid.isNotBlank()) { "match_uid fehlt." }
        require(originDeviceUid.isNotBlank()) {
            "origin_device_uid fehlt. Ohne sie ist der Dedup-Schlüssel unvollständig, " +
                "und dasselbe Event könnte zweimal ankommen (TDD 5.2)."
        }
        require(originSeq >= 0) { "origin_seq muss >= 0 sein, war $originSeq." }
        require(seq == null || seq >= 0) { "seq muss >= 0 sein, war $seq." }
        require(lamportClock >= 0) { "lamport_clock muss >= 0 sein, war $lamportClock." }
        require(type.isNotBlank()) { "type fehlt." }
        require(payloadSchemaVersion >= 1) {
            "payload_schema_version beginnt bei 1, war $payloadSchemaVersion."
        }

        // ── Die Trennung, um die es geht (TDD 5.2 / 7.3) ─────────────────────
        if (visibility.istOeffentlich) {
            require(payload !is Payload.Chiffrat) {
                "Ein PUBLIC-Event trägt kein Chiffrat. Sichtbarkeit ist im Entwurf eine " +
                    "Eigenschaft der Verschlüsselung (TDD 7.3) — was öffentlich ist, ist " +
                    "unverschlüsselt, sonst wäre die Angabe eine Behauptung statt einer Zusage."
            }
        } else {
            require(payload !is Payload.Klartext) {
                "Ein Event mit visibility=${visibility.wert} trägt keinen Klartext. " +
                    "payload_ciphertext ersetzt payload_json, sobald die Sichtbarkeit nicht " +
                    "PUBLIC ist (TDD 5.2). Andernfalls läge der Inhalt für den Host offen, " +
                    "der alle Metadaten sieht (TDD 7.4)."
            }
        }

        if (visibility == Visibility.PRIVATE) {
            require(!recipientParticipantUid.isNullOrBlank()) {
                "Ein PRIVATE-Event ohne Empfänger hat keine Zustellung — recipient_participant_uid fehlt."
            }
        } else {
            require(recipientParticipantUid == null) {
                "recipient_participant_uid gibt es nur bei PRIVATE (TDD 5.2), " +
                    "hier steht visibility=${visibility.wert}."
            }
        }

        // ── Zusammenhänge innerhalb eines Events ─────────────────────────────
        if (seq == null) {
            require(recordedAt == null) {
                "recorded_at ist die Wanduhr des Hosts bei der Bestätigung. Ohne seq gab es " +
                    "keine Bestätigung."
            }
        }
        if (isUndone) {
            require(!undoneByEventUid.isNullOrBlank()) {
                "Ein aufgehobenes Event nennt das Event, das es aufgehoben hat (TDD 5.3). " +
                    "Ohne diese Spur wäre das Undo über mehrere Geräte hinweg nicht nachvollziehbar."
            }
        } else {
            require(undoneByEventUid == null) {
                "undone_by_event_uid ist gesetzt, is_undone aber nicht — einer der beiden Werte ist falsch."
            }
        }
    }

    /** Der bekannte Typ, oder `null` bei einem Typ aus einer neueren App (TDD 5.5). */
    val typKennung: EventType? get() = EventType.vonWert(type)

    /**
     * Ob dieses Event die Projektion verändern **kann**.
     *
     * Drei Bedingungen, alle aus dem TDD:
     * `event_class = state` (5.2, „nur `state` verändert die Projektion") ·
     * nicht aufgehoben (5.3) · vom Host bestätigt, also mit `seq` (6.2).
     *
     * Ob es sie tatsächlich verändert, entscheidet die Faltung — ein bekannter
     * Typ, der noch nicht ausgewertet wird, erfüllt diese Bedingungen ebenfalls.
     */
    val istZustandswirksam: Boolean
        get() = eventClass == EventClass.STATE && !isUndone && seq != null

    /**
     * Ob Typ, Klasse und Sichtbarkeit zueinander passen.
     *
     * Nur für **bekannte** Typen aussagekräftig; bei einem unbekannten Typ gibt
     * es nichts zu vergleichen, und die Antwort ist `true` — ein fremdes Event
     * ist nicht deshalb falsch, weil es fremd ist (TDD 5.5).
     */
    fun passtZumVokabular(): Boolean {
        val kennung = typKennung ?: return true
        return kennung.eventClass == eventClass && visibility in kennung.erlaubteSichtbarkeiten
    }
}
