// T-001 Wurzel-Build · T-003 Architektur-Fitnesstest

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

// ─────────────────────────────────────────────────────────────────────────────
// T-003 — Architektur-Fitnesstest für die Modulgrenzen
//
// Architekturbezug: TDD 2.2 (Modulschnitt) und 7.4 (was der Host weiß).
//
// Die entscheidende Regel: Das Modul :host darf NIE Zugriff auf :deal oder
// :crypto bekommen. Ohne diese Module besitzt es keine Schlüssel, mit denen
// sich fremde Identitäten öffnen ließen — und genau darauf beruht die
// Kernzusage des Projekts.
//
// Bewusst als Positivliste, nicht als Verbotsliste: Eine Verbotsliste kennt
// nur die Fehler, die jemand vorhergesehen hat. Die Positivliste schlägt bei
// JEDER nicht vorgesehenen Kante fehl.
// ─────────────────────────────────────────────────────────────────────────────

val allowedModuleEdges: Map<String, Set<String>> = mapOf(
    "core" to emptySet(),
    "crypto" to emptySet(),
    "catalog" to emptySet(),
    "transport" to emptySet(),
    "projection" to setOf("core"),
    "deal" to setOf("core", "crypto"),
    "session" to setOf("core", "crypto", "transport"),
    // :host bewusst OHNE "deal" und OHNE "crypto"
    "host" to setOf("core", "transport"),
    "ui" to setOf(
        "core", "projection", "catalog", "crypto",
        "deal", "transport", "session", "host",
    ),
)

val declaredModuleEdges = mutableMapOf<String, Set<String>>()

gradle.projectsEvaluated {
    subprojects.forEach { sub ->
        val deps = mutableSetOf<String>()
        sub.configurations.forEach { configuration ->
            configuration.dependencies
                .withType(ProjectDependency::class.java)
                .forEach { dep -> deps += dep.path.trimStart(':') }
        }
        declaredModuleEdges[sub.name] = deps
    }
}

tasks.register("verifyModuleBoundaries") {
    group = "verification"
    description = "Prüft die Modulgrenzen aus TDD 2.2 — insbesondere, dass :host keinen Zugriff auf :deal oder :crypto hat."

    doLast {
        val problems = mutableListOf<String>()

        val unknown = declaredModuleEdges.keys - allowedModuleEdges.keys
        if (unknown.isNotEmpty()) {
            problems += "Unbekannte Module ohne Regel: ${unknown.sorted()}. " +
                "TDD 2.2 nennt genau neun Module — ein zehntes braucht eine ADR, keine stille Ergänzung."
        }

        allowedModuleEdges.forEach { (module, allowed) ->
            val declared = declaredModuleEdges[module] ?: return@forEach
            val violations = declared - allowed
            if (violations.isNotEmpty()) {
                problems += buildString {
                    append(":$module darf nicht auf ${violations.sorted().map { ":$it" }} zugreifen. ")
                    append("Erlaubt sind ausschließlich ${allowed.sorted().map { ":$it" }}.")
                    if (module == "host" && violations.any { it == "deal" || it == "crypto" }) {
                        append("\n    ")
                        append("Das ist die zentrale Grenze des Sicherheitsmodells: Der Host ist Infrastruktur ")
                        append("ohne Einblick (TDD 7.4). Bekommt :host Zugang zu :crypto oder :deal, besitzt er ")
                        append("die Schlüssel, mit denen fremde Identitäten zu öffnen wären. Diese Grenze wird ")
                        append("nicht umgangen — wenn sie im Weg steht, ist der Entwurf falsch verstanden worden.")
                    }
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "Verletzung der Modulgrenzen (TDD 2.2):\n\n" +
                    problems.joinToString("\n\n") { "  - $it" } +
                    "\n\nRegel und Begründung: Obsidian-Vault, 03_Implementation/Modules.md\n"
            )
        }

        logger.lifecycle("Modulgrenzen in Ordnung: ${declaredModuleEdges.size} Module geprüft.")
    }
}

// Zykelfreiheit prüft Gradle selbst — eine zyklische Projektabhängigkeit
// lässt die Konfiguration bereits fehlschlagen.

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("verifyModuleBoundaries"))
    }
}

tasks.register("checkAll") {
    group = "verification"
    description = "Führt verifyModuleBoundaries und alle Modul-Checks aus."
    dependsOn(tasks.named("verifyModuleBoundaries"))
    dependsOn(subprojects.map { "${it.path}:check" })
}
