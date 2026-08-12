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
        // Der TLS-Handshake mit einem echten Partie-Zertifikat ist nur auf einem
        // Gerät prüfbar (ADR-006/ADR-008) — dafür braucht dieses Modul erstmals
        // einen Runner.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    // Nur androidTest — landet nicht in der APK. Dieselben Koordinaten wie in
    // :catalog, :store und :ui; keine neue Version.
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
