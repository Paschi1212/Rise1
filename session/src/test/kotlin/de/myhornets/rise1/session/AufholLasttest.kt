package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.MatchEvent
import de.myhornets.rise1.core.event.Payload
import de.myhornets.rise1.core.event.Visibility
import de.myhornets.rise1.transport.Rahmencodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-078 (Teil) — der Nachweis hinter `resume_delta_threshold`.
 *
 * ## Warum es diesen Test gibt
 *
 * `Aufholschwelle.resumeDeltaSchwelle` war eine **gesetzte Zahl**. TDD 9.5
 * nennt keine, TDD 6.1 nennt dagegen eine Anforderung: *„Nachrichtengrößen im
 * zweistelligen Kilobyte-Bereich"*. Genau daraus lässt sich die Schwelle
 * herleiten, statt sie zu behaupten — und das tut dieser Test.
 *
 * Er ist kein Zeitmesser. Eine Zahl, die von der Maschine abhängt, wäre in der
 * CI mal grün und mal rot und hieße beides nichts. Gemessen werden **Bytes**,
 * und die sind auf jedem Gerät gleich.
 *
 * ## Der Aufbau
 *
 * Acht Spieler — die Obergrenze aus TDD 6.1 — und eine Partie mit 500
 * bestätigten Events, so gemischt, wie eine echte aussieht: öffentliche
 * Zustandsereignisse, Notizen für den Tisch und private Schlüsselpakete mit
 * einem Chiffrat in realistischer Größe.
 */
class AufholLasttest {

    private val spieler = (1..8).map { "p-$it" }

    /** 256 Byte Chiffrat — die Größenordnung eines Schlüsselpakets aus TDD 8.3. */
    private fun chiffrat(n: Int) = Payload.Chiffrat(ByteArray(256) { (it + n).toByte() }, "aes-gcm-256")

    /**
     * Eine Partie mit gemischter Sichtbarkeit.
     *
     * Jedes zehnte Event ist privat und geht reihum an einen Spieler; jedes
     * fünfzehnte ist eine Notiz für den Tisch. Der Rest ist öffentlich.
     */
    private fun partie(anzahl: Int): List<MatchEvent> = (1..anzahl).map { i ->
        val seq = i.toLong()
        when {
            i % 10 == 0 -> ereignis(
                seq,
                typ = "deal_key_packet",
                visibility = Visibility.PRIVATE,
                empfaenger = spieler[i / 10 % spieler.size],
            ).copy(payload = chiffrat(i))

            i % 15 == 0 -> ereignis(seq, typ = "note_added", visibility = Visibility.PLAYER_ONLY)
                .copy(payload = chiffrat(i))

            else -> ereignis(seq, typ = "life_changed", akteur = spieler[i % spieler.size])
        }
    }

    /**
     * Das Delta für einen Empfänger.
     *
     * [schwelle] ist einstellbar, weil einige Messungen hier ausrechnen, was ein
     * Delta über die ganze Partie **kosten würde** — genau der Fall, den die
     * echte Schwelle verhindert. Dass sie ihn verhindert, prüft
     * [einZuGrosserRueckstandFuehrtZurSchnappschussEntscheidung] mit dem
     * Standardwert.
     */
    private fun deltaFuer(
        alle: List<MatchEvent>,
        empfaenger: String,
        ab: Long,
        bis: Long,
        schwelle: Long = Aufholschwelle().resumeDeltaSchwelle,
    ) = Deltaauswahl(Listenquelle(alle), Aufholschwelle(schwelle))
        .fuer("m-1", empfaenger, ab, bis) as Aufholung.Delta

    // ── Der eigentliche Nachweis ────────────────────────────────────────────

    @Test
    fun einVollesDeltaAnDerSchwelleBleibtImZweistelligenKilobyteBereich() {
        val schwelle = Aufholschwelle().resumeDeltaSchwelle
        val alle = partie(500)
        val bis = 500L
        val ab = bis - (schwelle - 1) // die größte Lücke, die noch ein Delta ist

        val groessen = spieler.map { empfaenger ->
            Rahmencodec.kodiere(Sitzungsprotokoll.kodiere(deltaFuer(alle, empfaenger, ab, bis))).size
        }
        val groesste = groessen.max()

        assertTrue(
            groesste < 100 * 1024,
            "Das größte Delta an der Schwelle wäre $groesste Bytes. TDD 6.1 verlangt " +
                "zweistellige Kilobyte — dann ist entweder die Schwelle zu hoch oder ein " +
                "Event zu groß geworden.",
        )
        assertTrue(
            groesste < Rahmencodec.MAX_NUTZLAST / 4,
            "Weniger als ein Viertel der Rahmenobergrenze ($groesste Bytes) — die Reserve ist " +
                "für spätere Felder da, nicht für den Regelfall.",
        )
    }

    @Test
    fun dieSchwelleLiegtUnterDerGrenze_dieDerRahmenSetzt() {
        // Die Herleitung: Wie viele Events dieser Art passen überhaupt in einen
        // Rahmen? Die gesetzte Schwelle muss deutlich darunter liegen, sonst
        // wäre sie keine Fachentscheidung, sondern ein Zufall.
        val alle = partie(500)
        val grosse = deltaFuer(alle, spieler.first(), 0, 500, schwelle = 100_000)
        val bytesJeEvent = Sitzungsprotokoll.kodiere(grosse).nutzlast.size / grosse.events.size
        val passenInEinenRahmen = Rahmencodec.MAX_NUTZLAST / bytesJeEvent

        assertTrue(
            Aufholschwelle().resumeDeltaSchwelle < passenInEinenRahmen / 4,
            "In einen Rahmen passen rund $passenInEinenRahmen Events zu je etwa " +
                "$bytesJeEvent Bytes; die Schwelle steht bei " +
                "${Aufholschwelle().resumeDeltaSchwelle}. Weniger als ein Viertel ist die " +
                "Reserve für größere Nutzdaten.",
        )
    }

    @Test
    fun keinDeltaUeberschreitetDieRahmenobergrenze() {
        // Auch das ganze Log auf einmal — der Fall, den es nach TDD 9.5 nicht
        // geben soll, den ein Fehler aber erzeugen könnte.
        val alle = partie(500)
        spieler.forEach { empfaenger ->
            val bytes = Sitzungsprotokoll.kodiere(deltaFuer(alle, empfaenger, -1, 500, schwelle = 100_000)).nutzlast.size
            assertTrue(bytes <= Rahmencodec.MAX_NUTZLAST, "Delta für $empfaenger: $bytes Bytes.")
        }
    }

    // ── Sicherheit unter Last ───────────────────────────────────────────────

    @Test
    fun auchBeiFuenfhundertEventsSiehtNiemandEinFremdesPrivates() {
        // Dieselbe Regel wie im kleinen Test — aber über die ganze Partie und
        // alle acht Sitzplätze. Ein Filterfehler, der nur bei bestimmten
        // Indizes greift, käme hier heraus und im Einzelfall nicht.
        val alle = partie(500)
        spieler.forEach { empfaenger ->
            val delta = deltaFuer(alle, empfaenger, -1, 500, schwelle = 100_000)
            val fremde = delta.events.filter {
                it.visibility == Visibility.PRIVATE && it.recipientParticipantUid != empfaenger
            }
            assertEquals(emptyList<MatchEvent>(), fremde, "Fremdes PRIVATE im Delta für $empfaenger.")

            // Und der Client bestätigt es unabhängig noch einmal.
            val ergebnis = Deltapruefung("m-1", empfaenger).pruefe(-1, delta, 500)
            assertTrue(ergebnis is Deltaergebnis.Angenommen, "Delta für $empfaenger: $ergebnis")
        }
    }

    @Test
    fun dieSummeDerDeltasIstKleinerAlsAchtmalDasLog() {
        // Der Zweck der Filterung in einer Zahl: Acht Spieler bekommen zusammen
        // **weniger** als acht Kopien des Logs — weil die privaten Teile nur
        // einmal gehen. Wäre es genauso viel, ginge etwas an alle, das nur
        // einem gehört.
        val alle = partie(500)
        val summe = spieler.sumOf { deltaFuer(alle, it, -1, 500, schwelle = 100_000).events.size }
        assertTrue(
            summe < spieler.size * alle.size,
            "Summe $summe gegenüber ${spieler.size * alle.size} — es geht zu viel an alle.",
        )

        // Jedes private Event genau einmal, nicht achtmal.
        val privateGesamt = alle.count { it.visibility == Visibility.PRIVATE }
        val privateVerteilt = spieler.sumOf { e ->
            deltaFuer(alle, e, -1, 500, schwelle = 100_000).events.count { it.visibility == Visibility.PRIVATE }
        }
        assertEquals(privateGesamt, privateVerteilt)
    }

    @Test
    fun einZuGrosserRueckstandFuehrtZurSchnappschussEntscheidung() {
        // Der Gegentest zur Herleitung: Oberhalb der Schwelle wird kein Delta
        // gebaut — auch nicht [nur dieses eine Mal].
        val alle = partie(500)
        val ergebnis = Deltaauswahl(Listenquelle(alle)).fuer("m-1", spieler.first(), -1, 500)
        assertTrue(
            ergebnis is Aufholung.SchnappschussNoetig,
            "500 Events Rückstand bei Schwelle ${Aufholschwelle().resumeDeltaSchwelle}.",
        )
    }
}
