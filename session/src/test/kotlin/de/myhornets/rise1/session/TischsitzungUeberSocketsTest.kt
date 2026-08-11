package de.myhornets.rise1.session

import de.myhornets.rise1.transport.Gegenstelle
import de.myhornets.rise1.transport.Lauschposten
import de.myhornets.rise1.transport.Sitzungsthread
import de.myhornets.rise1.transport.Sockettransport
import de.myhornets.rise1.transport.Socketquelle
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `T-101` über echte Sockets — der Nachweis, dass der Weg zusammenpasst.
 *
 * ## Was dieser Test zusätzlich zeigt
 *
 * `TischsitzungTest` prüft das Protokoll gegen ein synchrones Leitungspaar:
 * Reihenfolgen, Ablehnungen, Fehlerbilder. Er sagt nichts darüber, ob dieselbe
 * Folge auch dann trägt, wenn ein Rahmen wirklich durch einen TCP-Strom geht,
 * ein Annahmethread ihn entgegennimmt und alle Rückrufe über den einen
 * Sitzungsthread laufen.
 *
 * Genau das steht hier: **ein** Ablauf, vom `lausche` bis zum Sitzplatz, über
 * `127.0.0.1`.
 *
 * ## Was er nicht zeigt
 *
 * **TLS.** ADR-008: *„Was er nicht kann: TLS mit einem echten
 * Partie-Zertifikat, weil sich ein selbstsigniertes Zertifikat in reinem JVM
 * ohne zusätzliche Bibliothek nicht ausstellen lässt."* Die Klartextfabriken
 * hier sprechen kein TLS und können deshalb auch keine Fingerabdruckprüfung
 * abschwächen — der einzige Weg zu TLS führt weiterhin durch `TlsSocketquelle`.
 *
 * ## Alles läuft auf dem Sitzungsthread
 *
 * ADR-008: *„Alles, was oberhalb von `Transport` liegt, läuft auf dem
 * Sitzungsthread — und nur dort."* Deshalb wird hier nichts vom Testthread aus
 * an `Tischdienst` oder `Beitrittsablauf` gefasst; gelesen wird über
 * [aufSitzungsthread].
 */
class TischsitzungUeberSocketsTest {

    private val sitzung = Sitzungsthread("rise1-sitzung-e2e")
    private val transporte = mutableListOf<Sockettransport>()

    @AfterTest
    fun raeumeAuf() {
        transporte.forEach { runCatching { it.schliesse() } }
        runCatching { sitzung.beende(2_000) }
    }

    private class KlartextLauschposten : Lauschposten {
        override fun oeffne(): ServerSocket = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    }

    private class KlartextSocketquelle(private val zielport: Int) : Socketquelle {
        override fun verbinde(gegenstelle: Gegenstelle): Socket {
            // Bewusst ohne `apply`: Darin wäre `this` der Socket, und
            // `Socket.getPort()` verdeckte die Eigenschaft dieser Klasse — man
            // verbände sich nach Port 0 und suchte den Fehler im Netz.
            val socket = Socket()
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), zielport), 2_000)
            return socket
        }
    }

    private fun transport(quelle: Socketquelle = Socketquelle.NurAnnehmend): Sockettransport =
        Sockettransport(sitzung, quelle).also { transporte += it }

    /** Liest etwas auf dem Sitzungsthread und gibt es heraus. */
    private fun <T> aufSitzungsthread(lesen: () -> T): T {
        val fertig = CountDownLatch(1)
        var wert: T? = null
        sitzung.fuehreAus {
            wert = lesen()
            fertig.countDown()
        }
        assertTrue(fertig.await(5, TimeUnit.SECONDS), "Der Sitzungsthread hat nicht geantwortet.")
        @Suppress("UNCHECKED_CAST")
        return wert as T
    }

    private fun warteBis(millis: Long = 5_000, bedingung: () -> Boolean): Boolean {
        val ende = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < ende) {
            if (bedingung()) return true
            Thread.sleep(2)
        }
        return bedingung()
    }

    @Test
    fun derGanzeWegLaeuftAuchUeberEchteSockets() {
        val partie = "m-e2e"
        val wirt = transport()
        val port = wirt.lausche(KlartextLauschposten())
        assertTrue(port > 0, "Der Host konnte nicht lauschen.")

        // Der Host bedient den Tisch. Die Platzvergabe steht hier als Funktion —
        // im Betrieb ist es `Beitrittsstelle::beitreten`, das gegen `rise.db`
        // arbeitet und hier nichts zu suchen hat.
        lateinit var dienst: Tischdienst
        aufSitzungsthread {
            dienst = Tischdienst(wirt, partie) { gesuch ->
                Beitrittsantwort.Angenommen(
                    participantUid = "p-${gesuch.deviceUid}",
                    sitzplatz = 2,
                    rejoinToken = "b".repeat(32),
                )
            }
            dienst.starte()
        }

        val gastTransport = transport(KlartextSocketquelle(port))
        val host = Gegenstelle("host-e2e", "Tisch am Fenster")
        lateinit var ablauf: Beitrittsablauf
        aufSitzungsthread {
            ablauf = Beitrittsablauf(
                gastTransport,
                host,
                Beitrittsgesuch(matchUid = partie, deviceUid = "d-gast", anzeigename = "Bert"),
            )
            ablauf.starte()
        }

        assertTrue(
            warteBis { aufSitzungsthread { ablauf.stand } is Beitrittsablauf.Stand.Angenommen },
            "Der Gast kam nicht an den Tisch: ${aufSitzungsthread { ablauf.stand }}",
        )

        val stand = assertIs<Beitrittsablauf.Stand.Angenommen>(aufSitzungsthread { ablauf.stand })
        assertEquals("p-d-gast", stand.participantUid)
        assertEquals(2, stand.sitzplatz)

        val gaeste = aufSitzungsthread { dienst.gaeste }
        assertEquals(1, gaeste.size)
        assertEquals("Bert", gaeste.single().anzeigename)
        assertTrue(gaeste.single().steht)
        assertTrue(
            gaeste.single().gegenstelle.geraeteUid.startsWith(Sockettransport.VORLAEUFIG),
            "Vor dem Handshake aus TDD 9.3 trägt niemand eine device_uid.",
        )

        // Und der Stand, den die Oberfläche zeigen würde.
        val hostStand = aufSitzungsthread { Sitzungsstand.vomHost(partie, "K7F2-9QXM-4TBH", dienst) }
        assertEquals(Tischrolle.HOST, hostStand.rolle)
        assertEquals(1, hostStand.gegenstellen.size)

        val gastStand = aufSitzungsthread { Sitzungsstand.vomGast(partie, "K7F2-9QXM-4TBH", ablauf) }
        assertEquals(Tischrolle.GAST, gastStand.rolle)
        assertTrue(gastStand.amTisch)
        assertEquals(2, gastStand.eigenerSitzplatz)
        assertTrue(gastStand.leitungSteht)
    }

    @Test
    fun einAbgelehnterGastErfaehrtDenGrundUeberDieLeitung() {
        val partie = "m-voll"
        val wirt = transport()
        val port = wirt.lausche(KlartextLauschposten())
        aufSitzungsthread {
            Tischdienst(wirt, partie) { Beitrittsantwort.Abgelehnt(Beitrittsablehnung.TISCH_VOLL) }.starte()
        }

        val gastTransport = transport(KlartextSocketquelle(port))
        lateinit var ablauf: Beitrittsablauf
        aufSitzungsthread {
            ablauf = Beitrittsablauf(
                gastTransport,
                Gegenstelle("host-voll", "Voller Tisch"),
                Beitrittsgesuch(matchUid = partie, deviceUid = "d-spaet", anzeigename = "Cem"),
            )
            ablauf.starte()
        }

        assertTrue(
            warteBis { aufSitzungsthread { ablauf.stand } is Beitrittsablauf.Stand.Abgelehnt },
            "Die Ablehnung kam nicht an: ${aufSitzungsthread { ablauf.stand }}",
        )
        assertEquals(
            Beitrittsablauf.Stand.Abgelehnt(Beitrittsablehnung.TISCH_VOLL),
            aufSitzungsthread { ablauf.stand },
        )
        assertEquals(
            "Der Tisch ist voll.",
            aufSitzungsthread { Sitzungsstand.vomGast(partie, null, ablauf).meldung },
        )
    }
}
