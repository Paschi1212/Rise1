package de.myhornets.rise1.tools.catalog

import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.system.exitProcess

/**
 * T-015 — füllt `identity_keyword` aus `text_raw`.
 *
 * ## Was hier entsteht — und was ausdrücklich nicht
 *
 * `identity_keyword` ist eine **Filtermarke**, damit „alle Undercover-Identitäten"
 * auffindbar sind ([[Cards]]). Es ist keine Modellierung dessen, was Undercover
 * *bewirkt*. Die Tabelle hält fest, dass auf der Karte eine Schlüsselwortzeile
 * steht — sie zieht daraus keinen Schluss.
 *
 * ## Die Regel, nach der ein Schlüsselwort erkannt wird
 *
 * Ein Schlüsselwort liegt vor, wenn ein Abschnitt des Regeltexts — die Quelle
 * trennt sie mit `|` — **mit** einem Wort des Vokabulars **beginnt**. Maßgeblich
 * ist das **ganze führende Wort**, nicht ein Präfix davon.
 *
 * Das ist wörtlich dieselbe Regel, die `T-011` für den Unveil-Cost gefunden hat,
 * und aus demselben Grund: `Undercover (Unveil only if another identity has been
 * revealed …)` enthält das Wort `Unveil` mitten im Erinnerungstext. Wer irgendwo
 * im Abschnitt sucht, vergibt dort ein Schlüsselwort, das die Karte nicht trägt.
 *
 * Die Trennung von `Unveil {9}` und einem hypothetischen `Unveiling` entsteht
 * **nicht** an der Wortgrenze, sondern am Vokabularvergleich: `fuehrendesWort`
 * liefert `Unveiling`, und `Unveiling` steht nicht im Vokabular. Die beiden
 * Schritte sind bewusst getrennt — `schluesselwortVon` entscheidet,
 * `fuehrendesWort` zerlegt nur. `T-019` hat diese Beschreibung berichtigt: Sie
 * behauptete zuvor eine Prüfung „kein Kleinbuchstabe danach", die im Code
 * unerreichbar war und deshalb nichts trennte.
 *
 * Was **nach** dem Wort noch folgt, ist gleichgültig:
 * `Unveil {9}. This cost is reduced by {1} for each creature …` ist eine
 * Schlüsselwortzeile, auch wenn ein ganzer Satz daran hängt. Genau daran sind bei
 * der Vorbereitung zwei Guardians durchgefallen, als die Regel noch verlangte,
 * dass der Abschnitt **nur** aus Schlüsselwort und Kosten besteht.
 *
 * ## Geschlossenheit
 *
 * Das Vokabular ist eine feste Liste, keine Heuristik. Damit ein späterer
 * Kartensatz nicht still ein Schlüsselwort verliert, sucht der Lauf zusätzlich
 * nach **Kandidaten**: Abschnitte, die mit einem großgeschriebenen Wort beginnen,
 * auf das unmittelbar `—` oder ` {` folgt — die beiden Formen, in denen eine
 * Schlüsselwortzeile in diesem Satz auftritt. Steht dort ein Wort, das nicht im
 * Vokabular ist, bricht der Lauf ab und nennt Karte und Abschnitt.
 *
 * ## Reproduzierbarkeit
 *
 * Wie `database` und `roomStamp`: Es wird zweimal in je eine Kopie geschrieben
 * und verglichen. Die Zeilen gehen in fester Sortierung nach `identity_uid` und
 * `keyword` hinein; ohne das hinge die Datei an der Reihenfolge der Abfrage.
 *
 * Und wie `roomStamp`: Auf eine Datei, die die Tabelle schon hat, wird **nicht**
 * erneut geschrieben. Der Weg ist `database` neu laufen lassen, dann `keywords`.
 *
 * ## Reihenfolge
 *
 *     cd tools/catalog-import
 *     ./gradlew database    # frische catalog.db
 *     ./gradlew keywords    # diese Aufgabe
 *     cd ../.. && ./gradlew :catalog:assembleDebug
 *     cd tools/catalog-import && ./gradlew roomStamp
 *
 * `keywords` läuft **vor** `roomStamp`, weil es das Schema ändert — und ein
 * geändertes Schema ist ein anderer `identityHash`.
 */
object KeywordIndex {

    /**
     * Das Vokabular. Feste Liste, bewusst kurz.
     *
     * Beide Wörter stehen so auf den Karten des Sets TRD-2025. Gespeichert wird
     * kleingeschrieben, weil das Feld ein Filterschlüssel ist und keine Anzeige.
     */
    private val VOKABULAR = listOf("Undercover", "Unveil")

    private const val TABELLE = "identity_keyword"
    private const val CREATE_TABELLE =
        "CREATE TABLE IF NOT EXISTS `$TABELLE` " +
            "(`identity_uid` TEXT NOT NULL, `keyword` TEXT NOT NULL, " +
            "PRIMARY KEY(`identity_uid`, `keyword`))"
    private const val CREATE_INDEX =
        "CREATE INDEX IF NOT EXISTS `index_identity_keyword_keyword` ON `$TABELLE` (`keyword`)"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 1) {
            System.err.println(
                """
                keywords — füllt identity_keyword aus text_raw (T-015).

                Aufruf:  keywords <catalog.db>

                Bequemer:  cd tools/catalog-import && ./gradlew keywords
                """.trimIndent(),
            )
            exitProcess(2)
        }

        val dbDatei = File(args[0])
        if (!dbDatei.isFile) {
            abbruch(
                """
                Die Katalogdatenbank fehlt:
                  $dbDatei

                Sie entsteht mit dem Befehl `database` aus T-013.
                """.trimIndent(),
            )
        }

        println("catalog.db: $dbDatei")
        println("  Größe:   ${dbDatei.length() / 1024} KiB")
        println("  SHA-256: ${sha256(dbDatei)}")
        println()

        if (hatTabelle(dbDatei)) {
            abbruch(
                """
                Diese catalog.db hat `$TABELLE` bereits.

                Es wird bewusst nicht nachgefüllt. Ein zweiter Schreibvorgang auf
                dieselbe Datei ergibt andere Bytes als ein erster auf eine frisch
                gebaute — der Inhalt hinge dann an der Vorgeschichte der Datei
                statt an ihren Daten. Dieselbe Regel wie bei `roomStamp`.

                Der Weg ist:
                  ./gradlew database    # frische, leere catalog.db
                  ./gradlew keywords
                """.trimIndent(),
            )
        }

        val identitaeten = lesIdentitaeten(dbDatei)
        if (identitaeten.isEmpty()) abbruch("`identity` ist leer — hier gibt es nichts abzuleiten.")

        val kandidaten = mutableListOf<String>()
        val paare = sortedSetOf<Paar>(compareBy({ it.identityUid }, { it.keyword }))

        identitaeten.forEach { (uid, textRaw) ->
            textRaw.split('|').forEach { abschnitt ->
                val s = abschnitt.trim()
                val wort = fuehrendesWort(s) ?: return@forEach
                val treffer = schluesselwortVon(s)
                if (treffer != null) {
                    paare += Paar(uid, treffer)
                } else {
                    val rest = s.substring(wort.length)
                    if (rest.startsWith("—") || rest.trimStart().startsWith("{")) {
                        kandidaten += "$uid — ${s.take(100)}"
                    }
                }
            }
        }

        if (kandidaten.isNotEmpty()) {
            abbruch(
                buildString {
                    appendLine("Unerwartete Schlüsselwörter — die Menge ist nicht geschlossen:")
                    appendLine()
                    kandidaten.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Diese Abschnitte sehen aus wie Schlüsselwortzeilen, ihr Wort steht")
                    appendLine("aber nicht im Vokabular ${VOKABULAR}. Entweder gehört es dazu —")
                    appendLine("dann wird es hier ausdrücklich aufgenommen — oder die Erkennungsregel")
                    appendLine("greift zu weit. Beides ist ein Befund, keine Kleinigkeit: Es wird")
                    appendLine("nichts geschrieben, bis das entschieden ist.")
                },
            )
        }

        val jeKeyword = paare.groupingBy { it.keyword }.eachCount()
        println("Abgeleitet aus text_raw:")
        println("  Identitäten gelesen:   ${identitaeten.size}")
        println("  Paare:                 ${paare.size}")
        jeKeyword.toSortedMap().forEach { (k, v) -> println("  davon $k: ${" ".repeat(maxOf(0, 12 - k.length))}$v") }
        println("  Karten mit Marke:      ${paare.map { it.identityUid }.distinct().size}")
        println("  Vokabular geschlossen: ja, keine unerwarteten Kandidaten")
        println()

        val kopieA = File(dbDatei.parentFile, "${dbDatei.name}.kw-a")
        val kopieB = File(dbDatei.parentFile, "${dbDatei.name}.kw-b")
        try {
            dbDatei.copyTo(kopieA, overwrite = true)
            dbDatei.copyTo(kopieB, overwrite = true)
            schreibe(kopieA, paare)
            schreibe(kopieB, paare)

            val shaA = sha256(kopieA)
            val shaB = sha256(kopieB)
            if (shaA != shaB) {
                abbruch(
                    """
                    Der Schreibvorgang ist nicht reproduzierbar:
                      Lauf A: $shaA
                      Lauf B: $shaB

                    Zweimal derselbe Eingang muss dieselbe Datei ergeben. Solange das
                    nicht gilt, wird nichts ausgeliefert.
                    """.trimIndent(),
                )
            }

            kopieA.copyTo(dbDatei, overwrite = true)

            val nachher = zaehle(dbDatei)
            if (nachher != paare.size) {
                abbruch("Nachkontrolle fehlgeschlagen: ${paare.size} Paare geschrieben, $nachher gelesen.")
            }

            println("Geschrieben.")
            println("  Zeilen in `$TABELLE`: $nachher")
            println("  Größe:                ${dbDatei.length() / 1024} KiB")
            println("  SHA-256 neu:          ${sha256(dbDatei)}")
            println("Reproduzierbar: zweimal geschrieben, bit-gleiches Ergebnis.")
            println()
            println("Nächster Schritt: :catalog übersetzen, dann `roomStamp` — das Schema")
            println("hat sich geändert, der identityHash ist damit ein anderer.")
        } finally {
            kopieA.delete()
            kopieB.delete()
            File(kopieA.path + "-journal").delete()
            File(kopieB.path + "-journal").delete()
        }
    }

    // ── Erkennung ────────────────────────────────────────────────────────────

    private data class Paar(val identityUid: String, val keyword: String)

    /**
     * Das Schlüsselwort eines Abschnitts, kleingeschrieben — oder `null`, wenn
     * der Abschnitt keines trägt.
     *
     * Hier und nur hier fällt die Entscheidung. `"Unveil {4}"` → `unveil` ·
     * `"Undercover (…)"` → `undercover` · `"Unveiling the …"` → `null`, weil
     * das führende Wort `Unveiling` lautet und nicht im Vokabular steht ·
     * `"When The Augur is unveiled, …"` → `null` aus demselben Grund.
     *
     * `T-019`: Vorher stand dieser Vergleich unbenannt in [main]. Damit war die
     * Eigenschaft „`Unveiling` wird nicht zu `unveil`" nirgends prüfbar — der
     * Test dazu hing an [fuehrendesWort] und behauptete dort etwas Falsches.
     */
    internal fun schluesselwortVon(abschnitt: String): String? {
        val wort = fuehrendesWort(abschnitt) ?: return null
        return VOKABULAR.firstOrNull { it == wort }?.lowercase()
    }

    /**
     * Das führende Wort eines Abschnitts, wenn er mit einem Großbuchstaben
     * beginnt — vom ersten Zeichen bis zum ersten Nicht-Buchstaben.
     *
     * Reine Wortzerlegung, ohne Kenntnis des Vokabulars:
     * `"Unveil {9}. …"` → `Unveil` · `"Undercover"` → `Undercover` ·
     * `"Unveiling the …"` → `Unveiling` · `"When The Augur …"` → `When` ·
     * `"• Create a …"` → `null`
     *
     * `T-019`: Hier stand eine Prüfung „direkt danach kein Kleinbuchstabe".
     * Sie war unerreichbar — die Schleife endet am ersten Nicht-Buchstaben, und
     * jeder Kleinbuchstabe ist ein Buchstabe. Sie ist entfernt, das Verhalten
     * ist unverändert.
     */
    internal fun fuehrendesWort(abschnitt: String): String? {
        if (abschnitt.isEmpty()) return null
        if (!abschnitt[0].isUpperCase()) return null
        var i = 1
        while (i < abschnitt.length && abschnitt[i].isLetter()) i++
        return abschnitt.substring(0, i)
    }

    // ── SQLite ───────────────────────────────────────────────────────────────

    private fun hatTabelle(datei: File): Boolean = verbinde(datei) { verbindung ->
        verbindung.createStatement().use { anweisung ->
            anweisung.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='$TABELLE'",
            ).use { it.next() }
        }
    }

    private fun lesIdentitaeten(datei: File): List<Pair<String, String>> = verbinde(datei) { verbindung ->
        verbindung.createStatement().use { anweisung ->
            anweisung.executeQuery(
                "SELECT identity_uid, text_raw FROM identity ORDER BY card_number",
            ).use { ergebnis ->
                val liste = mutableListOf<Pair<String, String>>()
                while (ergebnis.next()) liste += ergebnis.getString(1) to ergebnis.getString(2)
                liste
            }
        }
    }

    private fun schreibe(datei: File, paare: Collection<Paar>) = verbinde(datei) { verbindung ->
        verbindung.autoCommit = false
        verbindung.createStatement().use { anweisung ->
            anweisung.executeUpdate(CREATE_TABELLE)
            anweisung.executeUpdate(CREATE_INDEX)
        }
        verbindung.prepareStatement(
            "INSERT INTO `$TABELLE` (`identity_uid`, `keyword`) VALUES (?, ?)",
        ).use { anweisung ->
            paare.forEach { paar ->
                anweisung.setString(1, paar.identityUid)
                anweisung.setString(2, paar.keyword)
                anweisung.addBatch()
            }
            anweisung.executeBatch()
        }
        verbindung.commit()
    }

    private fun zaehle(datei: File): Int = verbinde(datei) { verbindung ->
        verbindung.createStatement().use { anweisung ->
            anweisung.executeQuery("SELECT COUNT(*) FROM `$TABELLE`").use { ergebnis ->
                if (ergebnis.next()) ergebnis.getInt(1) else 0
            }
        }
    }

    private fun <T> verbinde(datei: File, arbeit: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${datei.absolutePath}").use { verbindung ->
            arbeit(verbindung)
        }

    // ── Kleinkram ────────────────────────────────────────────────────────────

    private fun sha256(datei: File): String {
        val verdauung = MessageDigest.getInstance("SHA-256")
        datei.inputStream().use { strom ->
            val puffer = ByteArray(16 * 1024)
            while (true) {
                val gelesen = strom.read(puffer)
                if (gelesen <= 0) break
                verdauung.update(puffer, 0, gelesen)
            }
        }
        return verdauung.digest().joinToString("") { "%02x".format(it) }
    }

    private fun abbruch(meldung: String): Nothing {
        System.err.println()
        System.err.println(meldung)
        System.err.println()
        exitProcess(1)
    }
}
