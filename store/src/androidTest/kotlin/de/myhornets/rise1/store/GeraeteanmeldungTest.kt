package de.myhornets.rise1.store

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Regressionstest** zu `FOREIGN KEY constraint failed (787)` beim ersten
 * echten Gerätelauf.
 *
 * ## Was schiefging
 *
 * Der Tisch-Bildschirm zog eine `device_uid` je Prozess und legte damit direkt
 * eine Partie an. `match.host_device_uid` zeigt aber auf `device.device_uid`,
 * und diese Zeile schrieb niemand. Derselbe Fremdschlüssel steht ein zweites
 * Mal im Weg: `participant_session.device_uid`.
 *
 * Der Fehler war kein Datenbankproblem, sondern eine fehlende **fachliche
 * Reihenfolge**: Gerät → Partie → Sitzplatz → Sitzung. Jeder Schritt braucht
 * den Ausgang des vorherigen.
 *
 * ## Warum die Prüfung hier steht und nicht als JVM-Test
 *
 * Weil ein Fremdschlüssel nur dann etwas prüft, wenn eine echte SQLite ihn
 * prüft. Ein Test gegen eine Attrappe hätte den Fehler nie gesehen — genau
 * deshalb ist er erst auf dem Gerät aufgefallen.
 *
 * ## Der wichtigste
 *
 * [einePartieOhneAngemeldetesGeraetScheitertMitFremdschluessel]. Er hält den
 * Fehler fest, statt nur die Reparatur zu prüfen: Ohne ihn könnte jemand die
 * Anmeldung später wieder entfernen, und alle anderen Tests blieben grün.
 */
@RunWith(AndroidJUnit4::class)
class GeraeteanmeldungTest {

    private lateinit var db: RiseDatabase
    private lateinit var anmeldung: Geraeteanmeldung

    @Before
    fun oeffne() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Bewusst ohne vorab eingefügtes Gerät: Genau das ist der Zustand einer
        // frisch installierten App.
        db = Room.inMemoryDatabaseBuilder(context, RiseDatabase::class.java).build()
        anmeldung = Geraeteanmeldung(db, uhr = { 1_000L })
    }

    @After
    fun schliesse() {
        db.close()
    }

    // ── Der Fehler ──────────────────────────────────────────────────────────

    @Test
    fun einePartieOhneAngemeldetesGeraetScheitertMitFremdschluessel() {
        // Das ist der Absturz aus der ersten APK, in einem Test festgehalten.
        assertThrows(SQLiteConstraintException::class.java) {
            db.matchDao().einfuegen(partie(hostGeraet = "d-nie-angemeldet"))
        }
    }

    @Test
    fun eineSitzungOhneAngemeldetesGeraetScheitertEbenso() {
        // Derselbe Elternsatz, zweiter Fremdschlüssel: Er hätte den Beitritt
        // eines Gasts genauso zerlegt wie zuvor die Partieanlage.
        val geraet = anmeldung.eigenes("Host")
        db.matchDao().einfuegen(partie(geraet.deviceUid))
        db.matchParticipantDao().einfuegen(teilnehmer(PARTIE, 0, "Bert"))

        assertThrows(SQLiteConstraintException::class.java) {
            Sitzungsverwaltung(db).eroeffne(
                sitzungsUid = "s-1",
                participantUid = "$PARTIE#0",
                deviceUid = "d-gast-unbekannt",
                jetzt = 1_000L,
            )
        }
    }

    // ── Die Reparatur ───────────────────────────────────────────────────────

    @Test
    fun nachDerAnmeldungGelingtDieVollstaendigeReihenfolge() {
        // Gerät → Partie → Sitzplatz → Sitzung. Genau der Weg, den der
        // Tisch-Bildschirm geht.
        val geraet = anmeldung.eigenes("Anna")

        db.matchDao().einfuegen(partie(geraet.deviceUid))
        db.matchParticipantDao().einfuegen(teilnehmer(PARTIE, 0, "Anna"))
        val abloesung = Sitzungsverwaltung(db).eroeffne(
            sitzungsUid = "s-1",
            participantUid = "$PARTIE#0",
            deviceUid = geraet.deviceUid,
            jetzt = 1_000L,
        )

        assertNotNull(db.matchDao().nachUid(PARTIE))
        assertEquals(1, db.matchParticipantDao().sitzordnung(PARTIE).size)
        assertEquals(geraet.deviceUid, abloesung.laufend.deviceUid)
    }

    @Test
    fun einGastgeraetWirdVorSeinerSitzungBekannt() {
        val host = anmeldung.eigenes("Anna")
        db.matchDao().einfuegen(partie(host.deviceUid))
        db.matchParticipantDao().einfuegen(teilnehmer(PARTIE, 1, "Bert"))

        anmeldung.merkeFremdes(
            deviceUid = "d-gast",
            anzeigename = "Berts Telefon",
            durchGeraeteUid = host.deviceUid,
        )
        Sitzungsverwaltung(db).eroeffne("s-2", "$PARTIE#1", "d-gast", 1_000L)

        val gast = db.deviceDao().nachUid("d-gast")
        assertNotNull(gast)
        assertTrue("Nur das eigene Gerät trägt is_self.", gast!!.isSelf.not())
        assertEquals(host.deviceUid, gast.originDeviceUid)
    }

    // ── Die Zusagen der Anmeldung ───────────────────────────────────────────

    @Test
    fun dasEigeneGeraetEntstehtGenauEinmal() {
        val erstes = anmeldung.eigenes("Anna")
        val zweites = anmeldung.eigenes("Anna")
        val drittes = anmeldung.eigenes("Anna")

        assertEquals(erstes.deviceUid, zweites.deviceUid)
        assertEquals(erstes.deviceUid, drittes.deviceUid)
        assertEquals(1, db.deviceDao().alle().count { it.isSelf })
    }

    @Test
    fun dieKennungUeberlebtEineUmbenennung() {
        // TDD 4.3: `device_uid` ist dauerhaft. Ein Nutzer, der sich umbenennt,
        // wird kein neues Gerät — sonst fände der Wiedereinstieg aus TDD 9.3
        // seine Sitzung nicht wieder.
        val vorher = anmeldung.eigenes("Anna")
        val nachher = anmeldung.eigenes("Anna B.")

        assertEquals(vorher.deviceUid, nachher.deviceUid)
        assertEquals("Anna B.", nachher.displayName)
        assertEquals(1, db.deviceDao().alle().size)
    }

    @Test
    fun eineZweiteAnmeldungDesselbenGastsLegtKeinZweitesGeraetAn() {
        val host = anmeldung.eigenes("Anna")
        anmeldung.merkeFremdes("d-gast", "Bert", host.deviceUid)
        anmeldung.merkeFremdes("d-gast", "Bert", host.deviceUid)

        assertEquals(2, db.deviceDao().alle().size)
    }

    @Test
    fun einGeraetOhneKennungGibtEsNicht() {
        val host = anmeldung.eigenes("Anna")

        assertThrows(IllegalArgumentException::class.java) {
            anmeldung.merkeFremdes("", "Niemand", host.deviceUid)
        }
    }

    // ── Bauhilfen ───────────────────────────────────────────────────────────

    private fun partie(hostGeraet: String) = MatchEntity(
        matchUid = PARTIE,
        modeUid = "mode_standard",
        hostDeviceUid = hostGeraet,
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
        originDeviceUid = hostGeraet,
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
        originDeviceUid = "d-egal",
    )

    private companion object {
        const val PARTIE = "m-787"
    }
}
