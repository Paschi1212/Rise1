package de.myhornets.rise1.catalog

import androidx.room.Dao
import androidx.room.Query

// T-014 — Abfragen für Anzeige, Suche und Filter.
//
// Die Grenze aus dem README gilt hier wörtlich: Jede Abfrage beantwortet eine
// Frage über die KARTE, keine über das SPIEL. `byRole` filtert nach der Rolle,
// die auf der Karte steht. Eine Abfrage wie "welche Identitäten kann Spieler X
// jetzt aufdecken" wäre eine Spielfrage und gehört nicht hierher — sie hätte
// auch keinen Ort, an dem sie doch hingehörte.
//
// Volltext läuft über LIKE, nicht über FTS. Eine FTS-Tabelle wäre eine
// zusätzliche Tabelle im Schema und würde die ausgelieferte `catalog.db`
// ungültig machen. Bei 62 Zeilen ist LIKE ohnehin nicht der Engpass.

/** Die vier Rollen aus TDD 8.2. Verteilung im Pool: 13/18/18/13. */
object CatalogRole {
    const val LEADER = "leader"
    const val GUARDIAN = "guardian"
    const val ASSASSIN = "assassin"
    const val TRAITOR = "traitor"
}

/**
 * T-015 — die Filtermarken, die im Katalog vorkommen.
 *
 * Kleingeschrieben wie in der Datenbank. Die Menge ist im Import-Werkzeug als
 * festes Vokabular hinterlegt (`KeywordIndex`); kommt in einem späteren
 * Kartensatz etwas Neues dazu, bricht der Import ab, statt es still zu
 * übergehen.
 *
 * Für die Anzeige gehören die Beschriftungen nach `:ui` — hier steht der
 * Schlüssel, nicht sein Text.
 */
object CatalogKeyword {
    const val UNDERCOVER = "undercover"
    const val UNVEIL = "unveil"
}

@Dao
interface CatalogDao {

    // ── Anzeige ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM identity ORDER BY card_number")
    fun alleIdentitaeten(): List<IdentityEntity>

    @Query("SELECT * FROM identity WHERE identity_uid = :identityUid")
    fun identitaet(identityUid: String): IdentityEntity?

    @Query("SELECT * FROM identity WHERE slug_ascii = :slugAscii")
    fun identitaetNachSlug(slugAscii: String): IdentityEntity?

    @Query("SELECT * FROM identity_ruling WHERE identity_uid = :identityUid ORDER BY ordinal")
    fun rulings(identityUid: String): List<IdentityRulingEntity>

    // ── Filter ───────────────────────────────────────────────────────────────

    @Query("SELECT * FROM identity WHERE role = :role ORDER BY card_number")
    fun nachRolle(role: String): List<IdentityEntity>

    @Query("SELECT * FROM identity WHERE color = :color ORDER BY card_number")
    fun nachFarbe(color: String): List<IdentityEntity>

    /**
     * T-015 — Filter über die Schlüsselwortmarke.
     *
     * Beantwortet „welche Karten tragen dieses Schlüsselwort", nicht „was tut
     * das Schlüsselwort". Der Unterschied ist der ganze Punkt der Tabelle.
     */
    @Query(
        """
        SELECT i.* FROM identity i
        JOIN identity_keyword k ON k.identity_uid = i.identity_uid
        WHERE k.keyword = :keyword
        ORDER BY i.card_number
        """,
    )
    fun nachSchluesselwort(keyword: String): List<IdentityEntity>

    /** Die Schlüsselwörter einer Identität, alphabetisch. */
    @Query("SELECT keyword FROM identity_keyword WHERE identity_uid = :identityUid ORDER BY keyword")
    fun schluesselwoerter(identityUid: String): List<String>

    /** Alle im Katalog vorkommenden Schlüsselwörter — die Menge für die Filterleiste. */
    @Query("SELECT DISTINCT keyword FROM identity_keyword ORDER BY keyword")
    fun alleSchluesselwoerter(): List<String>

    // ── Suche ────────────────────────────────────────────────────────────────

    /**
     * Sucht in Name, ASCII-Slug und Regeltext.
     *
     * Der Slug ist mitgesucht, weil er die Akzente auflöst: Wer `aetherist`
     * tippt, findet `The Ætherist`. Genau dafür ist er in T-011 entstanden.
     */
    @Query(
        """
        SELECT * FROM identity
        WHERE name LIKE '%' || :suchtext || '%'
           OR slug_ascii LIKE '%' || :suchtext || '%'
           OR text_raw LIKE '%' || :suchtext || '%'
        ORDER BY card_number
        """,
    )
    fun suche(suchtext: String): List<IdentityEntity>

    // ── Zählungen und Herkunft ───────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM identity")
    fun anzahlIdentitaeten(): Int

    @Query("SELECT COUNT(*) FROM identity WHERE role = :role")
    fun anzahlNachRolle(role: String): Int

    @Query("SELECT COUNT(*) FROM identity_ruling")
    fun anzahlRulings(): Int

    @Query("SELECT COUNT(*) FROM identity WHERE image_asset IS NULL")
    fun anzahlOhneBild(): Int

    @Query("SELECT COUNT(*) FROM identity_keyword")
    fun anzahlSchluesselwortPaare(): Int

    @Query("SELECT COUNT(*) FROM identity_keyword WHERE keyword = :keyword")
    fun anzahlMitSchluesselwort(keyword: String): Int

    @Query("SELECT * FROM catalog_meta WHERE id = 1")
    fun herkunft(): CatalogMetaEntity?

    @Query("SELECT * FROM card_set")
    fun kartensets(): List<CardSetEntity>
}
