plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// :ui ist zugleich das Anwendungsmodul — siehe Modules.md, "Warum kein :app".
//
// D-004 (2026-07-30): Jetpack Compose ist die UI-Grundlage. Bis dahin war dieses
// Modul bewusst ohne jede UI-Bibliothek gebaut — solange nichts anzuzeigen war,
// wäre jede nur ein Versionsrisiko gewesen. Mit T-017 gibt es etwas anzuzeigen.
// Kein AppCompat: Compose braucht es nicht, ComponentActivity genügt.

android {
    namespace = "de.myhornets.rise1"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.myhornets.rise1"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        // Die EINZIGE Stelle, an der der Stand steht. Die Statusansicht liest
        // ihn über BuildConfig — damit können Anzeige und Build nicht mehr
        // auseinanderlaufen, wie es zwischen T-005 und T-011 passiert ist.
        versionName = "0.2.0-T011"
    }

    buildFeatures {
        // Für BuildConfig.VERSION_NAME in der Statusansicht. Ab AGP 8 muss das
        // ausdrücklich eingeschaltet werden.
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
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
    // Die BOM legt die Versionen aller Compose-Artefakte fest — siehe D-004.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(project(":core"))
    implementation(project(":projection"))
    implementation(project(":catalog"))
    implementation(project(":crypto"))
    implementation(project(":deal"))
    implementation(project(":transport"))
    implementation(project(":session"))
    implementation(project(":host"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}
