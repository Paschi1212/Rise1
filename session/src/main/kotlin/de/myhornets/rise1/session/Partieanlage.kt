package de.myhornets.rise1.session

import de.myhornets.rise1.core.verifikation.Fingerabdruck
import de.myhornets.rise1.session.tls.Partiezertifikat
import java.util.UUID

/**
 * T-100 — eine Partie anlegen.
 *
 * ## Was hier entsteht
 *
 * Der **Kontext** einer Partie: ihre Kennung, ihr Status, das Salz für die
 * Wiedereinstiegs-Token und der Fingerabdruck, den die Beitretenden prüfen. Das
 * ist alles, was die Sitzungsschicht zum Anlegen beitragen kann — und alles,
 * was die späteren Schritte von ihr brauchen: `T-101` vergibt Sitzplätze in
 * diesem Kontext, `T-105` prüft gegen sein Salz, ADR-002A prüft seinen
 * Fingerabdruck.
 *
 * ## Was hier nicht entsteht
 *
 * **Kein `match_created`-Event und keine Zeile in `match`.** Beides gehört ins
 * Log beziehungsweise in die Ablage, und `:session` hat keine Kante auf
 * `:store` — dieselbe Grenze wie bei [Partienachschlag]. Geschrieben wird in
 * `:ui`, mit `EventAbbildung.oeffentlichesZustandsEvent`; die Reihenfolge steht
 * in [Partieeroeffnung].
 *
 * **Keine Spielregel.** Wie viele Plätze ein Modus hat, welche Startpunkte
 * gelten, wie verteilt wird — nichts davon steht hier. Rise simuliert keine
 * Treachery-Regeln.
 *
 * ## Der Fingerabdruck kommt aus dem Zertifikat
 *
 * Nicht aus einer Zufallszahl und nicht aus der `match_uid` allein: ADR-002A
 * — *„Wer geprüft wird, darf nicht die Prüfgrundlage liefern"* — und ADR-006
 * binden ihn an das DER des Host-Zertifikats. Deshalb nimmt [Partieanlage] ein
 * [Partiezertifikat] entgegen und rechnet daraus, statt selbst etwas zu erfinden.
 */

/** Was der Gastgeber vorgibt. */
data class Partieplan(
    val modeUid: String,
    val hostDeviceUid: String,
    /** Wie viele Sitzplätze der Tisch hat. */
    val plaetze: Int,
    val catalogVersion: String,
    val setCode: String,
    /** TDD 7.6: in Version 1 unbenutzt und deshalb standardmäßig aus. */
    val moderatorModus: Boolean = false,
) {
    init {
        require(modeUid.isNotBlank()) { "Eine Partie ohne Modus ist keine." }
        require(hostDeviceUid.isNotBlank()) { "Eine Partie ohne Host-Gerät ist keine." }
        require(catalogVersion.isNotBlank()) { "Ohne Katalogfassung ist später nicht mehr nachvollziehbar, welche Karten galten (TDD 4.4)." }
        require(setCode.isNotBlank()) { "Ohne Set-Kennung dito." }
        // Zwei ist die Untergrenze, ab der ein Tisch einer ist. Die Obergrenze
        // ist **keine Spielregel**, sondern die des Nutzlastformats: Ein
        // Schnappschuss trägt höchstens so viele Sitzplätze (ADR-007). Wie viele
        // Plätze ein Modus wirklich zulässt, entscheidet der Modus.
        require(plaetze >= 2) { "Ein Tisch mit $plaetze Plätzen ist keiner." }
        require(plaetze <= HOECHSTENS_PLAETZE) {
            "Mehr als $HOECHSTENS_PLAETZE Sitzplätze passen nicht in einen Schnappschuss (ADR-007)."
        }
    }

    companion object {
        /** Aus `Sitzungsprotokoll.MAX_SITZPLAETZE` — dieselbe Zahl, ein Ort. */
        const val HOECHSTENS_PLAETZE = Sitzungsprotokoll.MAX_SITZPLAETZE
    }
}

/**
 * Der Kontext einer angelegten Partie.
 *
 * Er wandert durch die ganze Sitzungsschicht: [Beitrittsstelle] braucht das
 * Salz, [RejoinPruefer] auch, die Oberfläche den Fingerabdruck.
 */
data class Partiekontext(
    val matchUid: String,
    val hostDeviceUid: String,
    /** Immer `setup` — eine frisch angelegte Partie ist nichts anderes (TDD 4.4). */
    val status: String,
    val plaetze: Int,
    /**
     * Das Salz dieser Partie für die Token-Hashes (TDD 9.1).
     *
     * Es ist **kein Geheimnis** im selben Sinn wie ein Token: Es verhindert,
     * dass derselbe Token in zwei Partien denselben Hash ergibt. Es steht
     * trotzdem nicht in dieser Textausgabe — es gehört zu den Hashes und nicht
     * in ein Protokoll.
     */
    val salz: String,
    /** Was die Beitretenden vergleichen (ADR-002A / ADR-006). */
    val fingerabdruck: Fingerabdruck,
    val angelegtAm: Long,
) {
    init {
        require(matchUid.isNotBlank()) { "Eine Partie ohne Kennung ist keine." }
        require(status == Partiestatus.SETUP) {
            "Eine neu angelegte Partie steht auf `${Partiestatus.SETUP}`, hier steht `$status`. " +
                "Jeder andere Status wäre eine Behauptung über Ereignisse, die es noch nicht gibt."
        }
    }

    /** Was der Gastgeber vorliest oder als QR zeigt. */
    val tischcode: String get() = fingerabdruck.lesbar

    override fun toString(): String =
        "Partiekontext(match=$matchUid, host=$hostDeviceUid, plaetze=$plaetze, code=$tischcode)"
}

/**
 * Das Ergebnis der Anlage.
 *
 * @param naechsteSchritte was der Aufrufer in `:ui` noch zu tun hat, in dieser
 *   Reihenfolge. Bewusst als Text und nicht als ausgeführte Wirkung: Diese
 *   Schicht **darf** die Ablage nicht anfassen, und eine Liste, die so tut, als
 *   hätte sie es getan, wäre schlimmer als keine.
 */
data class Partieeroeffnung(
    val kontext: Partiekontext,
    val naechsteSchritte: List<String> = SCHRITTE,
) {
    companion object {
        val SCHRITTE: List<String> = listOf(
            "Zeile in `match` anlegen (status=setup, host_device_uid, player_count)",
            "`match_created` über MatchEventLog.anhaengenLokal anhängen (PUBLIC, state)",
            "Fingerabdruck anzeigen, damit die Beitretenden ihn vergleichen können (ADR-002A)",
        )
    }
}

/**
 * Legt Partien an (T-100).
 *
 * Kennung und Salz kommen aus injizierten Quellen — wie der Zufall bei der
 * [Beitrittsstelle] und die Zeit beim [RejoinPruefer]. Die Voreinstellungen
 * sind `UUID.randomUUID` und [Beitrittsstelle.zufallsbytes], also
 * `SecureRandom`.
 */
class Partieanlage(
    private val kennung: () -> String = { UUID.randomUUID().toString() },
    private val salzquelle: () -> ByteArray = { Beitrittsstelle.zufallsbytes(SALZ_BYTES) },
    private val uhr: () -> Long = System::currentTimeMillis,
) {

    /**
     * Legt eine Partie an.
     *
     * @param zertifikat das Partie-Zertifikat aus ADR-006. Es wird **nach** der
     *   Kennung erzeugt, weil sein Fingerabdruck die `match_uid` einschließt —
     *   deshalb ist es hier eine Funktion und kein fertiger Wert.
     */
    fun lege(plan: Partieplan, zertifikat: (matchUid: String) -> Partiezertifikat): Partieeroeffnung {
        val matchUid = kennung()
        require(matchUid.isNotBlank()) { "Die Kennungsquelle hat nichts geliefert." }

        val zert = zertifikat(matchUid)
        require(zert.matchUid == matchUid) {
            "Das Zertifikat gehört zu ${zert.matchUid}, die Partie zu $matchUid. Ein Zertifikat " +
                "aus einer anderen Partie würde einen Fingerabdruck ergeben, den niemand " +
                "wiedererkennt (ADR-006)."
        }

        val salz = salzquelle()
        require(salz.size >= SALZ_BYTES) {
            "Ein Salz aus ${salz.size} Bytes ist zu kurz; nötig sind $SALZ_BYTES."
        }

        return Partieeroeffnung(
            Partiekontext(
                matchUid = matchUid,
                hostDeviceUid = plan.hostDeviceUid,
                status = Partiestatus.SETUP,
                plaetze = plan.plaetze,
                salz = salz.joinToString("") { "%02x".format(it) },
                fingerabdruck = zert.fingerabdruck(),
                angelegtAm = uhr(),
            ),
        )
    }

    /** Eine Beitrittsstelle für diese Partie — damit das Salz nicht von Hand weitergereicht wird. */
    fun beitrittsstelle(kontext: Partiekontext, nachschlag: Tischnachschlag): Beitrittsstelle =
        Beitrittsstelle(nachschlag, kontext.salz)

    companion object {
        /** 16 Byte Salz. Dieselbe Größenordnung wie der Token selbst. */
        const val SALZ_BYTES = 16
    }
}
