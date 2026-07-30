package de.myhornets.rise1.tools.catalog

import java.io.File
import java.time.Instant

private const val TOOL_VERSION = "T-013"
private const val CATALOG_VERSION = "1"
private const val SET_CODE = "TRD-2025"
private const val SOURCE_URL = "https://mtgtreachery.net/rules/oracle/treachery-cards.json"
private const val SOURCE_FILE = "treachery-cards.json"
private const val CHECKSUM_FILE = "treachery-cards.json.sha256"
private const val PROVENANCE_FILE = "provenance.json"
// Relativ zur Repository-Wurzel: das Modul, das den Katalog ausliefert.
private const val ASSET_PFAD = "catalog/src/main/assets/cards"
private const val DB_PFAD = "catalog/src/main/assets/catalog.db"

/**
 * Einstiegspunkt des Import-Werkzeugs. T-010 (laden und prüfen), T-011 (abbilden).
 *
 * Der Bau von `catalog.db` ist `T-013` und passiert hier nicht.
 *
 * **Der Build lädt nichts aus dem Netz.** Quelldatei und Bilder liegen
 * versioniert im Repository. `images` ist wie `checksum` ein ausdrücklich
 * aufzurufender Schritt und läuft nie beiläufig mit.
 */
public fun main(args: Array<String>) {
    val befehl = args.getOrNull(0) ?: "validate"
    val verzeichnis = File(args.getOrNull(1) ?: "catalog-source")

    when (befehl) {
        "validate" -> validate(verzeichnis)
        "transform" -> transform(verzeichnis)
        "images" -> images(verzeichnis)
        "database" -> database(verzeichnis)
        "checksum" -> checksum(verzeichnis)
        else -> {
            System.err.println("Unbekannter Befehl '$befehl'. Erlaubt: validate, transform, images, database, checksum")
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

/**
 * Bezieht die Kartenbilder und prüft den Bestand vollständig. T-012.
 *
 * Der Ablauf ist bewusst so herum: Erst wird die Quelle geprüft und abgebildet,
 * dann wird für **jede** der 62 Identitäten abgerufen — auch nach Fehlern —,
 * und erst danach wird geurteilt. Ein Abbruch beim ersten 404 würde die Liste
 * der fehlenden Assets unvollständig machen, und genau die ist der Zweck.
 *
 * Gespeichert wird nur, was inhaltlich ein Bild ist. Eine Fehlerseite unter
 * `.jpg` sieht im Verzeichnis aus wie ein Bild und wäre später kaum zu finden.
 */
private fun images(verzeichnis: File) {
    val (wurzel, pruefsumme) = ladeUndPruefe(verzeichnis)
    val bericht = CatalogSourceValidator().validate(wurzel)
    if (!bericht.isValid) {
        println(bericht.render()); kotlin.system.exitProcess(1)
    }
    val ergebnis = IdentityMapper(SET_CODE, CATALOG_VERSION).map(
        wurzel, SOURCE_URL, pruefsumme, Instant.now().toString(),
    )
    if (!ergebnis.isValid) {
        println(ergebnis.render()); kotlin.system.exitProcess(1)
    }
    val identitaeten = ergebnis.data!!.identities

    // Der Untertyp steht in der Quelle groß geschrieben und wird im Bildpfad
    // genau so gebraucht — `identity.role` ist kleingeschrieben.
    val subtypeVon: (Identity) -> String = { i ->
        i.role.replaceFirstChar { c -> c.uppercase() }
    }

    val ziel = File(verzeichnis.parentFile, ASSET_PFAD).apply { mkdirs() }
    println("Beziehe ${identitaeten.size} Bilder nach ${ziel.path} …")

    val abrufe = ImageFetcher().holeAlle(
        identitaeten = identitaeten,
        subtypeVon = subtypeVon,
        speichere = { identity, bytes ->
            File(ziel, identity.imageAsset!!).writeBytes(bytes)
        },
        fortschritt = { n, gesamt -> if (n % 10 == 0 || n == gesamt) println("  $n/$gesamt") },
    )

    val pruefung = ImageAudit(identitaeten.size).pruefe(identitaeten, abrufe)
    println()
    println(pruefung.render())
    if (!pruefung.vollstaendig) kotlin.system.exitProcess(1)

    val gesamtBytes = pruefung.inOrdnung.sumOf { it.bytes.toLong() }
    println()
    println("Gesamtgröße der Assets: ${gesamtBytes / 1024} KiB — wandert unverändert als JPEG in die APK.")
}

/**
 * Baut `catalog.db`. T-013.
 *
 * Zweimal, und vergleicht die Prüfsummen: Reproduzierbarkeit ist eine Zusage,
 * die geprüft gehört. Schlägt der Vergleich fehl, ist das ein Befund und kein
 * Schönheitsfehler — dann rauscht bei jedem Import ein Binärunterschied durch
 * die Versionsverwaltung, ohne dass sich etwas geändert hätte.
 */
private fun database(verzeichnis: File) {
    val (wurzel, pruefsumme) = ladeUndPruefe(verzeichnis)
    val bericht = CatalogSourceValidator().validate(wurzel)
    if (!bericht.isValid) { println(bericht.render()); kotlin.system.exitProcess(1) }

    val ergebnis = IdentityMapper(SET_CODE, CATALOG_VERSION).map(
        wurzel, SOURCE_URL, pruefsumme, Instant.now().toString(),
    )
    if (!ergebnis.isValid) { println(ergebnis.render()); kotlin.system.exitProcess(1) }
    val daten = ergebnis.data!!

    val ziel = File(verzeichnis.parentFile, DB_PFAD)
    val db = CatalogDatabase()
    val wiederholbar = db.buildeZweimal(daten, ziel)

    println("catalog.db geschrieben: ${ziel.path}")
    println("  Identitäten: ${daten.identities.size} · Rulings: ${daten.rulings.size}")
    println("  Größe:       ${wiederholbar.bytes / 1024} KiB")
    println("  SHA-256:     ${wiederholbar.ersteSumme}")
    println()
    if (wiederholbar.bitgleich) {
        println("Reproduzierbar: zweimal gebaut, bit-gleiches Ergebnis.")
    } else {
        System.err.println("NICHT reproduzierbar — zwei Läufe, zwei Dateien:")
        System.err.println("  erster Lauf:  ${wiederholbar.ersteSumme}")
        System.err.println("  zweiter Lauf: ${wiederholbar.zweiteSumme}")
        System.err.println()
        System.err.println("Damit erzeugt jeder Import einen Binärunterschied, auch ohne inhaltliche")
        System.err.println("Änderung. Vor dem Einchecken klären, woher die Abweichung kommt.")
        kotlin.system.exitProcess(1)
    }
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
