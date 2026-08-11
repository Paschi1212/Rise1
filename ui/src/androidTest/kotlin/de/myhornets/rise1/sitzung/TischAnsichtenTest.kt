package de.myhornets.rise1.sitzung

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Regressionstest** zu dem Absturz beim Antippen von „Partie beitreten".
 *
 * ## Was schiefging
 *
 * Der ganze Bildschirm lag in einem `Column(Modifier.verticalScroll(…))`, und
 * die Liste der gefundenen Tische war eine `LazyColumn` **darin**. Ein
 * senkrechter Scroller misst sein Kind mit unbegrenzter Höhe; eine `LazyColumn`
 * kann damit nichts anfangen und wirft:
 *
 * ```
 * IllegalStateException: Vertically scrollable component was measured
 * with an infinity maximum height constraints
 * ```
 *
 * Der Absturz kam erst beim **Zeichnen** der Beitrittsansicht — kein Compiler
 * und kein JVM-Test hätte ihn gefunden. Deshalb steht die Prüfung hier und
 * nicht in `:ui/src/test`.
 *
 * ## Warum das Antippen und nicht nur das Öffnen
 *
 * Weil der Startbildschirm keine Liste enthält und deshalb nie abstürzte. Erst
 * der Wechsel nach `Schritt.SUCHE` baut den Abschnitt auf, an dem es zerbrach.
 * Ein Test, der nur die Activity startet, wäre auch mit dem Fehler grün
 * geblieben.
 *
 * ## Aufruf
 *
 * ```
 * ./gradlew :ui:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=de.myhornets.rise1.sitzung.TischAnsichtenTest
 * ```
 *
 * `checkAll` fährt ihn nicht mit — `check` führt keine instrumentierten Tests aus.
 */
@RunWith(AndroidJUnit4::class)
class TischAnsichtenTest {

    @get:Rule
    val regel = createAndroidComposeRule<TischActivity>()

    @Test
    fun dieBeitrittsansichtOeffnetOhneAbsturz() {
        regel.onNodeWithTag(TischActivity.MARKE_BEITRETEN).performClick()

        // Kommt diese Zeile an, hat die Ansicht vollständig gemessen und
        // gezeichnet — genau das ging vorher nicht.
        regel.onNodeWithTag(TischActivity.MARKE_SUCHE).assertExists()
        regel.onNodeWithText("Es horcht — bisher wurde nichts gefunden.").assertExists()
    }

    @Test
    fun derStartbildschirmBietetBeideWege() {
        regel.onNodeWithTag(TischActivity.MARKE_ERSTELLEN).assertExists()
        regel.onNodeWithTag(TischActivity.MARKE_BEITRETEN).assertExists()
        regel.onNodeWithText("Dein Name am Tisch").assertExists()
    }

    @Test
    fun ausDerSucheFuehrtEinWegZurueck() {
        regel.onNodeWithTag(TischActivity.MARKE_BEITRETEN).performClick()
        regel.onNodeWithTag(TischActivity.MARKE_SUCHE).assertExists()

        regel.onNodeWithText("Zurück").performClick()

        regel.onNodeWithTag(TischActivity.MARKE_ERSTELLEN).assertExists()
    }
}
