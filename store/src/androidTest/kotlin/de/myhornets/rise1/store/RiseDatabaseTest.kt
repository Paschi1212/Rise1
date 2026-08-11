package de.myhornets.rise1.store

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-020 / T-023 — `rise.db` im Betrieb.
 *
 * Läuft gegen eine **im Speicher** erzeugte Datenbank: Anders als bei
 * `catalog.db` gibt es hier nichts Ausgeliefertes zu prüfen — `rise.db` entsteht
 * leer auf dem Gerät. Geprüft wird deshalb, dass das Schema die Zusagen aus
 * TDD 4.4 tatsächlich durchsetzt, nicht bloß beschreibt.
 *
 * Instrumentiert, weil Room den SQLite der Plattform braucht. Der textuelle
 * Wächter über den Schemaexport (`SchemaWaechterTest`) läuft dagegen in
 * `checkAll` mit — die beiden ergänzen sich: der eine prüft, was **nicht** da
 * ist, der andere, dass das Vorhandene greift.
 */
@RunWith(AndroidJUnit4::class)
class RiseDatabaseTest {

    private lateinit var db: RiseDatabase

    @Before
    fun oeffne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, RiseDatabase::class.java).build()
        db.deviceDao().einfuegen(geraet(GERAET))
    }

    @After
    fun schliesse() {
        db.close()
    }

    // ── Sitzordnung ──────────────────────────────────────────────────────────

    @Test
    fun dieSitzordnungKommtNachSeatIndexZurueck() {
        db.matchDao().einfuegen(partie(PARTIE))
        // Bewusst in verkehrter Reihenfolge eingefügt: Sonst wäre der Test auch
        // grün, wenn die Abfrage gar nicht sortierte.
        listOf(3, 0, 2, 1).forEach { platz ->
            db.matchParticipantDao().einfuegen(teilnehmer(PARTIE, platz, "Sitz $platz"))
        }

        val ordnung = db.matchParticipantDao().sitzordnung(PARTIE)
        assertEquals(listOf(0, 1, 2, 3), ordnung.map { it.seatIndex })
        assertEquals(listOf("Sitz 0", "Sitz 1", "Sitz 2", "Sitz 3"), ordnung.map { it.displayNameSnapshot })
    }

    @Test
    fun einSitzplatzKannNichtDoppeltVergebenWerden() {
        db.matchDao().einfuegen(partie(PARTIE))
        db.matchParticipantDao().einfuegen(teilnehmer(PARTIE, seatIndex = 2, name = "Erster"))

        assertThrows(SQLiteConstraintException::class.java) {
            db.matchParticipantDao().einfuegen(teilnehmer(PARTIE, seatIndex = 2, name = "Zweiter"))
        }
    }

    @Test
    fun derselbeSitzplatzInZweiPartienIstErlaubt() {
        // Die Eindeutigkeit gilt je Partie, nicht global — sonst könnte am
        // zweiten Abend niemand mehr auf Platz 0 sitzen.
        db.matchDao().einfuegen(partie(PARTIE))
        db.matchDao().einfuegen(partie(PARTIE_ZWEI))
        db.matchParticipantDao().einfuegen(teilnehmer(PARTIE, 0, "A"))
        db.matchParticipantDao().einfuegen(teilnehmer(PARTIE_ZWEI, 0, "B"))

        assertEquals(1, db.matchParticipantDao().anzahl(PARTIE))
        assertEquals(1, db.matchParticipantDao().anzahl(PARTIE_ZWEI))
    }

    @Test
    fun einSitzplatzIstUeberPartieUndPlatzAuffindbar() {
        db.matchDao().einfuegen(partie(PARTIE))
        db.matchParticipantDao().einfuegen(teilnehmer(PARTIE, 4, "Vierter"))

        assertEquals("Vierter", db.matchParticipantDao().aufSitzplatz(PARTIE, 4)?.displayNameSnapshot)
        assertNull(db.matchParticipantDao().aufSitzplatz(PARTIE, 5))
    }

    // ── Die Grenze: nichts weiß vorab, wer welche Rolle hat ──────────────────

    @Test
    fun einFrischerSitzplatzTraegtKeineIdentitaet() {
        // Das ist die Zusage aus TDD 3.4, als Test: „Vor dem Aufdecken sind
        // diese Felder leer — es gibt nichts zu snapshotten, weil der Host die
        // Identität nicht kennt."
        db.matchDao().einfuegen(partie(PARTIE))
        db.matchParticipantDao().einfuegen(teilnehmer(PARTIE, 0, "Neu"))

        val sitz = db.matchParticipantDao().aufSitzplatz(PARTIE, 0)!!
        assertEquals(false, sitz.isRevealed)
        assertNull(sitz.revealedIdentityUid)
        assertNull(sitz.identityNameSnapshot)
        assertNull(sitz.identityRoleSnapshot)
        assertNull(sitz.identityCommitment)
        assertNull(sitz.revealedAtSeq)
        assertTrue(db.matchParticipantDao().aufgedeckte(PARTIE).isEmpty())
    }

    // ── Gäste und Soft Delete ────────────────────────────────────────────────

    @Test
    fun einNamenloserGastHatKeinenSpieler() {
        // TDD 4.4: `player_uid` ist „null bei namenlosen Gästen".
        db.matchDao().einfuegen(partie(PARTIE))
        db.matchParticipantDao().einfuegen(teilnehmer(PARTIE, 0, "Gast am Tisch"))

        assertNull(db.matchParticipantDao().aufSitzplatz(PARTIE, 0)?.playerUid)
        assertEquals(0, db.playerDao().anzahl())
    }

    @Test
    fun einWeichGeloeschterSpielerVerschwindetAusDenAbfragen() {
        // TDD 3.3: Ein hartes DELETE ist nicht propagierbar, deshalb Soft Delete.
        val spieler = spieler(SPIELER, "Paschi")
        db.playerDao().einfuegen(spieler)
        assertEquals(1, db.playerDao().anzahl())

        db.playerDao().aktualisieren(spieler.copy(deletedAt = 42L))
        assertEquals(0, db.playerDao().anzahl())
        assertNull(db.playerDao().nachUid(SPIELER))
        assertTrue(db.playerDao().alle().isEmpty())
    }

    @Test
    fun dasEigeneGeraetIstAuffindbar() {
        assertEquals(GERAET, db.deviceDao().eigenes()?.deviceUid)
    }

    // ── Bausteine ────────────────────────────────────────────────────────────

    private fun geraet(uid: String) = DeviceEntity(
        deviceUid = uid,
        displayName = "Testgerät",
        platform = "android",
        isSelf = true,
        lastSeenAt = null,
        createdAt = 1L,
        updatedAt = 1L,
        deletedAt = null,
        originDeviceUid = uid,
    )

    private fun spieler(uid: String, name: String) = PlayerEntity(
        playerUid = uid,
        displayName = name,
        avatarRef = null,
        colorTag = null,
        isGuest = false,
        linkedDeviceUid = null,
        createdAt = 1L,
        updatedAt = 1L,
        deletedAt = null,
        originDeviceUid = GERAET,
    )

    private fun partie(uid: String) = MatchEntity(
        matchUid = uid,
        modeUid = "mode_standard",
        hostDeviceUid = GERAET,
        status = MatchStatus.SETUP,
        hostHeartbeatAt = null,
        playerCount = 0,
        catalogVersionUsed = "1",
        setCodeUsed = "TRD-2025",
        dealTranscriptDigest = null,
        moderatorMode = false,
        startedAt = null,
        endedAt = null,
        lastAppliedSeq = 0L,
        createdAt = 1L,
        updatedAt = 1L,
        deletedAt = null,
        originDeviceUid = GERAET,
    )

    private fun teilnehmer(matchUid: String, seatIndex: Int, name: String) = MatchParticipantEntity(
        participantUid = "$matchUid#$seatIndex",
        matchUid = matchUid,
        playerUid = null,
        seatIndex = seatIndex,
        displayNameSnapshot = name,
        identityCommitment = null,
        isRevealed = false,
        revealedAtSeq = null,
        revealedIdentityUid = null,
        identityNameSnapshot = null,
        identityRoleSnapshot = null,
        finalStatus = null,
        placement = null,
        rejoinTokenHash = null,
        seatState = SeatState.ACTIVE,
        lastSeenAt = null,
        createdAt = 1L,
        updatedAt = 1L,
        deletedAt = null,
        originDeviceUid = GERAET,
    )

    private companion object {
        const val GERAET = "device-test"
        const val SPIELER = "player-test"
        const val PARTIE = "match-test-1"
        const val PARTIE_ZWEI = "match-test-2"
    }
}
