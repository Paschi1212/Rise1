plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Zwei-Parteien-Verfahren, Commitments, Öffnen. Inhalt kommt in E09.

android {
    namespace = "de.myhornets.rise1.deal"
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
    implementation(project(":crypto"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}
