package de.myhornets.rise1.tools.catalog

/**
 * Das Zielschema aus TDD 4.1, als reine Datenklassen. T-011.
 *
 * Diese Datei beschreibt, was in `catalog.db` landet — sie erzeugt die Datenbank
 * nicht. Das ist `T-013`. Die Trennung hält den Mapper prüfbar, ohne dass ein
 * SQLite-Treiber im Werkzeug liegt.
 *
 * Keine Abhängigkeiten, wie schon beim Validator: Die Testfälle bauen Eingaben
 * von Hand und vergleichen Werte, ohne Datei, Parser oder Netz.
 */

/** Vier Rollen, geschlossene Menge. In der Quelle `types.subtype`. */
public enum class Role {
    LEADER, GUARDIAN, ASSASSIN, TRAITOR;

    /** Der Wert, wie er in `identity.role` steht — Kleinschreibung nach TDD 4.1. */
    public val key: String get() = name.lowercase()

    public companion object {
        public fun fromSubtype(subtype: String): Role? =
            entries.firstOrNull { it.name.equals(subtype, ignoreCase = true) }
    }
}

/** Eine Zeile in `identity`. Feldnamen und Reihenfolge folgen TDD 4.1. */
public data class Identity(
    val identityUid: String,
    val setCode: String,
    val cardNumber: Int,
    val name: String,
    val slugAscii: String,
    val role: String,
    val color: String,
    val typeLine: String,
    val rarity: String,
    val textRaw: String,
    val flavor: String?,
    val artist: String,
    /**
     * Der Ausdruck hinter `Unveil`, roh übernommen — `{3}`, `{X}{X}{2}` oder
     * `Discard a nonland card.`
     *
     * **Nullable**, und das ist eine bewusste Abweichung vom TDD-Stand vom
     * 2026-07-29: Leader liegen von Beginn an offen und haben gar keinen
     * Unveil-Cost. Siehe Task-Notiz zu T-011.
     */
    val unveilCost: String?,
    /** Wird erst in `T-012` gefüllt; hier immer null. */
    val imageAsset: String?,
    val sourceUri: String,
)

/** Eine Zeile in `identity_ruling`. */
public data class IdentityRuling(
    val rulingUid: String,
    val identityUid: String,
    val ordinal: Int,
    val text: String,
)

/** Die eine Zeile in `card_set`. */
public data class CardSet(
    val setCode: String,
    val name: String,
    val lang: String,
    val cardCount: Int,
    val releaseYear: Int?,
    val isOfficial: Boolean,
)

/** Die eine Zeile in `catalog_meta`. */
public data class CatalogMeta(
    val catalogVersion: String,
    val sourceApiVersion: String,
    val sourceSetCode: String,
    val sourceUrl: String,
    val sourceChecksum: String,
    val importedAt: String,
)

/** Das vollständige Ergebnis der Transformation. */
public data class CatalogData(
    val meta: CatalogMeta,
    val cardSet: CardSet,
    val identities: List<Identity>,
    val rulings: List<IdentityRuling>,
)
