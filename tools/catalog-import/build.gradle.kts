plugins {
    kotlin("jvm") version "2.2.20"
    application
}

// Abhängigkeiten nach den Vorgaben zu T-010:
//
//   minimale Abhängigkeit  — genau EIN Artefakt, und das ohne transitive
//                            Abhängigkeiten. Die Validierungslogik selbst hat
//                            NULL Abhängigkeiten und kennt kein JSON.
//   nur für das Werkzeug   — eigenständiger Build, eigener Abhängigkeitsgraph
//   keine Auswirkung       — die App-Module sehen davon strukturell nichts
//
// Warum Gson und nicht kotlinx.serialization: Letzteres verlangt zusätzlich
// ein Compiler-Plugin. Zwei bewegliche Teile statt einem, für einen Nutzen,
// den wir hier nicht brauchen — siehe JsonAdapter.

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")

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

// Bequemer Einstiegspunkt: gradle -p tools/catalog-import validate
tasks.register<JavaExec>("validate") {
    group = "verification"
    description = "Prüft Prüfsumme und Struktur der eingecheckten Treachery-Quelldatei."
    mainClass.set("de.myhornets.rise1.tools.catalog.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
    // Zwei Ebenen hoch: tools/catalog-import -> tools -> Repository-Wurzel.
    args = listOf("validate", file("../../catalog-source").absolutePath)
}
