package de.myhornets.rise1.tools.catalog

/**
 * Vollständigkeitsprüfung der Bilder über **alle** Karten. T-012.
 *
 * Diese Klasse kennt weder Netz noch Dateisystem — sie bekommt je Identität
 * das Ergebnis eines Abrufs und fällt das Urteil. Dieselbe Bauweise wie
 * [CatalogSourceValidator]: ohne Abhängigkeit, ohne Datei, ohne Netz testbar.
 *
 * **Der Prüfumfang ist bewusst vierteilig.** Jede einzelne Frage hat schon
 * einmal irgendwo ein Bildarchiv stillschweigend unvollständig gemacht:
 *
 *  1. Gibt es zu **jeder** der 62 Identitäten einen Abruf? Eine Karte, die gar
 *     nicht erst versucht wurde, fehlt sonst unbemerkt.
 *  2. War der Abruf erfolgreich?
 *  3. Ist die Antwort **inhaltlich** ein Bild — geprüft am Dateianfang, nicht
 *     am HTTP-Status und nicht an der Endung.
 *  4. Passen Nummer, Rolle und Name im Dateinamen zur Identität?
 */
public class ImageAudit(private val erwarteteAnzahl: Int = 62) {

    /** Was ein Abruf je Identität ergeben hat. */
    public data class Abruf(
        val identityUid: String,
        val cardNumber: Int,
        val name: String,
        val subtype: String,
        val url: String,
        val statusCode: Int?,
        val bytes: Int,
        val art: ImageKind.Art?,
        val fehler: String? = null,
    )

    public data class Ergebnis(
        val vollstaendig: Boolean,
        val geprueft: Int,
        val inOrdnung: List<Abruf>,
        val fehlend: List<Abruf>,
        val befunde: List<Finding>,
    ) {
        public fun render(): String = buildString {
            if (vollstaendig) {
                appendLine("Bilder vollständig: $geprueft von $geprueft.")
                append("  Alle Antworten sind inhaltlich Bilder, alle Dateinamen passen zur Identität.")
                return@buildString
            }
            appendLine("Bilder UNVOLLSTÄNDIG: ${inOrdnung.size} von $geprueft in Ordnung, ${fehlend.size} nicht.")
            appendLine()
            appendLine("Fehlende oder unbrauchbare Assets:")
            // Vollständige Liste, ausdrücklich ohne Kürzung. Eine abgeschnittene
            // Liste sieht aus wie ein kleines Problem.
            fehlend.sortedBy { it.cardNumber }.forEach { a ->
                appendLine("  ${a.identityUid}  ${a.name}")
                appendLine("      → ${a.url}")
                appendLine("      Status ${a.statusCode ?: "—"} · ${a.bytes} Bytes · Art ${a.art ?: "—"}" +
                    (a.fehler?.let { " · $it" } ?: ""))
            }
            if (befunde.isNotEmpty()) {
                appendLine()
                appendLine("Weitere Befunde:")
                befunde.forEach { appendLine("  - [${it::class.simpleName}] ${it.message}") }
            }
            appendLine()
            append("Der Import wird abgebrochen. Es wird nichts erzeugt und nichts überschrieben.")
        }
    }

    public fun pruefe(identitaeten: List<Identity>, abrufe: List<Abruf>): Ergebnis {
        val befunde = mutableListOf<Finding>()
        val nachUid = abrufe.associateBy { it.identityUid }

        // 1. Zu jeder Identität ein Abruf.
        val ohneAbruf = identitaeten.filter { it.identityUid !in nachUid }
        ohneAbruf.forEach {
            befunde += Finding.Fehlend("${it.identityUid} (${it.name}): kein Abruf versucht")
        }

        if (identitaeten.size != erwarteteAnzahl) {
            befunde += Finding.Anzahl(
                "Erwartet werden $erwarteteAnzahl Identitäten, übergeben wurden ${identitaeten.size}"
            )
        }

        val inOrdnung = mutableListOf<Abruf>()
        val fehlend = mutableListOf<Abruf>()

        identitaeten.forEach { identity ->
            val a = nachUid[identity.identityUid] ?: return@forEach

            // 2. Erfolgreich abgerufen.
            val statusOk = a.statusCode == 200 && a.fehler == null
            // 3. Inhaltlich ein Bild.
            val istBild = a.art != null && ImageKind.istBild(a.art)
            // 4. Dateiname passt zur Identität.
            val erwarteterName = ImageSource.dateiname(identity.cardNumber, a.subtype, identity.name)
            val nameOk = a.url.endsWith(erwarteterName) ||
                a.url.endsWith(erwarteterName.replace(" ", "%20"))

            if (!nameOk) {
                befunde += Finding.Wert(
                    "${identity.identityUid}: URL passt nicht zur Identität — erwartet '$erwarteterName'"
                )
            }
            if (statusOk && istBild && nameOk) inOrdnung += a else fehlend += a
        }

        return Ergebnis(
            vollstaendig = fehlend.isEmpty() && ohneAbruf.isEmpty() && befunde.isEmpty(),
            geprueft = identitaeten.size,
            inOrdnung = inOrdnung,
            fehlend = fehlend,
            befunde = befunde,
        )
    }
}
