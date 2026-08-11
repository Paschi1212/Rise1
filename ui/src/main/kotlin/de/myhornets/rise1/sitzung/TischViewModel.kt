package de.myhornets.rise1.sitzung

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.myhornets.rise1.core.verifikation.Fingerabdruck
import de.myhornets.rise1.session.Beitrittsantwort
import de.myhornets.rise1.session.Beitrittsgesuch
import de.myhornets.rise1.session.Partieanlage
import de.myhornets.rise1.session.Partieplan
import de.myhornets.rise1.session.Sitzungsstand
import de.myhornets.rise1.session.Tischrolle
import de.myhornets.rise1.session.tls.AndroidKeyStoreZertifikat
import de.myhornets.rise1.store.RiseStore
import de.myhornets.rise1.transport.DIENSTTYP
import de.myhornets.rise1.transport.Dienst
import de.myhornets.rise1.transport.Dienstverzeichnis
import de.myhornets.rise1.transport.Gegenstelle
import de.myhornets.rise1.transport.NsdDienstverzeichnis
import de.myhornets.rise1.transport.Registrierung
import de.myhornets.rise1.transport.Sockettransport
import de.myhornets.rise1.transport.Suchereignis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Der erste sichtbare Multiplayer-Bildschirm — Partie eröffnen, finden, beitreten.
 *
 * ## MVVM nach ADR-005
 *
 * Das ViewModel **besitzt keinen Sitzungszustand**. Der liegt im
 * `Sitzungsdienst` (`T-075`), der ihn über eine Activity hinweg hält; hier wird
 * er gelesen und als [Tischansicht] herausgegeben. Dieselbe Regel wie beim
 * `PrototypViewModel`: *„Jede Handlung geht durch das Event-Log; danach wird
 * neu gelesen."*
 *
 * ## Warum ein Takt und kein Rückruf
 *
 * Weil der Sitzungszustand **auf dem Sitzungsthread** lebt (ADR-008) und die
 * Oberfläche ihn nicht anfassen darf. Statt einen Rückruf durch drei Schichten
 * zu fädeln, liest [beobachteStand] ihn regelmäßig **auf dem Sitzungsthread**
 * ab und schiebt ein fertiges Abbild in den `StateFlow`. Der Takt ist eine
 * Coroutine im `viewModelScope` — er lebt und stirbt mit dem Bildschirm und
 * hat mit dem Threadmodell aus ADR-008 nichts zu tun.
 */
class TischViewModel(private val kontext: Context) : ViewModel() {

    /** Wo der Nutzer gerade ist. */
    enum class Schritt { START, HOST, SUCHE, GAST }

    /** Was der Bildschirm zeigt. */
    data class Tischansicht(
        val schritt: Schritt = Schritt.START,
        val laeuft: Boolean = false,
        val fehler: String? = null,
        val stand: Sitzungsstand? = null,
        val tischcode: String? = null,
        val port: Int = Sockettransport.KEIN_PORT,
        val angekuendigt: Boolean = false,
        val gefundene: List<Dienst> = emptyList(),
        val eigenerName: String = "",
    )

    private val _ansicht = MutableStateFlow(Tischansicht())
    val ansicht: StateFlow<Tischansicht> = _ansicht.asStateFlow()

    /**
     * Die Kennung dieses Geräts — **aus der Datenbank**, nicht aus diesem Lauf.
     *
     * TDD 4.3: `device_uid` ist dauerhaft. Sie einmal je Prozess zu ziehen war
     * der Fehler, der zu `FOREIGN KEY constraint failed (787)` geführt hat: Die
     * `device`-Zeile wurde nie geschrieben, und `match.host_device_uid` zeigt
     * darauf. `Geraeteanmeldung.eigenes` legt sie beim ersten Mal an und liest
     * sie danach — der Wiedereinstieg aus TDD 9.3 findet seine Sitzung nur
     * wieder, wenn das Gerät dasselbe bleibt.
     */
    @Volatile
    private var geraeteUid: String = ""

    private fun geraet(datenbank: de.myhornets.rise1.store.RiseDatabase, anzeigename: String): String =
        RiseStore.geraete(datenbank).eigenes(anzeigename).deviceUid.also { geraeteUid = it }

    @Volatile
    private var eigenerParticipantUid: String? = null

    @Volatile
    private var eigenerSitzplatz: Int? = null

    private var verzeichnis: Dienstverzeichnis? = null
    private var hostbausatz: Hostbausatz? = null
    private var gastbausatz: Gastbausatz? = null

    init {
        beobachteStand()
    }

    // ── Host ────────────────────────────────────────────────────────────────

    /**
     * Eröffnet eine Partie: Zertifikat, Zeile, `match_created`, Sitzung, Ankündigung.
     *
     * Die Reihenfolge ist die aus `Partieeroeffnung.SCHRITTE` und nicht
     * beliebig: Erst muss die Partie in `rise.db` stehen, dann darf ein Gast
     * beitreten — sonst legte `Beitrittsstelle` einen Sitzplatz an einem Tisch
     * an, den es noch nicht gibt.
     */
    fun eroeffnePartie(anzeigename: String, plaetze: Int = 4) {
        starteHandlung {
            val datenbank = RiseStore.open(kontext)
            val log = RiseStore.eventLog(datenbank)
            val anlage = Partieanlage()

            // SCHRITT 1: das eigene Gerät. Es ist der Elternsatz von
            // `match.host_device_uid` und `participant_session.device_uid` —
            // ohne ihn scheitert alles Weitere mit FOREIGN KEY 787.
            val eigenesGeraet = geraet(datenbank, anzeigename)

            val plan = Partieplan(
                modeUid = MODUS,
                hostDeviceUid = eigenesGeraet,
                plaetze = plaetze,
                catalogVersion = KATALOGFASSUNG,
                setCode = SATZ,
            )
            val eroeffnung = anlage.lege(plan) { matchUid -> AndroidKeyStoreZertifikat.fuerPartie(matchUid) }
            val kontextDerPartie = eroeffnung.kontext

            Partieschreiber(datenbank, log).schreibe(
                kontext = kontextDerPartie,
                modeUid = plan.modeUid,
                catalogVersion = plan.catalogVersion,
                setCode = plan.setCode,
            )

            val nachschlag = RaumTischnachschlag(datenbank, kontextDerPartie)
            val stelle = anlage.beitrittsstelle(kontextDerPartie, nachschlag)

            // SCHRITT 3: Der Host nimmt selbst Platz — über **denselben** Weg
            // wie jeder Gast. Eine zweite Platzvergabe nur für den Host wäre
            // eine zweite Stelle, an der Sitzplätze entstehen.
            val eigenerPlatz = stelle.beitreten(
                Beitrittsgesuch(
                    matchUid = kontextDerPartie.matchUid,
                    deviceUid = eigenesGeraet,
                    anzeigename = anzeigename.ifBlank { "Host" },
                ),
            ) as? Beitrittsantwort.Angenommen

            val zertifikat = AndroidKeyStoreZertifikat.fuerPartie(kontextDerPartie.matchUid)

            val bausatz = Hostbausatz(
                zertifikat = zertifikat,
                matchUid = kontextDerPartie.matchUid,
                platzvergabe = stelle::beitreten,
                sitzplaetze = plaetze,
            )
            hostbausatz = bausatz
            Sitzungsdienst.starte(kontext, bausatz)

            eigenerParticipantUid = eigenerPlatz?.participantUid
            eigenerSitzplatz = eigenerPlatz?.sitzplatz

            _ansicht.value = _ansicht.value.copy(
                schritt = Schritt.HOST,
                tischcode = kontextDerPartie.tischcode,
                eigenerName = anzeigename,
            )
            kuendigeAn(bausatz, kontextDerPartie.matchUid, anzeigename)
        }
    }

    /**
     * Kündigt den Tisch im lokalen Netz an — **ohne** den Fingerabdruck.
     *
     * ADR-002A 3.2: *„Wer geprüft wird, darf nicht die Prüfgrundlage liefern."*
     * `Dienst` weist ein solches Merkmal von sich aus zurück; hier steht nur,
     * was zur Auswahl in einer Liste taugt.
     */
    private suspend fun kuendigeAn(bausatz: Hostbausatz, matchUid: String, anzeigename: String) {
        // Der Port entsteht im Aufbau der Sitzung, also nach `Sitzungsdienst.starte`.
        val port = warteAufPort(bausatz) ?: run {
            _ansicht.value = _ansicht.value.copy(
                fehler = "Dieses Gerät konnte keinen Port öffnen. Eine andere Partie kann " +
                    "ein anderes Gerät eröffnen — beitreten kannst du weiterhin.",
            )
            return
        }

        val dienst = Dienst(
            gegenstelle = Gegenstelle(geraeteUid, anzeigename.ifBlank { "Rise-Tisch" }),
            port = port,
            merkmale = mapOf(
                Dienst.MERKMAL_PARTIENAME to matchUid,
                Dienst.MERKMAL_PROTOKOLL to PROTOKOLLFASSUNG,
            ),
        )
        val verz = NsdDienstverzeichnis(kontext, geraeteUid).also { verzeichnis = it }
        verz.registriere(dienst) { ergebnis ->
            _ansicht.value = when (ergebnis) {
                is Registrierung.Erfolgreich -> _ansicht.value.copy(port = port, angekuendigt = true)
                is Registrierung.Gescheitert -> _ansicht.value.copy(
                    port = port,
                    angekuendigt = false,
                    fehler = "Der Tisch ließ sich nicht ankündigen (${ergebnis.grund}). " +
                        "Gäste können ihn dann nicht finden.",
                )
            }
        }
    }

    private suspend fun warteAufPort(bausatz: Hostbausatz): Int? {
        repeat(PORT_VERSUCHE) {
            val port = bausatz.port
            if (port > 0) return port
            delay(PORT_WARTEN_MILLIS)
        }
        return null
    }

    // ── Gast ────────────────────────────────────────────────────────────────

    /** Wechselt in die Suche und beginnt zu horchen. */
    fun sucheTische() {
        _ansicht.value = _ansicht.value.copy(schritt = Schritt.SUCHE, gefundene = emptyList(), fehler = null)
        val verz = NsdDienstverzeichnis(kontext, geraeteUid).also { verzeichnis = it }
        verz.sucheAb { ereignis ->
            val bisher = _ansicht.value.gefundene
            _ansicht.value = when (ereignis) {
                is Suchereignis.Gefunden -> _ansicht.value.copy(
                    // Bonjour kündigt neu an, wenn sich etwas ändert — der
                    // Aufrufer führt eine Menge, keine Liste.
                    gefundene = bisher.filterNot {
                        it.gegenstelle.geraeteUid == ereignis.dienst.gegenstelle.geraeteUid
                    } + ereignis.dienst,
                )

                is Suchereignis.Verschwunden -> _ansicht.value.copy(
                    gefundene = bisher.filterNot { it.gegenstelle == ereignis.gegenstelle },
                )

                is Suchereignis.Gescheitert -> _ansicht.value.copy(
                    fehler = "Die Suche im lokalen Netz ist gescheitert: ${ereignis.meldung}",
                )
            }
        }
    }

    /**
     * Tritt einem gefundenen Tisch bei.
     *
     * [tischcode] ist, was der Nutzer vom Bildschirm des Hosts abgelesen hat.
     * Er ist die **einzige** Prüfgrundlage — nicht die Adresse, nicht der Name
     * in der Liste, nicht die Ankündigung.
     */
    fun tritteBei(dienst: Dienst, tischcode: String, anzeigename: String) {
        starteHandlung {
            // Auch der Gast meldet zuerst sein Gerät an: Er schreibt zwar
            // nichts in eine fremde Datenbank, braucht aber eine dauerhafte
            // `device_uid` für den Wiedereinstieg (TDD 4.3/9.3).
            val eigenesGeraet = geraet(RiseStore.open(kontext), anzeigename)
            val matchUid = dienst.merkmale[Dienst.MERKMAL_PARTIENAME]
                ?: fehlerAus("Dieser Tisch nennt keine Partie — er gehört nicht zu Rise.")
            val adresse = dienst.adresse
                ?: fehlerAus("Der Tisch wurde gefunden, aber seine Adresse nicht aufgelöst.")
            val erwartet = try {
                Fingerabdruck.ausEingabe(tischcode)
            } catch (fehler: IllegalArgumentException) {
                fehlerAus("Der Tischcode ist nicht lesbar: ${fehler.message}")
            }

            val bausatz = Gastbausatz(
                matchUid = matchUid,
                erwartet = erwartet,
                wirt = adresse,
                port = dienst.port,
                host = dienst.gegenstelle,
                gesuch = Beitrittsgesuch(
                    matchUid = matchUid,
                    deviceUid = eigenesGeraet,
                    anzeigename = anzeigename.ifBlank { "Gast" },
                ),
            )
            gastbausatz = bausatz
            verzeichnis?.beendeSuche()
            Sitzungsdienst.starte(kontext, bausatz)

            _ansicht.value = _ansicht.value.copy(
                schritt = Schritt.GAST,
                tischcode = erwartet.lesbar,
                eigenerName = anzeigename,
            )
        }
    }

    // ── Gemeinsam ───────────────────────────────────────────────────────────

    /** Beendet Sitzung und Ankündigung und geht zurück an den Anfang. */
    fun beende() {
        verzeichnis?.beendeRegistrierung()
        verzeichnis?.beendeSuche()
        verzeichnis = null
        Sitzungsdienst.stoppe(kontext)
        hostbausatz = null
        gastbausatz = null
        eigenerParticipantUid = null
        eigenerSitzplatz = null
        _ansicht.value = Tischansicht(eigenerName = _ansicht.value.eigenerName)
    }

    fun fehlerGesehen() {
        _ansicht.value = _ansicht.value.copy(fehler = null)
    }

    override fun onCleared() {
        // Der Dienst wird **nicht** beendet: Er hält die Sitzung über das Ende
        // dieses Bildschirms hinweg — genau dafür gibt es ihn (ADR-008, T-075).
        verzeichnis?.beendeSuche()
        super.onCleared()
    }

    /**
     * Liest den Sitzungszustand regelmäßig — auf dem Sitzungsthread.
     *
     * `Sitzungsstand.vomHost`/`vomGast` fassen `Tischdienst` beziehungsweise
     * `Beitrittsablauf` an. Beide sind nicht threadsicher und sollen es nicht
     * werden (ADR-008); deshalb geht der Zugriff durch
     * `Sitzungslaufzeit.fuehreAus` und nicht von hier aus direkt.
     */
    private fun beobachteStand() {
        viewModelScope.launch {
            while (true) {
                delay(TAKT_MILLIS)
                val laufzeit = Sitzungswerk.laufzeit ?: continue
                val host = hostbausatz
                val gast = gastbausatz
                laufzeit.fuehreAus {
                    val stand = when {
                        host != null -> host.dienst?.let {
                            Sitzungsstand.vomHost(
                                matchUid = host.matchUid,
                                tischcode = host.tischcode,
                                dienst = it,
                                eigenerParticipantUid = eigenerParticipantUid,
                                eigenerSitzplatz = eigenerSitzplatz,
                            )
                        }

                        gast != null -> gast.ablauf?.let {
                            Sitzungsstand.vomGast(gast.matchUid, _ansicht.value.tischcode, it)
                        }

                        else -> null
                    }
                    if (stand != null) _ansicht.value = _ansicht.value.copy(stand = stand)
                }
            }
        }
    }

    private fun starteHandlung(arbeit: suspend () -> Unit) {
        viewModelScope.launch {
            _ansicht.value = _ansicht.value.copy(laeuft = true, fehler = null)
            try {
                // Zertifikat, Datenbank und Sockets gehören nicht auf den
                // Hauptthread. Der Sitzungsthread ist es auch nicht — er gehört
                // der Sitzung, nicht dem Aufbau.
                withContext(Dispatchers.IO) { arbeit() }
            } catch (fehler: Throwable) {
                _ansicht.value = _ansicht.value.copy(fehler = fehler.message ?: fehler.javaClass.simpleName)
            } finally {
                _ansicht.value = _ansicht.value.copy(laeuft = false)
            }
        }
    }

    private fun fehlerAus(meldung: String): Nothing = throw IllegalStateException(meldung)

    companion object {

        /** Wie oft der Sitzungszustand neu gelesen wird. */
        const val TAKT_MILLIS = 500L

        private const val PORT_VERSUCHE = 40
        private const val PORT_WARTEN_MILLIS = 100L

        /** Was in der Ankündigung steht — nie ein Fingerabdruck. */
        const val PROTOKOLLFASSUNG = "1"

        /** Platzhalter, bis `T-030` die Modi bringt. */
        private const val MODUS = "treachery-standard"
        private const val KATALOGFASSUNG = "1"
        private const val SATZ = "TREACHERY"

        /** Der Diensttyp aus ADR-001 — hier nur, damit er sichtbar bleibt. */
        const val DIENST = DIENSTTYP

        fun fabrik(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TischViewModel(context.applicationContext) as T
            }

        /** Nur zur Anzeige: Auf welcher Android-Fassung läuft das hier? */
        val geraetefassung: Int get() = Build.VERSION.SDK_INT
    }
}

/** Kurzform für die Oberfläche. */
val Sitzungsstand.rollenname: String
    get() = when (rolle) {
        Tischrolle.HOST -> "HOST"
        Tischrolle.GAST -> "GAST"
    }
