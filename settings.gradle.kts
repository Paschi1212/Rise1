// T-001 / T-002 — Projektwurzel und Modulschnitt nach TDD 2.2

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rise1"

// Die neun Module aus TDD 2.2. Keine weiteren.
// ":ui" ist zugleich das Anwendungsmodul — siehe Modules.md, Abschnitt "Warum kein :app".
include(":core")
include(":projection")
include(":catalog")
include(":crypto")
include(":deal")
include(":transport")
include(":session")
include(":host")
include(":ui")
