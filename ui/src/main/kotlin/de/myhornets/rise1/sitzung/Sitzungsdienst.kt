package de.myhornets.rise1.sitzung

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import de.myhornets.rise1.core.log.RiseLog
import de.myhornets.rise1.session.Sitzungsbausatz
import de.myhornets.rise1.session.Sitzungslaufzeit

/**
 * T-075 — der Vordergrunddienst.
 *
 * ## Der eine Satz, um den es geht
 *
 * ADR-008: *„Der Vordergrunddienst (`T-075`) hält den Sitzungsthread am Leben,
 * **nicht die Oberfläche**. Eine Activity, die verschwindet, darf keine
 * Verbindung beenden."*
 *
 * Und ADR-001: *„Ein Vordergrunddienst mit Benachrichtigung läuft, solange eine
 * Partie aktiv ist. Das ist kein Sonderfall dieser Entscheidung — jede der vier
 * Optionen hätte ihn gebraucht."*
 *
 * ## Was hier steht — und was ausdrücklich nicht
 *
 * Hier steht **nur**, was ohne Android nicht geht: Benachrichtigungskanal,
 * Vordergrundstart, Lebenszyklus-Rückrufe, Bindung. Alles, was ein Verhalten
 * hat, das man prüfen können muss, steht in [Sitzungslaufzeit] in `:session`
 * und läuft dort ohne Gerät.
 *
 * **Keine Spiellogik, keine Oberfläche, kein Zustand über den Halter hinaus.**
 * Ein Dienst, der anfängt Entscheidungen zu treffen, ist eine zweite Stelle
 * neben `Sitzungsverbindung` — und die eine Sitzung hätte dann zwei Herren.
 *
 * ## Warum `startForegroundService` und nicht Bindung
 *
 * Ein Dienst, der nur gebunden ist, endet mit dem letzten Binder. Genau das
 * wäre die Abhängigkeit von der Activity, die ADR-008 verbietet: Dreht der
 * Nutzer das Gerät, verschwindet die Activity und käme wieder — der Dienst
 * dazwischen aber nicht. Deshalb wird er **gestartet** und läuft weiter, auch
 * wenn niemand gebunden ist. Die Bindung ist nur die Leseklappe: [Anschluss]
 * reicht die laufende [Sitzungslaufzeit] heraus, mehr nicht.
 *
 * ## Warum `START_NOT_STICKY`
 *
 * Eine Sitzung ohne ihre Angaben — welche Partie, welcher Host, welcher
 * Fingerabdruck — ist keine. Ein vom System nachgestarteter Dienst hätte sie
 * nicht: Er bekäme `null` als Intent und fände einen leeren [Sitzungswerk].
 * Statt in diesem Zustand herumzustehen, endet er. Der Wiedereinstieg ist eine
 * bewusste Handlung des Nutzers, und das Verfahren dafür steht in TDD 9.3 —
 * *„Wiedereinstieg ist Authentifizierung, nicht Wiedererkennung."*
 *
 * ## Der Dienstart `dataSync`
 *
 * Ab Android 14 verlangt jeder Vordergrunddienst einen Typ. Gewählt ist
 * `dataSync`: Was hier läuft, gleicht ein Event-Log zwischen Geräten ab —
 * `Sitzungsprotokoll`, `Aufholung.Delta`, der Schnappschuss aus TDD 9.5.
 *
 * **Bekannte Grenze:** Ab Android 15 sind `dataSync`-Dienste auf sechs Stunden
 * je 24 Stunden begrenzt. Eine Partie dauert nach ADR-001 ein bis drei Stunden,
 * liegt also darunter — mehrere lange Partien an einem Tag aber nicht. Die
 * Alternative wäre `connectedDevice` ohne diese Grenze; sie verlangt zusätzlich
 * eine der Netz- oder Funkberechtigungen, die diese App heute nicht braucht.
 * Eine Berechtigung zu erklären, die nur einen Dienstart freischaltet, wäre der
 * schlechtere Tausch. Wenn die Grenze je zuschlägt, ist das eine Entscheidung
 * und keine Nebenbei-Änderung.
 */
class Sitzungsdienst : Service() {

    @Volatile
    private var laufzeit: Sitzungslaufzeit? = null

    /**
     * Die Leseklappe für eine Activity.
     *
     * Sie reicht die Laufzeit heraus, **nicht** die `Sitzungsverbindung`. Wer
     * etwas von der Sitzung will, geht durch `aufSitzungsthread` — die Regel
     * aus ADR-008 endet nicht an der Prozessgrenze eines Binders.
     */
    inner class Anschluss : Binder() {
        val laufzeit: Sitzungslaufzeit? get() = this@Sitzungsdienst.laufzeit
    }

    override fun onCreate() {
        super.onCreate()
        legeKanalAn()
    }

    /**
     * Start und **erneuter** Start.
     *
     * Beides landet hier, und der Unterschied ist der ganze Punkt von T-075:
     * Der zweite Aufruf baut **nichts** Neues. [Sitzungslaufzeit.starte] sagt
     * das mit `false`; hier wird nur noch nichts weiter getan. Eine Activity,
     * die nach jeder Drehung `starte` ruft, vervielfacht deshalb keinen
     * Sitzungsthread.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Zuerst und ohne Bedingung: Android räumt zwischen
        // `startForegroundService` und `startForeground` nur wenige Sekunden
        // ein, und ein Fehlschlag danach ist ein Absturz, kein Fehlerbild.
        starteImVordergrund()

        val vorhanden = laufzeit
        if (vorhanden != null && vorhanden.laeuft) {
            RiseLog.i(MARKE, "Erneuter Start — die Sitzung läuft bereits, es wird nichts gebaut.")
            return START_NOT_STICKY
        }

        val bausatz = Sitzungswerk.bausatz
        if (bausatz == null) {
            // Kein Bausatz heißt: Es gibt nichts zu halten. Ein Dienst, der das
            // aushält und weiterläuft, wäre eine Benachrichtigung ohne Sitzung.
            RiseLog.w(MARKE, "Ohne Bausatz gibt es keine Sitzung — der Dienst endet.")
            hoereAuf()
            return START_NOT_STICKY
        }

        val neu = Sitzungslaufzeit(bausatz)
        try {
            neu.starte()
        } catch (fehler: Throwable) {
            // `starte` hat bereits vollständig abgeräumt. Hier bleibt nur, den
            // Dienst nicht als leere Hülle stehen zu lassen.
            RiseLog.e(MARKE, "Die Sitzung ließ sich nicht aufbauen — der Dienst endet.", fehler)
            hoereAuf()
            return START_NOT_STICKY
        }
        laufzeit = neu
        Sitzungswerk.merke(neu)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = Anschluss()

    /**
     * Die Activity hat losgelassen.
     *
     * Und hier passiert **nichts** — das ist die Umsetzung des Satzes aus
     * ADR-008. `false` heißt außerdem: kein `onRebind`, eine erneute Bindung
     * ruft wieder [onBind]. Es gibt keinen Zustand, den ein Wiederanbinden
     * wiederherstellen müsste.
     */
    override fun onUnbind(intent: Intent?): Boolean = false

    /**
     * Das Ende des Dienstes ist das Ende der Sitzung — und nur dieses Ende.
     *
     * [Sitzungslaufzeit.beende] fährt Transport und Sitzungsthread in der
     * Reihenfolge aus ADR-008 herunter. Danach ist die Laufzeit unbrauchbar;
     * ein neuer Dienst baut eine neue.
     */
    override fun onDestroy() {
        laufzeit?.beende()
        laufzeit = null
        Sitzungswerk.merke(null)
        super.onDestroy()
    }

    // ── Android-Mechanik ────────────────────────────────────────────────────

    private fun starteImVordergrund() {
        startForeground(BENACHRICHTIGUNG_ID, baueBenachrichtigung(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun hoereAuf() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun legeKanalAn() {
        val kanal = NotificationChannel(KANAL, "Laufende Partie", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Zeigt an, dass Rise die Verbindung zum Tisch hält."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(kanal)
    }

    /**
     * Die Benachrichtigung.
     *
     * **Ein Platzhalter, und er steht hier als solcher.** `:ui` hat bis heute
     * keine Ressourcen — kein Symbol, keine Zeichenketten —, deshalb ein
     * Plattform-Symbol und deutsche Literale wie in `StatusActivity`. Mit der
     * Tischansicht aus `T-140` bekommt sie ein eigenes Symbol, einen Text mit
     * dem Partienamen und einen Tipp, der zurück an den Tisch führt.
     *
     * `setOngoing`: Sie lässt sich nicht wegwischen, solange die Partie läuft.
     * Das ist keine Bevormundung, sondern die Zusage des Vordergrunddienstes —
     * wer sie sieht, weiß, dass die Leitung steht.
     */
    private fun baueBenachrichtigung(): Notification =
        Notification.Builder(this, KANAL)
            .setContentTitle("Rise 1.0 — Partie läuft")
            .setContentText("Die Verbindung zum Tisch bleibt bestehen.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()

    companion object {

        private const val MARKE = "Sitzungsdienst"

        const val KANAL = "rise1-partie"
        const val BENACHRICHTIGUNG_ID = 1

        /**
         * Startet den Dienst — oder lässt einen laufenden in Ruhe.
         *
         * Der [bausatz] wird **vor** dem Start hinterlegt: `onStartCommand`
         * kann jederzeit kommen, auch bevor der Aufrufer die nächste Zeile
         * erreicht hat.
         */
        fun starte(context: Context, bausatz: Sitzungsbausatz) {
            Sitzungswerk.ruesteAus(bausatz)
            context.startForegroundService(Intent(context, Sitzungsdienst::class.java))
        }

        /** Beendet den Dienst und damit die Sitzung. */
        fun stoppe(context: Context) {
            context.stopService(Intent(context, Sitzungsdienst::class.java))
            Sitzungswerk.raeumeAb()
        }
    }
}

/**
 * Woher der Dienst seinen [Sitzungsbausatz] bekommt.
 *
 * ## Warum überhaupt eine Ablage
 *
 * Ein `Service` wird vom System erzeugt und nimmt keine Konstruktorwerte. Es
 * bleiben zwei Wege: die Angaben durch den `Intent` reichen oder sie
 * hinterlegen. Der Intent-Weg ist der richtige — **sobald es Angaben gibt**.
 * Heute gibt es sie nicht: Welche Partie, welcher Host, welcher Fingerabdruck,
 * das entsteht erst im Beitritt (`T-101`) und in der Dienstsuche (`T-069`,
 * verdrahtet in `T-076`).
 *
 * Diese Ablage ist deshalb **befristet**. Mit `T-101` trägt der Intent
 * `match_uid`, Adresse und Fingerabdruck, der Dienst baut den Bausatz selbst,
 * und dieses Objekt verschwindet. Bis dahin steht es hier — sichtbar, mit
 * genau einem Feld und einem Kommentar, der sein Ablaufdatum nennt.
 *
 * ## Was es nicht ist
 *
 * **Kein Zustand der Sitzung.** Hier liegt nur die Bauanleitung. Was läuft,
 * weiß allein die [Sitzungslaufzeit] im Dienst — sonst gäbe es zwei Stellen,
 * die „die Sitzung läuft" behaupten könnten, und irgendwann behaupteten sie
 * Verschiedenes.
 */
object Sitzungswerk {

    @Volatile
    var bausatz: Sitzungsbausatz? = null
        private set

    /**
     * Die laufende Sitzung — vom Dienst hinterlegt, von der Oberfläche gelesen.
     *
     * Damit kommt ein `ViewModel` an den Stand, ohne sich an den Dienst zu
     * binden. Der **Besitzer bleibt der Dienst**: Er baut sie, er beendet sie,
     * und er trägt sie hier aus, wenn er endet. Wer sie hier liest, liest ein
     * Abbild und niemals einen Besitzanspruch.
     *
     * Befristet wie [bausatz]: Mit `T-101` trägt der Intent die Angaben, und
     * eine Bindung ist dann der sauberere Weg.
     */
    @Volatile
    var laufzeit: Sitzungslaufzeit? = null
        private set

    fun ruesteAus(bausatz: Sitzungsbausatz) {
        this.bausatz = bausatz
    }

    fun merke(laufzeit: Sitzungslaufzeit?) {
        this.laufzeit = laufzeit
    }

    fun raeumeAb() {
        bausatz = null
        laufzeit = null
    }
}
