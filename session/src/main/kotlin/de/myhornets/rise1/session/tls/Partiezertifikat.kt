package de.myhornets.rise1.session.tls

import de.myhornets.rise1.core.verifikation.Fingerabdruck
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * T-070 — TLS mit selbstsigniertem Partie-Zertifikat, nach [[ADR-006 Host-Zertifikat im AndroidKeyStore]].
 *
 * ## Warum das in `:session` liegt und nicht in `:transport`
 *
 * Zwei Gründe, und der erste ist bindend:
 *
 * 1. **Die Modulgrenzen.** Die Prüfung braucht `Fingerabdruck` aus `:core`, und
 *    `allowedModuleEdges["transport"]` ist leer. Eine Kante `transport → core`
 *    wäre eine Architekturänderung und bräuchte eine ADR — hier steht sie nicht
 *    zur Debatte.
 * 2. **Das Zertifikat gehört zu einer Partie.** ADR-006 bindet es an die
 *    `match_uid` und wirft es mit ihr weg. Eine Partie ist Sitzungswissen, kein
 *    Transportwissen. `:transport` befördert Bytes und weiß nicht, für welches
 *    Spiel.
 *
 * Vermerkt als möglicher Kandidat für eine spätere Entscheidung: Sollte sich
 * zeigen, dass `:transport` die Prüfung selbst führen muss, ist das eine ADR
 * wert — und keine stille Kante.
 *
 * ## Was hier ausdrücklich nicht existiert
 *
 * **Kein `TrustManager`, der alles annimmt.** Nicht als Debug-Schalter, nicht
 * als Testhilfe, nicht als Übergangslösung. ADR-006 sagt es wörtlich, und der
 * Grund ist banal: Solche Schalter überleben. Wer die Prüfung im Test umgehen
 * kann, prüft im Test nicht die Prüfung.
 *
 * Der einzige Weg, eine Verbindung zustande zu bringen, führt über einen
 * passenden Fingerabdruck.
 */

/**
 * Das Zertifikat einer Partie.
 *
 * Der private Schlüssel taucht in dieser Schnittstelle **nicht auf**. Nach
 * ADR-006 verlässt er den `AndroidKeyStore` nie; was hier herausgereicht wird,
 * ist die DER-Kodierung des öffentlichen Zertifikats und der daraus abgeleitete
 * Fingerabdruck.
 */
interface Partiezertifikat {

    /** Die Partie, zu der dieses Zertifikat gehört. */
    val matchUid: String

    /**
     * Die DER-Kodierung — genau das, was auch über die Leitung geht.
     *
     * ADR-006: Der Fingerabdruck wird darüber gebildet und nicht über den
     * öffentlichen Schlüssel allein. Sonst trüge ein anderes Zertifikat mit
     * demselben Schlüssel denselben Fingerabdruck.
     */
    fun der(): ByteArray

    /**
     * Der Fingerabdruck, den der Host anzeigt und der Client prüft.
     *
     * Zwölf Zeichen in drei Gruppen — dieselbe Form wie in
     * [[ADR-002A Key Verification]], mit eigenem Verwendungspräfix.
     */
    fun fingerabdruck(): Fingerabdruck = Fingerabdruck.vonHostzertifikat(matchUid, der())
}

/**
 * Die Prüfung auf Client-Seite (`T-071` im Transportweg).
 *
 * ## Warum kein gewöhnlicher `TrustManager`
 *
 * Ein selbstsigniertes Zertifikat hat **keine Kette**. Die übliche Prüfung —
 * „führt ein Pfad zu einer vertrauenswürdigen Wurzel" — kann hier grundsätzlich
 * nicht greifen; es gibt keine Wurzel und soll keine geben. An ihre Stelle
 * tritt der Vergleich mit einem Fingerabdruck, der **außerhalb des Kanals**
 * hereinkam: vom Bildschirm des Hosts abgelesen oder als QR abfotografiert
 * (ADR-001).
 *
 * Das ist derselbe Grundsatz wie in ADR-002A 3.2: **Wer geprüft wird, darf
 * nicht die Prüfgrundlage liefern.**
 *
 * ## Nur das erste Zertifikat zählt
 *
 * Geprüft wird `kette[0]` — das Zertifikat, mit dem sich die Gegenstelle
 * ausweist. Würde irgendein Element der Kette genügen, könnte ein Angreifer
 * sein eigenes Zertifikat vorlegen und das echte einfach anhängen. Die Prüfung
 * wäre dann grün, und die Verbindung liefe zum Falschen.
 */
class FingerabdruckPruefer(
    /** Die Partie — geht in die Ableitung ein und bindet den Fingerabdruck an sie. */
    private val matchUid: String,
    /** Was der Nutzer vom Bildschirm des Hosts abgelesen hat. */
    private val erwartet: Fingerabdruck,
) : X509TrustManager {

    /**
     * Der zuletzt vorgelegte Fingerabdruck — **Auskunft, kein Schalter**.
     *
     * `T-070` braucht ihn: `TransportFehler.FingerabdruckPasstNicht` trägt
     * `erwartet` **und** `gesehen`, damit die Oberfläche beide nebeneinander
     * zeigen kann. JSSE verpackt die [CertificateException] unterwegs in eine
     * `SSLHandshakeException`; ohne diese Aufzeichnung bliebe von dem, was
     * tatsächlich vorgelegt wurde, nur ein Meldungstext, den jemand zerlegen
     * müsste.
     *
     * Er wird **nach** der Ableitung und **vor** dem Vergleich gesetzt — an der
     * Prüfung selbst ändert er nichts. Ein Wert hier bedeutet nicht, dass etwas
     * angenommen wurde; der einzige Weg zu einer Verbindung führt weiterhin
     * durch [checkServerTrusted] ohne Ausnahme.
     */
    @Volatile
    var zuletztGesehen: Fingerabdruck? = null
        private set

    /**
     * Prüft den Server.
     *
     * @throws CertificateException bei leerer Kette oder abweichendem
     *   Fingerabdruck. Die Ausnahme ist der einzige vorgesehene Ausgang für den
     *   Fehlerfall — es gibt keinen Rückgabewert, den ein Aufrufer übersehen
     *   könnte.
     */
    override fun checkServerTrusted(kette: Array<out X509Certificate>?, authTyp: String?) {
        val vorgelegt = kette?.firstOrNull()
            ?: throw CertificateException(
                "Die Gegenstelle hat kein Zertifikat vorgelegt. Ohne Zertifikat gibt es " +
                    "nichts zu prüfen — und ohne Prüfung keine Verbindung.",
            )

        val gesehen = Fingerabdruck.vonHostzertifikat(matchUid, vorgelegt.encoded)
        zuletztGesehen = gesehen
        if (!erwartet.stimmtUeberein(gesehen)) {
            throw CertificateException(
                "Der Fingerabdruck des Hosts stimmt nicht: erwartet ${erwartet.lesbar}, " +
                    "gesehen ${gesehen.lesbar}. Das ist kein Verbindungsfehler — entweder ist " +
                    "es der falsche Tisch, oder jemand sitzt dazwischen.",
            )
        }
    }

    /**
     * Der Client weist sich mit TLS **nicht** aus.
     *
     * Wer ein Client sein darf, entscheidet der Handshake aus TDD 9.3 mit dem
     * `rejoin_token` — nicht die Transportschicht. Ein Client-Zertifikat wäre
     * ein zweites Geheimnis mit derselben Aufgabe und einem eigenen
     * Lebenszyklus.
     */
    override fun checkClientTrusted(kette: Array<out X509Certificate>?, authTyp: String?) {
        throw CertificateException(
            "Dieser Prüfer ist für die Server-Seite. Client-Zertifikate sind in Rise nicht " +
                "vorgesehen (TDD 9.3: Der Nachweis ist der rejoin_token).",
        )
    }

    /**
     * Leer, und das ist die Aussage.
     *
     * Es gibt keine Zertifizierungsstelle, der vertraut wird. Eine nichtleere
     * Liste hier hieße, dass irgendein fremder Aussteller etwas ausstellen
     * könnte, das dieser Client annimmt.
     */
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
