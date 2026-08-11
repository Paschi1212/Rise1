package de.myhornets.rise1.transport

/**
 * T-068 / T-069 — das Dienstverzeichnis im [Attrappennetz].
 *
 * ## Warum es am selben Netz hängt
 *
 * Ein Dienstverzeichnis, das nichts mit dem Netz zu tun hat, wäre eine zweite
 * Attrappe mit eigener Wahrheit: Man könnte einen Dienst finden, der gar nicht
 * erreichbar ist, ohne dass es auffällt. Hier ist es dasselbe Netz und dieselbe
 * Uhr — was gefunden wird, ist auch verbindbar, und was unerreichbar wird,
 * verschwindet.
 *
 * Genau dieser Zusammenhang ist der Ablauf, den `T-069` bis `T-071` prüfen
 * müssen: **finden → verbinden → verifizieren.**
 *
 * ## Was sie erzeugen kann
 *
 * Registrierung: Erfolg, fehlende Berechtigung, vergebener Name.
 * Suche: Finden, Verschwinden, Scheitern, und den Fall „findet nichts".
 * Alles auf Knopf, alles ohne Zufall.
 */
class AttrappenVerzeichnis(
    private val netz: Attrappennetz,
    /** Wessen Verzeichnis das ist. Steht in Meldungen, damit Mitschnitte lesbar bleiben. */
    val eigeneUid: String,
) : Dienstverzeichnis {

    private var eigenerDienst: Dienst? = null
    private var hoerer: ((Suchereignis) -> Unit)? = null
    private val bereitsGemeldet = linkedSetOf<String>()

    private var registrierungScheitertMit: Registrierungsfehler? = null
    private var sucheScheitertMit: String? = null

    // ── Steuerung durch den Test ────────────────────────────────────────────

    /** Die nächste Registrierung scheitert. */
    fun lassRegistrierungScheitern(fehler: Registrierungsfehler) {
        registrierungScheitertMit = fehler
    }

    /** Die nächste Suche scheitert. */
    fun lassSucheScheitern(meldung: String) {
        sucheScheitertMit = meldung
    }

    /** Der zuletzt erfolgreich registrierte Dienst, oder `null`. */
    val angekuendigt: Dienst? get() = eigenerDienst

    /** Ob gerade gesucht wird. */
    val suchtGerade: Boolean get() = hoerer != null

    // ── Dienstverzeichnis ───────────────────────────────────────────────────

    override fun registriere(dienst: Dienst, rueckmeldung: (Registrierung) -> Unit) {
        netz.plane {
            val fehler = registrierungScheitertMit
            when {
                fehler != null -> {
                    registrierungScheitertMit = null
                    rueckmeldung(Registrierung.Gescheitert(fehler))
                }

                netz.istNameVergeben(dienst.gegenstelle.anzeigename, ausser = dienst.gegenstelle.geraeteUid) ->
                    rueckmeldung(
                        Registrierung.Gescheitert(
                            Registrierungsfehler.NameVergeben("${dienst.gegenstelle.anzeigename} (2)"),
                        ),
                    )

                else -> {
                    eigenerDienst = dienst
                    netz.veroeffentliche(dienst)
                    rueckmeldung(Registrierung.Erfolgreich(dienst))
                }
            }
        }
    }

    override fun beendeRegistrierung() {
        eigenerDienst?.let { netz.nimmZurueck(it.gegenstelle.geraeteUid) }
        eigenerDienst = null
    }

    override fun sucheAb(hoerer: (Suchereignis) -> Unit) {
        this.hoerer = hoerer
        bereitsGemeldet.clear()
        val fehler = sucheScheitertMit
        if (fehler != null) {
            sucheScheitertMit = null
            this.hoerer = null
            netz.plane { hoerer(Suchereignis.Gescheitert(fehler)) }
            return
        }
        netz.registriereSucher(this)
        // Was schon da ist, wird beim Beginn der Suche gemeldet — sonst fände
        // ein Client nur Hosts, die nach ihm gekommen sind.
        netz.plane { netz.veroeffentlichte().forEach { meldeGefunden(it) } }
    }

    override fun beendeSuche() {
        hoerer = null
        netz.vergiss(this)
        bereitsGemeldet.clear()
    }

    // ── Vom Netz aufgerufen ─────────────────────────────────────────────────

    internal fun meldeGefunden(dienst: Dienst) {
        val ziel = hoerer ?: return
        // Der eigene Dienst taucht in der eigenen Suche nicht auf. `NsdManager`
        // meldet ihn zwar, aber sich selbst beizutreten ergibt keinen Sinn —
        // und die Filterung an genau einer Stelle ist besser als in jedem
        // Aufrufer.
        if (dienst.gegenstelle.geraeteUid == eigeneUid) return
        bereitsGemeldet += dienst.gegenstelle.geraeteUid
        ziel(Suchereignis.Gefunden(dienst))
    }

    internal fun meldeVerschwunden(gegenstelle: Gegenstelle) {
        val ziel = hoerer ?: return
        if (gegenstelle.geraeteUid == eigeneUid) return
        // Nur melden, was vorher gefunden wurde. Ein „verschwunden" für etwas,
        // das der Client nie gesehen hat, wäre eine Meldung über nichts.
        if (!bereitsGemeldet.remove(gegenstelle.geraeteUid)) return
        ziel(Suchereignis.Verschwunden(gegenstelle))
    }
}
