plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    // Room über KSP — wie in :catalog.
    alias(libs.plugins.ksp)
}

// ADR-003 — dieses Modul besitzt `rise.db`.
//
// Gegenstück zu :catalog: Dort liegt die read-only ausgelieferte Katalog-
// Datenbank, hier die migrierte Nutzer- und Partiedatenbank (TDD 3.1).
//
// Keine Abhängigkeit auf ein Projektmodul, und das ist Absicht: Eine Ablage ist
// kein Mitspieler. Bräuchte sie :core, wäre entweder Domänenlogik hierher
// gewandert oder Ablagewissen dorthin.

android {
    namespace = "de.myhornets.rise1.store"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // T-025b: `MigrationTestHelper` legt eine Datenbank in einer alten Version
    // an und prüft nach der Migration gegen den exportierten Schemastand. Dazu
    // muss der Export dem Testlauf auf dem Gerät als Asset vorliegen — sonst
    // fände der Helfer nichts und der Test wäre grün, ohne etwas verglichen zu
    // haben.
    sourceSets["androidTest"].assets.srcDir("${projectDir}/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Schemaexport ins Repository — anders als bei :catalog nicht, weil ein Werkzeug
// ihn liest, sondern weil `rise.db` **migriert** wird (TDD 3.1). Room vergleicht
// beim Schreiben einer Migration den alten mit dem neuen Stand; ohne die
// exportierten Fassungen früherer Versionen lässt sich keine Migration prüfen.
//
// Er ist damit Teil des Quellstands, nicht Build-Ausgabe.
ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}

dependencies {
    // `api`, nicht `implementation`: Die Datenbankklasse erbt von RoomDatabase
    // und steht damit in der öffentlichen Schnittstelle — dieselbe Lehre wie in
    // :catalog, wo `implementation` den ersten Übersetzungsfehler des Projekts
    // erzeugt hat.
    api(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // T-025b — nur für den Migrationsnachweis. Landet nicht in der APK.
    // UNGEPRÜFT wie alle Versionsannahmen: dieselbe Version wie room-runtime.
    androidTestImplementation(libs.room.testing)
}
