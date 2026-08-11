package de.myhornets.rise1.tools.catalog

import com.google.gson.JsonParser
import java.io.File
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.system.exitProcess

/**
 * T-014 — trägt den Room-Identitätshash in eine fertige `catalog.db` nach.
 *
 * ## Warum das nötig ist
 *
 * Room prüft beim Öffnen einer mitgelieferten Datenbank zwei Dinge, bevor eine
 * einzige Abfrage läuft:
 *
 *  1. `PRAGMA user_version` muss der Datenbankversion aus `@Database`
 *     entsprechen. Steht dort 0 — der Wert, den eine frisch gebaute SQLite-
 *     Datei trägt —, hält der Android-`SQLiteOpenHelper` die Datei für **neu**
 *     und ruft `onCreate` auf. Room führt dann seine `CREATE TABLE IF NOT
 *     EXISTS`-Anweisungen aus, die an den vorhandenen Tabellen wirkungslos
 *     abprallen, und schreibt anschließend seinen eigenen Hash hinein. Das
 *     Ergebnis sieht grün aus und hat **nichts geprüft**: Auch ein falsches
 *     Schema käme so durch. Genau der Fall, den T-014 ausschließen soll.
 *
 *  2. `room_master_table` muss den `identityHash` tragen, den Room aus den
 *     Entities errechnet. Diesen Hash kennt erst der Compiler — T-013 konnte
 *     ihn nicht kennen, deshalb steht er dort als offener Punkt.
 *
 * Beides schreibt dieser Schritt nach, und beides liest er aus dem von KSP
 * exportierten Schema — nicht aus einer hier gepflegten Konstante. Laufen
 * Schema und Datei auseinander, ist der Export die Quelle, die gewinnt.
 *
 * ## Warum ein eigener Schritt und nicht Teil von `database`
 *
 * `database` ist mit Laufnachweis abgenommen und erzeugt eine bit-gleich
 * reproduzierbare Datei. Dieser Schritt kommt danach und hängt an einem
 * Artefakt des App-Builds, den dieses Werkzeug nicht kennt und nicht kennen
 * soll (Modules.md, "Wo Build-Werkzeuge leben"). Er bleibt deshalb getrennt
 * und bekommt den Pfad zum Export als Argument — dieselbe Bauart wie
 * `validate`.
 *
 * ## Reproduzierbarkeit — und warum nur auf ungestempeltem Stand gestempelt wird
 *
 * Gemessen, nicht angenommen: Zweimal in eine Kopie **derselben ungestempelten
 * Datei** gestempelt ergibt bit-gleiche Ergebnisse. Ein zweiter Stempel auf
 * eine bereits gestempelte Datei ergibt dagegen **andere Bytes** — SQLite legt
 * die Seiten anders ab, wenn die Tabelle schon einmal existiert hat. Auch ein
 * `VACUUM` dazwischen räumt das nicht vollständig aus.
 *
 * Damit hinge der Inhalt der ausgelieferten Datei an ihrer Vorgeschichte statt
 * an ihren Daten. Deshalb die Regel: Auf einen bereits gestempelten Stand wird
 * **nicht** erneut gestempelt. Wer die Entities ändert, lässt `database` neu
 * laufen und stempelt dann. Ein erneuter Aufruf mit unverändertem Hash lässt
 * die Datei unangetastet und meldet das.
 *
 * Aufruf:
 *
 *     cd tools/catalog-import
 *     ./gradlew roomStamp
 */
object RoomStamp {

    /** Wortgleich mit `androidx.room.RoomMasterTable`. */
    private const val MASTER_TABLE = "room_master_table"
    private const val MASTER_TABLE_ID = 42
    private const val CREATE_MASTER_TABLE =
        "CREATE TABLE IF NOT EXISTS $MASTER_TABLE (id INTEGER PRIMARY KEY,identity_hash TEXT)"

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 2) {
            System.err.println(
                """
                roomStamp — trägt identityHash und user_version aus dem exportierten
                Room-Schema in die ausgelieferte catalog.db nach (T-014).

                Aufruf:  roomStamp <schema.json> <catalog.db>

                Bequemer:  cd tools/catalog-import && ./gradlew roomStamp
                """.trimIndent(),
            )
            exitProcess(2)
        }

        val schemaDatei = File(args[0])
        val dbDatei = File(args[1])

        if (!schemaDatei.isFile) {
            abbruch(
                """
                Der Room-Schemaexport fehlt:
                  $schemaDatei

                Er entsteht beim Übersetzen von :catalog. Erst bauen, dann stempeln:
                  ./gradlew :catalog:assembleDebug
                """.trimIndent(),
            )
        }
        if (!dbDatei.isFile) {
            abbruch(
                """
                Die Katalogdatenbank fehlt:
                  $dbDatei

                Sie entsteht mit dem Befehl `database` aus T-013.
                """.trimIndent(),
            )
        }

        val schema = lesSchema(schemaDatei)
        val vorher = lesStand(dbDatei)

        println("Room-Schemaexport: $schemaDatei")
        println("  Version:      ${schema.version}")
        println("  identityHash: ${schema.identityHash}")
        println()
        println("catalog.db: $dbDatei")
        println("  Größe:   ${dbDatei.length() / 1024} KiB")
        println("  SHA-256: ${sha256(dbDatei)}")
        println(
            "  Stand:   user_version=${vorher.userVersion}, " +
                "identityHash=${vorher.identityHash ?: "— (nicht gestempelt)"}",
        )
        println()

        if (vorher.identityHash != null) {
            if (vorher.identityHash == schema.identityHash && vorher.userVersion == schema.version) {
                println("Bereits gestempelt und deckungsgleich mit dem Schemaexport. Nichts zu tun.")
                return
            }
            abbruch(
                """
                Diese catalog.db ist bereits gestempelt, aber mit einem anderen Stand:
                  in der Datei:  user_version=${vorher.userVersion}, identityHash=${vorher.identityHash}
                  im Export:     user_version=${schema.version}, identityHash=${schema.identityHash}

                Es wird bewusst NICHT übergestempelt. Ein zweiter Stempel auf dieselbe
                Datei ergibt andere Bytes als ein erster auf eine frisch gebaute — der
                Inhalt hinge dann an der Vorgeschichte der Datei statt an ihren Daten.

                Der Weg ist:
                  cd tools/catalog-import && ./gradlew database   # frische, ungestempelte Datei
                  ./gradlew roomStamp
                """.trimIndent(),
            )
        }

        // Zweimal in je eine Kopie stempeln und vergleichen. Erst wenn beide
        // Ergebnisse bit-gleich sind, wird eines davon zur ausgelieferten Datei.
        val kopieA = File(dbDatei.parentFile, "${dbDatei.name}.stamp-a")
        val kopieB = File(dbDatei.parentFile, "${dbDatei.name}.stamp-b")
        try {
            dbDatei.copyTo(kopieA, overwrite = true)
            dbDatei.copyTo(kopieB, overwrite = true)
            stemple(kopieA, schema)
            stemple(kopieB, schema)

            val shaA = sha256(kopieA)
            val shaB = sha256(kopieB)
            if (shaA != shaB) {
                abbruch(
                    """
                    Der Stempelschritt ist nicht reproduzierbar:
                      Lauf A: $shaA
                      Lauf B: $shaB

                    Zweimal derselbe Eingang muss dieselbe Datei ergeben. Solange das
                    nicht gilt, wird nichts ausgeliefert.
                    """.trimIndent(),
                )
            }

            // Erst jetzt anfassen, was ausgeliefert wird.
            kopieA.copyTo(dbDatei, overwrite = true)

            val nachher = lesStand(dbDatei)
            if (nachher.userVersion != schema.version || nachher.identityHash != schema.identityHash) {
                abbruch(
                    """
                    Nachkontrolle fehlgeschlagen — die Datei trägt nicht, was sie tragen soll:
                      erwartet: user_version=${schema.version}, identityHash=${schema.identityHash}
                      gelesen:  user_version=${nachher.userVersion}, identityHash=${nachher.identityHash}
                    """.trimIndent(),
                )
            }

            println("Gestempelt.")
            println("  user_version: ${nachher.userVersion}")
            println("  identityHash: ${nachher.identityHash}")
            println("  Größe:        ${dbDatei.length() / 1024} KiB")
            println("  SHA-256 neu:  ${sha256(dbDatei)}")
            println("Reproduzierbar: zweimal gestempelt, bit-gleiches Ergebnis.")
        } finally {
            kopieA.delete()
            kopieB.delete()
            File(kopieA.path + "-journal").delete()
            File(kopieB.path + "-journal").delete()
        }
    }

    // ── Schemaexport ─────────────────────────────────────────────────────────

    private data class Schemastand(val version: Int, val identityHash: String)

    private fun lesSchema(datei: File): Schemastand {
        val wurzel = JsonParser.parseString(datei.readText()).asJsonObject
        val datenbank = wurzel.getAsJsonObject("database")
            ?: abbruch("Im Schemaexport fehlt das Objekt `database`: $datei")
        val version = datenbank.get("version")?.asInt
            ?: abbruch("Im Schemaexport fehlt `database.version`: $datei")
        val hash = datenbank.get("identityHash")?.asString
            ?: abbruch("Im Schemaexport fehlt `database.identityHash`: $datei")
        if (hash.isBlank()) abbruch("`database.identityHash` ist leer: $datei")
        return Schemastand(version, hash)
    }

    // ── SQLite ───────────────────────────────────────────────────────────────

    private data class Dateistand(val userVersion: Int, val identityHash: String?)

    private fun lesStand(datei: File): Dateistand = verbinde(datei) { verbindung ->
        verbindung.createStatement().use { anweisung ->
            val userVersion = anweisung.executeQuery("PRAGMA user_version").use { ergebnis ->
                if (ergebnis.next()) ergebnis.getInt(1) else 0
            }
            val tabelleDa = anweisung.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='$MASTER_TABLE'",
            ).use { it.next() }
            val hash = if (!tabelleDa) {
                null
            } else {
                anweisung.executeQuery(
                    "SELECT identity_hash FROM $MASTER_TABLE WHERE id = $MASTER_TABLE_ID",
                ).use { ergebnis -> if (ergebnis.next()) ergebnis.getString(1) else null }
            }
            Dateistand(userVersion, hash)
        }
    }

    private fun stemple(datei: File, schema: Schemastand) = verbinde(datei) { verbindung ->
        verbindung.createStatement().use { anweisung ->
            anweisung.executeUpdate(CREATE_MASTER_TABLE)
            anweisung.executeUpdate(
                "INSERT OR REPLACE INTO $MASTER_TABLE (id,identity_hash) " +
                    "VALUES($MASTER_TABLE_ID,'${schema.identityHash}')",
            )
            // PRAGMA nimmt keine Platzhalter — der Wert kommt aus dem Export
            // und ist eine Zahl, kein Text.
            anweisung.execute("PRAGMA user_version = ${schema.version}")
        }
    }

    private fun <T> verbinde(datei: File, arbeit: (Connection) -> T): T =
        DriverManager.getConnection("jdbc:sqlite:${datei.absolutePath}").use { verbindung ->
            verbindung.autoCommit = true
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
