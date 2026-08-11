plugins {
    kotlin("jvm") version "2.2.20"
    application
}

// Abhängigkeiten nach der Regel aus T-013 (siehe Vault, Modules.md):
//
//   Build-Werkzeuge dürfen zusätzliche Gradle-Abhängigkeiten verwenden, wenn
//   sie reproduzierbare Artefakte erzeugen. Systemwerkzeuge mit lokaler
//   Installation werden vermieden. Für Laufzeit und APK bleibt es bei
//   Minimalismus — dieses Werkzeug wird nie ausgeliefert.
//
// Gson statt kotlinx.serialization: Letzteres verlangt zusätzlich ein
// Compiler-Plugin. Zwei bewegliche Teile statt einem.
//
// sqlite-jdbc statt des sqlite3-Programms: Ein Artefakt ist versionierbar und
// auf jedem Rechner gleich; ein lokal installiertes Programm ist es nicht.
// Aus demselben Grund wurde cwebp in T-012 abgelehnt.

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.xerial:sqlite-jdbc:3.50.1.0")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("de.myhornets.rise1.tools.catalog.MainKt")
}

// ─────────────────────────────────────────────────────────────────────────────
// Die Aufrufform von Main.kt
//
// Jeder Befehl nimmt **genau ein** Argument: das Verzeichnis mit der Quelldatei.
// Fehlt es, gilt `catalog-source`. Die Ziele leitet das Werkzeug daraus ab —
// es nimmt das **übergeordnete** Verzeichnis als Repository-Wurzel und hängt
// `catalog/src/main/assets/cards` beziehungsweise `catalog/src/main/assets/catalog.db`
// an. Deshalb steht in allen Aufgaben unten derselbe Pfad, und deshalb ist es
// derselbe wie bei `validate`.
//
// Befehle: validate · transform · images · database · checksum
//
// `transform` und `checksum` haben bewusst keine eigene Aufgabe: Der eine ist
// ein Bericht, der andere schreibt die Prüfsumme neu, wenn die Quelle bewusst
// erneuert wurde. Beide laufen über `./gradlew run --args="transform"` bzw.
// `--args="checksum"`.
// ─────────────────────────────────────────────────────────────────────────────

// Bequemer Einstiegspunkt: ./gradlew validate
tasks.register<JavaExec>("validate") {
    group = "verification"
    description = "Prüft Prüfsumme und Struktur der eingecheckten Treachery-Quelldatei."
    mainClass.set("de.myhornets.rise1.tools.catalog.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Zwei Ebenen hoch: tools/catalog-import -> tools -> Repository-Wurzel.
    args = listOf("validate", file("../../catalog-source").absolutePath)
}

// T-012 — ./gradlew images
//
// Bezieht die 62 Kartenbilder und legt sie unter `catalog/src/main/assets/cards`
// ab. Ein ausdrücklicher Schritt, kein Build-Schritt — dieselbe Regel wie für
// die Quelldatei aus T-010. Er läuft nie beiläufig mit.
//
// Nachgetragen am 2026-07-30: `images` und `database` waren bisher nur über
// `./gradlew run --args="…"` erreichbar, während `validate` eine eigene Aufgabe
// hatte. Die Dokumentation nannte sie trotzdem als Aufgaben — ein Widerspruch
// zum Code, der hier aufgelöst wird, statt ihn in der Dokumentation zu glätten.
tasks.register<JavaExec>("images") {
    group = "build"
    description = "Bezieht die Kartenbilder nach catalog/src/main/assets/cards (T-012)."
    mainClass.set("de.myhornets.rise1.tools.catalog.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("images", file("../../catalog-source").absolutePath)
}

// T-013 — ./gradlew database
//
// Baut `catalog/src/main/assets/catalog.db` aus der geprüften Quelldatei und
// belegt bei jedem Lauf ihre Reproduzierbarkeit: zweimal gebaut, Prüfsummen
// verglichen.
tasks.register<JavaExec>("database") {
    group = "build"
    description = "Baut catalog.db aus der Quelldatei, reproduzierbar geprüft (T-013)."
    mainClass.set("de.myhornets.rise1.tools.catalog.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("database", file("../../catalog-source").absolutePath)
}

// T-015 — ./gradlew keywords
//
// Füllt `identity_keyword` aus `text_raw`. Läuft NACH `database` und VOR
// `:catalog:assembleDebug`, weil es das Schema ändert — und ein geändertes
// Schema ist ein anderer identityHash.
tasks.register<JavaExec>("keywords") {
    group = "build"
    description = "Füllt identity_keyword aus text_raw (T-015)."
    mainClass.set("de.myhornets.rise1.tools.catalog.KeywordIndex")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(file("../../catalog/src/main/assets/catalog.db").absolutePath)
}

// T-014 — ./gradlew roomStamp
//
// Trägt identityHash und user_version aus dem von KSP exportierten Room-Schema
// in die ausgelieferte catalog.db nach. Läuft NACH `database` und NACH einem
// Übersetzen von :catalog — vorher gibt es den Export nicht.
//
// Der Pfad zum Export kommt als Argument, nicht über eine Projektabhängigkeit:
// Dieser Build kennt die Projektwurzel nicht und soll sie nicht kennen
// (Modules.md, "Wo Build-Werkzeuge leben"). Er liest hier eine Datei, wie
// `validate` die Quelldatei liest.
tasks.register<JavaExec>("roomStamp") {
    group = "build"
    description = "Trägt identityHash und user_version aus dem Room-Schemaexport in catalog.db nach (T-014)."
    mainClass.set("de.myhornets.rise1.tools.catalog.RoomStamp")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(
        file("../../catalog/schemas/de.myhornets.rise1.catalog.CatalogDatabase/1.json").absolutePath,
        file("../../catalog/src/main/assets/catalog.db").absolutePath,
    )
}
