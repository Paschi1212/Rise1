package de.myhornets.rise1.session

/**
 * T-112 — Offline-Verhalten (TDD 6.4 und 9.1).
 *
 * ## Die Sätze, aus denen alles folgt
 *
 * TDD 6.4: *„Ein Client ohne Verbindung bleibt vollständig bedienbar für alles,
 * was ihn selbst betrifft: Er zeigt seine Identität aus dem lokalen
 * verschlüsselten Speicher, zeigt den letzten bekannten Tischzustand **mit
 * deutlicher Kennzeichnung als veraltet** und sammelt eigene Aktionen in der
 * `sync_outbox`. Was er nicht tut: den Tischzustand raten, fremde Identitäten
 * anzeigen oder die Partie beenden."*
 *
 * Und TDD 9.1: *„Nur explizite Ereignisse beenden eine Partie."*
 *
 * ## Was diese Datei ist — und was nicht
 *
 * Sie ist die **Buchführung**: Was ist noch nicht draußen? Wie alt ist mein
 * Bild vom Tisch? Sie ist **nicht** die Anzeige und **nicht** der Versand. Der
 * Versand hängt an der Sitzung ([Wiedereinstiegsablauf]), die Anzeige an `:ui`.
 *
 * Sie kann die Partie nicht beenden, weil sie sie nicht erreicht — dieselbe
 * Bauart wie beim [Verbindungsautomat].
 */

/** Der Zustand eines Eintrags im Ausgangsstapel (TDD 4.5, `sync_outbox`). */
enum class Versandzustand {
    /** Liegt bereit. */
    OFFEN,

    /** Ist unterwegs — vom Host noch nicht bestätigt. */
    UNTERWEGS,

    /** Vom Host bestätigt. Endzustand. */
    ZUGESTELLT,

    /**
     * Der letzte Versuch ist gescheitert.
     *
     * **Kein Endzustand.** Ein gescheiterter Versand wird wiederholt, sobald es
     * wieder geht; verworfen wird nichts. Was der Spieler offline getan hat,
     * hat er getan.
     */
    GESCHEITERT,
}

/**
 * Ein Eintrag im Ausgangsstapel.
 *
 * @param entityUid die Kennung dessen, was hinaus soll — bei einem Event seine
 *   `event_uid`. Sie ist zugleich der **Idempotenzschlüssel**: Dasselbe Event
 *   liegt hier höchstens einmal, egal wie oft es angeboten wird.
 */
data class Ausgangseintrag(
    val outboxUid: String,
    val entityType: String,
    val entityUid: String,
    val zustand: Versandzustand,
    val versuche: Int,
    val letzterFehler: String?,
) {
    init {
        require(outboxUid.isNotBlank()) { "Ein Eintrag ohne Kennung ist keiner." }
        require(entityUid.isNotBlank()) { "Ein Eintrag ohne Bezug ist keiner." }
        require(versuche >= 0) { "Negative Versuche gibt es nicht." }
    }
}

/**
 * Der Ausgangsstapel (`sync_outbox`, TDD 4.5).
 *
 * ## Idempotenz
 *
 * [lege] mit derselben `entityUid` legt **keinen** zweiten Eintrag an und gibt
 * den vorhandenen zurück. Das ist der Fall, der im Betrieb dauernd vorkommt:
 * Ein Spieler drückt zweimal, eine Wiederholung läuft, ein Ablauf wird nach
 * einem Abbruch neu gestartet. Ein zweiter Eintrag hieße, dass dieselbe Aktion
 * zweimal beim Host ankommt — und das Log entscheidet zwar über Dubletten
 * (Dedup über `(origin_device_uid, origin_seq)`), aber die Verwirrung entstünde
 * schon vorher.
 *
 * ## Reihenfolge
 *
 * [naechste] gibt in **Einlagerungsreihenfolge** heraus. Nicht nach
 * Fehlversuchen sortiert und nicht nach Alter: Was der Spieler zuerst getan
 * hat, geht zuerst hinaus. Alles andere würde die Kausalität verdrehen, die
 * die Lamport-Uhr gerade festhalten soll.
 */
class Ausgangsstapel(private val kennung: () -> String) {

    private val eintraege = LinkedHashMap<String, Ausgangseintrag>()

    /** Alle Einträge in Einlagerungsreihenfolge. */
    fun alle(): List<Ausgangseintrag> = eintraege.values.toList()

    /** Was noch hinaus muss — offen oder gescheitert, in Einlagerungsreihenfolge. */
    fun offene(): List<Ausgangseintrag> = eintraege.values.filter {
        it.zustand == Versandzustand.OFFEN || it.zustand == Versandzustand.GESCHEITERT
    }

    /** Der nächste Eintrag, der hinaus soll, oder `null`. */
    fun naechste(): Ausgangseintrag? = offene().firstOrNull()

    /**
     * Legt einen Eintrag an — oder gibt den vorhandenen zurück.
     *
     * @return der Eintrag zu [entityUid]. Bei einem zweiten Aufruf **derselbe**.
     */
    fun lege(entityType: String, entityUid: String): Ausgangseintrag {
        require(entityType.isNotBlank()) { "Ein Eintrag ohne Art ist keiner." }
        eintraege[entityUid]?.let { return it }

        val neu = Ausgangseintrag(
            outboxUid = kennung(),
            entityType = entityType,
            entityUid = entityUid,
            zustand = Versandzustand.OFFEN,
            versuche = 0,
            letzterFehler = null,
        )
        eintraege[entityUid] = neu
        return neu
    }

    /** Merkt, dass ein Eintrag unterwegs ist, und zählt den Versuch. */
    fun unterwegs(entityUid: String): Ausgangseintrag? = aendere(entityUid) { alt ->
        if (alt.zustand == Versandzustand.ZUGESTELLT) {
            return@aendere alt
        }
        alt.copy(zustand = Versandzustand.UNTERWEGS, versuche = alt.versuche + 1, letzterFehler = null)
    }

    /**
     * Der Host hat bestätigt.
     *
     * Endzustand — eine zweite Bestätigung ändert nichts. Genau so kommt sie im
     * Betrieb auch an: Der Client hat sein Event geschickt, die Bestätigung ging
     * verloren, er hat es noch einmal geschickt, und jetzt kommen zwei.
     */
    fun zugestellt(entityUid: String): Ausgangseintrag? = aendere(entityUid) { alt ->
        alt.copy(zustand = Versandzustand.ZUGESTELLT, letzterFehler = null)
    }

    /** Der Versuch ist gescheitert. Der Eintrag bleibt liegen. */
    fun gescheitert(entityUid: String, grund: String): Ausgangseintrag? = aendere(entityUid) { alt ->
        if (alt.zustand == Versandzustand.ZUGESTELLT) {
            return@aendere alt
        }
        alt.copy(zustand = Versandzustand.GESCHEITERT, letzterFehler = grund)
    }

    /**
     * Stellt alles Unterwegse wieder auf offen.
     *
     * Nach einem Verbindungsverlust: Was unterwegs war, ist im Zweifel nicht
     * angekommen. Es noch einmal zu schicken ist ungefährlich — der Log dedupt
     * über `(origin_device_uid, origin_seq)` —, es **nicht** zu schicken wäre
     * ein verlorener Spielzug.
     */
    fun zuruecksetzenNachAbbruch(): Int {
        var betroffen = 0
        eintraege.keys.toList().forEach { uid ->
            val alt = eintraege.getValue(uid)
            if (alt.zustand == Versandzustand.UNTERWEGS) {
                eintraege[uid] = alt.copy(zustand = Versandzustand.OFFEN)
                betroffen++
            }
        }
        return betroffen
    }

    private fun aendere(
        entityUid: String,
        wandle: (Ausgangseintrag) -> Ausgangseintrag,
    ): Ausgangseintrag? {
        val alt = eintraege[entityUid] ?: return null
        val neu = wandle(alt)
        eintraege[entityUid] = neu
        return neu
    }
}

/**
 * Wie aktuell das eigene Bild vom Tisch ist (TDD 6.4).
 *
 * `veraltet` ist **keine** Fehlermeldung, sondern eine Eigenschaft der Anzeige:
 * Der Spieler sieht weiter, was er zuletzt wusste, und sieht zugleich, dass es
 * alt sein könnte. Was er **nicht** sieht, ist ein geratener Zustand.
 */
data class Anzeigelage(
    val zustand: Verbindungszustand,
    /** Der eigene Stand — die höchste übernommene `seq`. */
    val eigenerStand: Long,
    /** Der zuletzt vom Host zugesagte Stand, oder `null`, wenn nie einer kam. */
    val zugesagterStand: Long?,
    /** Wie viele eigene Aktionen noch nicht bestätigt sind. */
    val ausstehend: Int,
) {

    /**
     * Ob der angezeigte Tischzustand als veraltet zu kennzeichnen ist.
     *
     * Drei Gründe, und jeder allein genügt: Die Sitzung steht nicht; der Host
     * war weiter als dieses Gerät; eigene Aktionen sind noch nicht bestätigt.
     *
     * Der dritte ist der, den man leicht vergisst: Wer offline einen Zug macht,
     * sieht ihn lokal — bestätigt ist er erst, wenn der Host ihn eingeordnet
     * hat (TDD 6.2). Bis dahin ist auch das eigene Bild vorläufig.
     */
    val veraltet: Boolean
        get() = zustand != Verbindungszustand.VERBUNDEN ||
            (zugesagterStand != null && zugesagterStand > eigenerStand) ||
            ausstehend > 0

    /**
     * Ob dieses Gerät bedienbar bleibt.
     *
     * **Immer.** TDD 6.4 lässt hier keinen Spielraum, und deshalb gibt es keine
     * Bedingung: Ein Client ohne Verbindung bleibt vollständig bedienbar für
     * alles, was ihn selbst betrifft. Die Eigenschaft steht trotzdem hier, weil
     * sie geprüft gehört — eine Zusage, die nirgends steht, wird irgendwann
     * versehentlich gebrochen.
     */
    val bedienbar: Boolean get() = true

    /**
     * Ob die Partie zu Ende ist.
     *
     * **Nie aus dieser Lage heraus.** TDD 9.1: Nur explizite Ereignisse beenden
     * eine Partie — kein Timeout, kein Herzschlagausfall, keine
     * Fehlerbedingung. Diese Eigenschaft ist konstant `false` und existiert als
     * Prüfpunkt: Wer sie eines Tages von `zustand` abhängig macht, wird von
     * einem Test daran gehindert.
     */
    val partieBeendet: Boolean get() = false
}
