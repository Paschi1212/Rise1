package de.myhornets.rise1.core.verifikation

import java.security.MessageDigest

/**
 * T-071 — der Fingerabdruck, wie [[ADR-002A Key Verification]] ihn festlegt.
 *
 * ## Die Ableitung, wörtlich
 *
 * ADR-002A, Abschnitt 3.1:
 *
 *     fp = Base32-Crockford( SHA-256( "rise1-fp-v1" ‖ match_uid ‖ participant_uid ‖ pubkey )[0..59] )
 *
 * 60 Bit, dargestellt als **zwölf Zeichen in drei Gruppen von vier** — etwa
 * `K7F2-9QXM-4TBH`.
 *
 * ## Warum diese Klasse in `:core` liegt
 *
 * Weil sie an drei Stellen gebraucht wird, die einander nicht kennen dürfen:
 * bei der Host-Verifikation im Transport (`T-071`), bei der Schlüsselzeremonie
 * vor dem Verteilen (ADR-002A) und in der Oberfläche, die beides anzeigt. Läge
 * sie im Transport, müsste `:deal` ihn kennen — und die Modulgrenzen sind nicht
 * verhandelbar.
 *
 * Sie hat **keine Abhängigkeit außer `java.security.MessageDigest`** und ist
 * damit vollständig ohne Android prüfbar. Das ist bei einer sicherheits­kritischen
 * Ableitung kein Nebeneffekt, sondern der Punkt.
 *
 * ## Crockford, nicht das übliche Base32
 *
 * ADR-002A: *„Crockford-Base32 lässt `I`, `L`, `O` und `U` weg und beseitigt
 * damit die Verwechslungen mit `1` und `0`, die beim Ablesen vom Nachbargerät
 * entstehen."* Beim Einlesen werden `I` und `L` zu `1`, `O` zu `0` — genau
 * dafür ist die Kodierung gemacht. Ein Spieler, der `KLF2` statt `K1F2` tippt,
 * soll nicht an der Zeremonie scheitern, sondern durchkommen.
 *
 * ## Was hier nicht passiert
 *
 * **Keine Abkürzung beim Vergleich.** Verglichen werden immer alle zwölf
 * Zeichen. Ein Vergleich über ein Präfix wäre die Sorte Bequemlichkeit, die
 * 60 Bit auf 20 reduziert, ohne dass es jemand merkt.
 *
 * **Kein Umgehungsweg für Tests.** Es gibt keinen Schalter, keinen
 * „Debug-Fingerabdruck", kein `equals`, das immer `true` liefert. Ein Test, der
 * die Prüfung ausschalten kann, prüft sie nicht.
 */
@ConsistentCopyVisibility
data class Fingerabdruck private constructor(
    /** Die zwölf Zeichen ohne Trenner, in Crockford-Base32. */
    val zeichen: String,
) {

    init {
        require(zeichen.length == LAENGE) { "Ein Fingerabdruck hat $LAENGE Zeichen, nicht ${zeichen.length}." }
        require(zeichen.all { it in ALPHABET }) { "Nicht-Crockford-Zeichen im Fingerabdruck: $zeichen" }
    }

    /** Zur Anzeige: drei Gruppen zu vier, etwa `K7F2-9QXM-4TBH`. */
    val lesbar: String get() = zeichen.chunked(4).joinToString("-")

    override fun toString(): String = lesbar

    /**
     * Vergleicht zwei Fingerabdrücke.
     *
     * Über [MessageDigest.isEqual], also in **konstanter Zeit**. Bei einem
     * Vergleich, den ein Angreifer beliebig oft auslösen kann, ist ein
     * frühzeitiger Abbruch beim ersten abweichenden Zeichen eine Auskunft
     * darüber, wie weit er schon gekommen ist.
     */
    fun stimmtUeberein(anderer: Fingerabdruck): Boolean =
        MessageDigest.isEqual(zeichen.toByteArray(Charsets.US_ASCII), anderer.zeichen.toByteArray(Charsets.US_ASCII))

    companion object {

        /** Zwölf Zeichen zu je fünf Bit — die 60 Bit aus ADR-002A. */
        const val LAENGE = 12

        /**
         * Crockford-Base32: ohne `I`, `L`, `O` und `U`.
         *
         * `U` fehlt bei Crockford, damit versehentlich entstehende Schimpfwörter
         * ausgeschlossen sind; `I`, `L` und `O` wegen der Verwechslung mit `1`
         * und `0`.
         */
        const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** Trennung der Verwendungszwecke — ADR-002A 3.1. */
        private const val ZWECK_TEILNEHMER = "rise1-fp-v1"

        /**
         * Trennung für den Host-Fingerabdruck (`T-071`).
         *
         * ADR-002A legt die **Form** fest, ADR-001 verlangt sie für das
         * Host-Zertifikat: *„sein Fingerabdruck wird als kurzer Code oder QR
         * angezeigt und vom beitretenden Gerät geprüft"*. Beides ist derselbe
         * Aufbau über eine andere Eingabe — mit **eigenem Präfix**, damit ein
         * Host-Fingerabdruck nie als Teilnehmer-Fingerabdruck durchgehen kann
         * und umgekehrt. Ohne diese Trennung wären zwei Zeremonien mit
         * demselben Zeichenvorrat gegeneinander austauschbar.
         */
        private const val ZWECK_HOST = "rise1-host-v1"

        /** Trennung für den Tischcode — ADR-002A 3.3. */
        private const val ZWECK_TISCH = "rise1-tisch-v1"

        /**
         * Der Fingerabdruck eines Teilnehmer-Signaturschlüssels — ADR-002A 3.1.
         *
         * Die Bindung an [matchUid] und [participantUid] verhindert, dass ein
         * Fingerabdruck aus einer früheren Partie oder von einem anderen
         * Sitzplatz wiederverwendet wird.
         */
        fun vonTeilnehmerschluessel(
            matchUid: String,
            participantUid: String,
            oeffentlicherSchluessel: ByteArray,
        ): Fingerabdruck {
            require(matchUid.isNotBlank() && participantUid.isNotBlank()) {
                "Ein Fingerabdruck ohne Partie- und Sitzplatzbindung wäre wiederverwendbar (ADR-002A 3.1)."
            }
            require(oeffentlicherSchluessel.isNotEmpty()) { "Kein Schlüssel, kein Fingerabdruck." }
            return ausBytes(
                ZWECK_TEILNEHMER.toByteArray() +
                    matchUid.toByteArray() +
                    participantUid.toByteArray() +
                    oeffentlicherSchluessel,
            )
        }

        /**
         * Der Fingerabdruck eines Host-Zertifikats — `T-071`.
         *
         * @param zertifikatDer die DER-Kodierung des Zertifikats, also genau
         *   das, was auch über die Leitung geht. Über den *öffentlichen
         *   Schlüssel allein* zu hashen wäre schwächer: Dann trüge ein anderes
         *   Zertifikat mit demselben Schlüssel denselben Fingerabdruck.
         */
        fun vonHostzertifikat(matchUid: String, zertifikatDer: ByteArray): Fingerabdruck {
            require(matchUid.isNotBlank()) {
                "Ein Host-Fingerabdruck ohne Partiebindung ließe sich in die nächste Partie mitnehmen."
            }
            require(zertifikatDer.isNotEmpty()) { "Kein Zertifikat, kein Fingerabdruck." }
            return ausBytes(ZWECK_HOST.toByteArray() + matchUid.toByteArray() + zertifikatDer)
        }

        /**
         * Der Tischcode — ADR-002A 3.3.
         *
         * *„Derselbe Fingerprint-Aufbau über die sortierte Menge **aller**
         * öffentlichen Signaturschlüssel der Partie."* Alle Geräte zeigen
         * dieselben zwölf Zeichen; ein Blick über den Tisch genügt statt n
         * Einzelvergleiche.
         *
         * Sortiert wird über die Bytes, nicht über die Sitzplätze: Der Code muss
         * auf jedem Gerät gleich herauskommen, auch wenn die Schlüssel dort in
         * anderer Reihenfolge eingetroffen sind.
         */
        fun tischcode(matchUid: String, oeffentlicheSchluessel: Collection<ByteArray>): Fingerabdruck {
            require(matchUid.isNotBlank()) { "Ein Tischcode ohne Partiebindung ist keiner." }
            require(oeffentlicheSchluessel.isNotEmpty()) { "Ein Tischcode ohne Schlüssel ist keiner." }
            val sortiert = oeffentlicheSchluessel.sortedWith(BYTE_VERGLEICH)
            var eingabe = ZWECK_TISCH.toByteArray() + matchUid.toByteArray()
            sortiert.forEach { eingabe += it }
            return ausBytes(eingabe)
        }

        /**
         * Liest einen abgetippten oder eingescannten Code ein.
         *
         * Nachsichtig bei der Form, streng beim Inhalt: Trenner und
         * Kleinschreibung sind egal, `I`/`L` werden zu `1` und `O` zu `0`
         * (Crockford). Alles andere wird abgewiesen — eine Eingabe, die nicht
         * eindeutig ist, darf nicht zu einem Fingerabdruck werden.
         */
        fun ausEingabe(text: String): Fingerabdruck {
            val bereinigt = buildString {
                text.uppercase().forEach { zeichen ->
                    when (zeichen) {
                        '-', ' ', '\t', '\n' -> Unit
                        'I', 'L' -> append('1')
                        'O' -> append('0')
                        else -> append(zeichen)
                    }
                }
            }
            require(bereinigt.length == LAENGE) {
                "Ein Fingerabdruck hat $LAENGE Zeichen; die Eingabe [$text] ergibt ${bereinigt.length}."
            }
            require(bereinigt.all { it in ALPHABET }) {
                "Die Eingabe [$text] enthält Zeichen, die es in Crockford-Base32 nicht gibt."
            }
            return Fingerabdruck(bereinigt)
        }

        /** SHA-256 über die Eingabe, davon die ersten 60 Bit als zwölf Zeichen. */
        private fun ausBytes(eingabe: ByteArray): Fingerabdruck {
            val verdauung = MessageDigest.getInstance("SHA-256").digest(eingabe)
            val zeichen = buildString {
                for (i in 0 until LAENGE) append(ALPHABET[fuenfBitAb(verdauung, i * 5)])
            }
            return Fingerabdruck(zeichen)
        }

        /**
         * Fünf Bit ab [bitVersatz], von vorn gezählt, höchstwertiges Bit zuerst.
         *
         * Von Hand statt über eine BigInteger-Umrechnung: Der Umweg über eine
         * Zahl hätte führende Nullbits verschluckt, und dann hinge das Ergebnis
         * am Zufall des ersten Bytes.
         */
        private fun fuenfBitAb(bytes: ByteArray, bitVersatz: Int): Int {
            var wert = 0
            for (i in 0 until 5) {
                val bit = bitVersatz + i
                val byte = bytes[bit / 8].toInt() and 0xFF
                val gesetzt = (byte shr (7 - bit % 8)) and 1
                wert = (wert shl 1) or gesetzt
            }
            return wert
        }

        private val BYTE_VERGLEICH = Comparator<ByteArray> { a, b ->
            val gemeinsam = minOf(a.size, b.size)
            for (i in 0 until gemeinsam) {
                val vergleich = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
                if (vergleich != 0) return@Comparator vergleich
            }
            a.size.compareTo(b.size)
        }
    }
}
