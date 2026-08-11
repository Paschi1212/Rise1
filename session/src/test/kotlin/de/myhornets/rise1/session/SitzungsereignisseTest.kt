package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.EventClass
import de.myhornets.rise1.core.event.EventType
import de.myhornets.rise1.core.event.Payload
import de.myhornets.rise1.core.event.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-104 — die Sitzungs-Events (TDD 5.4).
 *
 * ## Der Test, um den es hier geht
 *
 * [keinSitzungsereignisVeraendertDenSpielzustand]. TDD 9.2 sagt es als Satz;
 * hier ist es eine Prüfung, die über **alle** erzeugten Entwürfe läuft. Ein
 * `event_class = state` an dieser Stelle würde einen Verbindungsabbruch zu
 * einem Spielzug machen — und der Verlauf einer Partie hinge am WLAN.
 *
 * Der zweite ist [einWackelnErzeugtKeinEreignis]: Der Zustand `wackelig`
 * existiert nur für die Anzeige (TDD 9.2). Ein Verlauf, in dem jeder
 * Funkschatten steht, ist unlesbar — und das fällt erst am Tisch auf.
 */
class SitzungsereignisseTest {

    private fun automatBisOffline(): Pair<Verbindungsautomat, List<Verbindungsmeldung>> {
        val a = Verbindungsautomat("p-1", Verbindungsschwellen(), startzeit = 0)
        return a to a.zeitLaeuft(40_000)
    }

    @Test
    fun einVerbindungsverlustWirdEinEreignis() {
        val (_, meldungen) = automatBisOffline()
        val entwuerfe = Sitzungsereignisse.entwuerfe(meldungen)

        assertEquals(1, entwuerfe.size)
        val e = entwuerfe.single()
        assertEquals("participant_disconnected", e.typ)
        assertEquals(EventClass.SESSION, e.eventClass)
        assertEquals(Visibility.PUBLIC, e.visibility)
        assertEquals("p-1", e.actorParticipantUid)
        assertEquals<Payload>(Payload.Leer, e.payload)
        assertEquals(40_000L, e.occurredAt, "Die Wanduhr des Erzeugers, nicht die des Hosts.")
    }

    @Test
    fun eineRueckkehrWirdEinEreignis() {
        val (a, _) = automatBisOffline()
        a.handshakeBegonnen(41_000)
        val entwuerfe = Sitzungsereignisse.entwuerfe(a.handshakeErfolgreich(42_000))

        assertEquals(listOf("participant_reconnected"), entwuerfe.map { it.typ })
        assertEquals(EventClass.SESSION, entwuerfe.single().eventClass)
    }

    @Test
    fun eineAblehnungWirdEineAnmerkung() {
        // TDD 5.4: `rejoin_rejected` ist `annotation` und PUBLIC — [Fehlversuch,
        // sicherheitsrelevant, deshalb für alle sichtbar].
        val (a, _) = automatBisOffline()
        a.handshakeBegonnen(41_000)
        val entwuerfe = Sitzungsereignisse.entwuerfe(a.handshakeAbgelehnt(42_000))

        assertEquals(listOf("rejoin_rejected"), entwuerfe.map { it.typ })
        assertEquals(EventClass.ANNOTATION, entwuerfe.single().eventClass)
        assertEquals(Visibility.PUBLIC, entwuerfe.single().visibility)
    }

    @Test
    fun einWackelnErzeugtKeinEreignis() {
        val a = Verbindungsautomat("p-1", Verbindungsschwellen(), startzeit = 0)
        val meldungen = a.zeitLaeuft(11_000) // zwei verpasste Herzschläge → wackelig
        assertEquals(Verbindungszustand.WACKELIG, a.zustand)
        assertEquals(emptyList<Ereignisentwurf>(), Sitzungsereignisse.entwuerfe(meldungen))
    }

    @Test
    fun einGescheitertesAufholenErzeugtKeinEreignis() {
        // Weder eine Ablehnung noch eine Trennung — es gab beides nicht.
        val (a, _) = automatBisOffline()
        a.handshakeBegonnen(41_000)
        assertEquals(
            emptyList<Ereignisentwurf>(),
            Sitzungsereignisse.entwuerfe(a.aufholenGescheitert(42_000)),
        )
    }

    @Test
    fun einVorschlagIstKeinEreignis() {
        // TDD 9.2: Der Ablauf der Karenzzeit schlägt dem Host höchstens vor, den
        // Platz freizugeben. Ein Ereignis daraus wäre ein Timeout, der etwas
        // beendet — genau das verbietet TDD 9.1.
        val schwellen = Verbindungsschwellen(karenzSekunden = 30)
        val a = Verbindungsautomat("p-1", schwellen, startzeit = 0)
        a.zeitLaeuft(40_000)
        val meldungen = a.zeitLaeuft(90_000)

        assertTrue(meldungen.any { it is Verbindungsmeldung.Vorschlag }, "Der Vorschlag kam.")
        assertEquals(emptyList<Ereignisentwurf>(), Sitzungsereignisse.entwuerfe(meldungen))
    }

    @Test
    fun einUebergangAlleinIstKeinEreignis() {
        val uebergang = Verbindungsmeldung.Uebergang(
            Verbindungszustand.VERBUNDEN,
            Verbindungszustand.WACKELIG,
            1_000,
        )
        assertNull(Sitzungsereignisse.entwurf(uebergang))
    }

    @Test
    fun einFremderMeldungstypWirdNichtGedeutet() {
        // Eine Meldung mit einem Typ, den der Automat nicht meldet, wird nicht
        // zu einem Event — auch dann nicht, wenn der Typ im Vokabular steht.
        val fremd = Verbindungsmeldung.Sitzungsereignis("session_superseded", "p-1", 1_000)
        assertNull(
            Sitzungsereignisse.entwurf(fremd),
            "session_superseded ist PRIVATE und gehört dem ablösenden Host (TDD 5.4).",
        )
    }

    @Test
    fun keinSitzungsereignisVeraendertDenSpielzustand() {
        // Über alle drei Typen, nicht über einen als Stellvertreter.
        val alle = listOf(
            Sitzungsereignistypen.VERBINDUNG_VERLOREN,
            Sitzungsereignistypen.VERBINDUNG_ZURUECK,
            Sitzungsereignistypen.WIEDEREINSTIEG_ABGELEHNT,
        ).map { typ ->
            Sitzungsereignisse.entwurf(Verbindungsmeldung.Sitzungsereignis(typ, "p-1", 1))!!
        }

        assertEquals(3, alle.size)
        alle.forEach { e ->
            assertTrue(
                e.eventClass != EventClass.STATE,
                "${e.typ} wäre zustandswirksam — TDD 9.2 verbietet das ausdrücklich.",
            )
        }
    }

    @Test
    fun dieTypenStehenSoImVokabular() {
        // Kein zweites Verzeichnis: Klasse und Sichtbarkeit kommen aus `:core`.
        // Dieser Test würde rot, wenn hier eine eigene Tabelle entstünde und
        // auseinanderliefe.
        listOf(
            Sitzungsereignistypen.VERBINDUNG_VERLOREN,
            Sitzungsereignistypen.VERBINDUNG_ZURUECK,
            Sitzungsereignistypen.WIEDEREINSTIEG_ABGELEHNT,
        ).forEach { typ ->
            val entwurf = Sitzungsereignisse.entwurf(
                Verbindungsmeldung.Sitzungsereignis(typ, "p-1", 1),
            )!!
            val ausVokabular = EventType.vonWert(typ)!!
            assertEquals(ausVokabular.eventClass, entwurf.eventClass)
            assertTrue(entwurf.visibility in ausVokabular.erlaubteSichtbarkeiten)
        }
    }

    @Test
    fun einEntwurfMitZustandsklasseLaesstSichNichtBauen() {
        assertFailsWith<IllegalArgumentException> {
            Ereignisentwurf("turn_started", EventClass.STATE, Visibility.PUBLIC, "p-1", Payload.Leer, 1)
        }
    }

    @Test
    fun einEntwurfMitChiffratLaesstSichNichtBauen() {
        assertFailsWith<IllegalArgumentException> {
            Ereignisentwurf(
                "participant_disconnected",
                EventClass.SESSION,
                Visibility.PRIVATE,
                "p-1",
                Payload.Chiffrat(byteArrayOf(1), "aes-gcm-256"),
                1,
            )
        }
    }
}
