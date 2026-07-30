package de.myhornets.rise1.tools.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-012 — URL-Bildung und Inhaltserkennung.
 *
 * Die beiden URL-Fälle sind **am 2026-07-30 an der Quelle bestätigt**, nicht
 * ausgedacht: Karte 2 als gewöhnlicher Fall, Karte 1 als der mit `Æ`.
 */
class ImageSourceTest {

    @Test
    fun `Dateiname folgt dem bestaetigten Muster`() {
        assertEquals("002 - Guardian - The Augur.jpg", ImageSource.dateiname(2, "Guardian", "The Augur"))
    }

    @Test
    fun `Kartennummer wird dreistellig aufgefuellt`() {
        assertEquals("001 - Guardian - The Ætherist.jpg", ImageSource.dateiname(1, "Guardian", "The Ætherist"))
        assertEquals("062 - Traitor - Letzte.jpg", ImageSource.dateiname(62, "Traitor", "Letzte"))
    }

    @Test
    fun `der Quellname steht im Dateinamen, nicht der Slug`() {
        // Der Slug wäre "the-aetherist" — damit gäbe es einen 404. Und weil nur
        // wenige Karten Sonderzeichen tragen, fiele das bei einer Stichprobe
        // nicht auf. Genau deshalb steht dieser Test hier.
        val name = ImageSource.dateiname(1, "Guardian", "The Ætherist")
        assertTrue(name.contains("The Ætherist"))
        assertFalse(name.contains(SlugAscii.of("The Ætherist")))
    }

    @Test
    fun `URL enthaelt Basispfad, Sprache und Set`() {
        val uri = ImageSource.uri("en", 2, "Guardian", "The Augur")
        assertEquals("mtgtreachery.net", uri.host)
        assertEquals("https", uri.scheme)
        assertEquals("/images/cards/en/trd/002 - Guardian - The Augur.jpg", uri.path)
    }

    @Test
    fun `Leerzeichen und Sonderzeichen werden kodiert`() {
        val uri = ImageSource.uri("en", 1, "Guardian", "The Ætherist")
        val roh = uri.toASCIIString()
        assertFalse(roh.contains(' '), "Leerzeichen unkodiert: $roh")
        assertTrue(roh.contains("%20"), roh)
        assertFalse(roh.contains('Æ'), "Æ unkodiert: $roh")
    }

    @Test
    fun `Sprache wird kleingeschrieben`() {
        assertEquals("/images/cards/en/trd/002 - Guardian - X.jpg", ImageSource.uri("EN", 2, "Guardian", "X").path)
    }
}

/** T-012 — was eine Datei wirklich ist, unabhängig von Status und Endung. */
class ImageKindTest {

    private fun bytes(vararg v: Int) = ByteArray(16) { i -> if (i < v.size) v[i].toByte() else 0 }

    @Test
    fun `JPEG wird erkannt`() {
        assertEquals(ImageKind.Art.JPEG, ImageKind.of(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
    }

    @Test
    fun `PNG und GIF werden erkannt`() {
        assertEquals(ImageKind.Art.PNG, ImageKind.of(bytes(0x89, 0x50, 0x4E, 0x47)))
        assertEquals(ImageKind.Art.GIF, ImageKind.of(bytes(0x47, 0x49, 0x46, 0x38)))
    }

    @Test
    fun `WEBP braucht die Kennung an Position 8`() {
        val webp = "RIFF____WEBPVP8 ".toByteArray(Charsets.US_ASCII)
        assertEquals(ImageKind.Art.WEBP, ImageKind.of(webp))
        val riffOhneWebp = "RIFF____WAVEfmt ".toByteArray(Charsets.US_ASCII)
        assertEquals(ImageKind.Art.UNBEKANNT, ImageKind.of(riffOhneWebp))
    }

    @Test
    fun `eine Fehlerseite ist kein Bild`() {
        // Der Fall, um den es eigentlich geht: HTTP 200 mit HTML im Körper,
        // gespeichert unter .jpg. Im Verzeichnis sieht das aus wie ein Bild.
        val html = "<!DOCTYPE html><html><head><title>404</title>".toByteArray()
        assertEquals(ImageKind.Art.HTML, ImageKind.of(html))
        assertFalse(ImageKind.istBild(ImageKind.Art.HTML))
    }

    @Test
    fun `HTML wird auch mit fuehrendem Leerraum erkannt`() {
        assertEquals(ImageKind.Art.HTML, ImageKind.of("\n  <html><body>Fehler".toByteArray()))
    }

    @Test
    fun `leere und zu kurze Antworten sind unbekannt`() {
        assertEquals(ImageKind.Art.UNBEKANNT, ImageKind.of(ByteArray(0)))
        assertEquals(ImageKind.Art.UNBEKANNT, ImageKind.of(ByteArray(4)))
        assertFalse(ImageKind.istBild(ImageKind.Art.UNBEKANNT))
    }

    @Test
    fun `alle Bildarten gelten als Bild`() {
        listOf(ImageKind.Art.JPEG, ImageKind.Art.PNG, ImageKind.Art.WEBP, ImageKind.Art.GIF)
            .forEach { assertTrue(ImageKind.istBild(it), "$it sollte als Bild gelten") }
    }
}
