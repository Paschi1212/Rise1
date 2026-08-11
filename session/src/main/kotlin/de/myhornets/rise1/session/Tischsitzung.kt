package de.myhornets.rise1.session

import de.myhornets.rise1.transport.Gegenstelle
import de.myhornets.rise1.transport.Rahmen
import de.myhornets.rise1.transport.Rahmencodec
import de.myhornets.rise1.transport.Rahmenfehler
import de.myhornets.rise1.transport.Rahmenleser
import de.myhornets.rise1.transport.Rahmentyp
import de.myhornets.rise1.transport.Transport
import de.myhornets.rise1.transport.TransportEreignis

/**
 * `T-101` — der Beitritt über die Leitung.
 *
 * ## Was hier gefüllt wird
 *
 * `Beitritt.kt` entscheidet seit `T-101` bereits, **wer** einen Sitzplatz
 * bekommt — aber nur als Kotlin-Wert im selben Prozess. `Sitzungsprotokoll`
 * bringt seit `ADR-007` Nachrichten über die Leitung, kannte aber nur den
 * Wiedereinstieg. Dazwischen fehlte die Stelle, die einen ankommenden Rahmen
 * entgegennimmt, `Beitrittsstelle.beitreten` fragt und die Antwort
 * zurückschickt.
 *
 * Genau diese beiden Stellen stehen hier: [Tischdienst] auf dem Host,
 * [Beitrittsablauf] auf dem Gast.
 *
 * ## Der Thread
 *
 * Beide laufen **ausschließlich auf dem Sitzungsthread** (ADR-008). Sie hängen
 * sich über [Transport.beobachte] ein, und der Transport meldet dort. Keine
 * dieser Klassen ist threadsicher, keine soll es werden — sie sehen nie zwei
 * Threads.
 *
 * ## Wo die Sicherheit herkommt — und wo nicht
 *
 * **Der Beitritt weist nichts nach.** Es gibt hier kein Geheimnis, mit dem sich
 * ein Gast ausweisen könnte; er hat noch keins. Dass am anderen Ende der
 * richtige Host sitzt, hat der Gast bereits **vor dem ersten Byte** geprüft:
 * über den Fingerabdruck des Partie-Zertifikats (ADR-001, ADR-006). Steht die
 * TLS-Verbindung, ist das erledigt. Wer diese Reihenfolge umdreht, hat einen
 * Handshake gebaut, der einem Fremden zuerst zuhört.
 *
 * Umgekehrt: Ob ein Gerät einen Platz bekommt, entscheidet **allein der Host**
 * über `Beitrittsstelle`. Diese Datei trifft keine einzige dieser
 * Entscheidungen — sie befördert sie.
 */

/** Wer man am Tisch ist. */
enum class Tischrolle { HOST, GAST }

/**
 * Ein Gast, den der Host aufgenommen hat.
 *
 * Der `rejoin_token` steht **nicht** darin. Er geht einmal an den Gast und
 * bleibt beim Host nur als gesalzener Hash (TDD 4.4) — ihn hier zu führen wäre
 * eine zweite Kopie eines Geheimnisses, die niemand braucht.
 */
data class Gastplatz(
    val gegenstelle: Gegenstelle,
    val participantUid: String,
    val sitzplatz: Int,
    val anzeigename: String,
    val steht: Boolean,
)

/**
 * Der Host: nimmt Beitrittsgesuche entgegen und beantwortet sie.
 *
 * ## Was er **nicht** tut
 *
 * Er entscheidet nichts. [beitritt] ist im Betrieb
 * `Beitrittsstelle::beitreten`; im Test ist es eine Funktion, die einen
 * bestimmten Fall liefert. Wäre die Entscheidung hier drin, prüfte ein Test
 * für „Tisch voll" den Transport statt die Regel.
 *
 * Er spielt auch nicht: Kein Verteilen, kein Aufdecken, keine Zugzählung. Das
 * ist E07/E09 und wird **gegen** diese Klasse gebaut, nicht in sie hinein.
 */
class Tischdienst(
    private val transport: Transport,
    /** Die Partie, für die dieser Tisch offen ist. */
    val matchUid: String,
    /** Im Betrieb `Beitrittsstelle::beitreten`. */
    private val beitritt: (Beitrittsgesuch) -> Beitrittsantwort,
) {

    private val leser = mutableMapOf<String, Rahmenleser>()
    private val belegung = linkedMapOf<String, Gastplatz>()
    private val verlauf = mutableListOf<String>()

    /** Alle Gäste in der Reihenfolge ihres Beitritts. */
    val gaeste: List<Gastplatz> get() = belegung.values.toList()

    /** Was am Tisch passiert ist — für die Anzeige und die Fehlersuche. */
    val meldungen: List<String> get() = verlauf.toList()

    /** Hängt sich an den Transport. Ab hier läuft alles auf dem Sitzungsthread. */
    fun starte() {
        transport.beobachte { ereignis -> verarbeite(ereignis) }
    }

    fun verarbeite(ereignis: TransportEreignis) {
        when (ereignis) {
            is TransportEreignis.Verbunden -> {
                // Ein neuer Leser je Leitung. Ein gemeinsamer würde die Ströme
                // zweier Gäste ineinanderschieben.
                leser[ereignis.gegenstelle.geraeteUid] = Rahmenleser()
                verlauf += "Ein Gerät hat angeklopft: ${ereignis.gegenstelle.anzeigename}"
            }

            is TransportEreignis.Empfangen -> nimmEntgegen(ereignis.von, ereignis.rahmen)

            is TransportEreignis.Getrennt -> {
                leser -= ereignis.gegenstelle.geraeteUid
                // Der Sitzplatz bleibt: TDD 9.2 — eine verlorene Leitung ist
                // kein verlorener Platz. Nur die Leitung ist weg.
                belegung.computeIfPresent(ereignis.gegenstelle.geraeteUid) { _, platz ->
                    platz.copy(steht = false)
                }
                verlauf += "Leitung weg: ${ereignis.gegenstelle.anzeigename} (${ereignis.grund})"
            }

            is TransportEreignis.Fehlgeschlagen -> {
                verlauf += "Fehlgeschlagen: ${ereignis.fehler}"
            }
        }
    }

    private fun nimmEntgegen(von: Gegenstelle, bytes: ByteArray) {
        val leitung = leser.getOrPut(von.geraeteUid) { Rahmenleser() }
        val gelesen = try {
            leitung.fuettere(bytes)
        } catch (fehler: Rahmenfehler) {
            // T-072: Nach einem Protokollfehler ist der Leser unbrauchbar und
            // die Leitung nicht mehr vertrauenswürdig.
            trenne(von, "Protokollfehler: ${fehler.message}")
            return
        }

        gelesen.filterIsInstance<Rahmen>().forEach { rahmen ->
            when (rahmen.typ) {
                Rahmentyp.BEITRITT -> beantworteBeitritt(von, rahmen)

                // Ein Lebenszeichen wird gespiegelt. Ohne Antwort hielte der
                // Gast den Host für stumm (TDD 9.2) — und ein stummer Host ist
                // für `Sitzungsverbindung` nicht dasselbe wie ein abwesender.
                Rahmentyp.HERZSCHLAG -> sende(von, Rahmen(Rahmentyp.HERZSCHLAG, ByteArray(0)))

                else -> verlauf += "Rahmen ${rahmen.typ} von ${von.anzeigename} — hier noch ohne Aufgabe."
            }
        }
    }

    private fun beantworteBeitritt(von: Gegenstelle, rahmen: Rahmen) {
        val gesuch = try {
            Sitzungsprotokoll.liesBeitrittsgesuch(rahmen)
        } catch (fehler: Protokollfehler) {
            trenne(von, "Unlesbares Beitrittsgesuch: ${fehler.message}")
            return
        }

        if (gesuch.matchUid != matchUid) {
            // Ein Gesuch für eine andere Partie ist kein Fehler des Netzes,
            // sondern eine Verwechslung. Sie wird benannt, nicht verschwiegen.
            antworte(von, Beitrittsantwort.Abgelehnt(Beitrittsablehnung.PARTIE_UNBEKANNT))
            return
        }

        val antwort = beitritt(gesuch)
        if (antwort is Beitrittsantwort.Angenommen) {
            belegung[von.geraeteUid] = Gastplatz(
                gegenstelle = von,
                participantUid = antwort.participantUid,
                sitzplatz = antwort.sitzplatz,
                anzeigename = gesuch.anzeigename,
                steht = true,
            )
            verlauf += "${gesuch.anzeigename} sitzt auf Platz ${antwort.sitzplatz}."
        } else {
            verlauf += "${gesuch.anzeigename} abgelehnt: ${(antwort as Beitrittsantwort.Abgelehnt).grund}"
        }
        antworte(von, antwort)
    }

    private fun antworte(an: Gegenstelle, antwort: Beitrittsantwort) {
        sende(an, Sitzungsprotokoll.kodiere(antwort))
    }

    private fun sende(an: Gegenstelle, rahmen: Rahmen) {
        // `false` heißt: Die Leitung ist weg. Das ist in TDD 9 der Normalfall
        // und kein Fehler — der Transport meldet das Ende ohnehin gleich.
        transport.sende(an, Rahmencodec.kodiere(rahmen))
    }

    private fun trenne(von: Gegenstelle, grund: String) {
        verlauf += "Getrennt: ${von.anzeigename} — $grund"
        transport.trenne(von, grund)
    }
}

/**
 * Der Gast: verbindet sich, bittet um einen Platz, wartet auf die Antwort.
 *
 * ## Warum das Gesuch erst nach `Verbunden` hinausgeht
 *
 * Weil eine stehende Verbindung eine **geprüfte** Verbindung ist: Der
 * TLS-Handshake mit der Fingerabdruckprüfung ist dann durch (`TlsSocketquelle`
 * handelt aus, bevor sie den Socket herausgibt). Vorher zu sprechen hieße, in
 * eine Leitung zu reden, von der noch niemand weiß, wo sie endet.
 */
class Beitrittsablauf(
    private val transport: Transport,
    /** Der Host aus der Dienstsuche (`T-069`). */
    val host: Gegenstelle,
    private val gesuch: Beitrittsgesuch,
) {

    /** Wo der Beitritt gerade steht. */
    sealed interface Stand {
        data object Ungestartet : Stand
        data object Verbindet : Stand

        /** Die Leitung steht und das Gesuch ist draußen. */
        data object Gefragt : Stand

        data class Angenommen(val participantUid: String, val sitzplatz: Int, val rejoinToken: String) : Stand

        data class Abgelehnt(val grund: Beitrittsablehnung) : Stand

        /** Es kam nicht dazu — kein Netz, falscher Fingerabdruck, Leitung weg. */
        data class Gescheitert(val grund: String) : Stand
    }

    private val leser = Rahmenleser()

    var stand: Stand = Stand.Ungestartet
        private set

    /** Ob die Leitung steht. Nicht zu verwechseln mit „am Tisch". */
    var leitungSteht: Boolean = false
        private set

    fun starte() {
        transport.beobachte { ereignis -> verarbeite(ereignis) }
        stand = Stand.Verbindet
        transport.verbinde(host)
    }

    fun verarbeite(ereignis: TransportEreignis) {
        when (ereignis) {
            is TransportEreignis.Verbunden -> {
                if (ereignis.gegenstelle != host) return
                leitungSteht = true
                // Genau einmal fragen. Ein zweiter Aufbau derselben Leitung —
                // etwa nach einer Wiederverbindung — ist kein zweiter Beitritt:
                // Ab dann gilt der Wiedereinstieg mit dem `rejoin_token`
                // (TDD 9.1: „Wiedereinstieg ist Authentifizierung, nicht
                // Wiedererkennung.")
                if (stand is Stand.Verbindet) {
                    // Der Stand wird **vor** dem Senden gesetzt, nicht danach.
                    // Sonst hinge die Richtigkeit daran, dass die Antwort später
                    // kommt als die nächste Zeile — bei einem Socket trifft das
                    // zu, bei einem synchronen Transport nicht, und eine Zusage,
                    // die von der Verzögerung des Netzes lebt, ist keine.
                    stand = Stand.Gefragt
                    if (!transport.sende(host, Rahmencodec.kodiere(Sitzungsprotokoll.kodiere(gesuch)))) {
                        stand = Stand.Gescheitert("Das Gesuch ging nicht hinaus — die Leitung war schon weg.")
                    }
                }
            }

            is TransportEreignis.Empfangen -> {
                if (ereignis.von != host) return
                nimmEntgegen(ereignis.rahmen)
            }

            is TransportEreignis.Getrennt -> {
                if (ereignis.gegenstelle != host) return
                leitungSteht = false
                // Eine Ablehnung überlebt das Ende der Leitung: Sie ist die
                // Antwort, nicht der Abbruch. Nur ein Ende **vor** der Antwort
                // ist ein Scheitern.
                if (stand is Stand.Verbindet || stand is Stand.Gefragt) {
                    stand = Stand.Gescheitert("Die Leitung ging, bevor eine Antwort kam: ${ereignis.grund}")
                }
            }

            is TransportEreignis.Fehlgeschlagen -> {
                leitungSteht = false
                if (stand is Stand.Verbindet || stand is Stand.Gefragt) {
                    stand = Stand.Gescheitert(erklaere(ereignis.fehler))
                }
            }
        }
    }

    private fun nimmEntgegen(bytes: ByteArray) {
        val gelesen = try {
            leser.fuettere(bytes)
        } catch (fehler: Rahmenfehler) {
            stand = Stand.Gescheitert("Der Host spricht ein anderes Protokoll: ${fehler.message}")
            transport.trenne(host, "Protokollfehler")
            return
        }

        gelesen.filterIsInstance<Rahmen>().forEach { rahmen ->
            when (rahmen.typ) {
                Rahmentyp.BEITRITT_ANTWORT, Rahmentyp.BEITRITT_ABLEHNUNG -> uebernimm(rahmen)
                else -> Unit // Herzschlag und alles Spätere gehen andere an.
            }
        }
    }

    private fun uebernimm(rahmen: Rahmen) {
        if (stand !is Stand.Gefragt) return
        stand = try {
            when (val antwort = Sitzungsprotokoll.liesBeitrittsantwort(rahmen)) {
                is Beitrittsantwort.Angenommen -> Stand.Angenommen(
                    participantUid = antwort.participantUid,
                    sitzplatz = antwort.sitzplatz,
                    rejoinToken = antwort.rejoinToken,
                )

                is Beitrittsantwort.Abgelehnt -> Stand.Abgelehnt(antwort.grund)
            }
        } catch (fehler: Protokollfehler) {
            Stand.Gescheitert("Unlesbare Antwort: ${fehler.message}")
        }
    }

    /**
     * Aus einem [de.myhornets.rise1.transport.TransportFehler] wird ein Satz.
     *
     * ADR-001 verlangt genau das: Der Fehlerfall wird **erklärt**, *„statt eine
     * kryptische Zeitüberschreitung zu zeigen"*. Und der Fingerabdruckfehler
     * bleibt einer — *„Ein Angriff darf nicht wie eine Störung aussehen."*
     */
    private fun erklaere(fehler: de.myhornets.rise1.transport.TransportFehler): String = when (fehler) {
        is de.myhornets.rise1.transport.TransportFehler.KeinGemeinsamesNetz ->
            "Kein gemeinsames Netz. Alle Geräte müssen im selben WLAN sein; sonst kann der " +
                "Host einen Hotspot öffnen — der braucht kein Internet."

        is de.myhornets.rise1.transport.TransportFehler.FingerabdruckPasstNicht ->
            "Der Tischcode stimmt nicht: erwartet ${fehler.erwartet}, gesehen ${fehler.gesehen}. " +
                "Entweder ist es der falsche Tisch, oder jemand sitzt dazwischen."

        is de.myhornets.rise1.transport.TransportFehler.Zeitueberschreitung ->
            "Der Host hat ${fehler.nachMillis} ms nicht geantwortet."

        is de.myhornets.rise1.transport.TransportFehler.Abgelehnt ->
            "Der Host nimmt nicht auf: ${fehler.grund}"

        is de.myhornets.rise1.transport.TransportFehler.VerbindungAbgebrochen ->
            "Die Verbindung ist abgebrochen: ${fehler.grund}"
    }
}

/**
 * Was beide Seiten von der Sitzung sehen.
 *
 * Ein **Abbild**, kein Zustand: Es wird aus den lebenden Objekten gelesen und
 * an die Oberfläche gegeben (ADR-005, `StateFlow`). Die Oberfläche besitzt
 * nichts davon — dieselbe Regel wie beim `PrototypViewModel`, das *„keinen
 * Spielzustand besitzt"*.
 */
data class Sitzungsstand(
    val rolle: Tischrolle,
    val matchUid: String,
    /** Der Fingerabdruck der Partie. Der Host zeigt ihn, der Gast tippt ihn ab. */
    val tischcode: String?,
    val eigenerParticipantUid: String?,
    val eigenerSitzplatz: Int?,
    /** Steht die Leitung? Nicht zu verwechseln mit „am Tisch" (TDD 9.1). */
    val leitungSteht: Boolean,
    val verbindungszustand: Verbindungszustand?,
    val gegenstellen: List<Gegenstellenstand>,
    /** Was zuletzt passiert ist, in einem Satz für die Oberfläche. */
    val meldung: String?,
) {
    val amTisch: Boolean get() = eigenerParticipantUid != null

    companion object {

        /** Der Stand des Hosts, aus seinem [Tischdienst] gelesen. */
        fun vomHost(
            matchUid: String,
            tischcode: String?,
            dienst: Tischdienst,
            eigenerParticipantUid: String? = null,
            eigenerSitzplatz: Int? = null,
        ): Sitzungsstand = Sitzungsstand(
            rolle = Tischrolle.HOST,
            matchUid = matchUid,
            tischcode = tischcode,
            eigenerParticipantUid = eigenerParticipantUid,
            eigenerSitzplatz = eigenerSitzplatz,
            // Der Host wartet nicht auf eine Leitung, er ist eine.
            leitungSteht = true,
            verbindungszustand = null,
            gegenstellen = dienst.gaeste.map {
                Gegenstellenstand(
                    kennung = it.gegenstelle.geraeteUid,
                    anzeigename = it.anzeigename,
                    participantUid = it.participantUid,
                    sitzplatz = it.sitzplatz,
                    steht = it.steht,
                )
            },
            meldung = dienst.meldungen.lastOrNull(),
        )

        /** Der Stand des Gasts, aus seinem [Beitrittsablauf] gelesen. */
        fun vomGast(
            matchUid: String,
            tischcode: String?,
            ablauf: Beitrittsablauf,
            verbindungszustand: Verbindungszustand? = null,
        ): Sitzungsstand {
            val platz = ablauf.stand as? Beitrittsablauf.Stand.Angenommen
            return Sitzungsstand(
                rolle = Tischrolle.GAST,
                matchUid = matchUid,
                tischcode = tischcode,
                eigenerParticipantUid = platz?.participantUid,
                eigenerSitzplatz = platz?.sitzplatz,
                leitungSteht = ablauf.leitungSteht,
                verbindungszustand = verbindungszustand,
                gegenstellen = listOf(
                    Gegenstellenstand(
                        kennung = ablauf.host.geraeteUid,
                        anzeigename = ablauf.host.anzeigename,
                        participantUid = null,
                        sitzplatz = null,
                        steht = ablauf.leitungSteht,
                    ),
                ),
                meldung = beschreibe(ablauf.stand),
            )
        }

        private fun beschreibe(stand: Beitrittsablauf.Stand): String = when (stand) {
            Beitrittsablauf.Stand.Ungestartet -> "Noch nichts unternommen."
            Beitrittsablauf.Stand.Verbindet -> "Verbindung zum Tisch wird aufgebaut."
            Beitrittsablauf.Stand.Gefragt -> "Der Tisch wurde um einen Platz gebeten."
            is Beitrittsablauf.Stand.Angenommen -> "Am Tisch, auf Platz ${stand.sitzplatz}."
            is Beitrittsablauf.Stand.Abgelehnt -> when (stand.grund) {
                Beitrittsablehnung.PARTIE_UNBEKANNT -> "Diese Partie kennt der Host nicht."
                Beitrittsablehnung.PARTIE_NIMMT_NICHT_MEHR_AUF -> "Die Partie hat schon begonnen."
                Beitrittsablehnung.TISCH_VOLL -> "Der Tisch ist voll."
                Beitrittsablehnung.PLATZ_BESETZT -> "Der gewünschte Platz ist besetzt."
                Beitrittsablehnung.GERAET_SITZT_SCHON -> "Dieses Gerät sitzt bereits am Tisch."
            }

            is Beitrittsablauf.Stand.Gescheitert -> stand.grund
        }
    }
}

/** Eine Gegenstelle, wie die Oberfläche sie zeigt. */
data class Gegenstellenstand(
    val kennung: String,
    val anzeigename: String,
    val participantUid: String?,
    val sitzplatz: Int?,
    val steht: Boolean,
)
