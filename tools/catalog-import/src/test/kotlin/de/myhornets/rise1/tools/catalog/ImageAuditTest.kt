package de.myhornets.rise1.tools.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** T-012 — Vollständigkeitsprüfung über den ganzen Bestand. */
class ImageAuditTest {

    private val audit = ImageAudit(erwarteteAnzahl = 3)

    private fun identity(n: Int, name: String, role: String = "guardian") = Identity(
        identityUid = "TRD-2025:${n.toString().padStart(3, '0')}",
        setCode = "TRD-2025", cardNumber = n, name = name,
        slugAscii = SlugAscii.of(name), role = role, color = "blue",
        typeLine = "Identity — X", rarity = "R", textRaw = "Undercover|Unveil {3}|X.",
        flavor = null, artist = "Jemand", unveilCost = "{3}", imageAsset = null,
        sourceUri = "https://example.invalid/$n",
    )

    private fun abruf(
        i: Identity, subtype: String = "Guardian", status: Int? = 200,
        art: ImageKind.Art? = ImageKind.Art.JPEG, bytes: Int = 40_000,
        fehler: String? = null, url: String? = null,
    ) = ImageAudit.Abruf(
        identityUid = i.identityUid, cardNumber = i.cardNumber, name = i.name,
        subtype = subtype,
        url = url ?: ("${ImageSource.BASIS}/en/trd/" + ImageSource.dateiname(i.cardNumber, subtype, i.name)),
        statusCode = status, bytes = bytes, art = art, fehler = fehler,
    )

    private val drei = listOf(identity(1, "The Ætherist"), identity(2, "The Augur"), identity(3, "The Third"))

    @Test
    fun `vollstaendiger Satz wird angenommen`() {
        val e = audit.pruefe(drei, drei.map { abruf(it) })
        assertTrue(e.vollstaendig, e.render())
        assertEquals(3, e.inOrdnung.size)
        assertTrue(e.render().contains("Bilder vollständig: 3 von 3"))
    }

    @Test
    fun `fehlendes Bild wird gemeldet und aufgelistet`() {
        val abrufe = drei.map { if (it.cardNumber == 2) abruf(it, status = 404, art = null, bytes = 0) else abruf(it) }
        val e = audit.pruefe(drei, abrufe)
        assertFalse(e.vollstaendig)
        assertEquals(1, e.fehlend.size)
        assertTrue(e.render().contains("TRD-2025:002"))
        assertTrue(e.render().contains("Status 404"))
    }

    @Test
    fun `HTTP 200 mit Fehlerseite gilt nicht als Bild`() {
        // Der Kern der Prüfung: Der Status sagt Erfolg, der Inhalt widerspricht.
        val abrufe = drei.map { if (it.cardNumber == 1) abruf(it, status = 200, art = ImageKind.Art.HTML, bytes = 1200) else abruf(it) }
        val e = audit.pruefe(drei, abrufe)
        assertFalse(e.vollstaendig)
        assertEquals(listOf("TRD-2025:001"), e.fehlend.map { it.identityUid })
        assertTrue(e.render().contains("Art HTML"))
    }

    @Test
    fun `Karte ohne Abruf wird gemeldet`() {
        val e = audit.pruefe(drei, drei.drop(1).map { abruf(it) })
        assertFalse(e.vollstaendig)
        assertTrue(e.befunde.any { it is Finding.Fehlend && it.message.contains("TRD-2025:001") })
        assertTrue(e.befunde.any { it.message.contains("kein Abruf versucht") })
    }

    @Test
    fun `falscher Dateiname wird gemeldet`() {
        // Slug statt Quellname — der Fall, der bei einer Stichprobe durchginge.
        val falsch = abruf(drei[0], url = "${ImageSource.BASIS}/en/trd/001 - Guardian - the-aetherist.jpg")
        val e = audit.pruefe(drei, listOf(falsch) + drei.drop(1).map { abruf(it) })
        assertFalse(e.vollstaendig)
        assertTrue(e.befunde.any { it is Finding.Wert && it.message.contains("TRD-2025:001") })
    }

    @Test
    fun `prozentkodierte URL gilt als passend`() {
        val kodiert = abruf(drei[1], url = "${ImageSource.BASIS}/en/trd/002%20-%20Guardian%20-%20The%20Augur.jpg")
        val e = audit.pruefe(drei, listOf(abruf(drei[0]), kodiert, abruf(drei[2])))
        assertTrue(e.vollstaendig, e.render())
    }

    @Test
    fun `Netzfehler wird als Fehlschlag gefuehrt`() {
        val abrufe = drei.map { if (it.cardNumber == 3) abruf(it, status = null, art = null, bytes = 0, fehler = "IOException: timeout") else abruf(it) }
        val e = audit.pruefe(drei, abrufe)
        assertFalse(e.vollstaendig)
        assertTrue(e.render().contains("IOException"))
    }

    @Test
    fun `abweichende Anzahl Identitaeten wird gemeldet`() {
        val zwei = drei.take(2)
        val e = audit.pruefe(zwei, zwei.map { abruf(it) })
        assertFalse(e.vollstaendig)
        assertTrue(e.befunde.any { it is Finding.Anzahl })
    }

    @Test
    fun `die Liste der Fehlenden wird nicht gekuerzt`() {
        // Eine abgeschnittene Liste sieht aus wie ein kleines Problem.
        val e = audit.pruefe(drei, drei.map { abruf(it, status = 404, art = null, bytes = 0) })
        val text = e.render()
        drei.forEach { assertTrue(text.contains(it.identityUid), "${it.identityUid} fehlt in der Ausgabe") }
        assertTrue(text.contains("Der Import wird abgebrochen"))
    }
}
