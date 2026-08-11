package de.myhornets.rise1.catalog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * T-018 — die Abnahme für [[Milestones|M2]].
 *
 * Drei Zusagen, wörtlich aus dem Sprint: **62 Karten**, **Verteilung
 * 13/18/18/13**, **ein Bild je Karte**. Diese Klasse prüft genau das und sonst
 * nichts — sie ist die Abnahme, nicht die laufende Prüfung. Was während der
 * Entwicklung greift, steht in [CatalogAssetTest].
 *
 * Beides läuft gegen das **ausgelieferte** Paket: Die Datenbank kommt aus dem
 * Asset, die Bilder werden aus dem Asset gelesen. Eine Abnahme gegen zur
 * Laufzeit erzeugte Daten belegt über das Ausgelieferte nichts.
 *
 * Aufruf:
 *
 *     ./gradlew :catalog:connectedDebugAndroidTest
 *
 * Nur diese Klasse:
 *
 *     ./gradlew :catalog:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.myhornets.rise1.catalog.M2AbnahmeTest
 */
@RunWith(AndroidJUnit4::class)
class M2AbnahmeTest {

    private lateinit var context: Context
    private lateinit var db: CatalogDatabase
    private lateinit var dao: CatalogDao

    @Before
    fun oeffneDasAusgelieferteAsset() {
        context = ApplicationProvider.getApplicationContext()
        // Ohne das Löschen öffnet Room eine Kopie aus einem früheren Lauf, und
        // die Abnahme sagt nichts mehr über das aktuelle Paket aus.
        context.deleteDatabase(CatalogAsset.LOCAL_NAME)
        db = CatalogAsset.open(context)
        dao = db.catalogDao()
    }

    @After
    fun schliesse() {
        db.close()
    }

    // ── Zusage 1: 62 Karten ──────────────────────────────────────────────────

    @Test
    fun dieAbnahmeZaehlt62Identitaeten() {
        assertEquals(62, dao.anzahlIdentitaeten())
    }

    // ── Zusage 2: Verteilung 13/18/18/13 ─────────────────────────────────────

    @Test
    fun dieRollenverteilungIst13_18_18_13() {
        // Reihenfolge wie in TDD 8.2 und in [[Cards]]: Leader, Guardian,
        // Assassin, Traitor.
        assertEquals(13, dao.anzahlNachRolle(CatalogRole.LEADER))
        assertEquals(18, dao.anzahlNachRolle(CatalogRole.GUARDIAN))
        assertEquals(18, dao.anzahlNachRolle(CatalogRole.ASSASSIN))
        assertEquals(13, dao.anzahlNachRolle(CatalogRole.TRAITOR))

        // Dieselbe Aussage noch einmal über den ganzen Bestand statt über vier
        // Abfragen. Sie fängt zusätzlich den Fall ab, den vier Zählungen nicht
        // sehen: eine fünfte Rolle, die danebenläge, ohne eine der vier zu
        // verändern.
        assertEquals(
            mapOf(
                CatalogRole.LEADER to 13,
                CatalogRole.GUARDIAN to 18,
                CatalogRole.ASSASSIN to 18,
                CatalogRole.TRAITOR to 13,
            ),
            dao.alleIdentitaeten().groupingBy { it.role }.eachCount(),
        )
    }

    // ── Zusage 3: ein Bild je Karte ──────────────────────────────────────────

    @Test
    fun jedeIdentitaetNenntEinenBildnamen() {
        val ohneNamen = dao.alleIdentitaeten().filter { it.imageAsset.isNullOrBlank() }
        assertTrue(
            "Ohne Bildnamen: ${ohneNamen.map { it.name }}",
            ohneNamen.isEmpty(),
        )
    }

    /**
     * Jedes genannte Bild liegt im Paket **und ist inhaltlich ein Bild**.
     *
     * Die zweite Hälfte ist nicht überflüssig: `T-012` hat festgehalten, dass
     * ein Server eine Fehlerseite mit `200 OK` beantworten kann und eine als
     * `.jpg` gespeicherte HTML-Seite im Verzeichnis wie ein Bild aussieht.
     * Geprüft wird deshalb die Dateikennung, nicht die Endung.
     */
    @Test
    fun zuJederIdentitaetLiegtEinBildImPaket() {
        val fehlend = mutableListOf<String>()
        val keinBild = mutableListOf<String>()

        dao.alleIdentitaeten().forEach { identitaet ->
            val datei = identitaet.imageAsset ?: return@forEach
            val bytes = try {
                context.assets.open(BILDVERZEICHNIS + datei).use { it.readBytes() }
            } catch (nichtDa: IOException) {
                fehlend += "${identitaet.name} → $datei"
                return@forEach
            }
            if (!istJpeg(bytes)) keinBild += "${identitaet.name} → $datei (${bytes.size} Bytes)"
        }

        assertTrue("Bilder fehlen im Paket: $fehlend", fehlend.isEmpty())
        assertTrue("Dateien sind inhaltlich kein JPEG: $keinBild", keinBild.isEmpty())
    }

    /**
     * Und die Gegenrichtung: Im Bildverzeichnis liegt nichts, was zu keiner
     * Identität gehört.
     *
     * Ohne diese Prüfung wäre „ein Bild je Karte" auch dann erfüllt, wenn ein
     * alter Bestand mit anderen Namen danebenläge — und beim nächsten
     * Kartensatz fiele niemandem auf, dass das Verzeichnis mitwächst.
     */
    @Test
    fun imBildverzeichnisLiegtNichtsUeberzaehliges() {
        val imPaket = context.assets.list(BILDVERZEICHNIS.trimEnd('/'))?.toSet().orEmpty()
        val genannt = dao.alleIdentitaeten().mapNotNull { it.imageAsset }.toSet()

        assertEquals("Genannte Bildnamen", 62, genannt.size)
        assertEquals("Dateien im Paket", 62, imPaket.size)
        assertEquals("Überzählig: ${imPaket - genannt} · Fehlend: ${genannt - imPaket}", genannt, imPaket)
    }

    // ── Herkunft ─────────────────────────────────────────────────────────────

    /**
     * Die Abnahme hält fest, **welcher** Katalog abgenommen wurde.
     *
     * Ohne diese Zeile bestätigt der Lauf Zahlen, ohne zu sagen, woraus sie
     * entstanden sind — und ein späterer Import wäre von diesem nicht zu
     * unterscheiden.
     */
    @Test
    fun dieAbnahmeNenntDenKatalogDenSieAbnimmt() {
        val herkunft = requireNotNull(dao.herkunft()) { "catalog_meta hat keine Zeile mit id = 1" }
        assertEquals("TRD-2025", herkunft.sourceSetCode)
        assertTrue("Prüfsumme der Quelle fehlt", herkunft.sourceChecksum.length == 64)

        val sets = dao.kartensets()
        assertEquals(1, sets.size)
        assertEquals(62, sets[0].cardCount)
    }

    private companion object {
        /** Wie im Import-Werkzeug festgelegt (`Main.kt`, `ASSET_PFAD`). */
        const val BILDVERZEICHNIS = "cards/"

        /** JPEG beginnt mit `FF D8 FF` — dieselbe Kennung, die `T-012` prüft. */
        fun istJpeg(bytes: ByteArray): Boolean =
            bytes.size > 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
    }
}
