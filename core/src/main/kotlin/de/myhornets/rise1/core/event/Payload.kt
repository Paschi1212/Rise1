package de.myhornets.rise1.core.event

/**
 * T-025a — die Nutzdaten eines Events.
 *
 * ## Warum ein versiegelter Typ und keine zwei nullbare Felder
 *
 * TDD 5.2 kennt zwei Felder: `payload_json` „nur bei `PUBLIC`" und
 * `payload_ciphertext`, das `payload_json` ersetzt, „sobald die Sichtbarkeit
 * nicht `PUBLIC` ist". Als zwei nullbare Felder wäre das eine **Regel**, die
 * man einhalten kann oder eben nicht — und ein Event mit beidem gefüllt wäre
 * konstruierbar. In genau so einem Event läge Klartext, den der Host nicht
 * sehen dürfte, direkt neben dem Chiffrat.
 *
 * Als versiegelter Typ ist es eine **Eigenschaft der Form**: Es gibt keinen
 * Ausdruck, der beides zugleich ist. Der Übergang zu den zwei Spalten passiert
 * an genau einer Stelle in `:store` und ist dort umkehrbar geprüft.
 *
 * Das ist die strukturelle Hälfte der Trennung. Die andere Hälfte — dass zur
 * Sichtbarkeit auch die passende Form gehört — steht in [MatchEvent].
 */
sealed interface Payload {

    /**
     * Klartext. Nur bei [Visibility.PUBLIC] zulässig.
     *
     * Der Inhalt wird hier **nicht** geparst. Die Faltung in `:projection`
     * kommt ohne Nutzdaten aus (TDD 5.2, letzter Absatz: die Felder, über die
     * abgefragt wird, stehen daneben als echte Spalten). Solange das gilt,
     * braucht weder `:core` noch `:projection` einen JSON-Parser.
     */
    data class Klartext(val json: String) : Payload {
        init {
            require(json.isNotBlank()) {
                "Ein leerer Klartext-Payload ist kein Payload — dafür gibt es Payload.Leer."
            }
        }
    }

    /**
     * Chiffrat plus Verfahrensangabe. Nur bei nicht-öffentlicher Sichtbarkeit.
     *
     * `encScheme` steht daneben, damit ein späterer Wechsel des Verfahrens alte
     * Events nicht unlesbar macht (TDD 5.2).
     *
     * Kein `data class`: Ein `ByteArray` vergleicht sich sonst über die
     * Referenz, und zwei gleiche Chiffrate wären ungleich. Das fiele erst in
     * einem Test auf, der zwei Wege vergleicht — also spät.
     */
    class Chiffrat(val bytes: ByteArray, val encScheme: String) : Payload {

        init {
            require(bytes.isNotEmpty()) { "Ein leeres Chiffrat ist kein Chiffrat." }
            require(encScheme.isNotBlank()) {
                "Ein Chiffrat ohne Verfahrensangabe wäre später nicht mehr zu entschlüsseln (TDD 5.2)."
            }
        }

        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Chiffrat && encScheme == other.encScheme && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + encScheme.hashCode()

        /** Bewusst ohne die Bytes: Ein Chiffrat gehört in kein Protokoll. */
        override fun toString(): String = "Chiffrat(${bytes.size} Bytes, $encScheme)"
    }

    /**
     * Kein Payload.
     *
     * Nicht jedes Event trägt Nutzdaten — `match_paused` etwa braucht keine.
     * Zulässig bei jeder Sichtbarkeit.
     */
    data object Leer : Payload

    /** Ob dieser Payload Klartext enthält, den der Host lesen könnte. */
    val istKlartext: Boolean get() = this is Klartext
}
