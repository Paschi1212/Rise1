package de.myhornets.rise1

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.myhornets.rise1.core.log.RiseLog

/**
 * PLATZHALTER-OBERFLÄCHE.
 *
 * Zeigt den Umsetzungsstand des Gerüsts an — nichts weiter. Sie existiert,
 * damit die Debug-APK startbar und von Hand prüfbar ist. Ersetzt wird sie mit
 * der Tischansicht aus `T-140` (E10).
 *
 * **Seit D-004 (2026-07-30) in Jetpack Compose.** Die Umstellung dieser Seite
 * ist zugleich der Nachweis, dass die Compose-Werkzeugkette trägt — Plugin,
 * BOM und Übersetzung. Ohne sie fiele ein Fehler in der Verdrahtung erst bei
 * `T-017` auf, mitten im Katalog-Browser.
 *
 * **Die Versionsangabe kommt aus `BuildConfig`, nicht aus dieser Datei.** Sie
 * stand hier einmal als Zeichenkette und lief mit dem Build auseinander: Die
 * APK meldete „T-001 bis T-005", während der Stand längst bei T-011 war.
 *
 * **Die Liste unter UMGESETZT bleibt Handarbeit** und gehört zur Definition of
 * Done jedes Tasks, der etwas Sichtbares ändert.
 */
class StatusActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // T-005: Logcat-Senke einsetzen. Die JVM-Module protokollieren über
        // dieselbe Schnittstelle und bleiben ohne Android testbar.
        RiseLog.installSink { level, tag, message ->
            val prio = when (level) {
                RiseLog.Level.DEBUG -> Log.DEBUG
                RiseLog.Level.INFO -> Log.INFO
                RiseLog.Level.WARN -> Log.WARN
                RiseLog.Level.ERROR -> Log.ERROR
            }
            Log.println(prio, "Rise/$tag", message)
        }
        RiseLog.i("UI", "StatusActivity gestartet")

        setContent {
            MaterialTheme {
                Surface { StatusScreen(BuildConfig.VERSION_NAME) }
            }
        }
    }
}

private val AKZENT = Color(0xFFA85E33)
private val GEDAEMPFT = Color(0xFF6E6A63)

@Composable
private fun StatusScreen(version: String) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp)
    ) {
        Text("Rise 1.0", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        // Version ausschließlich aus dem Build — siehe Klassenkommentar.
        Text(
            "Gerüst · Version $version",
            fontSize = 13.sp,
            color = GEDAEMPFT,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
        )

        Abschnitt("UMGESETZT — GERÜST")
        listOf(
            "T-001  Projekt, SDK 36 / min 29, Version-Katalog",
            "T-002  Neun Module nach TDD 2.2",
            "T-003  Fitnesstest der Modulgrenzen",
            "T-004  Testgerüst und CI",
            "T-005  Protokollierung und Fehlerkonzept",
        ).forEach { Zeile("✓  $it") }

        Abschnitt("UMGESETZT — KATALOG-IMPORT")
        Zeile("✓  T-010  Quelle laden und prüfen")
        Zeile("✓  T-011  Transformation nach TDD 4.1")
        Zeile(
            "Läuft zur Build-Zeit unter tools/, nicht in der App. " +
                "62 Identitäten, 295 Rulings — noch nicht in einer Datenbank."
        )

        Abschnitt("UMGESETZT — OBERFLÄCHE")
        Zeile("✓  D-004  Jetpack Compose als UI-Grundlage")
        Zeile("Diese Seite ist der Nachweis, dass die Werkzeugkette trägt.")

        Abschnitt("NOCH LEER — PLATZHALTER")
        listOf(
            "catalog     Kartendaten           → E02 (T-013, T-014)",
            "core        Event-Modell          → E04",
            "projection  Anzeigezustand        → E04",
            "crypto      Tink, Schlüssel       → E05",
            "transport   WLAN / WebSocket      → E06",
            "host        Reihenfolge, Log      → E07",
            "session     Beitritt, Reconnect   → E08",
            "deal        Rollenverteilung      → E09",
            "ui          Tischansicht          → E10",
        ).forEach { Zeile("·  $it") }

        Abschnitt("DIESE ANSICHT")
        Zeile("Platzhalter. Wird durch die Tischansicht aus T-140 ersetzt.")

        Abschnitt("DOKUMENTATION")
        Zeile("Obsidian-Vault: Rise1/00_Project/README.md")
    }
}

@Composable
private fun Abschnitt(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = AKZENT,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun Zeile(text: String) {
    Text(text, fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
}

@Preview(showBackground = true)
@Composable
private fun StatusScreenVorschau() {
    MaterialTheme { Surface { StatusScreen("0.2.0-D004") } }
}
