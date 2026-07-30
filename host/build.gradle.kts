plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Reihenfolge, Log-Speicherung, Zustellung. KEINE Abhängigkeit auf :crypto oder :deal — TDD 7.4, erzwungen durch T-003.

android {
    namespace = "de.myhornets.rise1.host"
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
    implementation(project(":core"))
    implementation(project(":transport"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}
