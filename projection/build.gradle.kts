plugins {
    alias(libs.plugins.kotlin.jvm)
    // T-025d: ausdrücklich, damit die `api`-Konfiguration unten sicher
    // existiert. Ob das Kotlin-JVM-Plugin sie ohnehin anlegt, hängt von seiner
    // Version ab — und eine Annahme über eine ungeprüfte Version ist genau das,
    // was dieses Projekt nicht macht.
    `java-library`
}

// Faltet Events zum Anzeigezustand. Rein JVM, damit Projektionstests ohne Android laufen.

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // `api`, nicht `implementation`: `MatchFold.falte` nimmt einen `MatchEvent`
    // aus `:core` entgegen — der Typ steht in der öffentlichen Signatur. Mit
    // `implementation` bekäme jeder Aufrufer denselben Fehler, den T-014 im
    // Katalog erzeugt hat: „Cannot access 'MatchEvent'".
    api(project(":core"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}
