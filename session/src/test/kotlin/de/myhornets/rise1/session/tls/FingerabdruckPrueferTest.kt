package de.myhornets.rise1.session.tls

import de.myhornets.rise1.core.verifikation.Fingerabdruck
import java.math.BigInteger
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-070 / T-071 — die Host-Verifikation.
 *
 * ## Wie hier ohne echte Zertifikate geprüft wird
 *
 * `X509Certificate` ist eine abstrakte Klasse. Für die Prüfung zählt genau eine
 * ihrer Methoden: `getEncoded()`. [Testzertifikat] liefert sie aus einem
 * beliebigen Byte-Feld — damit ist der gesamte Entscheidungsweg des
 * `TrustManager` auf einer gewöhnlichen JVM prüfbar, ohne `AndroidKeyStore`,
 * ohne Schlüsselerzeugung und ohne Netz.
 *
 * Das ist kein Behelf, sondern die richtige Aufteilung: Der
 * sicherheitskritische Teil ist der **Vergleich**, nicht die
 * Schlüsselerzeugung. Was hier grün ist, ist die Zusage, auf der die ganze
 * Host-Verifikation ruht.
 *
 * ## Der Test, der zählt
 *
 * [einAbweichendesZertifikatWirdAbgewiesen] — und, fast wichtiger,
 * [einAngehaengtesEchtesZertifikatRettetNichts].
 */
class FingerabdruckPrueferTest {

    private val partie = "m-1"
    private val echtesDer = byteArrayOf(10, 20, 30, 40)
    private val fremdesDer = byteArrayOf(10, 20, 30, 41)

    private fun erwarteter() = Fingerabdruck.vonHostzertifikat(partie, echtesDer)
    private fun pruefer(erwartet: Fingerabdruck = erwarteter()) = FingerabdruckPruefer(partie, erwartet)

    // ── Der Normalfall ──────────────────────────────────────────────────────

    @Test
    fun dasErwarteteZertifikatWirdAngenommen() {
        pruefer().checkServerTrusted(arrayOf(Testzertifikat(echtesDer)), "ECDHE_ECDSA")
    }

    @Test
    fun einAbweichendesZertifikatWirdAbgewiesen() {
        val fehler = assertFailsWith<CertificateException> {
            pruefer().checkServerTrusted(arrayOf(Testzertifikat(fremdesDer)), "ECDHE_ECDSA")
        }
        assertTrue(
            fehler.message!!.contains("Fingerabdruck"),
            "Die Meldung muss sagen, worum es geht: ${fehler.message}",
        )
    }

    @Test
    fun einEinzigesAbweichendesByteReicht() {
        // Der Unterschied zwischen `echtesDer` und `fremdesDer` ist ein Bit.
        assertFailsWith<CertificateException> {
            pruefer().checkServerTrusted(arrayOf(Testzertifikat(fremdesDer)), null)
        }
    }

    // ── Nur das erste Zertifikat zählt ──────────────────────────────────────

    @Test
    fun einAngehaengtesEchtesZertifikatRettetNichts() {
        // Der Angriff, den eine nachlässige Prüfung durchlässt: Der Angreifer
        // legt sein eigenes Zertifikat vor und hängt das echte hinten an. Würde
        // irgendein Element der Kette genügen, wäre die Prüfung grün — und die
        // Verbindung liefe zum Falschen.
        assertFailsWith<CertificateException> {
            pruefer().checkServerTrusted(
                arrayOf(Testzertifikat(fremdesDer), Testzertifikat(echtesDer)),
                "ECDHE_ECDSA",
            )
        }
    }

    @Test
    fun einVorangestelltesEchtesZertifikatGenuegt() {
        // Die Gegenprobe: Steht das echte vorn, ist es das, womit sich die
        // Gegenstelle ausweist — und dann ist alles in Ordnung.
        pruefer().checkServerTrusted(
            arrayOf(Testzertifikat(echtesDer), Testzertifikat(fremdesDer)),
            "ECDHE_ECDSA",
        )
    }

    // ── Nichts vorgelegt ────────────────────────────────────────────────────

    @Test
    fun eineLeereKetteWirdAbgewiesen() {
        assertFailsWith<CertificateException> { pruefer().checkServerTrusted(emptyArray(), "x") }
    }

    @Test
    fun keineKetteWirdAbgewiesen() {
        assertFailsWith<CertificateException> { pruefer().checkServerTrusted(null, "x") }
    }

    // ── Die Partiebindung ───────────────────────────────────────────────────

    @Test
    fun einZertifikatAusEinerAnderenPartiePasstNicht() {
        // ADR-006: Der Fingerabdruck ist an die match_uid gebunden, damit er
        // sich nicht in die nächste Partie mitnehmen lässt.
        val ausAndererPartie = Fingerabdruck.vonHostzertifikat("m-2", echtesDer)
        assertFailsWith<CertificateException> {
            pruefer(ausAndererPartie).checkServerTrusted(arrayOf(Testzertifikat(echtesDer)), "x")
        }
    }

    // ── Keine Hintertüren ───────────────────────────────────────────────────

    @Test
    fun esGibtKeineVertrauenswuerdigenAussteller() {
        // Eine nichtleere Liste hieße, dass irgendein fremder Aussteller etwas
        // ausstellen könnte, das dieser Client annimmt.
        assertEquals(0, pruefer().acceptedIssuers.size)
    }

    @Test
    fun derPrueferNimmtKeineClientZertifikate() {
        // Wer Client sein darf, entscheidet der Handshake aus TDD 9.3 mit dem
        // rejoin_token — nicht die Transportschicht.
        assertFailsWith<CertificateException> {
            pruefer().checkClientTrusted(arrayOf(Testzertifikat(echtesDer)), "x")
        }
    }

    // ── Das Zertifikat selbst ───────────────────────────────────────────────

    @Test
    fun einPartiezertifikatLeitetSeinenFingerabdruckAusDerDerAb() {
        val zertifikat = object : Partiezertifikat {
            override val matchUid = partie
            override fun der() = echtesDer
        }
        assertEquals(Fingerabdruck.vonHostzertifikat(partie, echtesDer), zertifikat.fingerabdruck())
        assertEquals(12, zertifikat.fingerabdruck().zeichen.length)
    }

    @Test
    fun zweiPartienErgebenZweiFingerabdruecke() {
        val a = object : Partiezertifikat {
            override val matchUid = "m-1"
            override fun der() = echtesDer
        }
        val b = object : Partiezertifikat {
            override val matchUid = "m-2"
            override fun der() = echtesDer
        }
        assertTrue(!a.fingerabdruck().stimmtUeberein(b.fingerabdruck()))
    }

    /**
     * Ein Zertifikat, das nur eines kann: seine Bytes herausgeben.
     *
     * Alles andere wirft. Das ist Absicht — sollte die Prüfung je etwas anderes
     * aufrufen als `getEncoded()`, fällt dieser Test um und zeigt genau das an.
     * Eine Prüfung, die zusätzlich den Betreff oder die Gültigkeit auswertet,
     * wäre eine andere Prüfung als die, die ADR-006 beschreibt.
     */
    private class Testzertifikat(private val bytes: ByteArray) : X509Certificate() {
        override fun getEncoded(): ByteArray = bytes

        private fun nein(): Nothing =
            throw UnsupportedOperationException(
                "Die Host-Verifikation prüft ausschließlich die DER-Kodierung (ADR-006). " +
                    "Wird hier etwas anderes gebraucht, hat sich die Prüfung geändert.",
            )

        override fun checkValidity() = nein()
        override fun checkValidity(datum: Date?) = nein()
        override fun getVersion(): Int = nein()
        override fun getSerialNumber(): BigInteger = nein()
        override fun getIssuerDN(): Principal = nein()
        override fun getSubjectDN(): Principal = nein()
        override fun getNotBefore(): Date = nein()
        override fun getNotAfter(): Date = nein()
        override fun getTBSCertificate(): ByteArray = nein()
        override fun getSignature(): ByteArray = nein()
        override fun getSigAlgName(): String = nein()
        override fun getSigAlgOID(): String = nein()
        override fun getSigAlgParams(): ByteArray = nein()
        override fun getIssuerUniqueID(): BooleanArray = nein()
        override fun getSubjectUniqueID(): BooleanArray = nein()
        override fun getKeyUsage(): BooleanArray = nein()
        override fun getBasicConstraints(): Int = nein()
        override fun verify(schluessel: PublicKey?) = nein()
        override fun verify(schluessel: PublicKey?, anbieter: String?) = nein()
        override fun toString(): String = "Testzertifikat(${bytes.size} Bytes)"
        override fun getPublicKey(): PublicKey = nein()
        override fun hasUnsupportedCriticalExtension(): Boolean = nein()
        override fun getCriticalExtensionOIDs(): MutableSet<String> = nein()
        override fun getNonCriticalExtensionOIDs(): MutableSet<String> = nein()
        override fun getExtensionValue(oid: String?): ByteArray = nein()
    }
}
