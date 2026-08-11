package de.myhornets.rise1.berechtigung

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.myhornets.rise1.BuildConfig
import de.myhornets.rise1.StatusActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-076 — **GERÄTETEST**. Die Naht zur Plattform.
 *
 * ## Was hier steht und was nicht
 *
 * Der Ablauf selbst — fünf Zustände, ihre Übergänge, die drei Nicht-Ergebnisse
 * — steht in `BerechtigungslageTest` und `BerechtigungsablaufTest` und läuft
 * ohne Gerät. Hier steht nur, was ein Gerät beantworten kann:
 *
 * - Meldet [AndroidBerechtigungssystem] **denselben** Stand wie der
 *   `PackageManager`, oder hat sich ein Name vertippt?
 * - Stimmt das Ziel-SDK, mit dem [Berechtigungsbedarf] rechnet, mit dem
 *   überein, das im Paket steht?
 * - Führt der letzte Ausweg — die Systemeinstellungen — irgendwohin?
 *
 * **Der Systemdialog wird nicht bedient.** Dafür bräuchte es UiAutomator, also
 * eine weitere Abhängigkeit, und der Test hinge an der Beschriftung von Knöpfen
 * in der jeweiligen Android-Fassung. Was der Dialog auslöst, ist ein
 * `onRequestPermissionsResult` — und dessen sämtliche Ausgänge, einschließlich
 * der abwegigen, sind bereits im JVM-Test geprüft.
 *
 * ## Aufruf
 *
 * ```
 * ./gradlew :ui:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=de.myhornets.rise1.berechtigung.BerechtigungenGeraetTest
 * ```
 *
 * ## Der wichtigste
 *
 * [dasZielSdkAusBuildConfigStimmtMitDemPaketUeberein]. An dieser einen Zahl
 * hängt die gesamte Entscheidung von T-076: Die Sperre für das lokale Netz gilt
 * ab Ziel-SDK 37. Läuft die Zahl im Quelltext mit der im Build auseinander,
 * fragt Rise entweder nach einer Berechtigung, die es nicht braucht, oder — viel
 * schlimmer — nicht nach einer, ohne die kein Host mehr zustande kommt.
 */
@RunWith(AndroidJUnit4::class)
class BerechtigungenGeraetTest {

    private lateinit var kontext: Context

    @Before
    fun bereiteVor() {
        kontext = ApplicationProvider.getApplicationContext()
    }

    /** Führt etwas mit einer echten Activity aus — die Naht braucht eine. */
    private fun <T> mitActivity(block: (Activity) -> T): T {
        var ergebnis: T? = null
        ActivityScenario.launch(StatusActivity::class.java).use { szene ->
            szene.onActivity { activity -> ergebnis = block(activity) }
        }
        @Suppress("UNCHECKED_CAST")
        return ergebnis as T
    }

    @Test
    fun dasZielSdkAusBuildConfigStimmtMitDemPaketUeberein() {
        // Dieselbe Vorsichtsmaßnahme wie bei der Versionsangabe in
        // StatusActivity: Sie stand einmal doppelt und lief auseinander.
        assertEquals(
            "BuildConfig.ZIEL_SDK und das targetSdk des Pakets sind auseinandergelaufen.",
            kontext.applicationInfo.targetSdkVersion,
            BuildConfig.ZIEL_SDK,
        )
    }

    @Test
    fun derGemeldeteStandIstDerDesPackageManagers() {
        // Ein vertippter Berechtigungsname fiele sonst erst auf, wenn ein Nutzer
        // vor einem Dialog steht, der nicht kommt.
        val naht = mitActivity { AndroidBerechtigungssystem(it) }

        Berechtigungsart.entries.forEach { art ->
            val vomSystem = kontext.checkSelfPermission(art.systemname) == PackageManager.PERMISSION_GRANTED
            assertEquals("Abweichung bei ${art.systemname}", vomSystem, naht.istErteilt(art.systemname))
        }
    }

    @Test
    fun beiZielSdk36IstDasLokaleNetzAufDiesemGeraetNichtNoetig() {
        // Der Befund, der über den Umfang von T-076 entschieden hat — hier gegen
        // die echte Geräteversion nachgerechnet, statt gegen eine Zahl im Test.
        val lage = berechtigungslage(Rolle.HOST)

        assertEquals(Build.VERSION.SDK_INT, lage.geraeteSdk)
        assertEquals(BuildConfig.ZIEL_SDK, lage.zielSdk)
        if (BuildConfig.ZIEL_SDK < Berechtigungsbedarf.SPERRE_AB_ZIELSDK) {
            assertEquals(
                "Bei Ziel-SDK ${BuildConfig.ZIEL_SDK} sperrt Android das lokale Netz nicht.",
                Berechtigungsstand.NICHT_NOETIG,
                lage.stand(Berechtigungsart.LOKALES_NETZ),
            )
        }
    }

    @Test
    fun derAblaufKommtGegenDieEchtePlattformZuEinemSchritt() {
        val schritt = mitActivity { activity ->
            val ablauf = Berechtigungsablauf(AndroidBerechtigungssystem(activity), berechtigungslage(Rolle.GAST))
            ablauf.leseSystemstand()
            ablauf.naechsterSchritt() to ablauf.lage.vollstaendig
        }

        // Es gibt genau vier Ausgänge, und „vollständig" darf nur mit
        // NichtsZuTun zusammenfallen — sonst behauptete die Oberfläche, alles
        // sei da, und böte gleichzeitig einen Knopf an.
        val (naechster, vollstaendig) = schritt
        assertTrue(
            "Unerwarteter Schritt: $naechster",
            naechster is Schritt.NichtsZuTun ||
                naechster is Schritt.Fragen ||
                naechster is Schritt.Begruenden ||
                naechster is Schritt.NurNochEinstellungen,
        )
        assertEquals(vollstaendig, naechster is Schritt.NichtsZuTun)
    }

    @Test
    fun derWegInDieEinstellungenFuehrtIrgendwohin() {
        // ADR-001 sieht die Systemeinstellungen als letzten Ausweg vor. Eine
        // unauflösbare Absicht wäre ein Absturz genau in dem Moment, in dem der
        // Nutzer ohnehin feststeckt.
        val absicht = einstellungsAbsicht(kontext.packageName)

        // `Intent.resolveActivity(PackageManager)` und nicht
        // `PackageManager.resolveActivity(Intent, Int)`: Letzteres ist seit
        // API 33 überholt, und eine Warnung im Testquellbaum ist eine Warnung.
        assertNotNull(
            "Die Berechtigungsseite dieser App ist auf diesem Gerät nicht erreichbar.",
            absicht.resolveActivity(kontext.packageManager),
        )
    }
}
