package de.myhornets.rise1.tools.catalog

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 über die eingecheckte Quelldatei. T-010.
 *
 * Nur JDK-Mittel, keine Abhängigkeit. Der Vergleich läuft absichtlich **vor**
 * jeder Verarbeitung: Eine Quelldatei, die sich unbemerkt geändert hat, darf
 * gar nicht erst geparst werden.
 */
public object SourceChecksum {

    public fun of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    public fun of(file: File): String = of(file.readBytes())

    /** Vergleicht ohne Rücksicht auf Groß-/Kleinschreibung und Leerraum. */
    public fun matches(actual: String, expected: String): Boolean =
        actual.trim().equals(expected.trim(), ignoreCase = true)
}
