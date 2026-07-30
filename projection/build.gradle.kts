plugins {
    alias(libs.plugins.kotlin.jvm)
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
    implementation(project(":core"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}
