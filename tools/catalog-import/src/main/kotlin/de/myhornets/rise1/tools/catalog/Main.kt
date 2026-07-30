package de.myhornets.rise1.tools.catalog

import java.io.File
import java.time.Instant

private const val TOOL_VERSION = "T-011"
private const val CATALOG_VERSION = "1"
private const val SET_CODE = "TRD-2025"
private const val SOURCE_URL = "https://mtgtreachery.net/rules/oracle/treachery-cards.json"
private const val SOURCE_FILE = "treachery-cards.json"
private const val CHECKSUM_FILE = "treachery-cards.json.sha256"
private const val PROVENANCE_FILE = "provenance.json"

/**
 * Einstiegspunkt des Import-Werkzeugs. T-010 (laden und prüfen), T-011 (abbilden).
 *
 * Der Bau von `catalog.db` ist `T-013` und passiert hier nicht.
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
        "transform" -> transform(verzeichnis)
        "checksum" -> checksum(verzeichnis)
        else -> {
            System.err.println("Unbekannter Befehl '$befehl'. Erlaubt: validate, transform, checksum")
            kotlin.system.exitProcess(2)
        }
    }
}

/** Prüfsumme und Struktur. Schreibt bei Erfolg den Herkunftsnachweis. */
private fun validate(verzeichnis: File) {
    val (wurzel, pruefsumme) = ladeUndPruefe(verzeichnis)
    val bericht = CatalogSourceValidator().validate(wurzel)
    println(bericht.render())
    if (!bericht.isValid) kotlin.system.exitProcess(1)

    val kopf = bericht.header!!
    val nachweis = Provenance(
        sourceUrl = SOURCE_URL,
        sourceFile = SOURCE_FILE,
        sha256 = pruefsumme,
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

/**
 * Prüft **und** bildet auf das Zielschema ab. T-011.
 *
 * Erzeugt bewusst keine Datei: Dieser Befehl ist der Nachweis, dass die
 * Abbildung über alle 62 echten Karten trägt — Rollenverteilung, eindeutige
 * Slugs, Unveil-Kosten. Was daraus eine Datenbank macht, ist `T-013`.
 */
private fun transform(verzeichnis: File) {
    val (wurzel, pruefsumme) = ladeUndPruefe(verzeichnis)

    val bericht = CatalogSourceValidator().validate(wurzel)
    if (!bericht.isValid) {
        println(bericht.render())
        kotlin.system.exitProcess(1)
    }

    val ergebnis = IdentityMapper(SET_CODE, CATALOG_VERSION).map(
        wurzel = wurzel,
        sourceUrl = SOURCE_URL,
        sourceChecksum = pruefsumme,
        importedAt = Instant.now().toString(),
    )
    println(ergebnis.render())
    if (!ergebnis.isValid) kotlin.system.exitProcess(1)
}

private fun checksum(verzeichnis: File) {
    val quelle = File(verzeichnis, SOURCE_FILE)
    if (!quelle.isFile) abbruch("Die Quelldatei fehlt: ${quelle.path}")
    val summe = SourceChecksum.of(quelle)
    File(verzeichnis, CHECKSUM_FILE).writeText("$summe  $SOURCE_FILE\n")
    println("Prüfsumme geschrieben: $summe")
    println("Diese Änderung gehört bewusst in einen Commit — sie erklärt, dass die Quelle erneuert wurde.")
}

/**
 * Gemeinsamer Vorlauf von `validate` und `transform`: Datei da, Prüfsumme
 * stimmt, JSON gelesen. Die Prüfsumme wird **vor** dem Parsen verglichen —
 * eine Datei, die sich unbemerkt geändert hat, wird gar nicht erst gelesen.
 */
private fun ladeUndPruefe(verzeichnis: File): Pair<Map<String, Any?>, String> {
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

    val wurzel = try {
        JsonAdapter.parse(quelle.readText())
    } catch (e: Exception) {
        abbruch("Die Quelldatei ließ sich nicht lesen: ${e.message}")
    }
    return wurzel to tatsaechlich
}

private fun abbruch(vararg zeilen: String): Nothing {
    zeilen.forEach { System.err.println(it) }
    kotlin.system.exitProcess(1)
}
