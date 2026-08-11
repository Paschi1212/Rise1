package de.myhornets.rise1.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T-024 — der Wächter über das Schema von `rise.db`.
 *
 * ## Was er verhindert
 *
 * Dass die **Position im Kartenstapel** in `match_participant` landet. TDD 8.4:
 * Sie ist ein Geheimnis, bekannt beim Verteiler und beim Eigentümer in
 * `own_identity.position_index`, „sonst nirgends, insbesondere nicht auf dem
 * Host und nicht in `match_participant`". Wäre sie dort, könnte der Packer
 * allein die gesamte Verteilung ableiten, und das Zwei-Parteien-Verfahren wäre
 * wirkungslos.
 *
 * Das ist einer der drei Fehler, die TDD Kapitel 0 bei der Konsolidierung
 * korrigiert hat — also einer, der in diesem Projekt **schon einmal gemacht
 * wurde**. Genau dafür ist ein Wächter da.
 *
 * ## Warum gegen den Schemaexport und nicht gegen die Entity-Klasse
 *
 * Reflexion über `MatchParticipantEntity` käme an die Spaltennamen nicht heran:
 * Rooms `@ColumnInfo` hat BINARY-Retention und ist zur Laufzeit unsichtbar. Ein
 * Feld `foo` mit `@ColumnInfo(name = "position_index")` liefe also durch.
 *
 * Der Export ist dagegen genau das, was Room später gegen die echte Datei
 * vergleicht. Er ist reiner Text — deshalb braucht dieser Test weder Android
 * noch eine Datenbank noch einen JSON-Parser und läuft in `./gradlew checkAll`
 * mit.
 *
 * ## Warum er rot werden kann
 *
 * „Eine Prüfung, die noch nie rot war, ist keine Prüfung" ([[Testing]]). Dieser
 * hier wird rot, sobald jemand `match_participant` eine Spalte mit `position`
 * im Namen gibt — probehalber einzufügen und wieder zu entfernen ist der
 * Verstoßtest, der zur Abnahme von `T-024` gehört.
 */
class SchemaWaechterTest {

    @Test
    fun derSchemaexportLiegtVor() {
        // Ohne diese Zusicherung könnten alle folgenden Tests grün sein, weil
        // sie nichts gefunden haben. Dieselbe Familie von Fehlern wie
        // `NO-SOURCE`: grün, ohne etwas geprüft zu haben.
        val export = schemaExport()
        assertTrue("Der Schemaexport ist leer: $export", export.length > 100)
    }

    @Test
    fun matchParticipantTraegtKeineVerteilposition() {
        val tabelle = tabellenAbschnitt("match_participant")
        assertFalse(
            "match_participant trägt eine Spalte mit `position` im Namen. " +
                "Die Position im Kartenstapel ist nach TDD 8.4 ein Geheimnis und " +
                "gehört nicht hierher — sie lebt beim Verteiler und in " +
                "own_identity.position_index. Siehe TDD Kapitel 0.\n\n" +
                tabelle,
            tabelle.contains("position", ignoreCase = true),
        )
    }

    @Test
    fun dieSitzordnungIstEindeutig() {
        val tabelle = tabellenAbschnitt("match_participant")
        assertTrue(
            "Der eindeutige Index auf (match_uid, seat_index) fehlt. Ohne ihn " +
                "könnten zwei Teilnehmer denselben Sitzplatz belegen, und die " +
                "Zugfolge wäre nicht mehr bestimmt (TDD 4.4).\n\n" + tabelle,
            tabelle.contains("index_match_participant_match_uid_seat_index") &&
                tabelle.contains("\"unique\": true"),
        )
    }

    @Test
    fun dieGeheimnistabellenSindInDieserVersionNichtEnthalten() {
        // TDD 3.2: `own_identity` und `active_membership` gehören in den vom
        // Betriebssystem geschützten Speicher, nicht in die normale
        // Datenbankdatei. Sie entstehen mit T-030 — und wenn, dann bewusst.
        val export = schemaExport()
        listOf("own_identity", "active_membership", "deal_envelope", "deal_key_packet")
            .forEach { verboten ->
                assertFalse(
                    "`$verboten` ist in rise.db aufgetaucht. Diese Tabelle hat " +
                        "eigene Regeln (TDD 3.2 / 8.3) und darf nicht nebenbei entstehen.",
                    export.contains("\"tableName\": \"$verboten\""),
                )
            }
    }

    @Test
    fun dieVierSynchronisationsfelderStehenAnJederTabelle() {
        // TDD 3.3. Fehlt eines, lässt sich der Datensatz nicht synchronisieren
        // und nicht weich löschen — und das fiele erst in E07 auf.
        listOf("device", "player", "match", "match_participant").forEach { name ->
            val tabelle = tabellenAbschnitt(name)
            listOf("created_at", "updated_at", "deleted_at", "origin_device_uid").forEach { feld ->
                assertTrue("`$name` fehlt das Feld `$feld` (TDD 3.3).", tabelle.contains("\"$feld\""))
            }
        }
    }

    // ── T-025: das Event-Log und seine Projektionen ──────────────────────────

    @Test
    fun matchEventTraegtKeinDeletedAt() {
        // TDD 5.1: append-only. Ein `deleted_at` wäre ein weiches Löschen —
        // also genau das, was der Grundsatz ausschließt. Dass die anderen
        // Tabellen es tragen (TDD 3.3), macht es hier besonders naheliegend,
        // es „der Vollständigkeit halber" mitzunehmen. Deshalb dieser Wächter.
        val tabelle = tabellenAbschnitt("match_event")
        assertFalse(
            "match_event trägt `deleted_at`. Das Log ist append-only (TDD 5.1); " +
                "ein Undo markiert (TDD 5.3), es löscht nicht — auch nicht weich.\n\n" + tabelle,
            tabelle.contains("deleted_at"),
        )
    }

    @Test
    fun derDedupSchluesselIstEindeutig() {
        // TDD 5.2: Unique auf (origin_device_uid, origin_seq). Ohne `unique`
        // wäre die Dedup-Garantie eine Absichtserklärung: Dasselbe Event nach
        // einem Reconnect zweimal angewandt, und der Spielstand ist falsch.
        val tabelle = tabellenAbschnitt("match_event")
        assertTrue(
            "Der eindeutige Index auf (origin_device_uid, origin_seq) fehlt (TDD 5.2).\n\n" + tabelle,
            tabelle.contains("index_match_event_origin_device_uid_origin_seq") &&
                tabelle.contains("\"unique\": true"),
        )
    }

    @Test
    fun derReplayIndexIstVorhanden() {
        val tabelle = tabellenAbschnitt("match_event")
        assertTrue(
            "Der Index auf (match_uid, seq) fehlt. Ohne ihn wird jeder Replay ein " +
                "vollständiger Tabellenscan (TDD 5.2).\n\n" + tabelle,
            tabelle.contains("index_match_event_match_uid_seq"),
        )
    }

    @Test
    fun beideNutzdatenfelderStehenGetrenntNebeneinander() {
        // TDD 5.2: `payload_json` nur bei PUBLIC, `payload_ciphertext` sonst.
        // Dass es **zwei** Spalten sind, ist die Voraussetzung dafür, dass die
        // Trennung überhaupt prüfbar ist — eine gemeinsame Spalte „payload"
        // würde sie unsichtbar machen.
        val tabelle = tabellenAbschnitt("match_event")
        listOf("payload_json", "payload_ciphertext", "enc_scheme", "recipient_participant_uid")
            .forEach { feld ->
                assertTrue("`match_event` fehlt das Feld `$feld` (TDD 5.2).", tabelle.contains("\"$feld\""))
            }
    }

    @Test
    fun dieDreiProjektionstabellenSindVorhanden() {
        // TDD 4.4. Fehlt eine, hätte die Faltung kein Ziel — und der Zustand
        // käme wieder aus den Zustandstabellen statt aus dem Log (TDD 5.1).
        listOf("participant_state", "participant_counter", "match_state").forEach { name ->
            assertTrue(
                "Die Projektionstabelle `$name` fehlt (TDD 4.4).",
                schemaExport().contains("\"tableName\": \"$name\""),
            )
        }
    }

    @Test
    fun matchStateTraegtDieZugzaehlung() {
        // D-003 (2026-08-10): Die Zugzählung ist Bestandteil von `match_state`.
        // Die Felder entstehen mit diesem Schema, damit die Zuglogik später
        // ohne eine weitere Migration dazukommen kann.
        val tabelle = tabellenAbschnitt("match_state")
        listOf("turn_number", "active_participant_uid", "last_applied_seq").forEach { feld ->
            assertTrue("`match_state` fehlt das Feld `$feld` (TDD 4.4 / D-003).", tabelle.contains("\"$feld\""))
        }
    }

    @Test
    fun dieProjektionenKennenIhrePartie() {
        // Bewusste Abweichung von TDD 4.4, dokumentiert in EventEntities.kt:
        // Ohne `match_uid` ließe sich die Projektion einer Partie nicht
        // verwerfen, ohne über `match_participant` zu verbinden — und genau
        // diese Abhängigkeit soll der Neuaufbau nicht haben.
        listOf("participant_state", "participant_counter").forEach { name ->
            assertTrue(
                "`$name` fehlt `match_uid`; der Neuaufbau einer einzelnen Partie wäre " +
                    "damit nur über einen Join möglich.",
                tabellenAbschnitt(name).contains("\"match_uid\""),
            )
        }
    }

    @Test
    fun dasSchemaIstAufVersionZwei() {
        // Hält fest, dass diese Prüfungen gegen den **neuen** Stand laufen.
        // Ohne sie wären sie grün, sobald jemand die Version zurückdreht und
        // damit wieder 1.json läse — dort gäbe es keine der Tabellen, und
        // `tabellenAbschnitt` würde fehlschlagen. Also eher Redundanz als
        // Lücke; sie kostet nichts und benennt die Erwartung.
        assertTrue("RISE_SCHEMA_VERSION ist $RISE_SCHEMA_VERSION, erwartet >= 2.", RISE_SCHEMA_VERSION >= 2)
    }

    // ── Zugriff auf den Export ───────────────────────────────────────────────

    private fun schemaExport(): String = gespeichert ?: ladeExport().also { gespeichert = it }

    /**
     * Der Abschnitt des Exports, der zu einer Tabelle gehört.
     *
     * Bewusst textuell statt über einen JSON-Parser: Der Wächter soll keine
     * Abhängigkeit brauchen, und der Export ist eine Datei mit stabiler Form.
     * Wird die Tabelle nicht gefunden, schlägt der Test fehl statt leer
     * durchzulaufen.
     */
    private fun tabellenAbschnitt(tabelle: String): String {
        val export = schemaExport()
        val marke = "\"tableName\": \"$tabelle\""
        val start = export.indexOf(marke)
        assertTrue("Die Tabelle `$tabelle` fehlt im Schemaexport.", start >= 0)
        val naechste = export.indexOf("\"tableName\": \"", start + marke.length)
        return if (naechste < 0) export.substring(start) else export.substring(start, naechste)
    }

    private fun ladeExport(): String {
        val relativ = "schemas/de.myhornets.rise1.store.RiseDatabase/$RISE_SCHEMA_VERSION.json"
        // Das Arbeitsverzeichnis von Unit-Tests ist je nach Aufruf das Modul-
        // oder das Wurzelverzeichnis. Statt eine Annahme zu treffen, werden die
        // in Frage kommenden Orte durchprobiert.
        val kandidaten = listOf(
            File(relativ),
            File("store/$relativ"),
            File("../store/$relativ"),
        )
        val gefunden = kandidaten.firstOrNull { it.isFile }
        assertTrue(
            "Der Schemaexport wurde nicht gefunden. Gesucht in:\n" +
                kandidaten.joinToString("\n") { "  ${it.absolutePath}" } +
                "\n\nEr entsteht beim Übersetzen von :store durch KSP.",
            gefunden != null,
        )
        return gefunden!!.readText()
    }

    private var gespeichert: String? = null
}
