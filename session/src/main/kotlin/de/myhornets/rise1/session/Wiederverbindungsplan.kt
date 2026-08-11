package de.myhornets.rise1.session

/**
 * T-074 — die Wartezeiten zwischen zwei Verbindungsversuchen.
 *
 * ADR-001, „Was gebaut werden muss": *„Wiederverbindung mit **ansteigenden
 * Wartezeiten**, angebunden an den Zustandsautomaten aus TDD 9.2."*
 *
 * ## Warum ansteigend
 *
 * Ein Gerät, das im Sekundentakt weiterversucht, verbraucht Akku und füllt das
 * Netz — und zwar am hartnäckigsten in genau der Lage, in der ohnehin nichts
 * geht. Ansteigende Wartezeiten machen den ersten Versuch schnell (der Host war
 * vielleicht nur kurz weg) und den zwanzigsten sparsam.
 *
 * ## Warum niemals aufgegeben wird
 *
 * [MAX_VERSUCHE] gibt es nicht. TDD 9.2 setzt die Karenzzeit standardmäßig auf
 * unbegrenzt — *„Ein Tischspiel kann pausieren, weil jemand die Pizza holt"* —
 * und TDD 9.1 ist noch deutlicher: *„Nur explizite Ereignisse beenden eine
 * Partie."* Ein Plan, der nach n Versuchen aufhört, wäre ein Timeout mit
 * anderem Namen. Die Wartezeit wächst bis [hoechstwartezeitMillis] und bleibt
 * dann dort.
 *
 * ## Streuung
 *
 * Fällt der Host aus, versuchen **alle** Geräte gleichzeitig neu — und dann
 * wieder gleichzeitig, im selben Takt. Deshalb kann eine Streuung
 * hinzugegeben werden.
 *
 * Sie ist **standardmäßig aus**, und das ist Absicht: Ein Zufall im Standardweg
 * machte jeden Test darüber unzuverlässig. Wer sie einschaltet, gibt eine
 * Funktion herein — im Betrieb einen Zufallsgenerator, im Test eine feste
 * Folge. Der Plan selbst würfelt nie.
 */
class Wiederverbindungsplan(
    /** Wartezeit vor dem ersten Wiederholungsversuch. */
    val grundwartezeitMillis: Long = 1_000,

    /** Wachstumsfaktor je Versuch. */
    val faktor: Double = 2.0,

    /** Obergrenze. Ab hier bleibt die Wartezeit stehen. */
    val hoechstwartezeitMillis: Long = 30_000,

    /**
     * Streuung in Millisekunden, abhängig vom Versuch.
     *
     * Rückgabe wird auf die berechnete Wartezeit **addiert**. Standard: keine.
     */
    val streuung: (versuch: Int) -> Long = { 0L },
) {

    init {
        require(grundwartezeitMillis > 0) { "Eine Grundwartezeit von 0 wäre keine Wartezeit." }
        require(faktor >= 1.0) { "Ein Faktor unter 1 ließe die Wartezeit schrumpfen." }
        require(hoechstwartezeitMillis >= grundwartezeitMillis) {
            "Die Obergrenze ($hoechstwartezeitMillis) liegt unter der Grundwartezeit " +
                "($grundwartezeitMillis) — dann wäre sie keine Obergrenze, sondern die einzige Zeit."
        }
    }

    /**
     * Die Wartezeit vor dem [versuch]-ten Versuch (1-basiert).
     *
     * Versuch 1 wartet [grundwartezeitMillis], Versuch 2 das Faktorfache, und
     * so weiter bis [hoechstwartezeitMillis].
     *
     * Gerechnet wird über `Math.pow` und danach **begrenzt** — nicht durch
     * wiederholtes Multiplizieren. Bei Versuch 40 liefe ein `Long` sonst über
     * und die Wartezeit wäre plötzlich negativ.
     */
    fun wartezeitFuer(versuch: Int): Long {
        require(versuch >= 1) { "Versuche werden ab 1 gezählt, nicht ab $versuch." }
        val roh = grundwartezeitMillis.toDouble() * Math.pow(faktor, (versuch - 1).toDouble())
        val begrenzt = if (roh >= hoechstwartezeitMillis.toDouble()) {
            hoechstwartezeitMillis
        } else {
            roh.toLong()
        }
        return begrenzt + streuung(versuch).coerceAtLeast(0)
    }

    /** Wann der [versuch]-te Versuch fällig ist, gerechnet ab [seit]. */
    fun faelligAb(seit: Long, versuch: Int): Long = seit + wartezeitFuer(versuch)
}
