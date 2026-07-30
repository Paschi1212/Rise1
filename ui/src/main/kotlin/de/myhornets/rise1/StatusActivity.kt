package de.myhornets.rise1

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.myhornets.rise1.core.log.RiseLog

/**
 * PLATZHALTER-OBERFLÄCHE.
 *
 * Zeigt den Umsetzungsstand des Gerüsts an — nichts weiter. Sie existiert,
 * damit die Debug-APK startbar und von Hand prüfbar ist.
 *
 * Sie wird mit der Tischansicht aus `T-140` (E10) ersetzt. Bis dahin ist sie
 * absichtlich ohne Bibliothek gebaut: kein AppCompat, kein Compose, keine
 * Ressourcendateien.
 */
class StatusActivity : Activity() {

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

        setContentView(buildView())
    }

    private fun buildView(): ViewGroup {
        val dichte = resources.displayMetrics.density
        fun dp(v: Int) = (v * dichte).toInt()

        val spalte = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        fun titel(text: String) = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, dp(4))
        }

        fun unterzeile(text: String) = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.parseColor("#6E6A63"))
            setPadding(0, 0, 0, dp(20))
        }

        fun abschnitt(text: String) = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.parseColor("#A85E33"))
            setPadding(0, dp(16), 0, dp(6))
        }

        fun zeile(text: String) = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(2), 0, dp(2))
        }

        spalte.addView(titel("Rise 1.0"))
        spalte.addView(unterzeile("Gerüst · T-001 bis T-005 · Version 0.1.0-T005"))

        spalte.addView(abschnitt("UMGESETZT"))
        listOf(
            "T-001  Projekt, SDK 36 / min 29, Version-Katalog",
            "T-002  Neun Module nach TDD 2.2",
            "T-003  Fitnesstest der Modulgrenzen",
            "T-004  Testgerüst und CI",
            "T-005  Protokollierung und Fehlerkonzept",
        ).forEach { spalte.addView(zeile("✓  $it")) }

        spalte.addView(abschnitt("NOCH LEER — PLATZHALTER"))
        listOf(
            "catalog     Kartendaten           → E02",
            "core        Event-Modell          → E04",
            "projection  Anzeigezustand        → E04",
            "crypto      Tink, Schlüssel       → E05",
            "transport   WLAN / WebSocket      → E06",
            "host        Reihenfolge, Log      → E07",
            "session     Beitritt, Reconnect   → E08",
            "deal        Rollenverteilung      → E09",
            "ui          Tischansicht          → E10",
        ).forEach { spalte.addView(zeile("·  $it")) }

        spalte.addView(abschnitt("DIESE ANSICHT"))
        spalte.addView(
            zeile(
                "Platzhalter. Wird durch die Tischansicht aus T-140 ersetzt. " +
                    "Absichtlich ohne AppCompat und ohne Compose gebaut."
            )
        )

        spalte.addView(abschnitt("DOKUMENTATION"))
        spalte.addView(zeile("Obsidian-Vault: Rise1/00_Project/README.md"))

        return ScrollView(this).apply { addView(spalte) }
    }
}
