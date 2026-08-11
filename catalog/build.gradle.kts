plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.library)
    // T-014: Room erzeugt seinen Code über KSP.
    alias(libs.plugins.ksp)
}

// Zugriff auf catalog.db und die Kartenbilder. Android wegen Room und Assets.

android {
    namespace = "de.myhornets.rise1.catalog"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        // T-014: Der Abnahmetest läuft instrumentiert, weil er das
        // AUSGELIEFERTE Asset öffnen muss. Aufruf:
        //   ./gradlew :catalog:connectedDebugAndroidTest
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

// T-014 — Schemaexport.
//
// Room schreibt sein erwartetes Schema als JSON nach `catalog/schemas/`. Darin
// steht der `identityHash`, den Room beim Öffnen einer mitgelieferten Datenbank
// gegen `room_master_table` prüft. Das Import-Werkzeug liest genau diese Datei
// und trägt Hash und Version in `catalog.db` nach (Aufgabe `roomStamp`).
//
// Der Export gehört ins Repository, nicht in `build/`: Er ist die Schnittstelle
// zwischen App-Build und Import-Werkzeug, und die beiden Builds kennen einander
// nicht (Modules.md, "Wo Build-Werkzeuge leben").
//
// Bewusst über das KSP-Argument statt über das Room-Gradle-Plugin: ein
// bewegliches Teil weniger beim ersten Build. Meldet Gradle hierzu eine
// Warnung zur Inkrementalität, ist das Room-Plugin
// (`androidx.room:androidx.room.gradle.plugin`) der vorgesehene Umstieg.
ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}

dependencies {
    // `api`, nicht `implementation` — und das ist keine Bequemlichkeit.
    //
    // `CatalogAsset.open()` gibt eine `CatalogDatabase` zurück, und die erbt von
    // `androidx.room.RoomDatabase`. Damit steht ein Room-Typ in der öffentlichen
    // Schnittstelle dieses Moduls. Unter `implementation` liegt `room-runtime`
    // nicht auf dem Compile-Klassenpfad der Aufrufer, und `:ui` scheitert mit
    //
    //     Cannot access 'RoomDatabase' which is a supertype of 'CatalogDatabase'.
    //
    // Genau dafür gibt es `api`: Was in der eigenen Schnittstelle auftaucht,
    // wird weitergereicht. An der APK ändert das nichts — `room-runtime` war
    // schon vorher im Laufzeitpfad; nur der Compile-Klassenpfad von `:ui` wird
    // um die Typen erweitert, die es ohnehin benutzt.
    //
    // Die engere Alternative wäre, `CatalogDatabase` gar nicht nach außen zu
    // geben und statt dessen nur `CatalogDao` — dann bliebe Room vollständig in
    // diesem Modul. Das wäre eine Änderung an der Schnittstelle und gehört
    // nicht in eine Fehlerbehebung; vermerkt als offener Punkt in T-014.
    api(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)

    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
