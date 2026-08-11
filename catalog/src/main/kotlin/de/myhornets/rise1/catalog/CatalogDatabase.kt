package de.myhornets.rise1.catalog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// T-014 — Room-Anbindung an die ausgelieferte `catalog.db`.
//
// `catalog.db` ist read-only und wird bei einem neuen Kartenset komplett
// ersetzt (Database.md). Es gibt deshalb keine Migrationen und es soll keine
// geben: Eine Schemaabweichung ist kein Fall für eine Migration, sondern ein
// Befund — die ausgelieferte Datei passt dann nicht zum Code.
//
// Genau darum steht hier auch KEIN `fallbackToDestructiveMigration()`. Es
// würde die Datei bei jeder Abweichung stillschweigend wegwerfen und den
// Katalog leer zurücklassen. Ein lauter Fehlschlag beim Öffnen ist das, was
// wir wollen — er fällt im Abnahmetest auf, nicht beim Nutzer.

/**
 * Version des Katalogschemas.
 *
 * Maßgeblich ist der exportierte Room-Schemastand unter `catalog/schemas/`.
 * Diese Zahl und die dort exportierte `version` müssen übereinstimmen; das
 * Werkzeug `roomStamp` liest die Version aus dem Export und schreibt sie als
 * `PRAGMA user_version` in die ausgelieferte Datei.
 */
const val CATALOG_SCHEMA_VERSION = 1

@Database(
    entities = [
        IdentityEntity::class,
        IdentityRulingEntity::class,
        IdentityKeywordEntity::class,
        CardSetEntity::class,
        CatalogMetaEntity::class,
    ],
    version = CATALOG_SCHEMA_VERSION,
    exportSchema = true,
)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
}

/**
 * Öffnet die **ausgelieferte** Katalogdatenbank aus dem Asset.
 *
 * Es gibt bewusst keinen zweiten Weg, an diese Datenbank zu kommen. Eine zur
 * Laufzeit erzeugte Datenbank würde in Tests grün leuchten und über das Asset
 * nichts belegen — der Punkt aus dem Validierungsblock zu T-014.
 */
object CatalogAsset {

    /** Dateiname im APK unter `assets/`. */
    const val ASSET_NAME = "catalog.db"

    /** Dateiname der Kopie im App-Datenverzeichnis. */
    const val LOCAL_NAME = "catalog.db"

    fun open(context: Context): CatalogDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            CatalogDatabase::class.java,
            LOCAL_NAME,
        )
            .createFromAsset(ASSET_NAME)
            .build()
}
