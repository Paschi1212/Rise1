package de.myhornets.rise1.session

import de.myhornets.rise1.core.verifikation.Fingerabdruck
import de.myhornets.rise1.session.tls.Partiezertifikat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * T-100 — eine Partie anlegen.
 *
 * ## Die Tests, um die es hier geht
 *
 * [derFingerabdruckKommtAusDemZertifikatDieserPartie]. ADR-002A: *„Wer geprüft
 * wird, darf nicht die Prüfgrundlage liefern."* Ein Fingerabdruck, der nicht am
 * Zertifikat hängt, wäre eine Zahl, die man vorliest, ohne dass sie etwas prüft.
 *
 * [einZertifikatAusEinerAnderenPartieWirdAbgelehnt] ist der Gegentest dazu, und
 * [zweiPartienHabenVerschiedeneSalze] hält TDD 9.1 fest: Ohne eigenes Salz wäre
 * derselbe Token in zwei Partien derselbe Hash.
 */
class PartieanlageTest {

    /** Ein Zertifikat mit vorgegebenem DER — echte Schlüssel braucht es dafür nicht. */
    private class Attrappenzertifikat(
        override val matchUid: String,
        private val bytes: ByteArray = byteArrayOf(1, 2, 3, 4, 5),
    ) : Partiezertifikat {
        override fun der(): ByteArray = bytes
    }

    private fun plan(plaetze: Int = 4) = Partieplan(
        modeUid = "modus-standard",
        hostDeviceUid = "d-host",
        plaetze = plaetze,
        catalogVersion = "2026.1",
        setCode = "TRE",
    )

    private fun anlage(
        kennung: String = "m-1",
        salz: ByteArray = ByteArray(16) { it.toByte() },
        jetzt: Long = 1_700_000_000_000,
    ) = Partieanlage({ kennung }, { salz }, { jetzt })

    // ── Der gute Weg ────────────────────────────────────────────────────────

    @Test
    fun eineNeuePartieStehtAufSetup() {
        val k = anlage().lege(plan()) { Attrappenzertifikat(it) }.kontext

        assertEquals(Partiestatus.SETUP, k.status)
        assertEquals("m-1", k.matchUid)
        assertEquals("d-host", k.hostDeviceUid)
        assertEquals(4, k.plaetze)
        assertEquals(1_700_000_000_000L, k.angelegtAm)
    }

    @Test
    fun derFingerabdruckKommtAusDemZertifikatDieserPartie() {
        val zert = Attrappenzertifikat("m-1")
        val k = anlage().lege(plan()) { zert }.kontext

        assertEquals(Fingerabdruck.vonHostzertifikat("m-1", zert.der()), k.fingerabdruck)
        assertEquals(k.fingerabdruck.lesbar, k.tischcode)
    }

    @Test
    fun einAnderesZertifikatErgibtEinenAnderenFingerabdruck() {
        val a = anlage().lege(plan()) { Attrappenzertifikat(it, byteArrayOf(1, 2, 3)) }.kontext
        val b = anlage().lege(plan()) { Attrappenzertifikat(it, byteArrayOf(9, 9, 9)) }.kontext
        assertNotEquals(a.fingerabdruck, b.fingerabdruck)
    }

    @Test
    fun zweiPartienHabenVerschiedeneKennungenUndSalze() {
        // Mit den echten Quellen, nicht mit den eingesetzten: Hier wird geprüft,
        // dass die Voreinstellung taugt — UUID und SecureRandom.
        val anlage = Partieanlage()
        val a = anlage.lege(plan()) { Attrappenzertifikat(it) }.kontext
        val b = anlage.lege(plan()) { Attrappenzertifikat(it) }.kontext

        assertNotEquals(a.matchUid, b.matchUid)
        assertNotEquals(a.salz, b.salz, "Ohne eigenes Salz wäre derselbe Token derselbe Hash (TDD 9.1).")
        assertTrue(a.salz.length >= 2 * Partieanlage.SALZ_BYTES)
    }

    @Test
    fun dieAnlageNennntDieNaechstenSchritte() {
        // Bewusst Text und keine ausgeführte Wirkung: `:session` darf die Ablage
        // nicht anfassen. Eine Liste, die so täte, als hätte sie es getan, wäre
        // schlimmer als keine.
        val eroeffnung = anlage().lege(plan()) { Attrappenzertifikat(it) }
        assertEquals(3, eroeffnung.naechsteSchritte.size)
        assertTrue(eroeffnung.naechsteSchritte.any { it.contains("match_created") })
    }

    @Test
    fun dasSalzWandertInDieBeitrittsstelle() {
        // Die Integration, um die es geht: `T-101` bekommt das Salz aus dem
        // Kontext und nicht von Hand. Ein von Hand weitergereichtes Salz ist
        // eines, das irgendwann nicht mehr passt.
        val kontext = anlage().lege(plan()) { Attrappenzertifikat(it) }.kontext
        val tisch = object : Tischnachschlag {
            var hash: String? = null
            override fun status(matchUid: String) = Partiestatus.SETUP
            override fun plaetze(matchUid: String) = 4
            override fun belegung(matchUid: String) = emptyList<Sitzplatz>()
            override fun legeSitzplatzAn(
                matchUid: String,
                sitzplatz: Int,
                anzeigename: String,
                tokenHash: String,
                deviceUid: String,
            ): String {
                hash = tokenHash
                return "p-0"
            }

            override fun ersetzeTokenHash(participantUid: String, tokenHash: String) = Unit
        }

        val antwort = Partieanlage().beitrittsstelle(kontext, tisch)
            .beitreten(Beitrittsgesuch(kontext.matchUid, "d-a", "Paschi")) as Beitrittsantwort.Angenommen

        assertEquals(RejoinPruefer.tokenHash(antwort.rejoinToken, kontext.salz), tisch.hash)
    }

    // ── Fehlerfälle ─────────────────────────────────────────────────────────

    @Test
    fun einZertifikatAusEinerAnderenPartieWirdAbgelehnt() {
        val fehler = assertFailsWith<IllegalArgumentException> {
            anlage(kennung = "m-1").lege(plan()) { Attrappenzertifikat("m-fremd") }
        }
        assertTrue(fehler.message!!.contains("m-fremd"))
    }

    @Test
    fun einTischOhneMitspielerIstKeiner() {
        assertFailsWith<IllegalArgumentException> { plan(plaetze = 1) }
    }

    @Test
    fun mehrPlaetzeAlsInEinenSchnappschussPassenWerdenAbgelehnt() {
        // Keine Spielregel — die Grenze des Nutzlastformats (ADR-007).
        assertFailsWith<IllegalArgumentException> { plan(plaetze = Partieplan.HOECHSTENS_PLAETZE + 1) }
    }

    @Test
    fun einePartieOhneKatalogfassungLaesstSichNichtAnlegen() {
        assertFailsWith<IllegalArgumentException> {
            Partieplan("modus", "d-host", 4, catalogVersion = " ", setCode = "TRE")
        }
    }

    @Test
    fun einKontextInEinemAnderenStatusLaesstSichNichtBauen() {
        assertFailsWith<IllegalArgumentException> {
            Partiekontext(
                matchUid = "m-1",
                hostDeviceUid = "d-host",
                status = Partiestatus.ACTIVE,
                plaetze = 4,
                salz = "ab",
                fingerabdruck = Fingerabdruck.vonHostzertifikat("m-1", byteArrayOf(1)),
                angelegtAm = 1,
            )
        }
    }

    @Test
    fun einZuKurzesSalzWirdBemerkt() {
        assertFailsWith<IllegalArgumentException> {
            Partieanlage(salzquelle = { ByteArray(4) }).lege(plan()) { Attrappenzertifikat(it) }
        }
    }

    @Test
    fun derKontextZeigtDasSalzNichtImText() {
        val k = anlage().lege(plan()) { Attrappenzertifikat(it) }.kontext
        assertFalse(k.toString().contains(k.salz))
        assertTrue(k.toString().contains(k.tischcode), "Der Tischcode gehört genau dorthin.")
    }
}
