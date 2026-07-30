package de.myhornets.rise1.core.log

import de.myhornets.rise1.core.secrecy.Secret

/**
 * Protokollierung für Rise 1.0.
 *
 * Architekturbezug: TDD 7.3. Umgesetzt in T-005.
 *
 * Zweck ist nicht Komfort, sondern eine Sperre: **Es darf kein Weg existieren,
 * auf dem eine Identität, ein Schlüssel oder ein Nutzdatum oberhalb von
 * `PUBLIC` in ein Protokoll gerät.** Dafür greifen zwei Schichten:
 *
 *  1. Geheime Werte erben von
 *     [de.myhornets.rise1.core.secrecy.SecretValue] und haben ein finales
 *     `toString`, das nur eine Kennzeichnung liefert. Auch
 *     `"Wert: $geheimnis"` ist damit harmlos.
 *  2. Die überladene Signatur mit [Secret] ist als Fehler markiert. Der
 *     Versuch, ein Geheimnis direkt zu protokollieren, **lässt den Build
 *     scheitern** — nicht erst einen Test.
 *
 * Die Ausgabe geht über eine austauschbare [Sink]. Standardmäßig auf die
 * Standardausgabe, damit die JVM-Module ohne Android testbar bleiben; das
 * Modul `ui` setzt eine Logcat-Senke ein.
 */
public object RiseLog {

    public enum class Level { DEBUG, INFO, WARN, ERROR }

    /** Ausgabekanal. Bewusst schmal — mehr braucht es nicht. */
    public fun interface Sink {
        public fun write(level: Level, tag: String, message: String)
    }

    private val stdoutSink = Sink { level, tag, message ->
        println("[${level.name}] $tag: $message")
    }

    @Volatile
    private var sink: Sink = stdoutSink

    /** Setzt den Ausgabekanal. Wird vom Modul `ui` beim Start aufgerufen. */
    public fun installSink(newSink: Sink) {
        sink = newSink
    }

    /** Nur für Tests: stellt die Standardausgabe wieder her. */
    public fun resetSink() {
        sink = stdoutSink
    }

    public fun d(tag: String, message: String): Unit = write(Level.DEBUG, tag, message)
    public fun i(tag: String, message: String): Unit = write(Level.INFO, tag, message)
    public fun w(tag: String, message: String): Unit = write(Level.WARN, tag, message)
    public fun e(tag: String, message: String): Unit = write(Level.ERROR, tag, message)

    /**
     * Fehler mit Ursache. Die Ursache wird auf Typ und Meldung reduziert —
     * kein Stacktrace ins Protokoll, weil Stacktraces Feldinhalte
     * durchscheinen lassen können.
     */
    public fun e(tag: String, message: String, cause: Throwable): Unit =
        write(Level.ERROR, tag, "$message | Ursache: ${cause::class.simpleName}: ${cause.message.orEmpty()}")

    // ── Compilerbremse ────────────────────────────────────────────────────────
    // Diese Überladungen existieren nur, um verweigert zu werden. Sie fangen den
    // häufigsten Fehlgriff ab: ein Geheimnis direkt zu übergeben.

    @Deprecated(
        message = "Geheime Werte dürfen nicht protokolliert werden (TDD 7.3). " +
            "Nutze secret.redacted(), wenn eine Kennzeichnung ins Protokoll soll.",
        level = DeprecationLevel.ERROR,
    )
    public fun d(tag: String, secret: Secret): Nothing = refuse()

    @Deprecated(
        message = "Geheime Werte dürfen nicht protokolliert werden (TDD 7.3). " +
            "Nutze secret.redacted(), wenn eine Kennzeichnung ins Protokoll soll.",
        level = DeprecationLevel.ERROR,
    )
    public fun i(tag: String, secret: Secret): Nothing = refuse()

    @Deprecated(
        message = "Geheime Werte dürfen nicht protokolliert werden (TDD 7.3). " +
            "Nutze secret.redacted(), wenn eine Kennzeichnung ins Protokoll soll.",
        level = DeprecationLevel.ERROR,
    )
    public fun w(tag: String, secret: Secret): Nothing = refuse()

    @Deprecated(
        message = "Geheime Werte dürfen nicht protokolliert werden (TDD 7.3). " +
            "Nutze secret.redacted(), wenn eine Kennzeichnung ins Protokoll soll.",
        level = DeprecationLevel.ERROR,
    )
    public fun e(tag: String, secret: Secret): Nothing = refuse()

    private fun refuse(): Nothing = throw IllegalStateException(
        "RiseLog hat die Protokollierung eines geheimen Werts verweigert (TDD 7.3)."
    )

    private fun write(level: Level, tag: String, message: String) {
        sink.write(level, tag, message)
    }
}
