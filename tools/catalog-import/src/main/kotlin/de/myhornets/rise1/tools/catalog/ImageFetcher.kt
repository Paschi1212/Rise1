package de.myhornets.rise1.tools.catalog

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Holt die Kartenbilder. T-012.
 *
 * **Ausdrücklich aufzurufen, niemals Teil des Builds.** Dieselbe Regel wie bei
 * der Quelldatei aus `T-010`: Der Build bleibt offline und reproduzierbar. Der
 * Bezug ist ein bewusster Schritt, sein Ergebnis wird versioniert.
 *
 * **Ohne neue Abhängigkeit** — `java.net.http` gehört zum JDK. Das Werkzeug hat
 * damit weiterhin genau ein Fremdartefakt (Gson).
 *
 * Der `User-Agent` ist nicht Zierde: Die Quelle antwortet ohne browsertypischen
 * Header mit HTTP 403. Bei den Bildern ist damit dasselbe zu erwarten wie bei
 * der JSON-Datei.
 */
public class ImageFetcher(
    private val lang: String = "en",
    private val userAgent: String = "Mozilla/5.0 (Rise1 catalog-import; einmaliger Bezug)",
    private val pauseMillis: Long = 250,
) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Ruft **jede** übergebene Identität ab — auch nach Fehlern. Ein Abbruch
     * beim ersten 404 würde die Liste der fehlenden Assets unvollständig
     * machen, und genau die ist der Zweck des Laufs.
     */
    public fun holeAlle(
        identitaeten: List<Identity>,
        subtypeVon: (Identity) -> String,
        speichere: (Identity, ByteArray) -> Unit,
        fortschritt: (Int, Int) -> Unit = { _, _ -> },
    ): List<ImageAudit.Abruf> = identitaeten.mapIndexed { i, identity ->
        val subtype = subtypeVon(identity)
        val uri = ImageSource.uri(lang, identity.cardNumber, subtype, identity.name)
        fortschritt(i + 1, identitaeten.size)

        val abruf = try {
            val antwort = client.send(
                HttpRequest.newBuilder(uri)
                    .header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(30))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            val koerper = antwort.body() ?: ByteArray(0)
            val art = ImageKind.of(koerper)
            if (antwort.statusCode() == 200 && ImageKind.istBild(art)) {
                speichere(identity, koerper)
            }
            ImageAudit.Abruf(
                identityUid = identity.identityUid,
                cardNumber = identity.cardNumber,
                name = identity.name,
                subtype = subtype,
                url = uri.toString(),
                statusCode = antwort.statusCode(),
                bytes = koerper.size,
                art = art,
            )
        } catch (e: Exception) {
            ImageAudit.Abruf(
                identityUid = identity.identityUid,
                cardNumber = identity.cardNumber,
                name = identity.name,
                subtype = subtype,
                url = uri.toString(),
                statusCode = null,
                bytes = 0,
                art = null,
                fehler = "${e::class.simpleName}: ${e.message}",
            )
        }

        // Höflichkeitspause. Die Quelle ist ein Fan-Projekt ohne CDN; 62 Abrufe
        // im Sekundentakt sind zumutbar, 62 gleichzeitig nicht.
        if (pauseMillis > 0) Thread.sleep(pauseMillis)
        abruf
    }
}
