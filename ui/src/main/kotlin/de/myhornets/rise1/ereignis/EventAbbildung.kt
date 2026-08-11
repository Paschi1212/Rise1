package de.myhornets.rise1.ereignis

import de.myhornets.rise1.core.event.EventClass
import de.myhornets.rise1.core.event.EventType
import de.myhornets.rise1.core.event.MatchEvent
import de.myhornets.rise1.core.event.Payload
import de.myhornets.rise1.core.event.Visibility
import de.myhornets.rise1.store.MatchEventEntity
import java.util.UUID

/**
 * S2 — die Übersetzung zwischen Log-Zeile und Domänenwert.
 *
 * ## Warum diese Datei in `:ui` liegt
 *
 * Sie gehört fachlich weder in `:store` noch in `:core`. `:store` darf `:core`
 * nicht kennen — `allowedModuleEdges["store"] = emptySet()`, „eine Ablage ist
 * kein Mitspieler" ([[ADR-003 Persistenzmodul]]). `:core` darf `:store` nicht
 * kennen, sonst hinge das Domänenmodell an Room.
 *
 * `:ui` sieht beide. Damit ist es heute der einzige Ort, an dem die Übersetzung
 * stehen **kann** — nicht der, an dem sie stehen sollte. Das ist die konkrete
 * Form des NEEDS-DECISION-Punkts aus [[T-025 Event-Log-Fundament]]: entweder
 * bekommt `:store` eine Kante auf `:core` (ändert ADR-003), oder es entsteht
 * ein drittes, abhängigkeitsfreies Modul. Bis dahin steht es hier, sichtbar,
 * mit diesem Kommentar daneben.
 *
 * Der Nutzen ist real und nicht nur Notlösung: Genau hier fällt auf, wenn die
 * Zeichenketten in `:store` und die `enum` in `:core` auseinanderlaufen — und
 * `VokabularAbgleichTest` macht daraus eine Prüfung.
 */
object EventAbbildung {

    /**
     * Log-Zeile → Domänenwert.
     *
     * `null`, wenn `event_class` oder `visibility` unbekannt sind. Das ist kein
     * Fehler, sondern TDD 5.5 in seiner allgemeinsten Form: Eine Zeile aus einer
     * neueren App wird gespeichert und nicht gedeutet. Der Aufrufer entscheidet,
     * was er mit dem `null` tut — er soll es zählen, nicht verschlucken.
     */
    fun zuDomaene(zeile: MatchEventEntity): MatchEvent? {
        val klasse = EventClass.vonWert(zeile.eventClass) ?: return null
        val sichtbarkeit = Visibility.vonWert(zeile.visibility) ?: return null

        val nutzdaten: Payload = when {
            zeile.payloadJson != null -> Payload.Klartext(zeile.payloadJson!!)
            zeile.payloadCiphertext != null ->
                Payload.Chiffrat(zeile.payloadCiphertext!!, zeile.encScheme ?: return null)
            else -> Payload.Leer
        }

        return MatchEvent(
            eventUid = zeile.eventUid,
            matchUid = zeile.matchUid,
            seq = zeile.seq,
            originDeviceUid = zeile.originDeviceUid,
            originSeq = zeile.originSeq,
            lamportClock = zeile.lamportClock,
            occurredAt = zeile.occurredAt,
            recordedAt = zeile.recordedAt,
            type = zeile.type,
            eventClass = klasse,
            actorParticipantUid = zeile.actorParticipantUid,
            targetParticipantUid = zeile.targetParticipantUid,
            payload = nutzdaten,
            payloadSchemaVersion = zeile.payloadSchemaVersion,
            visibility = sichtbarkeit,
            recipientParticipantUid = zeile.recipientParticipantUid,
            isUndone = zeile.isUndone,
            undoneByEventUid = zeile.undoneByEventUid,
            hasConflict = zeile.hasConflict,
        )
    }

    /**
     * Ein neues, öffentliches Zustands-Event ohne Nutzdaten.
     *
     * ## Warum ohne Nutzdaten
     *
     * Weil keine gebraucht werden. Alles, was die Faltung auswertet — Typ,
     * Klasse, Akteur — steht nach TDD 5.2 als echte Spalte daneben. Ein
     * `payload_json` wäre hier eine zweite Ablage derselben Angabe, und die
     * beiden könnten auseinanderlaufen.
     *
     * Das gilt für die drei Typen dieses Slices. `identity_revealed` trägt nach
     * TDD 5.4 später „Identität plus Nachweis" — der Nachweis entsteht mit dem
     * echten Verfahren (T-029/T-030), nicht im Prototyp, der die Zuordnung
     * ohnehin lokal kennt.
     *
     * `seq` und `origin_seq` bleiben hier leer; sie vergibt
     * `MatchEventLog.anhaengenLokal` innerhalb seiner Transaktion.
     */
    fun oeffentlichesZustandsEvent(
        matchUid: String,
        typ: EventType,
        deviceUid: String,
        jetzt: Long,
        akteur: String? = null,
        ziel: String? = null,
        eventUid: String = UUID.randomUUID().toString(),
    ): MatchEventEntity {
        require(typ.eventClass == EventClass.STATE) {
            "${typ.wert} ist kein Zustands-Event (event_class=${typ.eventClass.wert}). " +
                "Nur `state` verändert die Projektion (TDD 5.2)."
        }
        require(Visibility.PUBLIC in typ.erlaubteSichtbarkeiten) {
            "${typ.wert} darf nicht PUBLIC sein (erlaubt: ${typ.erlaubteSichtbarkeiten}). " +
                "Ein Klartext-Event, das keines sein dürfte, wäre für den Host lesbar (TDD 7.4)."
        }
        return MatchEventEntity(
            eventUid = eventUid,
            matchUid = matchUid,
            seq = null,
            originDeviceUid = deviceUid,
            originSeq = 0L,
            // Ohne Netz gibt es keine fremde Kausalität, die einzuordnen wäre.
            // Die Lamport-Uhr läuft mit der Ereignisfolge dieses Geräts mit;
            // ihren eigentlichen Zweck bekommt sie mit dem Transport (S5).
            lamportClock = 0L,
            occurredAt = jetzt,
            recordedAt = jetzt,
            type = typ.wert,
            eventClass = typ.eventClass.wert,
            actorParticipantUid = akteur,
            targetParticipantUid = ziel,
            payloadJson = null,
            payloadCiphertext = null,
            encScheme = null,
            payloadSchemaVersion = 1,
            visibility = Visibility.PUBLIC.wert,
            recipientParticipantUid = null,
            isUndone = false,
            undoneByEventUid = null,
            hasConflict = false,
        )
    }
}
