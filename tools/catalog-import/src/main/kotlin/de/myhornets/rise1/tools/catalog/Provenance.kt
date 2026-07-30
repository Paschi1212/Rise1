package de.myhornets.rise1.tools.catalog

/**
 * Herkunftsnachweis der geprüften Quelle. T-010.
 *
 * Wird nach erfolgreicher Prüfung geschrieben und in T-013 nach
 * `catalog_meta` übernommen (TDD 4.1).
 */
public data class Provenance(
    val sourceUrl: String,
    val sourceFile: String,
    val sha256: String,
    val apiVersion: String,
    val apiAuthor: String,
    val setCode: String,
    val setLang: String,
    val cardsCount: Int,
    val validatedAt: String,
    val toolVersion: String,
)
