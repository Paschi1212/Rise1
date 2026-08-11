package de.myhornets.rise1.transport

/**
 * T-067 — das Netz zwischen Client und [Hostattrappe].
 *
 * ## Wozu
 *
 * [Hostattrappe] ist ein Gegenüber, aber noch keine Verbindung. Diese Klasse
 * ist die Leitung dazwischen: eine Uhr, eine Warteschlange, und die Zusage,
 * dass beides sich ohne Zufall und ohne Nebenläufigkeit verhält.
 *
 * Damit ist der vollständige Weg abgedeckt, ohne dass ein Netz oder ein Gerät
 * beteiligt wäre:
 *
 *     Client → Transport → Attrappennetz → Hostattrappe
 *                                              ↓ Antwort
 *     Client ← Transport ← Attrappennetz ←──────┘
 *
 * ## Eine Uhr für alles
 *
 * Der wichtigste Unterschied zu [AttrappenTransport]: Hin- und Rückweg hängen
 * an **derselben** Uhr. Eine Anfrage bei `t = 0` mit 20 ms Verzögerung erreicht
 * den Host bei 20 und die Antwort den Client bei 40. Wer einen Herzschlag oder
 * eine Zeitüberschreitung prüfen will, braucht genau das — mit zwei Uhren wäre
 * jede Aussage über Laufzeiten wertlos.
 *
 * ## Was hier bewusst fehlt
 *
 * **Kein Protokoll.** Das Netz befördert Bytes und weiß nicht, was darin steht.
 * Beitritt, Handshake und Sequenzvergabe sind [[E08 Sitzung und Reconnect]] und
 * werden **gegen** dieses Netz gebaut.
 *
 * **Kein TLS.** Der Fingerabdruck wird verglichen, nicht gerechnet. Für die
 * Prüfung aus `T-071` ist das genug: Sie muss gegen einen passenden **und**
 * einen unpassenden Fall laufen, und beides lässt sich hier auf Knopf erzeugen.
 * Die echte Zertifikatsarbeit kommt in `T-070` und ersetzt nichts davon — sie
 * kommt darunter.
 *
 * ## Beispiel
 *
 *     val netz = Attrappennetz(verzoegerungMillis = 10)
 *     val host = Hostattrappe(Gegenstelle("d-host", "Tisch"), "AB-CD").spiegle("echo:")
 *     netz.melde(host)
 *
 *     val transport = netz.transportFuer("d-client")
 *     transport.erwarteFingerabdruck(host.gegenstelle, "AB-CD")
 *     transport.verbinde(host.gegenstelle)
 *     netz.laufeBis(10)                     // Verbunden
 *     transport.sende(host.gegenstelle, "hallo".toByteArray())
 *     netz.laufeBis(30)                     // Antwort da
 */
class Attrappennetz(

    /**
     * Laufzeit **je Richtung**.
     *
     * Ein Umlauf kostet also das Doppelte. Das ist die realistischere
     * Modellierung und zugleich die, bei der man sich beim Rechnen weniger
     * vertut als bei „Verzögerung pro Umlauf".
     */
    val verzoegerungMillis: Long = 0L,
) {

    /** Die Uhr. Bewegt sich ausschließlich durch [laufeBis]. */
    var jetzt: Long = 0L
        private set

    private val hosts = linkedMapOf<String, Hostattrappe>()
    private val dienste = linkedMapOf<String, Dienst>()
    private val sucher = mutableListOf<AttrappenVerzeichnis>()
    private val warteschlange = mutableListOf<Auftrag>()
    private var folge = 0L

    /**
     * Ein eingeplanter Schritt.
     *
     * [folgeNummer] entscheidet bei gleicher Fälligkeit — sonst hinge die
     * Reihenfolge an der Sortierstabilität einer Bibliothek, und das ist keine
     * Zusage, auf die man einen Test stellt.
     */
    private class Auftrag(
        val faellig: Long,
        val folgeNummer: Long,
        val tuIt: () -> Unit,
    )

    /** Meldet einen Host im Netz an. */
    fun melde(host: Hostattrappe): Hostattrappe {
        require(hosts.put(host.gegenstelle.geraeteUid, host) == null) {
            "Zwei Hosts mit der Kennung ${host.gegenstelle.geraeteUid}."
        }
        return host
    }

    /** Ein Transport für ein Client-Gerät. */
    fun transportFuer(geraeteUid: String): AttrappennetzTransport {
        require(geraeteUid.isNotBlank()) { "Ein Client ohne Kennung ist keiner." }
        return AttrappennetzTransport(geraeteUid)
    }

    /**
     * Lässt die Uhr laufen und arbeitet ab, was fällig wird.
     *
     * Auch das, was **während** des Ablaufs fällig wird — eine Antwort auf eine
     * Antwort landet im selben Aufruf, wenn ihre Zeit reicht. Sonst müsste ein
     * Test die Anzahl der Umläufe kennen, statt eine Zeit zu nennen.
     */
    fun laufeBis(zeitpunkt: Long) {
        require(zeitpunkt >= jetzt) { "Die Uhr läuft nicht rückwärts: $jetzt → $zeitpunkt." }
        while (true) {
            val naechster = warteschlange
                .filter { it.faellig <= zeitpunkt }
                .minWithOrNull(compareBy({ it.faellig }, { it.folgeNummer })) ?: break
            warteschlange.remove(naechster)
            // **Die Uhr wird auf die Fälligkeit gestellt, bevor der Auftrag
            // läuft** — nicht vorher aufs Ziel.
            //
            // Ein Auftrag plant oft einen weiteren ein: Der Host empfängt einen
            // Rahmen und schickt eine Antwort. Diese Antwort ist eine
            // Verzögerung nach dem **Empfang** fällig, nicht eine Verzögerung
            // nach dem Ende des Uhrlaufs. Stand die Uhr schon am Ziel, wurde
            // jede Antwort um eine Verzögerung zu spät eingeplant — und ein
            // vollständiger Umlauf passte nie in das Fenster, das der Aufrufer
            // dafür berechnet hatte.
            jetzt = maxOf(jetzt, naechster.faellig)
            naechster.tuIt()
        }
        jetzt = zeitpunkt
    }

    /** Bequemlichkeit: einen vollen Umlauf weiter. */
    fun laufeEinenUmlauf() = laufeBis(jetzt + 2 * verzoegerungMillis)

    /**
     * `internal`, damit [AttrappenVerzeichnis] dieselbe Uhr benutzt.
     *
     * Ein Verzeichnis mit eigener Zeitrechnung könnte einen Dienst melden, der
     * im Netz noch gar nicht da ist — und der Zusammenhang „gefunden heißt
     * erreichbar" wäre dahin.
     */
    internal fun plane(inMillis: Long = verzoegerungMillis, tuIt: () -> Unit) {
        warteschlange += Auftrag(jetzt + inMillis, folge++, tuIt)
    }

    // ── Dienstverzeichnis (T-068/T-069) ─────────────────────────────────────

    /** Ein Dienstverzeichnis für ein Gerät in diesem Netz. */
    fun verzeichnisFuer(geraeteUid: String): AttrappenVerzeichnis {
        require(geraeteUid.isNotBlank()) { "Ein Gerät ohne Kennung hat kein Verzeichnis." }
        return AttrappenVerzeichnis(this, geraeteUid)
    }

    /**
     * Die derzeit auffindbaren Dienste.
     *
     * Gefiltert nach Erreichbarkeit: Ein Host, der nicht erreichbar ist, wird
     * auch nicht gefunden. Ohne diese Kopplung könnte ein Test einen Dienst
     * finden, zu dem keine Verbindung zustande kommt — und niemand wüsste, ob
     * das ein Fehler ist oder Absicht.
     */
    internal fun veroeffentlichte(): List<Dienst> =
        dienste.values.filter { hosts[it.gegenstelle.geraeteUid]?.erreichbar != false }

    internal fun istNameVergeben(anzeigename: String, ausser: String): Boolean =
        dienste.values.any { it.gegenstelle.anzeigename == anzeigename && it.gegenstelle.geraeteUid != ausser }

    internal fun veroeffentliche(dienst: Dienst) {
        dienste[dienst.gegenstelle.geraeteUid] = dienst
        sucher.toList().forEach { verzeichnis -> plane { verzeichnis.meldeGefunden(dienst) } }
    }

    internal fun nimmZurueck(geraeteUid: String) {
        val weg = dienste.remove(geraeteUid) ?: return
        sucher.toList().forEach { verzeichnis -> plane { verzeichnis.meldeVerschwunden(weg.gegenstelle) } }
    }

    internal fun registriereSucher(verzeichnis: AttrappenVerzeichnis) {
        if (verzeichnis !in sucher) sucher += verzeichnis
    }

    internal fun vergiss(verzeichnis: AttrappenVerzeichnis) {
        sucher -= verzeichnis
    }

    /**
     * Der Client-seitige Transport dieses Netzes.
     *
     * Erfüllt [Transport] vollständig — die Schicht darüber merkt keinen
     * Unterschied zu einem echten. Genau darum geht es.
     */
    inner class AttrappennetzTransport internal constructor(
        /** Das eigene Gerät. Steht in jedem Fehlertext, damit Mitschnitte lesbar bleiben. */
        val eigeneUid: String,
    ) : Transport {

        private val hoerer = mutableListOf<(TransportEreignis) -> Unit>()
        private val offen = linkedSetOf<Gegenstelle>()
        private val erwartet = mutableMapOf<String, String>()
        private var geschlossen = false

        /** Alles, was dieser Client gesendet hat — auch, was nie ankam. */
        val gesendet = mutableListOf<Pair<Gegenstelle, ByteArray>>()

        override val verbundene: Set<Gegenstelle> get() = offen.toSet()

        /**
         * Legt fest, welchen Fingerabdruck dieser Client bei einem Host erwartet.
         *
         * Ohne Erwartung wird **nicht** geprüft — das ist der Erstbeitritt, bei
         * dem der Nutzer den Code vom Bildschirm des Hosts abliest. Ist eine
         * Erwartung gesetzt und passt sie nicht, scheitert die Verbindung mit
         * [TransportFehler.FingerabdruckPasstNicht] statt mit irgendeinem
         * Verbindungsfehler (ADR-001 / [[ADR-002A Key Verification]]).
         */
        fun erwarteFingerabdruck(gegenstelle: Gegenstelle, fingerabdruck: String) {
            erwartet[gegenstelle.geraeteUid] = fingerabdruck
        }

        override fun beobachte(hoerer: (TransportEreignis) -> Unit) {
            this.hoerer += hoerer
        }

        override fun verbinde(gegenstelle: Gegenstelle) {
            pruefeOffen()
            if (gegenstelle in offen) return
            plane {
                val host = hosts[gegenstelle.geraeteUid]
                when {
                    host == null || !host.erreichbar ->
                        benachrichtige(TransportEreignis.Fehlgeschlagen(gegenstelle, TransportFehler.KeinGemeinsamesNetz))

                    erwartet[gegenstelle.geraeteUid]?.let { it != host.fingerabdruck } == true ->
                        benachrichtige(
                            TransportEreignis.Fehlgeschlagen(
                                gegenstelle,
                                TransportFehler.FingerabdruckPasstNicht(
                                    erwartet = erwartet.getValue(gegenstelle.geraeteUid),
                                    gesehen = host.fingerabdruck,
                                ),
                            ),
                        )

                    else -> {
                        offen += gegenstelle
                        benachrichtige(TransportEreignis.Verbunden(gegenstelle))
                    }
                }
            }
        }

        override fun sende(an: Gegenstelle, rahmen: ByteArray): Boolean {
            pruefeOffen()
            if (an !in offen) return false
            val kopie = rahmen.copyOf()
            gesendet += an to kopie

            plane {
                val host = hosts[an.geraeteUid]
                if (host == null || !host.erreichbar) {
                    // Weggebrochen, während der Rahmen unterwegs war. Genau der
                    // Fall, für den es den Herzschlag gibt.
                    if (offen.remove(an)) {
                        benachrichtige(TransportEreignis.Getrennt(an, "Host nicht mehr erreichbar"))
                    }
                    return@plane
                }
                when (val antwort = host.nimmEntgegen(kopie)) {
                    is Hostattrappe.Antwort.Sende ->
                        plane { if (an in offen) benachrichtige(TransportEreignis.Empfangen(an, antwort.rahmen)) }

                    Hostattrappe.Antwort.Schweige -> Unit

                    is Hostattrappe.Antwort.Trenne -> plane {
                        if (offen.remove(an)) benachrichtige(TransportEreignis.Getrennt(an, antwort.grund))
                    }

                    is Hostattrappe.Antwort.LehneAb -> plane {
                        benachrichtige(
                            TransportEreignis.Fehlgeschlagen(
                                an,
                                TransportFehler.Abgelehnt(antwort.grund),
                            ),
                        )
                    }
                }
            }
            return true
        }

        override fun trenne(gegenstelle: Gegenstelle, grund: String) {
            pruefeOffen()
            if (offen.remove(gegenstelle)) {
                plane { benachrichtige(TransportEreignis.Getrennt(gegenstelle, grund)) }
            }
        }

        override fun schliesse() {
            if (geschlossen) return
            geschlossen = true
            offen.clear()
            hoerer.clear()
        }

        private fun benachrichtige(ereignis: TransportEreignis) {
            if (geschlossen) return
            hoerer.toList().forEach { it(ereignis) }
        }

        private fun pruefeOffen() {
            check(!geschlossen) { "Dieser Transport ($eigeneUid) ist geschlossen." }
        }
    }
}
