package de.myhornets.rise1.session

import de.myhornets.rise1.core.event.EventClass
import de.myhornets.rise1.core.event.Payload
import de.myhornets.rise1.core.event.Visibility
import java.security.SecureRandom

/**
 * T-101 / T-111 / T-110 — Beitritt, Sitzplatz und Wiederzulassung.
 *
 * ## Warum die drei zusammen stehen
 *
 * Sie beantworten **eine** Frage: Wer bekommt einen Sitzplatz und ein
 * Geheimnis? Der Beitritt vergibt beides zum ersten Mal (`T-101`), der
 * Sitzplatzzustand hält fest, was daraus geworden ist (`T-111`), und die
 * Wiederzulassung ist der einzige Weg, ein verlorenes Geheimnis zu ersetzen
 * (`T-110`, TDD 9.6). Getrennt gebaut hätte jede von ihnen eine eigene
 * Vorstellung davon, wann ein Platz besetzt ist.
 *
 * ## Der Token entsteht hier — und nur hier
 *
 * TDD 9.1 verlangt *„ein echtes Geheimnis von mindestens 128 Bit, das nur das
 * Gerät des Spielers kennt"*. Deshalb:
 *
 * - Er wird mit [SecureRandom] gezogen, nicht mit `Random`. Ein vorhersagbarer
 *   Wiedereinstiegs-Token wäre kein Geheimnis, sondern eine Formalität.
 * - Der Host behält **nur** den gesalzenen Hash aus [RejoinPruefer.tokenHash].
 *   Es gibt in diesem Ablauf keinen Rückgabewert, der den Klartext für den Host
 *   aufbewahrt: [Beitrittsantwort.Angenommen] geht an den Beitretenden.
 * - Er taucht in keinem `toString` auf.
 *
 * ## Was hier nicht passiert
 *
 * **Keine Identitätsverteilung.** Wer welche Karte bekommt, ist `E09` und
 * beginnt frühestens, wenn alle Plätze besetzt sind (TDD 8.3).
 *
 * **Keine Wiedererkennung.** Ein zweiter Beitrittsversuch desselben Geräts
 * bekommt **keinen** vorhandenen Sitzplatz zurück — anders als beim
 * Wiedereinstieg, der einen Nachweis mitbringt. Wer seinen Token verloren hat,
 * geht über [Wiederzulassung] und damit über einen Menschen.
 */

/** Der Zustand eines Sitzplatzes (TDD 4.4). */
object Sitzplatzzustand {
    /** Besetzt und bespielt. */
    const val AKTIV = "active"

    /**
     * Reserviert — der Platz gehört jemandem, der gerade nicht da ist.
     *
     * TDD 6.4: *„Der abwesende Spieler behält seinen Platz mit
     * `seat_state = reserved`."* Und TDD 9.6: *„Es passiert nichts von selbst."*
     * Ein reservierter Platz wird **nie** durch Zeitablauf frei.
     */
    const val RESERVIERT = "reserved"

    /** Freigegeben — durch eine bewusste Handlung, nicht durch ein Timeout. */
    const val FREIGEGEBEN = "released"

    val ALLE = listOf(AKTIV, RESERVIERT, FREIGEGEBEN)
}

/** Was ein beitretendes Gerät schickt (TDD 9.3, erster Kontakt). */
data class Beitrittsgesuch(
    val matchUid: String,
    val deviceUid: String,
    /** Der Name, unter dem der Spieler am Tisch erscheint. */
    val anzeigename: String,
    /**
     * Der gewünschte Sitzplatz, oder `null` für „irgendeiner".
     *
     * Ein Wunsch, kein Anspruch: Vergeben wird er nur, wenn er frei ist.
     */
    val wunschplatz: Int? = null,
) {
    init {
        require(matchUid.isNotBlank()) { "Ein Gesuch ohne Partie ist keines." }
        require(deviceUid.isNotBlank()) { "Ein Gesuch ohne Gerät ist keines." }
        require(anzeigename.isNotBlank()) { "Ein Sitzplatz ohne Namen wäre am Tisch nicht ansprechbar." }
        require(wunschplatz == null || wunschplatz >= 0) { "Sitzplätze zählen ab 0." }
    }
}

/** Warum ein Beitritt abgelehnt wurde. */
enum class Beitrittsablehnung {
    /** Die Partie gibt es hier nicht. */
    PARTIE_UNBEKANNT,

    /**
     * Die Partie nimmt niemanden mehr auf.
     *
     * TDD 4.4: Beitritt gibt es in `setup`. Läuft die Verteilung oder die
     * Partie, kommt niemand mehr **neu** hinzu — wer zurückkehrt, ist ein
     * Wiedereinstieg und kein Beitritt (TDD 9.3).
     */
    PARTIE_NIMMT_NICHT_MEHR_AUF,

    /** Alle Plätze sind vergeben. */
    TISCH_VOLL,

    /** Der gewünschte Platz ist besetzt. */
    PLATZ_BESETZT,

    /** Dieses Gerät sitzt bereits am Tisch. */
    GERAET_SITZT_SCHON,
}

sealed interface Beitrittsantwort {

    /**
     * Angenommen.
     *
     * @param rejoinToken **Klartext, einmalig.** Er geht an das beitretende
     *   Gerät und wird dort verwahrt (`active_membership`, TDD 4.5,
     *   verschlüsselt). Der Host behält nur den Hash.
     */
    data class Angenommen(
        val participantUid: String,
        val sitzplatz: Int,
        val rejoinToken: String,
    ) : Beitrittsantwort {
        /** Bewusst ohne den Token. */
        override fun toString(): String =
            "Angenommen(sitzplatz=$sitzplatz, participant=$participantUid)"
    }

    data class Abgelehnt(val grund: Beitrittsablehnung) : Beitrittsantwort
}

/** Ein Sitzplatz, wie ihn der Beitritt sieht. */
data class Sitzplatz(
    val participantUid: String,
    val sitzplatz: Int,
    val zustand: String,
    /** Das Gerät der laufenden Sitzung, oder `null`. */
    val geraeteUid: String?,
) {
    init {
        require(zustand in Sitzplatzzustand.ALLE) { "Unbekannter seat_state `$zustand` (TDD 4.4)." }
    }
}

/**
 * Was der Beitritt über die Partie wissen muss.
 *
 * Wieder eine Schnittstelle statt einer Kante auf `:store` — dieselbe
 * Begründung wie bei [Partienachschlag].
 */
interface Tischnachschlag {

    fun status(matchUid: String): String?

    /** Wie viele Plätze diese Partie hat. */
    fun plaetze(matchUid: String): Int

    /** Die bereits vergebenen Sitzplätze. */
    fun belegung(matchUid: String): List<Sitzplatz>

    /**
     * Legt den Sitzplatz an, merkt sich den **Hash** des Tokens und eröffnet die
     * erste Sitzung für [deviceUid].
     *
     * Der Klartext des Tokens wird nicht übergeben: Was der Host nicht bekommt,
     * kann er nicht verlieren.
     *
     * Dass die **Sitzung** hier entsteht, ist kein Beiwerk: Ein Sitzplatz kennt
     * kein Gerät (TDD 4.4 führt keine Gerätespalte in `match_participant`), die
     * Sitzung dagegen ist genau die Zuordnung Sitzplatz ⇄ Gerät auf Zeit
     * (TDD 4.5). Ohne sie könnte [belegung] nicht sagen, welches Gerät bereits
     * am Tisch sitzt — und ein zweimal gedrückter Knopf verbrauchte einen Platz.
     * Umgesetzt wird sie über `Sitzungsverwaltung` aus `T-102`.
     */
    fun legeSitzplatzAn(
        matchUid: String,
        sitzplatz: Int,
        anzeigename: String,
        tokenHash: String,
        deviceUid: String,
    ): String

    /** Ersetzt den Hash eines bestehenden Sitzplatzes (TDD 9.6, Wiederzulassung). */
    fun ersetzeTokenHash(participantUid: String, tokenHash: String)
}

/**
 * Der Beitritt auf der Host-Seite (T-101 / T-111).
 *
 * Zufall kommt von außen — wie die Zeit beim [RejoinPruefer]. Nicht, damit ein
 * Test ihn abschalten kann, sondern damit er ihn **nachrechnen** kann: Die
 * Voreinstellung ist [SecureRandom], und es gibt keinen Weg, sie im Betrieb zu
 * ersetzen, der nicht durch diesen Konstruktor führt.
 */
class Beitrittsstelle(
    private val nachschlag: Tischnachschlag,
    private val salz: String,
    private val zufall: () -> ByteArray = { zufallsbytes(TOKEN_BYTES) },
) {

    init {
        require(salz.isNotBlank()) { "Ohne Salz wäre derselbe Token in zwei Partien derselbe Hash." }
    }

    fun beitreten(gesuch: Beitrittsgesuch): Beitrittsantwort {
        val status = nachschlag.status(gesuch.matchUid)
            ?: return Beitrittsantwort.Abgelehnt(Beitrittsablehnung.PARTIE_UNBEKANNT)

        // Nur `setup`. Ein Beitritt während der Verteilung bekäme ein halbes
        // Schlüsselpaket; einer während der Partie wäre ein neunter Spieler in
        // einem Spiel für acht.
        if (status != Partiestatus.SETUP) {
            return Beitrittsantwort.Abgelehnt(Beitrittsablehnung.PARTIE_NIMMT_NICHT_MEHR_AUF)
        }

        val belegung = nachschlag.belegung(gesuch.matchUid)
        if (belegung.any { it.geraeteUid == gesuch.deviceUid && it.zustand != Sitzplatzzustand.FREIGEGEBEN }) {
            return Beitrittsantwort.Abgelehnt(Beitrittsablehnung.GERAET_SITZT_SCHON)
        }

        val besetzt = belegung
            .filter { it.zustand != Sitzplatzzustand.FREIGEGEBEN }
            .map { it.sitzplatz }
            .toSet()
        val plaetze = nachschlag.plaetze(gesuch.matchUid)

        val platz = when (val wunsch = gesuch.wunschplatz) {
            null -> (0 until plaetze).firstOrNull { it !in besetzt }
                ?: return Beitrittsantwort.Abgelehnt(Beitrittsablehnung.TISCH_VOLL)

            else -> {
                if (wunsch >= plaetze) {
                    return Beitrittsantwort.Abgelehnt(Beitrittsablehnung.PLATZ_BESETZT)
                }
                if (wunsch in besetzt) {
                    return Beitrittsantwort.Abgelehnt(Beitrittsablehnung.PLATZ_BESETZT)
                }
                wunsch
            }
        }

        val token = neuerToken()
        val participantUid = nachschlag.legeSitzplatzAn(
            matchUid = gesuch.matchUid,
            sitzplatz = platz,
            anzeigename = gesuch.anzeigename,
            tokenHash = RejoinPruefer.tokenHash(token, salz),
            deviceUid = gesuch.deviceUid,
        )
        return Beitrittsantwort.Angenommen(participantUid, platz, token)
    }

    fun neuerToken(): String = tokenAus(zufall)

    companion object {

        /** 16 Byte = 128 Bit, die Untergrenze aus TDD 9.1. */
        const val TOKEN_BYTES = 16

        private val QUELLE = SecureRandom()

        /**
         * Zufall aus [SecureRandom].
         *
         * Nicht `Random`: Der Wiedereinstiegs-Token ist das einzige, was einen
         * Sitzplatz schützt. Ein vorhersagbarer Wert wäre kein Geheimnis,
         * sondern eine Formalität.
         */
        fun zufallsbytes(anzahl: Int): ByteArray =
            ByteArray(anzahl).also { QUELLE.nextBytes(it) }

        /**
         * Zieht einen Token aus einer Byte-Quelle.
         *
         * Hexadezimal, damit er als Text durch das Protokoll geht und die
         * Mindestlänge aus TDD 9.1 nicht an einer Kodierung scheitert: 16
         * zufällige Bytes werden zu 32 Zeichen und damit zu 32 Bytes UTF-8 —
         * doppelt so viel, wie `RejoinPruefer` verlangt.
         */
        fun tokenAus(zufall: () -> ByteArray): String {
            val roh = zufall()
            require(roh.size >= TOKEN_BYTES) {
                "Ein Wiedereinstiegs-Token braucht mindestens $TOKEN_BYTES zufällige Bytes (TDD 9.1)."
            }
            return roh.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * T-110 — die Wiederzulassung durch Menschen (TDD 9.6).
 *
 * ## Der Satz, der die Bauart bestimmt
 *
 * TDD 9.6: *„Der Ausweg ist eine bewusste Handlung am Host: Ein Mensch
 * bestätigt ‚Pascal möchte auf Platz 3 zurück', der Host erzeugt ein neues
 * Geheimnis und schreibt `participant_readmitted`. Diese Bestätigung darf
 * **nicht wegkonfigurierbar** sein."*
 *
 * „Nicht wegkonfigurierbar" heißt hier: Es gibt keinen Schalter, kein
 * Standardargument und keinen zweiten Weg. [Bestaetigung] ist ein
 * Pflichtparameter, und sie trägt, **wer** bestätigt hat — eine Bestätigung
 * ohne Bestätigenden wäre ein Formfehler mit Ansage.
 */
class Wiederzulassung(
    private val nachschlag: Tischnachschlag,
    private val salz: String,
    private val zufall: () -> ByteArray = { Beitrittsstelle.zufallsbytes(Beitrittsstelle.TOKEN_BYTES) },
) {

    /**
     * Eine Bestätigung durch einen Menschen am Host-Gerät.
     *
     * Ein eigener Typ und kein `Boolean`: Ein `true` lässt sich versehentlich
     * durchreichen, ein Wert mit Namen und Zeitpunkt nicht.
     */
    data class Bestaetigung(
        /** Wer bestätigt hat — im Zweifel der Gastgeber am Tisch. */
        val durch: String,
        val bei: Long,
    ) {
        init {
            require(durch.isNotBlank()) {
                "Eine Bestätigung ohne Bestätigenden ist keine (TDD 9.6). Sie darf nicht " +
                    "wegkonfigurierbar sein — auch nicht durch einen leeren Namen."
            }
        }
    }

    /**
     * Stellt ein neues Geheimnis für einen bestehenden Sitzplatz aus.
     *
     * Der alte Hash wird **ersetzt**, nicht ergänzt: Zwei gültige Geheimnisse
     * für einen Sitzplatz wären zwei Schlüssel für dieselbe Tür, und einer davon
     * liegt auf einem Gerät, das der Spieler nicht mehr hat.
     *
     * @return der neue Token im Klartext — einmalig, für das Gerät des Spielers.
     */
    fun stelleNeuAus(
        participantUid: String,
        bestaetigung: Bestaetigung,
    ): Neuausstellung {
        require(participantUid.isNotBlank()) { "Ohne Sitzplatz gibt es nichts wiederzuzulassen." }

        val token = Beitrittsstelle.tokenAus(zufall)
        nachschlag.ersetzeTokenHash(participantUid, RejoinPruefer.tokenHash(token, salz))

        return Neuausstellung(
            participantUid = participantUid,
            rejoinToken = token,
            ereignis = Ereignisentwurf(
                typ = "participant_readmitted",
                eventClass = EventClass.SESSION,
                visibility = Visibility.PUBLIC,
                actorParticipantUid = participantUid,
                payload = Payload.Leer,
                occurredAt = bestaetigung.bei,
            ),
        )
    }

    /** Das Ergebnis: ein Geheimnis für den Spieler und ein Ereignis für den Tisch. */
    data class Neuausstellung(
        val participantUid: String,
        val rejoinToken: String,
        /**
         * `participant_readmitted` (TDD 5.4, `session`, PUBLIC).
         *
         * Öffentlich, und das ist Absicht: Dass jemand ohne Nachweis wieder
         * hereingelassen wurde, ist eine Information für den ganzen Tisch.
         */
        val ereignis: Ereignisentwurf,
    ) {
        /** Bewusst ohne den Token. */
        override fun toString(): String = "Neuausstellung(participant=$participantUid)"
    }
}
