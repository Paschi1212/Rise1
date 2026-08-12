package de.myhornets.rise1.session.tls

import de.myhornets.rise1.core.verifikation.Fingerabdruck
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * **GERÄTETEST** — der TLS-Handshake mit einem echten Partie-Zertifikat.
 *
 * ## Warum es diesen Test gibt
 *
 * Weil genau hier der erste Zwei-Geräte-Versuch gescheitert ist:
 *
 * ```
 * android.security.KeyStoreException: Incompatible digest
 * public error code: 13 · internal Keystore code: -13
 * Error::Km(INCOMPATIBLE_DIGEST)
 * ```
 *
 * Der Schlüssel autorisierte ausschließlich `DIGEST_SHA256`. Conscrypt bildet
 * den Hash beim Handshake aber selbst und lässt den Keystore den **fertigen**
 * Digest signieren — das ist `NONEwithECDSA`. Der Keymaster wies die Operation
 * ab, der Handshake brach auf der **Serverseite** ab, und der Fehler kam als
 * `SSLHandshakeException` aus `Sockettransport.leseSchleife` heraus. Der Host
 * meldete `Getrennt`, der Gast „Verbindung abgebrochen", und niemand sah, dass
 * es der Schlüssel war.
 *
 * ## Warum das nur hier prüfbar ist
 *
 * ADR-008 sagt es für den ganzen TLS-Pfad: *„Was er nicht kann: TLS mit einem
 * echten Partie-Zertifikat, weil sich ein selbstsigniertes Zertifikat in reinem
 * JVM ohne zusätzliche Bibliothek nicht ausstellen lässt (ADR-006 macht das im
 * `AndroidKeyStore`)."* Ein Schlüssel mit Keymaster-Autorisierungen existiert
 * nur auf einem Gerät — und nur dort kann er eine Operation ablehnen.
 *
 * ## Was hier **nicht** passiert
 *
 * Keine Abschwächung. Der einzige `TrustManager` ist [FingerabdruckPruefer],
 * und [B_einFalscherFingerabdruckKommtNichtDurch] belegt, dass er weiterhin
 * abweist. Die Reparatur der Digest-Liste darf die Prüfung nicht nebenbei
 * aufweichen — deshalb steht der Gegenfall direkt neben dem Erfolgsfall.
 *
 * ## Aufruf
 *
 * ```
 * ./gradlew :session:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=de.myhornets.rise1.session.tls.TlsHandshakeGeraetTest
 * ```
 */
@RunWith(AndroidJUnit4::class)
class TlsHandshakeGeraetTest {

    /** Je Lauf eine eigene Partie — sonst hinge der Test an dem, was vorher lief. */
    private val matchUid = "test-" + UUID.randomUUID()

    private val fleissig = Executors.newCachedThreadPool()
    private var lauscher: ServerSocket? = null

    @After
    fun raeumeAuf() {
        runCatching { lauscher?.close() }
        fleissig.shutdownNow()
        // Der Schlüssel gehört zu dieser Partie und wird mit ihr weggeworfen —
        // ADR-006. Ein Test, der Schlüssel im Keystore liegen lässt, füllt ihn.
        runCatching { AndroidKeyStoreZertifikat.fuerPartie(matchUid).entferne() }
    }


    /** Nimmt genau eine Verbindung an und gibt zurück, was darauf ankam. */
    private fun nimmAn(server: ServerSocket, erwarteteBytes: Int): Future<ByteArray> =
        fleissig.submit(
            Callable {
                server.accept().use { angenommen ->
                    // Der Handshake läuft beim ersten Lesen — genau dort ist er
                    // vorher zerbrochen.
                    val ein = angenommen.getInputStream()
                    val puffer = ByteArray(erwarteteBytes)
                    var gelesen = 0
                    while (gelesen < erwarteteBytes) {
                        val n = ein.read(puffer, gelesen, erwarteteBytes - gelesen)
                        if (n < 0) break
                        gelesen += n
                    }
                    puffer.copyOf(gelesen)
                }
            },
        )

    // ── A) Der Handshake gelingt ────────────────────────────────────────────

    @Test
    fun A_einEchterHandshakeGelingtUndTraegtBytes() {
        val zertifikat = AndroidKeyStoreZertifikat.fuerPartie(matchUid)
        val server = zertifikat.serverSocketFabrik()
            .createServerSocket(0, 4, InetAddress.getLoopbackAddress()) as SSLServerSocket
        lauscher = server
        server.needClientAuth = false

        val gruss = "rise".toByteArray()
        val empfangen = nimmAn(server, gruss.size)

        val pruefer = FingerabdruckPruefer(matchUid, zertifikat.fingerabdruck())
        val roh = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val tls = TlsFabrik.clientFabrik(pruefer)
            .createSocket(roh, "127.0.0.1", server.localPort, true) as SSLSocket
        tls.useClientMode = true

        // Genau das warf vorher INCOMPATIBLE_DIGEST auf der Serverseite.
        tls.startHandshake()

        assertNotNull("Ohne ausgehandelte Chiffre gab es keinen Handshake.", tls.session.cipherSuite)
        assertTrue("Die Sitzung ist nicht gültig.", tls.session.isValid)

        tls.getOutputStream().write(gruss)
        tls.getOutputStream().flush()

        assertArrayEquals("Es kam nicht an, was geschickt wurde.", gruss, empfangen.get(15, TimeUnit.SECONDS))
        assertEquals(
            "Der Prüfer sah einen anderen Fingerabdruck als der Host zeigt.",
            zertifikat.fingerabdruck(),
            pruefer.zuletztGesehen,
        )
        tls.close()
    }

    // ── B) Der falsche Fingerabdruck kommt nicht durch ──────────────────────

    @Test
    fun B_einFalscherFingerabdruckKommtNichtDurch() {
        val zertifikat = AndroidKeyStoreZertifikat.fuerPartie(matchUid)
        val server = zertifikat.serverSocketFabrik()
            .createServerSocket(0, 4, InetAddress.getLoopbackAddress()) as SSLServerSocket
        lauscher = server
        // Die Annahme läuft mit, damit der Server überhaupt bis zum Handshake
        // kommt; sie darf scheitern.
        fleissig.submit { runCatching { server.accept().use { it.getInputStream().read() } } }

        // Ein gültiger, aber **anderer** Fingerabdruck — nicht etwa Unsinn:
        // Geprüft werden soll die Ablehnung, nicht die Eingabeprüfung.
        val fremder = Fingerabdruck.vonHostzertifikat("eine-andere-partie", zertifikat.der())
        val pruefer = FingerabdruckPruefer(matchUid, fremder)

        val roh = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val tls = TlsFabrik.clientFabrik(pruefer)
            .createSocket(roh, "127.0.0.1", server.localPort, true) as SSLSocket
        tls.useClientMode = true

        val gescheitert = runCatching { tls.startHandshake() }.exceptionOrNull()

        assertNotNull("Mit falschem Fingerabdruck kam eine Verbindung zustande.", gescheitert)
        assertEquals(
            "Der Prüfer hat das vorgelegte Zertifikat nicht angesehen.",
            zertifikat.fingerabdruck(),
            pruefer.zuletztGesehen,
        )
        assertTrue(
            "Der gesehene Fingerabdruck darf nicht der erwartete sein — sonst prüft der Test nichts.",
            pruefer.zuletztGesehen != fremder,
        )
        runCatching { tls.close() }
    }

    // ── C) Ein Zertifikat je Partie ─────────────────────────────────────────

    @Test
    fun C_zweiAufrufeErgebenDasselbeZertifikat() {
        // ADR-006: „Idempotent: Ein zweiter Aufruf für dieselbe Partie erzeugt
        // kein neues Zertifikat. Täte er es, änderte sich der Fingerabdruck, und
        // jeder Client, der den alten abgelesen hat, käme nicht mehr herein."
        val erstes = AndroidKeyStoreZertifikat.fuerPartie(matchUid)
        val zweites = AndroidKeyStoreZertifikat.fuerPartie(matchUid)

        assertArrayEquals("Die DER-Kodierung unterscheidet sich.", erstes.der(), zweites.der())
        assertEquals("Der Fingerabdruck unterscheidet sich.", erstes.fingerabdruck(), zweites.fingerabdruck())
        assertEquals(matchUid, zweites.matchUid)
    }

    @Test
    fun C_zweiPartienHabenVerschiedeneZertifikate() {
        // Die Gegenprobe zur Idempotenz: Ein Zertifikat gehört zu **einer**
        // Partie und ist über Partien hinweg kein wiedererkennbares Merkmal.
        val andereUid = "test-" + UUID.randomUUID()
        try {
            val hier = AndroidKeyStoreZertifikat.fuerPartie(matchUid)
            val dort = AndroidKeyStoreZertifikat.fuerPartie(andereUid)

            assertTrue("Zwei Partien teilen sich ein Zertifikat.", !hier.der().contentEquals(dort.der()))
            assertTrue(
                "Zwei Partien teilen sich einen Fingerabdruck.",
                hier.fingerabdruck() != dort.fingerabdruck(),
            )
        } finally {
            runCatching { AndroidKeyStoreZertifikat.fuerPartie(andereUid).entferne() }
        }
    }
}
