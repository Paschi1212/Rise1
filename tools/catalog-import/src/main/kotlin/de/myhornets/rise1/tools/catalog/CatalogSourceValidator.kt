package de.myhornets.rise1.tools.catalog

/**
 * Prüft die Struktur der Treachery-Quelldatei.
 *
 * Architekturbezug: TDD 4.1 · Umgesetzt in T-010.
 *
 * **Diese Klasse hat keine einzige Abhängigkeit** — auch keine auf JSON. Sie
 * arbeitet auf einer bereits geparsten Struktur aus Maps und Listen. Das ist
 * kein Zufall, sondern der Kern des Entwurfs:
 *
 *  - Sie lässt sich ohne Parser, ohne Datei und ohne Netz testen. Die Testfälle
 *    bauen ihre Eingabe von Hand.
 *  - Ein Wechsel der JSON-Bibliothek berührt genau eine andere Datei
 *    ([JsonAdapter]) und diese hier gar nicht.
 *
 * Und ein zweiter Punkt, der bei einem *validierenden* Importeur zählt:
 * Untypisierte Eingabe ist hier kein Nachteil. Eine typisierte Deserialisierung
 * würde fehlende Felder stillschweigend zu `null` machen; hier muss jede
 * Erwartung ausdrücklich benannt werden. **Die Extraktion ist die Validierung.**
 */
public class CatalogSourceValidator(
    private val erwarteteKartenzahl: Int = 62,
    private val erwarteterPool: Map<String, Int> = mapOf(
        "Leader" to 13,
        "Guardian" to 18,
        "Assassin" to 18,
        "Traitor" to 13,
    ),
) {

    public fun validate(wurzel: Map<String, Any?>): ValidationReport {
        val befunde = mutableListOf<Finding>()

        // ── Kopfdaten ────────────────────────────────────────────────────────
        val pflichtfelder = listOf(
            "game_variant", "api_author", "api_version",
            "set_name", "set_code", "set_lang", "cards_count", "cards",
        )
        pflichtfelder.filterNot { wurzel.containsKey(it) }.forEach {
            befunde += Finding.Fehlend("Kopffeld '$it' fehlt")
        }

        val karten = wurzel["cards"] as? List<*>
        if (karten == null) {
            befunde += Finding.Struktur("'cards' fehlt oder ist keine Liste")
            return ValidationReport(befunde, null)
        }

        // ── Anzahl ───────────────────────────────────────────────────────────
        val angegebeneZahl = (wurzel["cards_count"] as? Number)?.toInt()
        if (angegebeneZahl != null && angegebeneZahl != karten.size) {
            befunde += Finding.Anzahl(
                "cards_count sagt $angegebeneZahl, die Liste enthält ${karten.size} Einträge"
            )
        }
        if (karten.size != erwarteteKartenzahl) {
            befunde += Finding.Anzahl(
                "Erwartet werden $erwarteteKartenzahl Karten, gefunden ${karten.size}"
            )
        }

        // ── Karten einzeln ───────────────────────────────────────────────────
        val ids = mutableListOf<Int>()
        val subtypen = mutableMapOf<String, Int>()

        karten.forEachIndexed { index, roh ->
            val karte = roh as? Map<*, *>
            if (karte == null) {
                befunde += Finding.Struktur("Eintrag $index ist kein Objekt")
                return@forEachIndexed
            }

            val id = (karte["id"] as? Number)?.toInt()
            if (id == null) befunde += Finding.Struktur("Eintrag $index hat keine numerische 'id'")
            else ids += id

            val bezeichnung = id?.let { "Karte $it" } ?: "Eintrag $index"

            listOf("name", "name_anchor", "uri", "type", "text", "artist").forEach { feld ->
                val wert = karte[feld] as? String
                if (wert.isNullOrBlank()) {
                    befunde += Finding.Fehlend("$bezeichnung: '$feld' fehlt oder ist leer")
                }
            }

            val typen = karte["types"] as? Map<*, *>
            if (typen == null) {
                befunde += Finding.Struktur("$bezeichnung: 'types' fehlt")
            } else {
                val supertype = typen["supertype"] as? String
                if (supertype != "Identity") {
                    befunde += Finding.Wert(
                        "$bezeichnung: supertype ist '$supertype', erwartet 'Identity'"
                    )
                }
                val subtype = typen["subtype"] as? String
                if (subtype == null || subtype !in erwarteterPool.keys) {
                    befunde += Finding.Wert(
                        "$bezeichnung: subtype ist '$subtype', erwartet eines von ${erwarteterPool.keys.sorted()}"
                    )
                } else {
                    subtypen[subtype] = (subtypen[subtype] ?: 0) + 1
                }
            }

            // 'cost' und 'cmc' werden bewusst NICHT geprüft: Sie sind in der
            // Quelle ungepflegt (leer bzw. 0, obwohl ein Unveil-Cost existiert)
            // und werden nicht übernommen. Siehe Vault, 04_Game/Cards.md.
        }

        // ── Lückenlose Nummerierung ──────────────────────────────────────────
        val doppelte = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        if (doppelte.isNotEmpty()) {
            befunde += Finding.Nummerierung("Doppelte IDs: $doppelte")
        }
        val erwarteteIds = (1..erwarteteKartenzahl).toSet()
        val fehlende = (erwarteteIds - ids.toSet()).sorted()
        val ueberzaehlige = (ids.toSet() - erwarteteIds).sorted()
        if (fehlende.isNotEmpty()) {
            befunde += Finding.Nummerierung("Fehlende IDs: $fehlende")
        }
        if (ueberzaehlige.isNotEmpty()) {
            befunde += Finding.Nummerierung("IDs außerhalb von 1..$erwarteteKartenzahl: $ueberzaehlige")
        }

        // ── Rollenverteilung im Pool ─────────────────────────────────────────
        erwarteterPool.forEach { (rolle, erwartet) ->
            val gefunden = subtypen[rolle] ?: 0
            if (gefunden != erwartet) {
                befunde += Finding.Pool("Rolle $rolle: erwartet $erwartet, gefunden $gefunden")
            }
        }

        val kopf = if (befunde.none { it is Finding.Fehlend || it is Finding.Struktur }) {
            SourceHeader(
                apiVersion = wurzel["api_version"]?.toString().orEmpty(),
                apiAuthor = wurzel["api_author"] as? String ?: "",
                setName = wurzel["set_name"] as? String ?: "",
                setCode = wurzel["set_code"] as? String ?: "",
                setLang = wurzel["set_lang"] as? String ?: "",
                cardsCount = karten.size,
            )
        } else {
            null
        }

        return ValidationReport(befunde, kopf)
    }
}
