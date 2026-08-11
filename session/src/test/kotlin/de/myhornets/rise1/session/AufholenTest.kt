package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.MatchEvent
import de.myhornets.rise1.core.event.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-108 — das Aufholen nach dem Wiedereinstieg, TDD 9.5.
 *
 * ## Die Tests, um die es hier geht
 *
 * [einFremdesPrivatesEventKommtNichtInsDelta] ist der eine, der zählt. Ein
 * `deal_key_packet` beim falschen Spieler ist das Ende der Partie — und zwar
 * unbemerkt, denn der Empfänger könnte es sogar entschlüsseln, wenn es an ihn
 * gerichtet wäre. Deshalb steht die Regel in genau einer Funktion und wird hier
 * aus beiden Richtungen geprüft: Der Host packt es nicht ein
 * ([einFremdesPrivatesEventKommtNichtInsDelta]), und der Client nimmt es nicht
 * an ([derClientVerwirftEinDeltaMitFremdemPrivatem]).
 *
 * Der zweite ist [gefilterteLueckenSindKeineLuecken]. Eine Prüfung auf
 * lückenlose `seq` wäre die naheliegende und wäre falsch: Sie würde jedes
 * korrekte Delta ablehnen, sobald ein anderer Spieler ein privates Event bekommen
 * hat. Der Bereich wird geprüft, nicht der Abstand.
 */
class AufholenTest {

    private val ich = "p-1"

    private fun auswahl(events: List<MatchEvent>, schwelle: Long = 200) =
        Deltaauswahl(Listenquelle(events), Aufholschwelle(schwelle))

    private fun pruefung(eigener: String = ich) = Deltapruefung("m-1", eigener)

    // ── Die Auswahl auf der Host-Seite ──────────────────────────────────────

    @Test
    fun einDeltaEnthaeltGenauDenBereichDahinter() {
        val alle = (1L..5L).map { ereignis(it) }
        val delta = auswahl(alle).fuer("m-1", ich, lastSeqSeen = 2, bisSeq = 5) as Aufholung.Delta

        assertEquals(listOf(3L, 4L, 5L), delta.events.mapNotNull { it.seq })
        assertEquals(2L, delta.abSeqExklusiv)
        assertEquals(5L, delta.bisSeq)
    }

    @Test
    fun einFremdesPrivatesEventKommtNichtInsDelta() {
        val alle = listOf(
            ereignis(1),
            ereignis(2, typ = "deal_key_packet", visibility = Visibility.PRIVATE, empfaenger = "p-2"),
            ereignis(3, typ = "deal_key_packet", visibility = Visibility.PRIVATE, empfaenger = ich),
            ereignis(4),
        )
        val delta = auswahl(alle).fuer("m-1", ich, lastSeqSeen = 0, bisSeq = 4) as Aufholung.Delta

        assertEquals(listOf(1L, 3L, 4L), delta.events.mapNotNull { it.seq })
        assertTrue(
            delta.events.none { it.recipientParticipantUid == "p-2" },
            "Kein Event, das an einen anderen Sitzplatz gerichtet ist.",
        )
    }

    @Test
    fun einPlayerOnlyEventGehtAnJedenSpielerDerPartie() {
        // PLAYER_ONLY heißt: alle am Tisch, aber nicht der Host als Zuschauer.
        // Der Empfänger hat den Handshake bestanden, also ist er am Tisch.
        val alle = listOf(ereignis(1, typ = "note_added", visibility = Visibility.PLAYER_ONLY))
        val delta = auswahl(alle).fuer("m-1", "p-9", lastSeqSeen = 0, bisSeq = 1) as Aufholung.Delta
        assertEquals(listOf(1L), delta.events.mapNotNull { it.seq })
    }

    @Test
    fun unbestaetigteEventsKommenNichtInsDelta() {
        // Ohne `seq` keine Position in der Reihenfolge (TDD 6.2). Ein Aufholen
        // ist genau eine Aussage über eine Reihenfolge.
        assertEquals(false, Deltaauswahl.sichtbarFuer(ereignis(seq = null), ich))
    }

    @Test
    fun eineFremdePartieWirdNichtAusgeliefert() {
        val alle = listOf(ereignis(1), ereignis(2, matchUid = "m-2"))
        val delta = auswahl(alle).fuer("m-1", ich, lastSeqSeen = 0, bisSeq = 2) as Aufholung.Delta
        assertEquals(listOf(1L), delta.events.mapNotNull { it.seq })
    }

    @Test
    fun eineZuGrosseLueckeVerlangtEinenSchnappschuss() {
        val ergebnis = auswahl(emptyList(), schwelle = 10)
            .fuer("m-1", ich, lastSeqSeen = 0, bisSeq = 500)

        val schnappschuss = ergebnis as Aufholung.SchnappschussNoetig
        assertEquals(500L, schnappschuss.bisSeq)
        assertEquals(500L, schnappschuss.luecke)
    }

    @Test
    fun genauAnDerSchwelleGiltSchonDerSchnappschuss() {
        // Die Grenze wird ausdrücklich geprüft, weil hier sonst niemand hinsieht:
        // Lücke == Schwelle ist Schnappschuss, ein weniger ist Delta.
        val zehn = auswahl(emptyList(), schwelle = 10).fuer("m-1", ich, 0, 10)
        val neun = auswahl(emptyList(), schwelle = 10).fuer("m-1", ich, 1, 10)
        assertTrue(zehn is Aufholung.SchnappschussNoetig)
        assertTrue(neun is Aufholung.Delta)
    }

    @Test
    fun einClientDerWeiterIstAlsDerHostBekommtEinLeeresDelta() {
        // Kein Fehler, keine Ausnahme: Der Host kann eine Bestätigung verloren
        // haben. Ein leeres Delta sagt genau das Richtige — hier ist nichts.
        val delta = auswahl((1L..3L).map { ereignis(it) })
            .fuer("m-1", ich, lastSeqSeen = 99, bisSeq = 3) as Aufholung.Delta

        assertEquals(emptyList<Long>(), delta.events.mapNotNull { it.seq })
        assertEquals(3L, delta.abSeqExklusiv)
        assertEquals(3L, delta.bisSeq)
    }

    @Test
    fun dieQuelleWirdMitDemFestenEndeGefragt() {
        // TDD 9.5: bis zu einem **festen** `up_to_seq`. Wächst das Log während
        // des Aufholens, ändert das an dieser Antwort nichts.
        val quelle = Listenquelle((1L..9L).map { ereignis(it) })
        Deltaauswahl(quelle).fuer("m-1", ich, lastSeqSeen = 4, bisSeq = 7)
        assertEquals<Triple<String, Long, Long>?>(Triple("m-1", 4L, 7L), quelle.letzterAufruf)
    }

    // ── Die Prüfung auf der Client-Seite ────────────────────────────────────

    @Test
    fun einSauberesDeltaWirdAngenommen() {
        val delta = Aufholung.Delta("m-1", ich, 2, 5, listOf(ereignis(3), ereignis(4), ereignis(5)))
        val ergebnis = pruefung().pruefe(stand = 2, delta = delta)

        val angenommen = ergebnis as Deltaergebnis.Angenommen
        assertEquals(listOf(3L, 4L, 5L), angenommen.events.mapNotNull { it.seq })
        assertEquals(5L, angenommen.neuerStand)
    }

    @Test
    fun gefilterteLueckenSindKeineLuecken() {
        // Zwischen 3 und 7 fehlen vier `seq` — sie gingen an andere Spieler.
        // Das Delta ist trotzdem vollständig, denn der **Bereich** ist gedeckt.
        val delta = Aufholung.Delta("m-1", ich, 2, 8, listOf(ereignis(3), ereignis(7)))
        val ergebnis = pruefung().pruefe(stand = 2, delta = delta)

        assertEquals(8L, (ergebnis as Deltaergebnis.Angenommen).neuerStand)
    }

    @Test
    fun einLeeresDeltaSchiebtDenStandTrotzdemVor() {
        // Sonst fragte der Client denselben Bereich immer wieder an und bekäme
        // immer wieder nichts.
        val delta = Aufholung.Delta("m-1", ich, 2, 9, emptyList())
        assertEquals(9L, (pruefung().pruefe(2, delta) as Deltaergebnis.Angenommen).neuerStand)
    }

    @Test
    fun einDeltaDasNichtAnschliesstWirdVerworfen() {
        // Der Host beginnt bei 5, der Client steht bei 2 — dazwischen fehlt
        // Verlauf. Das ist die echte Lücke.
        val delta = Aufholung.Delta("m-1", ich, 5, 7, listOf(ereignis(6), ereignis(7)))
        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.FALSCHER_ANSCHLUSS, 5),
            pruefung().pruefe(stand = 2, delta = delta),
        )
    }

    @Test
    fun einDeltaMitDoppelterSeqWirdVerworfen() {
        val delta = Aufholung.Delta("m-1", ich, 0, 3, listOf(ereignis(1), ereignis(1), ereignis(2)))
        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.DOPPELTE_SEQ, 1),
            pruefung().pruefe(stand = 0, delta = delta),
        )
    }

    @Test
    fun einDeltaInFalscherReihenfolgeWirdVerworfen() {
        val delta = Aufholung.Delta("m-1", ich, 0, 3, listOf(ereignis(3), ereignis(2)))
        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.FALSCHE_REIHENFOLGE, 2),
            pruefung().pruefe(stand = 0, delta = delta),
        )
    }

    @Test
    fun einBereitsBekanntesEventWirdVerworfenUndHeisstAuchSo() {
        val delta = Aufholung.Delta("m-1", ich, 4, 6, listOf(ereignis(5), ereignis(4)))
        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.BEREITS_BEKANNT, 4),
            pruefung().pruefe(stand = 4, delta = delta),
        )
    }

    @Test
    fun einEventJenseitsDesZugesagtenEndesWirdVerworfen() {
        val delta = Aufholung.Delta("m-1", ich, 0, 3, listOf(ereignis(1), ereignis(9)))
        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.UEBER_DEM_ENDE, 9),
            pruefung().pruefe(stand = 0, delta = delta),
        )
    }

    @Test
    fun einUnbestaetigtesEventImDeltaWirdVerworfen() {
        val delta = Aufholung.Delta("m-1", ich, 0, 3, listOf(ereignis(1), ereignis(null)))
        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.UNBESTAETIGT),
            pruefung().pruefe(stand = 0, delta = delta),
        )
    }

    @Test
    fun derClientVerwirftEinDeltaMitFremdemPrivatem() {
        val delta = Aufholung.Delta(
            "m-1", ich, 0, 2,
            listOf(
                ereignis(1),
                ereignis(2, typ = "deal_key_packet", visibility = Visibility.PRIVATE, empfaenger = "p-2"),
            ),
        )
        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.FREMDES_PRIVATES, 2),
            pruefung().pruefe(stand = 0, delta = delta),
        )
    }

    @Test
    fun einDeltaEinerFremdenPartieWirdVerworfen() {
        val delta = Aufholung.Delta("m-2", ich, 0, 1, listOf(ereignis(1, matchUid = "m-2")))
        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.FREMDE_PARTIE),
            pruefung().pruefe(stand = 0, delta = delta),
        )
    }

    @Test
    fun einEinzelnesFremdesEventInEinemPassendenDeltaWirdAuchVerworfen() {
        val delta = Aufholung.Delta("m-1", ich, 0, 2, listOf(ereignis(1), ereignis(2, matchUid = "m-2")))
        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.FREMDE_PARTIE, 2),
            pruefung().pruefe(stand = 0, delta = delta),
        )
    }

    @Test
    fun einAnderesEndeAlsZugesagtWirdVerworfen() {
        val delta = Aufholung.Delta("m-1", ich, 0, 9, listOf(ereignis(1)))
        assertEquals<Deltaergebnis>(
            Deltaergebnis.Abgelehnt(Deltafehler.PASST_NICHT_ZUR_ANTWORT, 9),
            pruefung().pruefe(stand = 0, delta = delta, zugesagtesEnde = 5),
        )
    }

    @Test
    fun beiAblehnungWirdNichtsUebernommen() {
        // Auch nicht der gute Teil davor. Ein halb übernommenes Delta ließe einen
        // Stand zurück, den niemand mehr erklären kann.
        val delta = Aufholung.Delta("m-1", ich, 0, 3, listOf(ereignis(1), ereignis(2), ereignis(2)))
        val ergebnis = pruefung().pruefe(stand = 0, delta = delta)
        assertTrue(ergebnis is Deltaergebnis.Abgelehnt)
    }

    @Test
    fun dieselbePruefungZweimalErgibtDasselbe() {
        // Zustandslos: Der Stand kommt hinein, er wird nicht gehalten.
        val pruefer = pruefung()
        val delta = Aufholung.Delta("m-1", ich, 1, 3, listOf(ereignis(2), ereignis(3)))
        assertEquals(pruefer.pruefe(1, delta), pruefer.pruefe(1, delta))
    }

    // ── Host und Client zusammen ────────────────────────────────────────────

    @Test
    fun wasDerHostAuswaehltNimmtDerClientAn() {
        // Der Rundlauf. Beide Seiten sind einzeln geprüft; das hier prüft, dass
        // sie dieselbe Vorstellung von einem Bereich haben.
        val alle = listOf(
            ereignis(1),
            ereignis(2, visibility = Visibility.PRIVATE, empfaenger = "p-2"),
            ereignis(3, visibility = Visibility.PRIVATE, empfaenger = ich),
            ereignis(4, typ = "note_added", visibility = Visibility.PLAYER_ONLY),
            ereignis(5),
        )
        val delta = auswahl(alle).fuer("m-1", ich, lastSeqSeen = 0, bisSeq = 5) as Aufholung.Delta
        val ergebnis = pruefung().pruefe(stand = 0, delta = delta, zugesagtesEnde = 5)

        val angenommen = ergebnis as Deltaergebnis.Angenommen
        assertEquals(listOf(1L, 3L, 4L, 5L), angenommen.events.mapNotNull { it.seq })
        assertEquals(5L, angenommen.neuerStand)
    }
}
