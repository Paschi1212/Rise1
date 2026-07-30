package de.myhornets.rise1.tools.catalog

import java.io.File
import java.time.Instant

private const val TOOL_VERSION = "T-010"
private const val SOURCE_URL = "https://mtgtreachery.net/rules/oracle/treachery-cards.json"
private const val SOURCE_FILE = "treachery-cards.json"
private const val CHECKSUM_FILE = "treachery-cards.json.sha256"
private const val PROVENANCE_FILE = "provenance.json"

/**
 * Einstiegspunkt des Import-Werkzeugs. T-010.
 *
 * Umfang von T-010: **laden und prüfen**. Die Transformation ist T-011, der
 * Bau von `catalog.db` ist T-013. Dieses Werkzeug erzeugt nichts außer dem
 * Herkunftsnachweis.
 *
 * Es lädt **nichts aus dem Netz**. Die Quelldatei liegt versioniert im
 * Repository; ein erneuter Download ist ein bewusster manueller Schritt und in
 * `catalog-source/README.md` beschrieben.
 */
public fun main(args: Array<String>) {
    val befehl = args.getOrNull(0) ?: "validate"
    val verzeichnis = File(args.getOrNull(1) ?: "catalog-source")

    when (befehl) {
        "validate" -> validate(verzeichnis)
        "checksum" -> checksum(verzeichnis)
        else -> {
            System.err.println("Unbekannter Befehl '$befehl'. Erlaubt: validate, checksum")
            kotlin.system.exitProcess(2)
        }
    }
}

private fun validate(verzeichnis: File) {
    val quelle = File(verzeichnis, SOURCE_FILE)
    val pruefsummendatei = File(verzeichnis, CHECKSUM_FILE)

    if (!quelle.isFile) {
        abbruch(
            "Die Quelldatei fehlt: ${quelle.path}",
            "Sie wird nicht automatisch geladen — das ist Absicht.",
            "Vorgehen steht in ${File(verzeichnis, "README.md").path}.",
        )
    }
    if (!pruefsummendatei.isFile) {
        abbruch(
            "Die Prüfsummendatei fehlt: ${pruefsummendatei.path}",
            "Erzeugen mit: ./gradlew run --args=\"checksum\" (im Verzeichnis tools/catalog-import)",
        )
    }

    // Prüfsumme VOR dem Parsen. Eine Datei, die sich unbemerkt geändert hat,
    // wird gar nicht erst verarbeitet.
    val tatsaechlich = SourceChecksum.of(quelle)
    val erwartet = pruefsummendatei.readText().trim().substringBefore(' ')
    if (!SourceChecksum.matches(tatsaechlich, erwartet)) {
        abbruch(
            "Die Prüfsumme stimmt nicht.",
            "  erwartet:   $erwartet",
            "  tatsächlich: $tatsaechlich",
            "",
            "Entweder wurde die Quelldatei bewusst erneuert — dann die Prüfsumme",
            "neu schreiben und die Änderung im Development Log festhalten — oder",
            "sie hat sich unbemerkt geändert. Beides will gesehen werden.",
        )
    }

    val bericht = try {
        CatalogSourceValidator().validate(JsonAdapter.parse(quelle.readText()))
    } catch (e: Exception) {
        abbruch("Die Quelldatei ließ sich nicht lesen: ${e.message}")
    }

    println(bericht.render())
    if (!bericht.isValid) kotlin.system.exitProcess(1)

    val kopf = bericht.header!!
    val nachweis = Provenance(
        sourceUrl = SOURCE_URL,
        sourceFile = SOURCE_FILE,
        sha256 = tatsaechlich,
        apiVersion = kopf.apiVersion,
        apiAuthor = kopf.apiAuthor,
        setCode = kopf.setCode,
        setLang = kopf.setLang,
        cardsCount = kopf.cardsCount,
        validatedAt = Instant.now().toString(),
        toolVersion = TOOL_VERSION,
    )
    File(verzeichnis, PROVENANCE_FILE).writeText(JsonAdapter.toPrettyJson(nachweis) + "\n")
    println("Herkunftsnachweis geschrieben: ${File(verzeichnis, PROVENANCE_FILE).path}")
}

private fun checksum(verzeichnis: File) {
    val quelle = File(verzeichnis, SOURCE_FILE)
    if (!quelle.isFile) abbruch("Die Quelldatei fehlt: ${quelle.path}")
    val summe = SourceChecksum.of(quelle)
    File(verzeichnis, CHECKSUM_FILE).writeText("$summe  $SOURCE_FILE\n")
    println("Prüfsumme geschrieben: $summe")
    println("Diese Änderung gehört bewusst in einen Commit — sie erklärt, dass die Quelle erneuert wurde.")
}

private fun abbruch(vararg zeilen: String): Nothing {
    zeilen.forEach { System.err.println(it) }
    kotlin.system.exitProcess(1)
}
