package de.myhornets.rise1.projection

import de.myhornets.rise1.core.event.EventClass
import de.myhornets.rise1.core.event.MatchEvent
import de.myhornets.rise1.core.event.Payload
import de.myhornets.rise1.core.event.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-109 — der personalisierte Schnappschuss (TDD 9.5).
 *
 * ## Die Tests, um die es hier geht
 *
 * [dieFeldstrukturIstFuerAlleEmpfaengerGleich]. TDD 9.5 sagt warum: *„ein
 * weggelassenes Feld wäre selbst eine Information."* Wer zwei Schnappschüsse
 * nebeneinanderlegt, darf am **Bau** nichts ablesen können — nur an dem, was
 * für ihn bestimmt ist.
 *
 * [niemandBekommtEinFremdesSchluesselpaket] ist der andere: Ein Schnappschuss
 * ist die größte Nachricht des Systems und die einzige, die alle privaten
 * Blobs einer Partie berühren muss.
 */
class SchnappschussbauTest {

    private fun ereignis(
        seq: Long,
        typ: String = "turn_started",
        visibility: Visibility = Visibility.PUBLIC,
        empfaenger: String? = null,
        akteur: String? = null,
    ): MatchEvent = MatchEvent(
        eventUid = "e-$typ-$seq-${empfaenger ?: "alle"}",
        matchUid = "m-1",
        seq = seq,
        originDeviceUid = "d-host",
        originSeq = seq,
        lamportClock = seq,
        occurredAt = 1_000,
        recordedAt = 1_000,
        type = typ,
        eventClass = EventClass.STATE,
        actorParticipantUid = akteur,
        targetParticipantUid = null,
        payload = if (visibility == Visibility.PUBLIC) {
            Payload.Klartext("{}")
        } else {
            Payload.Chiffrat(byteArrayOf(1, 2, 3), "aes-gcm-256")
        },
        payloadSchemaVersion = 1,
        visibility = visibility,
        recipientParticipantUid = empfaenger,
    )

    /** Eine Partie: angelegt, drei Beitritte, ein Zug, Umschläge und Pakete. */
    private fun partie(): List<MatchEvent> = listOf(
        ereignis(1, "match_created"),
        ereignis(2, "participant_joined", akteur = "p-1"),
        ereignis(3, "participant_joined", akteur = "p-2"),
        ereignis(4, "participant_joined", akteur = "p-3"),
        ereignis(5, "deal_envelopes_published"),
        ereignis(6, "deal_key_packet", visibility = Visibility.PRIVATE, empfaenger = "p-1"),
        ereignis(7, "deal_key_packet", visibility = Visibility.PRIVATE, empfaenger = "p-2"),
        ereignis(8, "deal_key_packet", visibility = Visibility.PRIVATE, empfaenger = "p-3"),
        ereignis(9, "deal_assignment_committed"),
        ereignis(10, "turn_started", akteur = "p-1"),
        ereignis(11, "identity_revealed", akteur = "p-2"),
    )

    @Test
    fun einSchnappschussTraegtDenGefaltetenZustand() {
        val s = Schnappschussbau.baue("m-1", "p-1", partie(), bisSeq = 11)

        assertEquals(1, s.partie.zugnummer, "Ein begonnener Zug.")
        assertEquals("p-1", s.partie.amZug)
        assertEquals(listOf("p-1", "p-2", "p-3"), s.sitzplaetze.map { it.participantUid })
        assertTrue(s.sitzplaetze.single { it.participantUid == "p-2" }.istAufgedeckt)
    }

    @Test
    fun niemandBekommtEinFremdesSchluesselpaket() {
        listOf("p-1", "p-2", "p-3").forEach { wer ->
            val s = Schnappschussbau.baue("m-1", wer, partie(), bisSeq = 11)
            assertEquals(
                listOf(wer),
                s.eigenePrivate.map { it.recipientParticipantUid },
                "Im Schnappschuss für $wer liegt ein fremdes Paket.",
            )
        }
    }

    @Test
    fun dieFeldstrukturIstFuerAlleEmpfaengerGleich() {
        val alle = listOf("p-1", "p-2", "p-3").map { Schnappschussbau.baue("m-1", it, partie(), 11) }

        // Gleiche Sitzplätze, gleiche Reihenfolge, gleiche Felder — und dasselbe
        // öffentliche Transkript. Unterscheiden darf sich nur der eigene Anteil.
        val ersteStruktur = alle.first().sitzplaetze
        alle.forEach { s ->
            assertEquals(ersteStruktur, s.sitzplaetze, "Sitzplätze unterscheiden sich.")
            assertEquals(alle.first().transkript, s.transkript, "Das Transkript ist für alle gleich.")
            assertEquals(alle.first().partie, s.partie)
            assertEquals(1, s.eigenePrivate.size)
        }
    }

    @Test
    fun dasTranskriptIstVollstaendigUndOeffentlich() {
        // TDD 9.5: das **vollständige** öffentliche Deal-Transkript. Es ist ein
        // Nachweis; ein zusammengefasster Nachweis ist keiner mehr (TDD 8.3).
        val s = Schnappschussbau.baue("m-1", "p-1", partie(), 11)
        assertEquals(
            listOf("deal_envelopes_published", "deal_assignment_committed"),
            s.transkript.map { it.type },
        )
        assertTrue(s.transkript.all { it.visibility == Visibility.PUBLIC })
    }

    @Test
    fun einFesterStandSchneidetSauberAb() {
        // Alles über `bisSeq` bleibt draußen — sonst entstünde das Rennen, das
        // TDD 9.5 mit dem festen Stand ausschließt.
        val s = Schnappschussbau.baue("m-1", "p-1", partie(), bisSeq = 9)

        assertEquals(9, s.bisSeq)
        assertEquals(0, s.partie.zugnummer, "Der Zug bei seq 10 zählt nicht mehr mit.")
        assertTrue(s.sitzplaetze.none { it.istAufgedeckt }, "Die Aufdeckung bei seq 11 auch nicht.")
        assertTrue(s.transkript.all { (it.seq ?: 0) <= 9 })
    }

    @Test
    fun zweiSchnappschuesseZumSelbenStandSindGleich() {
        // Determinismus: Derselbe Stand ergibt dieselbe Nachricht. Sonst wäre
        // nicht zu unterscheiden, ob sich der Spielstand geändert hat oder nur
        // die Reihenfolge einer Map.
        assertEquals(
            Schnappschussbau.baue("m-1", "p-1", partie(), 11),
            Schnappschussbau.baue("m-1", "p-1", partie().shuffled(), 11),
        )
    }

    @Test
    fun eineFremdePartieGehtNichtEin() {
        val fremd = partie() + ereignis(12, "turn_started").copy(matchUid = "m-2")
        val s = Schnappschussbau.baue("m-1", "p-1", fremd, 12)
        assertEquals(1, s.partie.zugnummer, "Der Zug aus der fremden Partie zählt nicht.")
    }

    @Test
    fun unbestaetigteEventsGehenNichtEin() {
        val offen = partie() + ereignis(11, "turn_started").copy(seq = null, recordedAt = null)
        val s = Schnappschussbau.baue("m-1", "p-1", offen, 11)
        assertEquals(1, s.partie.zugnummer)
    }

    @Test
    fun einLeererStandIstEinGueltigerSchnappschuss() {
        // Der Fall aus TDD 9.5: „oder der Client keinen lokalen Zustand mehr hat."
        val s = Schnappschussbau.baue("m-1", "p-1", emptyList(), bisSeq = -1)
        assertEquals(emptyList<Any>(), s.sitzplaetze)
        assertEquals(emptyList<MatchEvent>(), s.transkript)
        assertEquals(emptyList<MatchEvent>(), s.eigenePrivate)
        assertEquals(0, s.ereignisanzahl)
    }

    @Test
    fun einSchnappschussMitFremdemPrivatemLaesstSichNichtBauen() {
        // Der Werttyp erzwingt die Regel — auch für einen Aufrufer, der sie
        // umgehen wollte oder einen Filter vergisst.
        assertFailsWith<IllegalArgumentException> {
            de.myhornets.rise1.core.event.Schnappschuss(
                matchUid = "m-1",
                empfaenger = "p-1",
                bisSeq = 1,
                partie = de.myhornets.rise1.core.event.Partiestand("m-1", 0, null, 0),
                sitzplaetze = emptyList(),
                transkript = emptyList(),
                eigenePrivate = listOf(
                    ereignis(1, visibility = Visibility.PRIVATE, empfaenger = "p-2"),
                ),
            )
        }
    }

    @Test
    fun einSitzplatzOhneAufdeckungTraegtKeineIdentitaet() {
        assertFailsWith<IllegalArgumentException> {
            de.myhornets.rise1.core.event.Sitzplatzstand("p-1", 40, false, false, "identity-7")
        }
    }
}
