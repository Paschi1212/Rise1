package de.myhornets.rise1.browser

import android.content.Context
import android.graphics.BitmapFactory
import de.myhornets.rise1.catalog.CatalogAsset
import de.myhornets.rise1.catalog.CatalogDao
import de.myhornets.rise1.catalog.CatalogDatabase
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException

// T-017 — Zugang zum Katalog aus der Oberfläche.
//
// Zwei kleine Helfer, die nichts entscheiden: Sie holen Daten und Bilder. Die
// Anzeige liegt in den Composables, die Kartenkunde in `:catalog`.

/**
 * Hält die geöffnete Katalogdatenbank für die Lebensdauer des Prozesses.
 *
 * Room warnt zu Recht, wenn dieselbe Datenbank mehrfach gebaut wird. Der Halter
 * steht hier und nicht in `:catalog`, weil er eine Frage der App-Lebensdauer ist
 * und keine der Kartendaten.
 */
internal object KatalogZugriff {

    @Volatile
    private var datenbank: CatalogDatabase? = null

    fun dao(context: Context): CatalogDao {
        val vorhanden = datenbank
        if (vorhanden != null) return vorhanden.catalogDao()
        return synchronized(this) {
            val nochmal = datenbank
            if (nochmal != null) {
                nochmal.catalogDao()
            } else {
                val neu = CatalogAsset.open(context)
                datenbank = neu
                neu.catalogDao()
            }
        }
    }
}

/**
 * Lädt ein Kartenbild aus den App-Assets.
 *
 * `identity.image_asset` ist ein **Dateiname**, kein Pfad. Das Verzeichnis
 * steht im Import-Werkzeug: Der Befehl `images` legt die Bilder unter
 * `catalog/src/main/assets/cards` ab, im APK also unter `assets/cards/`.
 *
 * Fehlt eine Datei, liefert die Funktion `null` und die Anzeige zeigt eine
 * Platzhalterfläche. Das ist bewusst nachsichtig: Eine fehlende Bilddatei darf
 * den Katalog nicht unbenutzbar machen. Dass sie fehlt, ist trotzdem ein
 * Befund — er steht **einmal** im Kopf der Liste, damit er nicht in 62 leeren
 * Kacheln untergeht.
 */
internal object Kartenbilder {

    /** Wie im Werkzeug festgelegt (`Main.kt`, `ASSET_PFAD`). */
    private const val ORT = "cards/"

    // Der Zwischenspeicher merkt sich auch das Nichtvorhandensein — sonst
    // versucht jede Zeile der Liste bei jedem Neuzeichnen erneut, eine Datei zu
    // öffnen, die es nicht gibt. `containsKey` statt `getOrPut`, weil der Wert
    // selbst null sein darf.
    private val zwischenspeicher = HashMap<String, ImageBitmap?>()

    /** Nur vom Hintergrund-Thread aufrufen — liest und dekodiert. */
    fun lade(context: Context, dateiname: String): ImageBitmap? = synchronized(this) {
        if (zwischenspeicher.containsKey(dateiname)) return@synchronized zwischenspeicher[dateiname]
        val gefunden = try {
            context.assets.open(ORT + dateiname).use { strom ->
                BitmapFactory.decodeStream(strom)?.asImageBitmap()
            }
        } catch (fehlt: IOException) {
            null
        }
        zwischenspeicher[dateiname] = gefunden
        gefunden
    }
}
