package de.myhornets.rise1.tools.catalog

import java.net.URI

/**
 * Bildet die Bild-URL einer Identität. T-012.
 *
 * **Das Muster ist am 2026-07-30 an der Quelle bestätigt worden**, nicht
 * abgeleitet geblieben:
 *
 * ```
 * https://mtgtreachery.net/images/cards/en/trd/002 - Guardian - The Augur.jpg
 * https://mtgtreachery.net/images/cards/en/trd/001 - Guardian - The Ætherist.jpg
 * ```
 *
 * Daraus: `images/cards/{lang}/trd/{NNN} - {Subtype} - {Name}.jpg`, Nummer
 * dreistellig aufgefüllt, Leerzeichen und Bindestriche als Literale.
 *
 * **Der entscheidende Punkt:** Der Dateiname trägt den **Quellnamen wörtlich**,
 * einschließlich `Æ` — nicht den ASCII-Slug. Das Asset in der App heißt
 * dagegen nach `slug_ascii`. Zwei Namensschemata, und genau dafür gibt es den
 * Slug. Wer hier den Slug einsetzt, bekommt für jede Karte mit Sonderzeichen
 * einen 404 — und das sind nicht viele, weshalb es beim Stichprobentest nicht
 * auffiele.
 *
 * Die Rolle im Pfad ist die Quellschreibweise mit großem Anfangsbuchstaben
 * (`Guardian`), nicht der Kleinbuchstabenwert aus `identity.role`.
 */
public object ImageSource {

    public const val BASIS: String = "https://mtgtreachery.net/images/cards"

    /**
     * Der unkodierte Pfadanteil — so, wie er im Quelltext der Seite steht.
     * Für die Anzeige in Meldungen; für die Anfrage siehe [uri].
     */
    public fun dateiname(cardNumber: Int, subtype: String, name: String): String =
        "${cardNumber.toString().padStart(3, '0')} - $subtype - $name.jpg"

    /**
     * Die anzufragende URL. Kodiert wird über [URI], nicht von Hand: Der Pfad
     * enthält Leerzeichen und je nach Karte `Æ`, und eine selbstgebaute
     * Ersetzungstabelle wäre genau die Sorte Detail, die man einmal übersieht.
     */
    public fun uri(lang: String, cardNumber: Int, subtype: String, name: String): URI =
        URI(
            "https", "mtgtreachery.net",
            "/images/cards/${lang.lowercase()}/trd/${dateiname(cardNumber, subtype, name)}",
            null,
        )
}

/**
 * Erkennt am Inhalt, **was** eine Datei ist. T-012.
 *
 * Warum nicht am HTTP-Status: Ein Server kann `200 OK` mit einer
 * Fehlerseite antworten, und eine als `.jpg` gespeicherte HTML-Seite sieht im
 * Verzeichnis aus wie ein Bild. Geprüft wird deshalb der Anfang der Datei.
 */
public object ImageKind {

    public enum class Art { JPEG, PNG, WEBP, GIF, HTML, UNBEKANNT }

    public fun of(bytes: ByteArray): Art = when {
        bytes.size < 12 -> Art.UNBEKANNT
        bytes.beginntMit(0xFF, 0xD8, 0xFF) -> Art.JPEG
        bytes.beginntMit(0x89, 0x50, 0x4E, 0x47) -> Art.PNG
        bytes.beginntMit(0x47, 0x49, 0x46, 0x38) -> Art.GIF
        bytes.beginntMit(0x52, 0x49, 0x46, 0x46) &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> Art.WEBP
        istHtml(bytes) -> Art.HTML
        else -> Art.UNBEKANNT
    }

    public fun istBild(art: Art): Boolean =
        art == Art.JPEG || art == Art.PNG || art == Art.WEBP || art == Art.GIF

    private fun istHtml(bytes: ByteArray): Boolean {
        val anfang = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1)
            .trimStart('﻿', ' ', '\n', '\r', '\t')
            .lowercase()
        return anfang.startsWith("<!doctype") || anfang.startsWith("<html") || anfang.startsWith("<?xml")
    }

    private fun ByteArray.beginntMit(vararg werte: Int): Boolean {
        if (size < werte.size) return false
        return werte.withIndex().all { (i, w) -> this[i] == w.toByte() }
    }
}
