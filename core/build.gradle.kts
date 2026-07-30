plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Domänenmodell, Event-Erzeugung, Protokollierung. Rein JVM — schnell testbar ohne Emulator (Testing.md).

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
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}
