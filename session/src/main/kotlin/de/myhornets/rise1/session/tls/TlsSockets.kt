package de.myhornets.rise1.session.tls

import de.myhornets.rise1.core.verifikation.Fingerabdruck
import de.myhornets.rise1.transport.Gegenstelle
import de.myhornets.rise1.transport.Lauschposten
import de.myhornets.rise1.transport.Socketquelle
import de.myhornets.rise1.transport.TransportFehler
import de.myhornets.rise1.transport.Verbindungsfehler
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * T-070 — die TLS-Seite des Sockettransports.
 *
 * ## Die Grenze, die ADR-008 zieht
 *
 * ADR-008 trennt zwei Fragen, und diese Datei ist die Antwort auf die zweite:
 *
 * - *„Wie funktioniert die Socket-Mechanik?"* — `Sockettransport` in
 *   `:transport`. Prüfbar über Klartext-Schleifensockets auf `localhost`.
 * - *„Wie wird der konkrete TLS-Socket erzeugt?"* — hier. **Gerätetest.**
 *
 * ADR-008, „Was ein JVM-Test hier prüfen kann": *„Was er nicht kann: TLS mit
 * einem echten Partie-Zertifikat, weil sich ein selbstsigniertes Zertifikat in
 * reinem JVM ohne zusätzliche Bibliothek nicht ausstellen lässt (ADR-006 macht
 * das im `AndroidKeyStore`). Dieser Teil ist ein **Gerätetest** und wird als
 * solcher gekennzeichnet, statt ihn mit einem abgeschwächten TrustManager
 * scheinbar prüfbar zu machen."*
 *
 * Deshalb steht hier keine Zeile, die sich in einem JVM-Test ausführen ließe,
 * und deshalb gibt es **keine Klartext-Variante** dieser beiden Klassen im
 * Auslieferungsstand. Die Klartext-Fabriken für die Socket-Mechanik liegen im
 * Testquellbaum von `:transport` — sie sprechen kein TLS und können deshalb
 * auch keine TLS-Prüfung umgehen.
 *
 * ## Warum diese Datei in `:session` liegt
 *
 * Dieselben zwei Gründe wie bei [Partiezertifikat]: `:transport` hat keine
 * Modulkante (TDD 2.2), und ein Partie-Zertifikat ist Sitzungswissen (ADR-006).
 */

/**
 * Die Client-Seite: baut eine geprüfte TLS-Verbindung zu **einem** Host auf.
 *
 * ## Der Handshake passiert hier, nicht später
 *
 * [SSLSocket.startHandshake] wird ausdrücklich aufgerufen. Ohne ihn handelte
 * der Socket erst beim ersten Schreiben aus — und die Fingerabdruckprüfung
 * fiele **hinter** das erste `Verbunden`. Ein `Verbunden` würde dann eine
 * ungeprüfte Leitung melden, und `T-101` schickte seinen Handshake auf gut
 * Glück los.
 *
 * ## Ein Prüfer je Versuch
 *
 * [FingerabdruckPruefer] hält fest, was zuletzt vorgelegt wurde. Ein
 * gemeinsamer Prüfer über mehrere Versuche hinweg würde diese Auskunft
 * vermischen; ein neuer je Versuch kostet nichts.
 */
class TlsSocketquelle(
    /** Die Partie — sie geht in die Ableitung des Fingerabdrucks ein (ADR-006). */
    private val matchUid: String,
    /** Was der Nutzer vom Bildschirm des Hosts abgelesen oder als QR erfasst hat. */
    private val erwartet: Fingerabdruck,
    /** Wo der Host lauscht — aus der Dienstsuche (`T-069`). */
    private val wirt: String,
    private val port: Int,
    /** Zeitlimit für den TCP-Aufbau. Der TLS-Handshake läuft danach ohne eigenes Limit. */
    private val zeitlimitMillis: Int = ZEITLIMIT_MILLIS,
) : Socketquelle {

    override fun verbinde(gegenstelle: Gegenstelle): Socket {
        val pruefer = FingerabdruckPruefer(matchUid, erwartet)
        val roh = Socket()
        try {
            roh.connect(InetSocketAddress(wirt, port), zeitlimitMillis)
            val tls = TlsFabrik.clientFabrik(pruefer)
                .createSocket(roh, wirt, port, /* autoClose = */ true) as SSLSocket
            tls.useClientMode = true
            tls.startHandshake()
            return tls
        } catch (fehler: SSLException) {
            schliesseLeise(roh)
            throw Verbindungsfehler(deuteTls(pruefer, fehler), fehler)
        } catch (fehler: SocketTimeoutException) {
            schliesseLeise(roh)
            throw Verbindungsfehler(TransportFehler.Zeitueberschreitung(zeitlimitMillis.toLong()), fehler)
        } catch (fehler: ConnectException) {
            schliesseLeise(roh)
            throw Verbindungsfehler(TransportFehler.KeinGemeinsamesNetz, fehler)
        } catch (fehler: NoRouteToHostException) {
            schliesseLeise(roh)
            throw Verbindungsfehler(TransportFehler.KeinGemeinsamesNetz, fehler)
        } catch (fehler: UnknownHostException) {
            schliesseLeise(roh)
            throw Verbindungsfehler(TransportFehler.KeinGemeinsamesNetz, fehler)
        } catch (fehler: IOException) {
            schliesseLeise(roh)
            throw fehler
        }
    }

    /**
     * Ein gescheiterter Handshake — war es der Fingerabdruck?
     *
     * JSSE verpackt die `CertificateException` des Prüfers in eine
     * `SSLHandshakeException`. Statt deren Meldungstext zu zerlegen, wird
     * gefragt, was der Prüfer gesehen hat: Nur wenn er ein Zertifikat vorgelegt
     * bekam, dessen Fingerabdruck **nicht** stimmt, ist es dieser Fall.
     *
     * Die Unterscheidung ist nicht kosmetisch. `T-065`: *„Ein Angriff darf nicht
     * wie eine Störung aussehen."*
     */
    private fun deuteTls(pruefer: FingerabdruckPruefer, fehler: SSLException): TransportFehler {
        val gesehen = pruefer.zuletztGesehen
        return if (gesehen != null && !erwartet.stimmtUeberein(gesehen)) {
            TransportFehler.FingerabdruckPasstNicht(erwartet.lesbar, gesehen.lesbar)
        } else {
            TransportFehler.VerbindungAbgebrochen("TLS: ${fehler.javaClass.simpleName}")
        }
    }

    private fun schliesseLeise(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
            // Ein Socket, der sich nicht schließen lässt, ist bereits zu.
        }
    }

    companion object {
        /**
         * Zehn Sekunden für den TCP-Aufbau.
         *
         * Lang genug für ein müdes WLAN, kurz genug, dass „kein gemeinsames
         * Netz" nicht wie ein Hänger aussieht — der Fall, den ADR-001 erklärt
         * haben will statt ihn als Zeitüberschreitung zu zeigen.
         */
        const val ZEITLIMIT_MILLIS = 10_000
    }
}

/**
 * Die Host-Seite: der lauschende TLS-Socket mit dem Partie-Zertifikat.
 *
 * ## Kein Client-Zertifikat
 *
 * `setNeedClientAuth` wird **nicht** gesetzt. [FingerabdruckPruefer] sagt
 * warum: *„Wer ein Client sein darf, entscheidet der Handshake aus TDD 9.3 mit
 * dem `rejoin_token` — nicht die Transportschicht. Ein Client-Zertifikat wäre
 * ein zweites Geheimnis mit derselben Aufgabe und einem eigenen Lebenszyklus."*
 *
 * ## Port 0 ist der Normalfall
 *
 * Das Betriebssystem vergibt einen freien Port; `T-068` kündigt ihn danach über
 * `NsdManager` an. Ein fest verdrahteter Port wäre eine Kollision, auf die
 * niemand vorbereitet ist.
 */
class TlsLauscher(
    private val zertifikat: AndroidKeyStoreZertifikat,
    private val port: Int = 0,
    /** Wie viele Verbindungswünsche das Betriebssystem puffert, bevor es ablehnt. */
    private val rueckstau: Int = RUECKSTAU,
) : Lauschposten {

    override fun oeffne(): ServerSocket {
        val server = zertifikat.serverSocketFabrik().createServerSocket(port, rueckstau)
        (server as? SSLServerSocket)?.needClientAuth = false
        return server
    }

    companion object {
        /** Acht Sitzplätze, ein wenig Luft für gleichzeitiges Anklopfen. */
        const val RUECKSTAU = 16
    }
}
