package de.myhornets.rise1.tools.catalog

/**
 * Bildet die geprüfte Quelle auf das Zielschema aus TDD 4.1 ab. T-011.
 *
 * **Setzt eine gültige Quelle voraus.** [CatalogSourceValidator] hat vorher
 * bestätigt, dass 62 Karten mit lückenlosen IDs und vollständigen Pflichtfeldern
 * vorliegen. Dieser Mapper prüft deshalb nicht noch einmal dasselbe — er meldet
 * nur, was **erst beim Abbilden** auffallen kann: doppelte Slugs und
 * unbekannte Rollen.
 *
 * Wie der Validator kennt er kein JSON und hat keine Abhängigkeiten.
 */
public class IdentityMapper(
    private val setCode: String,
    private val katalogVersion: String,
) {

    public fun map(
        wurzel: Map<String, Any?>,
        sourceUrl: String,
        sourceChecksum: String,
        importedAt: String,
    ): MappingResult {
        val befunde = mutableListOf<Finding>()
        val karten = wurzel["cards"] as? List<*> ?: return MappingResult(null, listOf(
            Finding.Struktur("'cards' fehlt oder ist keine Liste — der Mapper erwartet eine geprüfte Quelle.")
        ))

        val identitaeten = mutableListOf<Identity>()
        val rulings = mutableListOf<IdentityRuling>()

        karten.forEach { roh ->
            val karte = roh as? Map<*, *> ?: return@forEach
            val id = (karte["id"] as? Number)?.toInt() ?: return@forEach
            val name = karte["name"] as? String ?: return@forEach

            val subtype = (karte["types"] as? Map<*, *>)?.get("subtype") as? String
            val rolle = subtype?.let { Role.fromSubtype(it) }
            if (rolle == null) {
                befunde += Finding.Wert("Karte $id ($name): unbekannte Rolle '$subtype'")
                return@forEach
            }

            val identityUid = uid(id)
            val textRaw = karte["text"] as? String ?: ""
            val flavor = (karte["flavor"] as? String)?.trim()

            identitaeten += Identity(
                identityUid = identityUid,
                setCode = setCode,
                cardNumber = id,
                name = name,
                slugAscii = SlugAscii.of(name),
                role = rolle.key,
                color = karte["color"] as? String ?: "",
                typeLine = karte["type"] as? String ?: "",
                rarity = karte["rarity"] as? String ?: "",
                textRaw = textRaw,
                // Leerer Flavour-Text ist in der Quelle die Regel, nicht die
                // Ausnahme. Er wird zu null, damit "kein Flavour" und
                // "Flavour ist leer" nicht zwei Zustände sind.
                flavor = flavor?.ifEmpty { null },
                artist = karte["artist"] as? String ?: "",
                unveilCost = UnveilCost.of(textRaw),
                imageAsset = null, // T-012
                sourceUri = karte["uri"] as? String ?: "",
            )

            (karte["rulings"] as? List<*>).orEmpty().forEachIndexed { i, r ->
                val text = (r as? String)?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    rulings += IdentityRuling(
                        rulingUid = "$identityUid:r${(i + 1).toString().padStart(2, '0')}",
                        identityUid = identityUid,
                        ordinal = i + 1,
                        text = text,
                    )
                }
            }
        }

        // Erst nach dem Abbilden prüfbar: TDD 4.1 verlangt `slug_ascii` unique.
        SlugAscii.duplikate(identitaeten.map { it.slugAscii }).forEach { (slug, anzahl) ->
            val betroffen = identitaeten.filter { it.slugAscii == slug }.map { it.name }
            befunde += Finding.Wert("Slug '$slug' entsteht $anzahl-mal — aus $betroffen")
        }

        if (befunde.isNotEmpty()) return MappingResult(null, befunde)

        val daten = CatalogData(
            meta = CatalogMeta(
                catalogVersion = katalogVersion,
                sourceApiVersion = wurzel["api_version"]?.toString().orEmpty(),
                sourceSetCode = wurzel["set_code"] as? String ?: setCode,
                sourceUrl = sourceUrl,
                sourceChecksum = sourceChecksum,
                importedAt = importedAt,
            ),
            cardSet = CardSet(
                setCode = setCode,
                name = wurzel["set_name"] as? String ?: "",
                lang = wurzel["set_lang"] as? String ?: "",
                cardCount = identitaeten.size,
                // Das Jahr steckt im Set-Code (TRD-2025) und in keinem Feld.
                releaseYear = setCode.substringAfterLast('-').toIntOrNull(),
                // Fan-Content, kein offizielles Magic-Produkt.
                isOfficial = false,
            ),
            identities = identitaeten,
            rulings = rulings,
        )
        return MappingResult(daten, emptyList())
    }

    /** `TRD-2025:001` — dreistellig aufgefüllt, damit die Sortierung stimmt. */
    private fun uid(id: Int): String = "$setCode:${id.toString().padStart(3, '0')}"
}

public data class MappingResult(
    val data: CatalogData?,
    val findings: List<Finding>,
) {
    public val isValid: Boolean get() = data != null && findings.isEmpty()

    public fun render(): String = if (isValid) {
        val d = data!!
        val ohneCost = d.identities.count { it.unveilCost == null }
        val reineMana = d.identities.count { it.unveilCost?.let(UnveilCost::istReineManaAngabe) == true }
        buildString {
            appendLine("Transformation in Ordnung.")
            appendLine("  Identitäten:   ${d.identities.size}")
            appendLine("  Rulings:       ${d.rulings.size}")
            d.identities.groupingBy { it.role }.eachCount().toSortedMap()
                .forEach { (rolle, anzahl) -> appendLine("  Rolle $rolle: $anzahl") }
            appendLine("  Unveil-Cost:   ${d.identities.size - ohneCost} vorhanden, $ohneCost ohne (Leader)")
            appendLine("                 davon $reineMana reine Mana-Angaben")
            append("  Slugs:         ${d.identities.map { it.slugAscii }.distinct().size} eindeutig")
        }
    } else {
        buildString {
            appendLine("Die Transformation ist fehlgeschlagen. ${findings.size} Befund(e):")
            appendLine()
            findings.forEach { appendLine("  - [${it::class.simpleName}] ${it.message}") }
            append("Es wird nichts erzeugt.")
        }
    }
}
