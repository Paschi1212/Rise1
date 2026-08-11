package de.myhornets.rise1.catalog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-014 — Abnahme gegen die **tatsächlich ausgelieferte** Datenbank.
 *
 * Warum instrumentiert und nicht als JVM-Test: Belegt werden soll das Asset
 * im APK, geöffnet von Room über den Android-SQLite der Plattform. Ein Test
 * gegen eine zur Laufzeit erzeugte Datenbank belegt darüber nichts — er wäre
 * auch dann grün, wenn das Asset fehlte oder ein anderes Schema trüge.
 *
 * Der eigentliche Nachweis passiert bereits beim ersten Zugriff: Room
 * vergleicht den `identityHash` in `room_master_table` mit dem einkompilierten
 * und prüft anschließend Tabellen, Spalten und Indizes gegen das erwartete
 * Schema. Passt etwas nicht, scheitert schon `@Before` — die Zählungen darunter
 * sind die zweite Hälfte, nicht die erste.
 *
 * Aufruf (Gerät oder Emulator nötig):
 *
 *     ./gradlew :catalog:connectedDebugAndroidTest
 *
 * Bewusst **nicht** Teil von `./gradlew checkAll`: `check` fährt keine
 * instrumentierten Tests. Vermerkt in Testing.md.
 */
@RunWith(AndroidJUnit4::class)
class CatalogAssetTest {

    private lateinit var db: CatalogDatabase
    private lateinit var dao: CatalogDao

    @Before
    fun oeffneDasAusgelieferteAsset() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Ohne das Löschen öffnet Room eine Kopie aus einem früheren Lauf und
        // der Test sagt nichts mehr über das aktuelle Asset aus.
        context.deleteDatabase(CatalogAsset.LOCAL_NAME)
        db = CatalogAsset.open(context)
        dao = db.catalogDao()
    }

    @After
    fun schliesse() {
        db.close()
    }

    @Test
    fun dasAssetTraegt62Identitaeten() {
        assertEquals(62, dao.anzahlIdentitaeten())
    }

    @Test
    fun dieRollenverteilungIst13_18_18_13() {
        assertEquals(13, dao.anzahlNachRolle(CatalogRole.LEADER))
        assertEquals(18, dao.anzahlNachRolle(CatalogRole.GUARDIAN))
        assertEquals(18, dao.anzahlNachRolle(CatalogRole.ASSASSIN))
        assertEquals(13, dao.anzahlNachRolle(CatalogRole.TRAITOR))
    }

    @Test
    fun dasAssetTraegt295Rulings() {
        assertEquals(295, dao.anzahlRulings())
    }

    @Test
    fun jedeIdentitaetNenntEinBild() {
        assertEquals(0, dao.anzahlOhneBild())
    }

    @Test
    fun dieSlugsSindUeberDenGanzenBestandEindeutig() {
        val slugs = dao.alleIdentitaeten().map { it.slugAscii }
        assertEquals(62, slugs.size)
        assertEquals(62, slugs.toSet().size)
    }

    @Test
    fun genau13IdentitaetenHabenKeinenUnveilCost() {
        // T-011: Die 13 Leader liegen von Beginn an offen und haben keinen.
        val ohne = dao.alleIdentitaeten().filter { it.unveilCost == null }
        assertEquals(13, ohne.size)
        assertTrue(ohne.all { it.role == CatalogRole.LEADER })
    }

    @Test
    fun derHerkunftsnachweisIstDerAusT013() {
        val herkunft = requireNotNull(dao.herkunft()) { "catalog_meta hat keine Zeile mit id = 1" }
        assertEquals("TRD-2025", herkunft.sourceSetCode)
        // SHA-256 der Quelldatei aus T-010. Ändert sich der Katalog, ändert
        // sich diese Zeile bewusst mit — sie ist der Grund, warum sie hier steht.
        assertEquals(
            "cdc67d6bd312b47e624ff8e2d33b190cd013a612d7cbcd57116ce0cdb87b21f7",
            herkunft.sourceChecksum,
        )
    }

    @Test
    fun dasKartensetIstVollstaendigBeschrieben() {
        val sets = dao.kartensets()
        assertEquals(1, sets.size)
        assertEquals("TRD-2025", sets[0].setCode)
        assertEquals(62, sets[0].cardCount)
    }

    // ── Die Abfragen aus dem Validierungspunkt zu T-014 ──────────────────────

    @Test
    fun filterNachRolleLiefertNurDieseRolle() {
        val leader = dao.nachRolle(CatalogRole.LEADER)
        assertEquals(13, leader.size)
        assertTrue(leader.all { it.role == CatalogRole.LEADER })
    }

    @Test
    fun filterNachFarbeLiefertNurDieseFarbe() {
        val blau = dao.nachFarbe("blue")
        assertTrue(blau.isNotEmpty())
        assertTrue(blau.all { it.color == "blue" })
    }

    @Test
    fun dieSucheFindetUeberDenAsciiSlugAuchAkzentnamen() {
        // "The Ætherist" ist über `aetherist` erreichbar — dafür gibt es den Slug.
        val treffer = dao.suche("aetherist")
        assertTrue(
            "Erwartet: The Ætherist über den ASCII-Slug gefunden",
            treffer.any { it.slugAscii == "the-aetherist" },
        )
    }

    @Test
    fun dieSucheFindetImRegeltext() {
        val treffer = dao.suche("unveil")
        assertTrue("Erwartet: mindestens ein Regeltext enthält 'unveil'", treffer.isNotEmpty())
    }

    // ── T-015: die Filtermarken ──────────────────────────────────────────────

    @Test
    fun dieSchluesselwortmengeIstGeschlossen() {
        // Der Validierungspunkt zu T-015: Über alle 62 Karten entsteht nichts
        // außerhalb des Vokabulars. Kommt ein Kartensatz mit einem neuen
        // Schlüsselwort, bricht schon der Import ab — dieser Test ist die
        // zweite Sperre, auf der ausgelieferten Datei.
        assertEquals(
            listOf(CatalogKeyword.UNDERCOVER, CatalogKeyword.UNVEIL),
            dao.alleSchluesselwoerter(),
        )
    }

    @Test
    fun dieMarkenStehenAnDenKartenDieSieTragen() {
        assertEquals(67, dao.anzahlSchluesselwortPaare())
        assertEquals(18, dao.anzahlMitSchluesselwort(CatalogKeyword.UNDERCOVER))
        assertEquals(49, dao.anzahlMitSchluesselwort(CatalogKeyword.UNVEIL))
    }

    @Test
    fun derFilterNachSchluesselwortLiefertGenauDieseKarten() {
        val undercover = dao.nachSchluesselwort(CatalogKeyword.UNDERCOVER)
        assertEquals(18, undercover.size)
        assertTrue(
            "Jede Undercover-Karte muss die Marke auch einzeln melden",
            undercover.all { CatalogKeyword.UNDERCOVER in dao.schluesselwoerter(it.identityUid) },
        )
    }

    @Test
    fun keineKarteOhneUnveilCostTraegtDieUnveilMarke() {
        // Beobachtung, kein Regelschluss: Die 13 Leader haben weder Kosten noch
        // Marke. Bricht das in einem späteren Satz, ist das ein Befund und
        // keine Kleinigkeit — die Marke käme dann aus einer anderen Quelle als
        // dem Regeltext.
        val ohneKosten = dao.alleIdentitaeten().filter { it.unveilCost == null }
        assertTrue(
            ohneKosten.none { CatalogKeyword.UNVEIL in dao.schluesselwoerter(it.identityUid) },
        )
    }

    // ── T-016: der Regeltext-Renderer ────────────────────────────────────────

    @Test
    fun alle62RegeltexteRendernUndKeinerBehaeltEinPipe() {
        // Der Validierungspunkt zu T-016, wörtlich.
        val texte = dao.alleIdentitaeten().map { it to RulesTextParser.parse(it.textRaw) }
        assertEquals(62, texte.size)
        texte.forEach { (identitaet, gerendert) ->
            assertTrue("${identitaet.name} rendert zu nichts", !gerendert.isEmpty)
            assertFalse("${identitaet.name} behält ein Pipe", gerendert.plain.contains('|'))
        }
    }

    @Test
    fun dieZerlegungVerliertUeberDenGanzenBestandNichts() {
        // Stärker als „rendert ohne Ausnahme": Der Text muss danach noch
        // derselbe sein. Ein Zerleger, der still etwas wegwirft, wäre sonst
        // ebenfalls grün.
        dao.alleIdentitaeten().forEach { identitaet ->
            val erwartet = identitaet.textRaw.split('|')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString("\n") { zeile ->
                    if (zeile.startsWith("•")) "• " + zeile.drop(1).trimStart() else zeile
                }
            assertEquals(identitaet.name, erwartet, RulesTextParser.parse(identitaet.textRaw).plain)
        }
    }

    @Test
    fun dieSymbolcodesSindUeberDenGanzenBestandGeschlossen() {
        val codes = sortedSetOf<String>()
        fun sammle(tokens: List<RulesToken>) {
            tokens.forEach { token ->
                when (token) {
                    is RulesToken.Symbol -> codes += token.code
                    is RulesToken.Reminder -> sammle(token.tokens)
                    is RulesToken.Text -> Unit
                }
            }
        }
        dao.alleIdentitaeten().forEach { identitaet ->
            RulesTextParser.parse(identitaet.textRaw).lines.forEach { sammle(it.tokens) }
            identitaet.unveilCost?.let { sammle(RulesTextParser.parseInline(it)) }
        }
        // 16 Codes im Satz TRD-2025. Ein neuer Satz ändert diese Zeile bewusst —
        // sie ist der Grund, warum ein unbekanntes Symbol nicht unbemerkt in die
        // Anzeige rutscht.
        assertEquals(
            listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "T", "X", "Y", "a", "m"),
            codes.toList(),
        )
    }

    @Test
    fun dieAufzaehlungszeilenSindErhalten() {
        val aufzaehlungen = dao.alleIdentitaeten()
            .flatMap { RulesTextParser.parse(it.textRaw).lines }
            .count { it.isBullet }
        assertEquals(14, aufzaehlungen)
    }

    @Test
    fun rulingsHaengenAnIhrerIdentitaetUndSindGeordnet() {
        val mitRulings = dao.alleIdentitaeten().firstNotNullOfOrNull { identitaet ->
            dao.rulings(identitaet.identityUid).takeIf { it.isNotEmpty() }
        }
        val rulings = requireNotNull(mitRulings) { "Keine einzige Identität hat Rulings" }
        assertEquals(rulings.map { it.ordinal }.sorted(), rulings.map { it.ordinal })
    }
}
