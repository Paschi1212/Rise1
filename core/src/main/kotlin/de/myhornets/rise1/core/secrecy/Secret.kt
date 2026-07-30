package de.myhornets.rise1.core.secrecy

/**
 * Marker für Werte, die niemals im Klartext in einem Protokoll, einer
 * Fehlermeldung oder einer Serialisierung erscheinen dürfen.
 *
 * Architekturbezug: TDD 7.3 (Sichtbarkeit) und 7.4 (was der Host weiß).
 * Umgesetzt in T-005.
 *
 * Konkret betrifft das später: die eigene Identität aus `own_identity`,
 * private Schlüssel, Umschlagschlüssel, das `rejoin_token` und die beiden
 * Zuordnungsgeheimnisse aus dem Verteilverfahren.
 */
public interface Secret {
    /** Kurze, unverfängliche Kennzeichnung. Enthält niemals den Wert selbst. */
    public fun redacted(): String
}

/**
 * Basisklasse für geheime Werte.
 *
 * Der Kern ist das **finale** [toString]: Es gibt keinen Weg, den Klartext
 * versehentlich in eine Zeichenkette zu interpolieren. Das ist die wichtigere
 * der beiden Schutzschichten — die andere ist die Compilerbremse in
 * [de.myhornets.rise1.core.log.RiseLog].
 *
 * Wer eine Unterklasse schreibt, muss den Klartext in einem privaten Feld
 * halten und darf ihn ausschließlich über eine ausdrücklich benannte Methode
 * herausgeben (Konvention: `expose...()`), damit die Stelle im Code auffindbar
 * ist, an der ein Geheimnis den Typ verlässt.
 */
public abstract class SecretValue(private val label: String) : Secret {

    init {
        require(label.isNotBlank()) { "label darf nicht leer sein" }
    }

    final override fun redacted(): String = "$label(***)"

    final override fun toString(): String = redacted()

    /**
     * Absichtlich nicht implementiert: Geheimnisse werden nicht über
     * Wertgleichheit verglichen, weil das zu Vergleichen in nicht konstanter
     * Zeit einlädt. Wo ein Vergleich nötig ist — etwa beim `rejoin_token` —
     * gehört er in das Modul `crypto` und läuft dort in konstanter Zeit
     * (TDD 7.7, ADR-002).
     */
    final override fun equals(other: Any?): Boolean =
        throw UnsupportedOperationException(
            "Geheime Werte werden nicht über equals verglichen. " +
                "Siehe TDD 7.7 — Vergleiche laufen in konstanter Zeit im Modul crypto."
        )

    final override fun hashCode(): Int =
        throw UnsupportedOperationException(
            "Geheime Werte liefern keinen hashCode, damit sie nicht als " +
                "Map-Schlüssel oder in Mengen landen und dort verglichen werden."
        )
}
