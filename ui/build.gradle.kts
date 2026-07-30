plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// :ui ist zugleich das Anwendungsmodul — siehe Modules.md, "Warum kein :app".
// Bewusst OHNE externe Abhängigkeiten (kein AppCompat, kein Compose): Solange
// es nichts anzuzeigen gibt, ist jede Bibliothek nur ein Versionsrisiko beim
// ersten Build. Kommt mit der Tischansicht in E10.

android {
    namespace = "de.myhornets.rise1"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.myhornets.rise1"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-T005"
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
