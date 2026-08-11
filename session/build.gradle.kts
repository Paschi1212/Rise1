plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
}

// Beitritt, Heartbeat, Wiedereinstieg. Inhalt kommt in E08.

android {
    namespace = "de.myhornets.rise1.session"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // `api`, nicht `implementation`: `FingerabdruckPruefer` nimmt einen
    // `Fingerabdruck` aus `:core` entgegen und `Sitzungsverbindung` einen
    // `Transport` samt `Rahmen` aus `:transport` — beide stehen in öffentlichen
    // Signaturen. Mit `implementation` bekäme jeder Aufrufer denselben Fehler,
    // den T-014 im Katalog erzeugt hat: „Cannot access ...".
    api(project(":core"))
    api(project(":transport"))

    implementation(project(":crypto"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}
