package de.myhornets.rise1.prototyp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.myhornets.rise1.catalog.IdentityEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S4 / [[ADR-005 MVVM und StateFlow]] — die Zustandsschicht der Prototyp-Oberfläche.
 *
 * ## Was sich gegenüber T-017 ändert und warum
 *
 * [[T-017 Katalog-Browser]] hat die erste Oberfläche bewusst ohne ViewModel
 * gebaut: „Für eine Liste mit 62 Einträgen und zwei Ansichten wäre jede davon
 * mehr Infrastruktur als Nutzen." Das war richtig und ist es für den Browser
 * weiterhin.
 *
 * Hier ist die Lage eine andere. Seit [[S2 Prototyp auf Event-Log]] kommt der
 * Zustand aus einer **Projektion**, die sich fortschreibt, während die
 * Oberfläche sie anzeigt — und ab S5 zusätzlich, während Events von anderen
 * Geräten eintreffen. Ein Zustand, der sich von außen ändert, will beobachtet
 * werden, nicht geladen. Genau das ist [stand].
 *
 * ## Was dieses ViewModel ausdrücklich **nicht** tut
 *
 * Es **besitzt keinen Spielzustand.** [stand] ist ein Abbild der Projektion,
 * kein zweiter Speicher daneben. Jede Bedienhandlung geht durch
 * [PrototypSteuerung] und damit durch das Event-Log; danach wird neu gelesen.
 * Ein `_stand.value = _stand.value!!.copy(...)` — die naheliegende Abkürzung,
 * um die Oberfläche „sofort" reagieren zu lassen — wäre exakt die zweite
 * Wahrheitsquelle, die S2 beseitigt hat. Sie steht hier nicht, und das ist der
 * wichtigste Satz dieser Datei.
 *
 * Es enthält **keine Spielregel.** Was eine Handlung bedeutet, entscheidet der
 * Tisch; dieses ViewModel reicht sie weiter.
 *
 * ## Fehler
 *
 * [fehler] ist ein eigener Strom und wird nicht in [stand] gemischt. Ein
 * Fehlschlag beim Aufdecken macht den bisherigen Zustand nicht ungültig — die
 * Oberfläche soll ihn weiter anzeigen und die Meldung daneben.
 */
class PrototypViewModel(
    private val steuerung: PrototypSteuerung,
) : ViewModel() {

    private val _stand = MutableStateFlow<Rundenstand?>(null)

    /** Der Anzeigezustand. `null` heißt: noch nicht gelesen, nicht „leer". */
    val stand: StateFlow<Rundenstand?> = _stand.asStateFlow()

    private val _fehler = MutableStateFlow<String?>(null)
    val fehler: StateFlow<String?> = _fehler.asStateFlow()

    private val _laeuft = MutableStateFlow(false)

    /** Ob gerade eine Handlung läuft — für Schaltflächen, die nicht doppelt sollen. */
    val laeuft: StateFlow<Boolean> = _laeuft.asStateFlow()

    init {
        lade()
    }

    /** Liest den Zustand der zuletzt angelegten Runde neu aus der Ablage. */
    fun lade() = fuehreAus { }

    fun neueRunde(namen: List<String>) = fuehreAus { steuerung.neueRunde(namen) }

    fun verteile(matchUid: String) = fuehreAus { steuerung.verteile(matchUid) }

    fun decke(participantUid: String) = fuehreAus { steuerung.decke(participantUid) }

    fun setzeZurueck(matchUid: String) = fuehreAus { steuerung.setzeZurueck(matchUid) }

    fun verwirf(matchUid: String) = fuehreAus { steuerung.verwirf(matchUid) }

    // S3 / D-003
    fun starteZug(matchUid: String, participantUid: String) =
        fuehreAus { steuerung.starteZug(matchUid, participantUid) }

    fun beendeZug(matchUid: String) = fuehreAus { steuerung.beendeZug(matchUid) }

    fun scheideAus(matchUid: String, participantUid: String) =
        fuehreAus { steuerung.scheideAus(matchUid, participantUid) }

    /**
     * Die Karte eines Sitzplatzes — die einzige Abfrage, die etwas zurückgibt.
     *
     * Sie ändert nichts und gehört deshalb nicht in [stand]: Was der Spieler
     * beim Weiterreichen sieht, ist eine Ansicht, kein Zustand des Tisches.
     */
    suspend fun karteVon(matchUid: String, participantUid: String): IdentityEntity? =
        runCatching { withContext(Dispatchers.IO) { steuerung.karteVon(matchUid, participantUid) } }
            .onFailure { _fehler.value = it.message ?: it.toString() }
            .getOrNull()

    /** Die Meldung wurde angezeigt. */
    fun fehlerGesehen() {
        _fehler.value = null
    }

    /**
     * Handlung ausführen, danach **immer** neu lesen.
     *
     * Auch im Fehlerfall: Eine abgebrochene Handlung kann die Ablage bereits
     * verändert haben — `MatchEventLog.anhaengen` ist zwar eine Transaktion,
     * aber eine Bedienhandlung kann aus mehreren bestehen. Nicht neu zu lesen
     * hieße, eine Anzeige zu behalten, von der niemand mehr weiß, ob sie stimmt.
     */
    private fun fuehreAus(arbeit: () -> Unit) {
        viewModelScope.launch {
            _laeuft.value = true
            runCatching { withContext(Dispatchers.IO) { arbeit() } }
                .onFailure { _fehler.value = it.message ?: it.toString() }
            runCatching {
                withContext(Dispatchers.IO) { steuerung.stand(steuerung.letzteRunde()) }
            }
                .onSuccess { _stand.value = it }
                .onFailure { _fehler.value = it.message ?: it.toString() }
            _laeuft.value = false
        }
    }

    companion object {

        /**
         * Erzeugt das ViewModel mit einer Steuerung auf dem Anwendungskontext.
         *
         * Bewusst `applicationContext`: Die Steuerung hält eine Datenbank, das
         * ViewModel überlebt eine Drehung des Geräts — eine Activity, die es
         * nicht tut, hätte hier nichts verloren.
         */
        fun fabrik(context: Context): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PrototypViewModel(PrototypSteuerung(context.applicationContext)) as T
            }
    }
}
