package de.myhornets.rise1.sitzung

import de.myhornets.rise1.core.verifikation.Fingerabdruck
import de.myhornets.rise1.session.Beitrittsablauf
import de.myhornets.rise1.session.Beitrittsantwort
import de.myhornets.rise1.session.Beitrittsgesuch
import de.myhornets.rise1.session.Sitzungsbausatz
import de.myhornets.rise1.session.Sitzungsverbindung
import de.myhornets.rise1.session.Tischdienst
import de.myhornets.rise1.session.tls.AndroidKeyStoreZertifikat
import de.myhornets.rise1.session.tls.TlsLauscher
import de.myhornets.rise1.session.tls.TlsSocketquelle
import de.myhornets.rise1.transport.Gegenstelle
import de.myhornets.rise1.transport.Sitzungsthread
import de.myhornets.rise1.transport.Sockettransport
import de.myhornets.rise1.transport.Socketquelle
import de.myhornets.rise1.transport.Transport

/**
 * Die beiden Bausätze, aus denen eine Sitzung entsteht — Host und Gast.
 *
 * ## Warum hier
 *
 * Weil hier die einzige Stelle ist, die alles sieht: `:ui` kennt `:session`,
 * `:transport`, `:store` und `:core`. Ein Bausatz muss ein Partie-Zertifikat
 * (ADR-006, `:session`), einen `Sockettransport` (`:transport`) und im
 * Host-Fall die Platzvergabe (`:session` über `:store`) zusammenbringen — kein
 * anderes Modul darf das.
 *
 * Es ist dieselbe Rolle wie bei `Sitzungsanbindung` und `Partieanbindung`: Hier
 * steht Verdrahtung, keine Entscheidung. Jede Entscheidung ist schon woanders
 * getroffen und dort ohne Gerät geprüft.
 *
 * ## Was der Host anders macht als der Gast
 *
 * Der Host **lauscht** und hat keine [Sitzungsverbindung]: Er baut zu niemandem
 * eine Verbindung auf, hat keinen Herzschlag zu senden und nichts
 * wiederzuverbinden. Der Gast **verbindet** und bringt seinen
 * [Beitrittsablauf] mit.
 *
 * Beide hängen sich in [Sitzungsbausatz.nachStart] ein — auf dem Sitzungsthread
 * (ADR-008).
 */

/**
 * Der Host: lauscht mit dem Partie-Zertifikat und bedient den Tisch.
 *
 * ADR-006: Das Zertifikat entsteht im `AndroidKeyStore` und gehört zu **einer**
 * Partie. Sein Fingerabdruck ist der Tischcode, den der Host zeigt und der Gast
 * abliest — er geht **nicht** über die Dienstankündigung (ADR-002A 3.2: *„Wer
 * geprüft wird, darf nicht die Prüfgrundlage liefern."*).
 */
class Hostbausatz(
    private val zertifikat: AndroidKeyStoreZertifikat,
    val matchUid: String,
    /** Im Betrieb `Beitrittsstelle::beitreten`. */
    private val platzvergabe: (Beitrittsgesuch) -> Beitrittsantwort,
    private val sitzplaetze: Int = Sockettransport.STANDARD_SITZPLAETZE,
) : Sitzungsbausatz {

    /**
     * Der Port, auf dem gelauscht wird — erst nach dem Aufbau gefüllt.
     *
     * [Sockettransport.KEIN_PORT], wenn nicht gelauscht werden konnte; dann kam
     * zusätzlich ein `Fehlgeschlagen` beim Hörer an (`T-077`).
     */
    @Volatile
    var port: Int = Sockettransport.KEIN_PORT
        private set

    @Volatile
    var dienst: Tischdienst? = null
        private set

    val tischcode: String get() = zertifikat.fingerabdruck().lesbar

    override fun transport(sitzung: Sitzungsthread): Transport {
        val transport = Sockettransport(
            sitzung = sitzung,
            // Ein Host verbindet sich nicht. Ein `verbinde` auf ihm ist ein
            // Programmfehler und wird auch als solcher gemeldet.
            socketquelle = Socketquelle.NurAnnehmend,
            sitzplaetze = sitzplaetze,
        )
        port = transport.lausche(TlsLauscher(zertifikat))
        return transport
    }

    /** Kein Gast, keine Wiederverbindung — siehe Klassenkommentar. */
    override fun verbindung(transport: Transport): Sitzungsverbindung? = null

    override fun nachStart(transport: Transport, sitzung: Sitzungsthread) {
        dienst = Tischdienst(transport, matchUid, platzvergabe).also { it.starte() }
    }
}

/**
 * Der Gast: verbindet sich zu genau einem Host und bittet um einen Platz.
 *
 * ## Der Fingerabdruck kommt von außerhalb des Kanals
 *
 * [erwartet] hat der Nutzer vom Bildschirm des Hosts abgelesen. Er geht in
 * `TlsSocketquelle` und von dort in `FingerabdruckPruefer` — den **einzigen**
 * TrustManager (ADR-006). Stimmt er nicht, kommt keine Verbindung zustande,
 * und der Fehler bleibt als Fingerabdruckfehler erkennbar (`T-065`).
 */
class Gastbausatz(
    val matchUid: String,
    private val erwartet: Fingerabdruck,
    private val wirt: String,
    private val port: Int,
    /** Wie der Host in der Oberfläche heißt — aus der Dienstsuche. */
    val host: Gegenstelle,
    private val gesuch: Beitrittsgesuch,
) : Sitzungsbausatz {

    @Volatile
    var ablauf: Beitrittsablauf? = null
        private set

    override fun transport(sitzung: Sitzungsthread): Transport = Sockettransport(
        sitzung = sitzung,
        socketquelle = TlsSocketquelle(
            matchUid = matchUid,
            erwartet = erwartet,
            wirt = wirt,
            port = port,
        ),
    )

    /**
     * Noch keine.
     *
     * Herzschlag und Wiederverbindung (`T-073`/`T-074`) setzen eine **Sitzung**
     * voraus, und die entsteht erst mit dem angenommenen Beitritt. Sie hier
     * schon aufzubauen hieße, einen Automaten über eine Leitung laufen zu
     * lassen, an deren Ende noch kein Sitzplatz steht.
     */
    override fun verbindung(transport: Transport): Sitzungsverbindung? = null

    override fun nachStart(transport: Transport, sitzung: Sitzungsthread) {
        ablauf = Beitrittsablauf(transport, host, gesuch).also { it.starte() }
    }
}
