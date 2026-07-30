package de.myhornets.rise1.core.error

/**
 * Fehlerkonzept für Rise 1.0.
 *
 * Architekturbezug: TDD 7.3. Umgesetzt in T-005.
 *
 * Zwei Regeln, die den ganzen Zweck ausmachen:
 *
 *  1. **Fehlermeldungen tragen keine Geheimnisse.** Sie landen in Protokollen,
 *     in Absturzberichten und gelegentlich in der Oberfläche. Wer eine
 *     Identität in eine Meldung schreibt, hat die Sperre aus
 *     [de.myhornets.rise1.core.log.RiseLog] umgangen.
 *  2. **Jeder Fehler hat einen stabilen [code].** Der Code ist die Sache, auf
 *     die sich Protokolle, Tests und die Oberfläche beziehen — nicht der Text.
 *     Anzeigetexte kommen aus Android-Ressourcen (TDD 3.5).
 *
 * Die Liste der Fälle wächst mit den Epics. Hier steht bewusst nur der
 * Vertrag plus der eine Fall, den es immer gibt.
 */
public sealed interface RiseError {

    /** Stabiler, sprachunabhängiger Schlüssel. Konvention: `bereich.ursache`. */
    public val code: String

    /** Technische Beschreibung **ohne** geheime Werte. Für Protokolle. */
    public val technicalMessage: String

    /** Unerwarteter Fehler. Kein Ersatz für einen benannten Fall — ein Hinweis, dass einer fehlt. */
    public data class Unexpected(
        override val technicalMessage: String,
        public val cause: Throwable? = null,
    ) : RiseError {
        override val code: String = "core.unexpected"
    }
}

/**
 * Ausnahme, die einen [RiseError] trägt.
 *
 * Die Meldung der Ausnahme ist der [RiseError.technicalMessage] — damit gilt
 * die Regel „keine Geheimnisse in Fehlermeldungen" automatisch auch für alles,
 * was den Stacktrace ausgibt.
 */
public class RiseException(
    public val error: RiseError,
    cause: Throwable? = null,
) : Exception("[${error.code}] ${error.technicalMessage}", cause)
