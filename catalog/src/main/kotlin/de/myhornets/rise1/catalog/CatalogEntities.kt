package de.myhornets.rise1.catalog

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// T-014 — Room-Entities auf das bestehende Schema aus T-013.
//
// Diese Datei legt KEIN Schema fest. Sie bildet das Schema ab, das das
// Import-Werkzeug in `catalog.db` schreibt (tools/catalog-import,
// CatalogDatabase.kt). Maßgeblich ist die ausgelieferte Datei; weicht eine
// Entity davon ab, scheitert Room beim Öffnen — und zwar beim Abnahmetest,
// nicht beim Nutzer.
//
// Architekturprinzip (README): Kartendaten werden beschrieben, nicht gedeutet.
// Jede Spalte hier hält fest, was auf der Karte steht. Es gibt kein Feld, das
// eine Regel auswertet, und es soll keines geben.
//
// Zwei Dinge, die bewusst NICHT hier stehen:
//
//   Kein Fremdschlüssel von `identity_ruling` auf `identity`. Das gelieferte
//   Schema hat keinen, und Room vergleicht Fremdschlüssel beim Öffnen mit.
//   Die Zuordnung stellt das Import-Werkzeug sicher, nicht die Datenbank.
//
//   Keine FTS-Tabelle. Sie wäre eine zusätzliche Tabelle im Schema und würde
//   die ausgelieferte Datei ungültig machen. Volltext läuft über LIKE, siehe
//   CatalogDao.

/**
 * Eine der 62 Identitäten. Tabelle `identity`.
 *
 * `flavor`, `unveil_cost` und `image_asset` sind nullbar, weil das gelieferte
 * Schema sie nullbar deklariert. Bei `unveil_cost` ist das inhaltlich belegt:
 * die 13 Leader haben keinen (T-011).
 */
@Entity(
    tableName = "identity",
    indices = [
        Index(value = ["role"], name = "index_identity_role"),
        Index(value = ["color"], name = "index_identity_color"),
        Index(value = ["set_code"], name = "index_identity_set_code"),
        Index(value = ["slug_ascii"], name = "index_identity_slug_ascii", unique = true),
    ],
)
data class IdentityEntity(
    @PrimaryKey
    @ColumnInfo(name = "identity_uid") val identityUid: String,
    @ColumnInfo(name = "set_code") val setCode: String,
    @ColumnInfo(name = "card_number") val cardNumber: Int,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "slug_ascii") val slugAscii: String,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "color") val color: String,
    @ColumnInfo(name = "type_line") val typeLine: String,
    @ColumnInfo(name = "rarity") val rarity: String,
    @ColumnInfo(name = "text_raw") val textRaw: String,
    @ColumnInfo(name = "flavor") val flavor: String?,
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "unveil_cost") val unveilCost: String?,
    @ColumnInfo(name = "image_asset") val imageAsset: String?,
    @ColumnInfo(name = "source_uri") val sourceUri: String,
)

/** Ein Ruling zu einer Identität. Tabelle `identity_ruling`. */
@Entity(
    tableName = "identity_ruling",
    indices = [
        Index(value = ["identity_uid"], name = "index_identity_ruling_identity_uid"),
    ],
)
data class IdentityRulingEntity(
    @PrimaryKey
    @ColumnInfo(name = "ruling_uid") val rulingUid: String,
    @ColumnInfo(name = "identity_uid") val identityUid: String,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "text") val text: String,
)

/**
 * T-015 — eine Filtermarke an einer Identität. Tabelle `identity_keyword`.
 *
 * Hält fest, **dass** auf der Karte eine Schlüsselwortzeile steht — nicht, was
 * das Schlüsselwort bewirkt. Damit ist „alle Undercover-Identitäten" eine
 * Abfrage und keine Regelauswertung ([[Cards]]).
 *
 * Zusammengesetzter Primärschlüssel nach TDD 4.1. Kein Fremdschlüssel auf
 * `identity`, aus demselben Grund wie bei `identity_ruling`: Das gelieferte
 * Schema hat keinen, und Room vergleicht Fremdschlüssel beim Öffnen mit.
 */
@Entity(
    tableName = "identity_keyword",
    primaryKeys = ["identity_uid", "keyword"],
    indices = [
        Index(value = ["keyword"], name = "index_identity_keyword_keyword"),
    ],
)
data class IdentityKeywordEntity(
    @ColumnInfo(name = "identity_uid") val identityUid: String,
    @ColumnInfo(name = "keyword") val keyword: String,
)

/** Das Kartenset, aus dem der Katalog stammt. Tabelle `card_set`. */
@Entity(tableName = "card_set")
data class CardSetEntity(
    @PrimaryKey
    @ColumnInfo(name = "set_code") val setCode: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "lang") val lang: String,
    @ColumnInfo(name = "card_count") val cardCount: Int,
    @ColumnInfo(name = "release_year") val releaseYear: Int?,
    @ColumnInfo(name = "is_official") val isOfficial: Boolean,
)

/**
 * Herkunftsnachweis des Katalogs. Tabelle `catalog_meta`, genau eine Zeile
 * mit `id = 1`.
 *
 * `source_checksum` ist die SHA-256 der Quelldatei aus T-010 — damit lässt
 * sich zur Laufzeit belegen, aus welcher Quelle die ausgelieferte Datenbank
 * entstanden ist.
 */
@Entity(tableName = "catalog_meta")
data class CatalogMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: Int,
    @ColumnInfo(name = "catalog_version") val catalogVersion: String,
    @ColumnInfo(name = "source_api_version") val sourceApiVersion: String,
    @ColumnInfo(name = "source_set_code") val sourceSetCode: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String,
    @ColumnInfo(name = "source_checksum") val sourceChecksum: String,
    @ColumnInfo(name = "imported_at") val importedAt: String,
    @ColumnInfo(name = "sqlite_version") val sqliteVersion: String,
)
