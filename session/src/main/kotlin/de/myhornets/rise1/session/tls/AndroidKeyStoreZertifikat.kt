package de.myhornets.rise1.session.tls

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.security.auth.x500.X500Principal

/**
 * T-070 — das Host-Zertifikat nach [[ADR-006 Host-Zertifikat im AndroidKeyStore]].
 *
 * ## Nicht geprüft, und das steht hier
 *
 * **Keine Zeile dieser Datei ist in der Entwicklungsumgebung ausgeführt worden.**
 * `AndroidKeyStore` gibt es nur auf einem Gerät. Was hier steht, ist gegen die
 * Android-Dokumentation und ADR-006 geschrieben — die *Prüfung* auf
 * Client-Seite dagegen ([FingerabdruckPruefer]) ist reines JSSE und vollständig
 * getestet. Das ist die richtige Aufteilung: Der sicherheitskritische Teil ist
 * der Vergleich, nicht die Schlüsselerzeugung.
 *
 * ## Warum kein BouncyCastle
 *
 * ADR-006, kurz: Die Java-Standardbibliothek kann kein X.509 erzeugen,
 * BouncyCastle kostet mehrere Megabyte und eine weitere ungeprüfte Version, und
 * ASN.1 von Hand zu schreiben ist die schlechteste der drei Möglichkeiten —
 * *„Wer ASN.1 von Hand schreibt, schreibt früher oder später eine Längenangabe
 * falsch."* `KeyGenParameterSpec` erzeugt Schlüsselpaar **und** selbstsigniertes
 * Zertifikat in einem Aufruf, ohne jede zusätzliche Abhängigkeit.
 *
 * ## Ein Zertifikat je Partie
 *
 * Der Alias enthält die `match_uid`. Ein dauerhaftes Host-Zertifikat wäre ein
 * wiedererkennbares Merkmal des Geräts über Partien hinweg — und es gäbe keinen
 * Grund dafür. [entferne] wirft es weg, wenn die Partie vorbei ist.
 *
 * ## Was hier bewusst fehlt
 *
 * **`setIsStrongBoxBacked`.** Auf vielen Geräten nicht vorhanden; der Aufruf
 * scheiterte dann. Der Schutz, um den es geht, kommt aus dem
 * Fingerabdruckvergleich, nicht aus dem Speicherort (ADR-006).
 *
 * **`setUserAuthenticationRequired`.** Der Host müsste sonst bei jedem
 * Handshake entsperrt werden. Das Zertifikat schützt keine Geheimnisse — die
 * Nutzdaten sind bereits Ende-zu-Ende verschlüsselt (TDD 7).
 */
class AndroidKeyStoreZertifikat private constructor(
    override val matchUid: String,
    private val eintrag: KeyStore.PrivateKeyEntry,
) : Partiezertifikat {

    private val zertifikat: X509Certificate = eintrag.certificate as X509Certificate

    override fun der(): ByteArray = zertifikat.encoded

    /**
     * Die Fabrik für den lauschenden Socket des Hosts.
     *
     * Der private Schlüssel geht **nicht** durch diesen Code: Der
     * `KeyManagerFactory` bekommt den KeyStore, und der liefert für einen
     * Eintrag aus dem `AndroidKeyStore` nur ein Stellvertreterobjekt ohne
     * Schlüsselmaterial. Das Signieren passiert in der Hardware.
     */
    fun serverSocketFabrik(): SSLServerSocketFactory = kontext().serverSocketFactory

    private fun kontext(): SSLContext {
        val schluesselspeicher = KeyStore.getInstance(SPEICHER).apply { load(null) }
        val fabrik = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            // Kein Passwort: Der AndroidKeyStore kennt keines. Der Schutz ist
            // das Betriebssystem, nicht eine Zeichenkette im Programmtext.
            init(schluesselspeicher, null)
        }
        return SSLContext.getInstance(TLS_FASSUNG).apply {
            init(fabrik.keyManagers, null, null)
        }
    }

    /** Wirft das Zertifikat weg. Gehört zum Aufräumen einer Partie. */
    fun entferne() {
        KeyStore.getInstance(SPEICHER).apply { load(null) }.deleteEntry(alias(matchUid))
    }

    companion object {

        private const val SPEICHER = "AndroidKeyStore"
        private const val TLS_FASSUNG = "TLSv1.2"
        private const val GUELTIG_TAGE = 30

        /**
         * Der Alias trägt die Partie — siehe „Ein Zertifikat je Partie".
         *
         * **Ohne Fassungsnummer, und das ist geprüft.** Als die Digest-Liste
         * erweitert wurde, stand die Frage, ob ein `-v2-` nötig sei, um alte
         * Schlüssel mit der engeren Autorisierung auszuschließen. Sie ist es
         * nicht: `match_uid` ist je Partie neu, also gibt es für einen neuen
         * Tisch nie einen alten Schlüssel. Eine Fassungsnummer wäre eine
         * Änderung aus Vorsicht ohne Fall dahinter — und ein zweiter Grund,
         * warum ein Alias so aussieht, wie er aussieht.
         */
        fun alias(matchUid: String): String = "rise1-host-$matchUid"

        /**
         * Erzeugt das Zertifikat einer Partie oder holt das vorhandene.
         *
         * Idempotent: Ein zweiter Aufruf für dieselbe Partie erzeugt **kein**
         * neues Zertifikat. Täte er es, änderte sich der Fingerabdruck, und
         * jeder Client, der den alten abgelesen hat, käme nicht mehr herein.
         */
        fun fuerPartie(matchUid: String): AndroidKeyStoreZertifikat {
            require(matchUid.isNotBlank()) { "Ein Zertifikat ohne Partie gibt es nicht." }
            val speicher = KeyStore.getInstance(SPEICHER).apply { load(null) }
            val vorhanden = speicher.getEntry(alias(matchUid), null) as? KeyStore.PrivateKeyEntry
            if (vorhanden != null) return AndroidKeyStoreZertifikat(matchUid, vorhanden)

            erzeuge(matchUid)
            val neu = speicher.getEntry(alias(matchUid), null) as KeyStore.PrivateKeyEntry
            return AndroidKeyStoreZertifikat(matchUid, neu)
        }

        private fun erzeuge(matchUid: String) {
            val von = Calendar.getInstance()
            val bis = (von.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, GUELTIG_TAGE) }

            val vorgabe = KeyGenParameterSpec.Builder(
                alias(matchUid),
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                // EC statt RSA: kürzere Schlüssel, schnellere Erzeugung, und auf
                // einem Telefon zählt beides. D-002 hat für die Nutzdaten
                // ohnehin moderne Verfahren gewählt.
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                // Die Autorisierung des Schlüssels, nicht die Stärke der
                // Signatur — und sie muss breiter sein als der eine Digest, mit
                // dem das Zertifikat signiert wird.
                //
                // Conscrypt bildet den Hash beim TLS-Handshake **selbst** und
                // bittet den Keystore, den fertigen Digest zu signieren; das ist
                // `NONEwithECDSA` und verlangt `DIGEST_NONE`. Außerdem darf die
                // ausgehandelte `signature_algorithms`-Erweiterung SHA-384 oder
                // SHA-512 ergeben. Ein Schlüssel, der nur SHA-256 autorisiert,
                // weist beides ab — der Keymaster antwortet mit
                // `INCOMPATIBLE_DIGEST` (13 / -13), und der Handshake bricht auf
                // der Serverseite ab, bevor ein Byte fließt.
                //
                // `DIGEST_NONE` heißt **nicht**, dass ohne Hash signiert wird:
                // Der Hash entsteht in Conscrypt statt im TEE. Der private
                // Schlüssel verlässt den AndroidKeyStore weiterhin nie, und an
                // der Fingerabdruckprüfung ändert sich nichts.
                .setDigests(
                    KeyProperties.DIGEST_NONE,
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512,
                )
                // Der Betreff ist bedeutungslos: Niemand prüft ihn. Geprüft wird
                // der Fingerabdruck (ADR-001, ADR-006). Er steht trotzdem drin,
                // weil ein Zertifikat einen braucht.
                .setCertificateSubject(X500Principal("CN=Rise1 Host"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(von.time)
                // 30 Tage. Eine Partie dauert Stunden; alles darüber ist
                // Reserve für Geräte mit schiefer Uhr.
                .setCertificateNotAfter(bis.time)
                .build()

            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, SPEICHER)
                .apply { initialize(vorgabe) }
                .generateKeyPair()
        }
    }
}

/**
 * Die Client-Seite: ein `SSLSocketFactory`, der **nur** den erwarteten Host annimmt.
 *
 * Reines JSSE, ohne Android — deshalb ist der Weg hierher derselbe auf dem
 * Gerät und in einem Test. Was fehlt, ist nur ein echter Socket.
 */
object TlsFabrik {

    private const val TLS_FASSUNG = "TLSv1.2"

    /**
     * Erzeugt die Fabrik für eine Verbindung zu genau diesem Host.
     *
     * Der [pruefer] ist der einzige `TrustManager`. Es gibt keinen zweiten und
     * keinen Rückfall auf die Systemvertrauensanker: Ein selbstsigniertes
     * Zertifikat hätte dort ohnehin nichts zu suchen, und ein zusätzlicher
     * Anker wäre eine zweite Tür.
     */
    fun clientFabrik(pruefer: FingerabdruckPruefer): SSLSocketFactory =
        SSLContext.getInstance(TLS_FASSUNG)
            .apply { init(null, arrayOf(pruefer), null) }
            .socketFactory
}
