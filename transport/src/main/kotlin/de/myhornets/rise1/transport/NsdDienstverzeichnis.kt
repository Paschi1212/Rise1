package de.myhornets.rise1.transport

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * T-068 / T-069 — die echte Umsetzung über `NsdManager` (ADR-001).
 *
 * ## Nicht geprüft, und das ist hier festgehalten
 *
 * **Keine Zeile dieser Datei ist in der Entwicklungsumgebung ausgeführt worden.**
 * `NsdManager` braucht ein Gerät, ein Netz und mindestens zwei Teilnehmer. Was
 * hier steht, ist gegen die Android-Dokumentation geschrieben und gegen die
 * Schnittstelle, die [AttrappenVerzeichnis] vollständig und deterministisch
 * abdeckt — aber es ist **unbelegt**, bis es auf zwei Geräten gelaufen ist.
 *
 * Genau deshalb liegt der gesamte Ablauf hinter [Dienstverzeichnis]: Alles, was
 * darüber gebaut wird, ist gegen die Attrappe prüfbar. Was hier schiefgehen
 * kann, sind Plattformdetails — nicht die Logik der Partie.
 *
 * ## Die Stellen, an denen ich mir am unsichersten bin
 *
 * 1. **Fehlende Berechtigung.** ADR-001: Ab Android 17 braucht der Host
 *    `ACCESS_LOCAL_NETWORK`. `NsdManager` hat dafür **keinen eigenen
 *    Fehlercode**; je nach Version kommt eine `SecurityException` oder ein
 *    `FAILURE_INTERNAL_ERROR`. Beides wird hier auf
 *    [Registrierungsfehler.BerechtigungFehlt] beziehungsweise
 *    [Registrierungsfehler.Sonstiges] abgebildet — welcher Weg tatsächlich
 *    eintritt, muss auf dem Gerät nachgesehen werden.
 * 2. **`resolveService` ist ab API 34 abgekündigt** zugunsten von
 *    `registerServiceInfoCallback`. Weil `minSdk` 29 ist, steht hier der alte
 *    Weg; er funktioniert auch auf neueren Fassungen. Der Wechsel gehört in
 *    einen eigenen Task, nicht hierher.
 * 3. **Rückrufe kommen auf einem fremden Thread.** Diese Klasse reicht sie
 *    unverändert weiter und synchronisiert nichts. Wer sie benutzt, muss das
 *    wissen — die Schicht darüber (`:session`) bringt sie auf ihren eigenen.
 *
 * ## Ein Detail, das leicht falsch verstanden wird
 *
 * Bei einem Namenskonflikt **benennt `NsdManager` selbst um** und meldet den
 * neuen Namen in `onServiceRegistered`. Ein eigener Fehlerfall entsteht dabei
 * nicht. [Registrierungsfehler.NameVergeben] gibt es trotzdem — die Attrappe
 * erzeugt ihn, weil die Schicht darüber damit umgehen können muss, wenn ein
 * anderer Transport ihn eines Tages meldet. Hier wird stattdessen der
 * umbenannte Dienst als Erfolg zurückgegeben, damit die Anzeige den Namen zeigt,
 * unter dem das Gerät wirklich zu finden ist.
 */
class NsdDienstverzeichnis(
    context: Context,
    /**
     * Die Kennung dieses Geräts.
     *
     * `NsdManager` kennt sie nicht — sie wird als TXT-Merkmal mitgegeben, damit
     * ein gefundener Dienst dieselbe [Gegenstelle] ergibt wie im übrigen
     * Programm. Unbeglaubigt wie alles in einer Ankündigung; sie dient der
     * Zuordnung, nicht dem Vertrauen.
     */
    private val eigeneUid: String,
) : Dienstverzeichnis {

    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrierung: NsdManager.RegistrationListener? = null
    private var suche: NsdManager.DiscoveryListener? = null

    override fun registriere(dienst: Dienst, rueckmeldung: (Registrierung) -> Unit) {
        beendeRegistrierung()

        val info = NsdServiceInfo().apply {
            serviceName = dienst.gegenstelle.anzeigename
            serviceType = DIENSTTYP
            port = dienst.port
            setAttribute(MERKMAL_UID, eigeneUid)
            dienst.merkmale.forEach { (schluessel, wert) -> setAttribute(schluessel, wert) }
        }

        val hoerer = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                // Der Name kann ein anderer sein als der gewünschte — siehe
                // Klassenkommentar. Zurückgegeben wird der wirkliche.
                rueckmeldung(
                    Registrierung.Erfolgreich(
                        dienst.copy(
                            gegenstelle = dienst.gegenstelle.copy(
                                anzeigename = info.serviceName ?: dienst.gegenstelle.anzeigename,
                            ),
                        ),
                    ),
                )
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, fehlercode: Int) {
                rueckmeldung(Registrierung.Gescheitert(zuFehler(fehlercode)))
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, fehlercode: Int) = Unit
        }

        registrierung = hoerer
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, hoerer)
        } catch (fehlendeBerechtigung: SecurityException) {
            registrierung = null
            rueckmeldung(Registrierung.Gescheitert(Registrierungsfehler.BerechtigungFehlt))
        } catch (abgewiesen: IllegalArgumentException) {
            registrierung = null
            rueckmeldung(Registrierung.Gescheitert(Registrierungsfehler.Sonstiges(abgewiesen.message.orEmpty())))
        }
    }

    override fun beendeRegistrierung() {
        val hoerer = registrierung ?: return
        registrierung = null
        // `unregisterService` wirft, wenn nie registriert wurde. Ein Aufräumweg,
        // der beim Aufräumen scheitern kann, ist keiner.
        runCatching { nsd.unregisterService(hoerer) }
    }

    override fun sucheAb(hoerer: (Suchereignis) -> Unit) {
        beendeSuche()

        val zuhoerer = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(diensttyp: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                // Gefunden heißt nur: Es gibt etwas mit diesem Namen. Adresse
                // und Port kommen erst über die Auflösung.
                nsd.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onServiceResolved(aufgeloest: NsdServiceInfo) {
                            zuDienst(aufgeloest)?.let { hoerer(Suchereignis.Gefunden(it)) }
                        }

                        override fun onResolveFailed(info: NsdServiceInfo, fehlercode: Int) {
                            // Bewusst kein `Gescheitert`: Ein einzelner Host, der
                            // sich nicht auflösen lässt, ist kein Fehler der
                            // Suche. Die anderen sollen weiter gefunden werden.
                        }
                    },
                )
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val uid = info.attributes?.get(MERKMAL_UID)?.let { String(it) }
                    ?: return // Ohne Kennung lässt sich niemand zuordnen.
                hoerer(Suchereignis.Verschwunden(Gegenstelle(uid, info.serviceName ?: uid)))
            }

            override fun onDiscoveryStopped(diensttyp: String) = Unit

            override fun onStartDiscoveryFailed(diensttyp: String, fehlercode: Int) {
                suche = null
                hoerer(Suchereignis.Gescheitert("Suche konnte nicht starten (Code $fehlercode)."))
            }

            override fun onStopDiscoveryFailed(diensttyp: String, fehlercode: Int) = Unit
        }

        suche = zuhoerer
        try {
            nsd.discoverServices(DIENSTTYP, NsdManager.PROTOCOL_DNS_SD, zuhoerer)
        } catch (fehlendeBerechtigung: SecurityException) {
            suche = null
            hoerer(Suchereignis.Gescheitert("Für die Suche fehlt eine Berechtigung."))
        }
    }

    override fun beendeSuche() {
        val zuhoerer = suche ?: return
        suche = null
        runCatching { nsd.stopServiceDiscovery(zuhoerer) }
    }

    private fun zuDienst(info: NsdServiceInfo): Dienst? {
        val merkmale = info.attributes.orEmpty()
            .mapNotNull { (schluessel, wert) -> wert?.let { schluessel to String(it) } }
            .toMap()
        val uid = merkmale[MERKMAL_UID] ?: return null
        val port = info.port.takeIf { it in 1..65_535 } ?: return null
        return Dienst(
            gegenstelle = Gegenstelle(uid, info.serviceName ?: uid),
            port = port,
            // Die eigene Kennung ist Transportsache und gehört nicht in die
            // fachlichen Merkmale. Ein verbotenes Merkmal aus einer fremden
            // Ankündigung würde `Dienst` zu Recht abweisen — deshalb wird hier
            // gefiltert und nicht geworfen: Ein bösartiger Nachbar im Netz darf
            // nicht die eigene Suche zum Absturz bringen.
            merkmale = merkmale
                .filterKeys { it != MERKMAL_UID && it.lowercase() !in Dienst.VERBOTENE_MERKMALE },
        )
    }

    private fun zuFehler(fehlercode: Int): Registrierungsfehler = when (fehlercode) {
        NsdManager.FAILURE_MAX_LIMIT ->
            Registrierungsfehler.Sonstiges("Zu viele Dienste auf diesem Gerät.")

        else ->
            // Hierunter fällt auch der Fall, in dem `ACCESS_LOCAL_NETWORK` fehlt
            // und die Plattform ihn nicht als solchen meldet — siehe Klassenkommentar.
            Registrierungsfehler.Sonstiges("Registrierung fehlgeschlagen (Code $fehlercode).")
    }

    private companion object {
        /** TXT-Merkmal mit der Gerätekennung. Unbeglaubigt; dient der Zuordnung. */
        const val MERKMAL_UID = "uid"
    }
}
