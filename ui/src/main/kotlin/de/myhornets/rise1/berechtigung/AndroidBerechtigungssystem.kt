package de.myhornets.rise1.berechtigung

import android.app.Activity
import android.content.Intent
import de.myhornets.rise1.BuildConfig
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * T-076 — die Naht zur Plattform.
 *
 * ## Alles, was Android an T-076 ist
 *
 * Drei Fragen, zwei Handlungen, kein Zustand und keine Entscheidung. Jede
 * Entscheidung steht in [Berechtigungslage] und [Berechtigungsablauf] und ist
 * dort ohne Gerät geprüft. Was hier hinzukäme, wäre ungeprüft — deshalb kommt
 * hier nichts hinzu.
 *
 * ## Warum die Plattform-API und nicht `ActivityResultContracts`
 *
 * `registerForActivityResult` wäre bequemer und käme aus `androidx.activity`,
 * das ohnehin dabei ist (D-004). Es bindet den Ablauf aber an den
 * Lebenszyklus einer `ComponentActivity` und muss vor `onStart` registriert
 * werden — eine zusätzliche Regel, die man verletzen kann. `requestPermissions`
 * gibt es seit API 23, es kostet keine Abhängigkeit, und das Ergebnis geht
 * durch [Berechtigungsablauf.nimmErgebnis], wo die drei Nicht-Ergebnisse
 * bereits behandelt sind.
 */
class AndroidBerechtigungssystem(private val activity: Activity) : Berechtigungssystem {

    override fun istErteilt(systemname: String): Boolean =
        activity.checkSelfPermission(systemname) == PackageManager.PERMISSION_GRANTED

    override fun darfBegruendungZeigen(systemname: String): Boolean =
        activity.shouldShowRequestPermissionRationale(systemname)

    override fun frage(systemnamen: List<String>, kennung: Int) {
        if (systemnamen.isEmpty()) return
        activity.requestPermissions(systemnamen.toTypedArray(), kennung)
    }

    /**
     * Die Systemeinstellungen dieser App.
     *
     * Der letzte Weg nach [Schritt.NurNochEinstellungen]. `ACTION_APPLICATION_DETAILS_SETTINGS`
     * führt genau auf die Seite mit den Berechtigungen — und nicht in die
     * allgemeinen Einstellungen, wo der Nutzer sie suchen müsste.
     */
    override fun oeffneEinstellungen() {
        activity.startActivity(einstellungsAbsicht(activity.packageName))
    }
}

/**
 * Die Absicht, die auf die Berechtigungsseite dieser App führt.
 *
 * Eigene Funktion, damit ein Gerätetest sie **auflösen** kann, ohne sie zu
 * starten: Ein Weg, den ADR-001 als letzten Ausweg vorsieht, muss irgendwohin
 * führen. Ein `startActivity` auf eine unauflösbare Absicht ist ein Absturz,
 * und zwar genau dann, wenn der Nutzer ohnehin schon feststeckt.
 *
 * Kein `FLAG_ACTIVITY_NEW_TASK`: Gestartet wird aus einer Activity, und die
 * Einstellungen sollen auf deren Stapel liegen — sonst kommt der Nutzer mit
 * „Zurück" nicht dorthin zurück, wo er war.
 */
fun einstellungsAbsicht(paketname: String): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", paketname, null),
    )

/**
 * Baut die Lage für diese Rolle auf diesem Gerät.
 *
 * Die einzige Stelle, an der [Build.VERSION.SDK_INT] und das Ziel-SDK
 * zusammenkommen. Beides sind Zahlen; sie stehen hier und nicht in
 * [Berechtigungslage], damit deren Regeln für **jede** Kombination prüfbar
 * bleiben — auch für die von morgen.
 *
 * @param zielSdk das `targetSdk` dieses Builds. Voreingestellt ist
 *   `BuildConfig.ZIEL_SDK`, und das kommt aus **derselben Zeile** in
 *   `ui/build.gradle.kts`, aus der auch `targetSdk` gesetzt wird. Dieselbe
 *   Vorsichtsmaßnahme wie bei der Versionsangabe in `StatusActivity`: Sie stand
 *   dort einmal als Zeichenkette und lief mit dem Build auseinander.
 */
fun berechtigungslage(
    rolle: Rolle,
    zielSdk: Int = BuildConfig.ZIEL_SDK,
    schonGefragt: Set<Berechtigungsart> = emptySet(),
): Berechtigungslage = Berechtigungslage(
    rolle = rolle,
    geraeteSdk = Build.VERSION.SDK_INT,
    zielSdk = zielSdk,
    schonGefragt = schonGefragt,
)
