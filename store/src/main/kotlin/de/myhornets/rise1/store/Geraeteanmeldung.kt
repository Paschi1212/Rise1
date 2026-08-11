package de.myhornets.rise1.store

import java.util.UUID

/**
 * Der einzige Schreibweg auf `device` — und der erste Schritt jeder Partie.
 *
 * ## Warum es diese Klasse gibt
 *
 * Weil `device` der **Elternsatz** von zwei Fremdschlüsseln ist und beide beim
 * ersten echten Gerätelauf zugeschlagen haben:
 *
 * ```
 * match.host_device_uid            → device.device_uid
 * participant_session.device_uid   → device.device_uid
 * ```
 *
 * Eine Partie anzulegen, ohne dass das eigene Gerät in der Datenbank steht,
 * scheitert deshalb mit `FOREIGN KEY constraint failed (787)` — und zwar zu
 * Recht: Eine Partie gehört einem Host, und ein Host, den die Datenbank nicht
 * kennt, ist keiner. Der Fehler war kein Datenbankproblem, sondern eine
 * fehlende fachliche Reihenfolge.
 *
 * ## Die Reihenfolge, die daraus folgt
 *
 * ```
 * 1. Gerät anmelden      (hier)
 * 2. Partie anlegen      Partieanlage + Partieschreiber
 * 3. Sitzplatz vergeben  Beitrittsstelle  → braucht 1 und 2
 * 4. Sitzung eröffnen    Sitzungsverwaltung → braucht 1 und 3
 * ```
 *
 * Sie steht nicht zufällig so: Jeder Schritt braucht den Ausgang des
 * vorherigen. Wer sie umdreht, bekommt keinen anderen Fehler, sondern denselben.
 *
 * ## Die Kennung ist dauerhaft
 *
 * TDD 4.3: `device_uid` ist die **stabile** Kennung dieses Geräts. Sie einmal je
 * Prozess zu ziehen wäre bequem und falsch — nach jedem App-Neustart sähe das
 * Gerät wie ein anderes aus, und der Wiedereinstieg aus TDD 9.3 fände seine
 * Sitzung nicht wieder. Deshalb wird sie hier **gelesen**, wenn es sie gibt,
 * und nur beim allerersten Mal gezogen.
 *
 * ## Was hier nicht passiert
 *
 * **Kein Event.** Ein `device` ist Stammdatum und kein Spielereignis; es geht
 * nicht durch [MatchEventLog], weil es nichts am Spielzustand ändert. Die
 * Trennung ist dieselbe wie bei `player`: Was die Partie überdauert, ist keine
 * Partie.
 */
class Geraeteanmeldung(
    private val datenbank: RiseDatabase,
    private val uhr: () -> Long = System::currentTimeMillis,
    private val kennung: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Das eigene Gerät — angelegt beim ersten Mal, danach nur gelesen.
     *
     * Idempotent: Ein zweiter Aufruf erzeugt **kein** zweites Gerät. Täte er es,
     * gäbe es zwei Zeilen mit `is_self`, und keine Abfrage könnte mehr sagen,
     * auf welchem Gerät sie läuft.
     *
     * @param anzeigename wird bei einem bereits bekannten Gerät nachgeführt —
     *   der Nutzer darf sich umbenennen, ohne ein neues Gerät zu werden.
     */
    fun eigenes(anzeigename: String, plattform: String = ANDROID): DeviceEntity {
        val jetzt = uhr()
        val vorhanden = datenbank.deviceDao().eigenes()
        if (vorhanden != null) {
            if (vorhanden.displayName == anzeigename && vorhanden.lastSeenAt == jetzt) return vorhanden
            val gepflegt = vorhanden.copy(
                displayName = anzeigename.ifBlank { vorhanden.displayName },
                lastSeenAt = jetzt,
                updatedAt = jetzt,
            )
            datenbank.deviceDao().aktualisieren(gepflegt)
            return gepflegt
        }

        val neu = DeviceEntity(
            deviceUid = kennung(),
            displayName = anzeigename.ifBlank { STANDARDNAME },
            platform = plattform,
            isSelf = true,
            lastSeenAt = jetzt,
            createdAt = jetzt,
            updatedAt = jetzt,
            deletedAt = null,
            // Das eigene Gerät hat sich selbst angelegt. Ein anderer Wert wäre
            // eine Behauptung über eine Herkunft, die es nicht gibt.
            originDeviceUid = "",
        ).let { it.copy(originDeviceUid = it.deviceUid) }
        datenbank.deviceDao().einfuegen(neu)
        return neu
    }

    /**
     * Ein fremdes Gerät, das gerade beigetreten ist.
     *
     * Der Host braucht die Zeile, **bevor** er eine Sitzung für dessen Sitzplatz
     * eröffnet: `participant_session.device_uid` zeigt darauf. Ohne sie
     * scheiterte jeder Beitritt mit demselben 787 wie die Partieanlage.
     *
     * `is_self` ist hier immer `false` — genau eine Zeile trägt es, und das ist
     * die aus [eigenes].
     */
    fun merkeFremdes(
        deviceUid: String,
        anzeigename: String,
        durchGeraeteUid: String,
        plattform: String = UNBEKANNT,
    ): DeviceEntity {
        require(deviceUid.isNotBlank()) { "Ein Gerät ohne Kennung ist keines." }
        val jetzt = uhr()
        val vorhanden = datenbank.deviceDao().nachUid(deviceUid)
        if (vorhanden != null) {
            val gepflegt = vorhanden.copy(
                displayName = anzeigename.ifBlank { vorhanden.displayName },
                lastSeenAt = jetzt,
                updatedAt = jetzt,
            )
            datenbank.deviceDao().aktualisieren(gepflegt)
            return gepflegt
        }

        val neu = DeviceEntity(
            deviceUid = deviceUid,
            displayName = anzeigename.ifBlank { STANDARDNAME },
            platform = plattform,
            isSelf = false,
            lastSeenAt = jetzt,
            createdAt = jetzt,
            updatedAt = jetzt,
            deletedAt = null,
            originDeviceUid = durchGeraeteUid,
        )
        datenbank.deviceDao().einfuegen(neu)
        return neu
    }

    companion object {
        const val ANDROID = "android"
        const val UNBEKANNT = "unbekannt"
        const val STANDARDNAME = "Rise-Gerät"
    }
}
