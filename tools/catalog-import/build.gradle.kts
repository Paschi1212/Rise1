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

// Bequemer Einstiegspunkt: ./gradlew validate
tasks.register<JavaExec>("validate") {
    group = "verification"
    description = "Prüft Prüfsumme und Struktur der eingecheckten Treachery-Quelldatei."
    mainClass.set("de.myhornets.rise1.tools.catalog.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Zwei Ebenen hoch: tools/catalog-import -> tools -> Repository-Wurzel.
    args = listOf("validate", file("../../catalog-source").absolutePath)
}
