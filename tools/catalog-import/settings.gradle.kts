// T-010 — eigenständiger Build.
//
// Dieses Werkzeug ist bewusst NICHT Teil des App-Builds: Es erscheint in keinem
// `include` der Projektwurzel, taucht damit nicht in `subprojects` auf und
// berührt `verifyModuleBoundaries` (T-003) nicht. Die Regel "genau neun Module"
// aus TDD 2.2 bleibt unangetastet, weil dieses Werkzeug kein App-Modul ist —
// es läuft zur Build-Zeit und wird nie ausgeliefert.
//
// Aufruf ausdrücklich und getrennt:
//   cd tools/catalog-import && ./gradlew validate

rootProject.name = "catalog-import"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
