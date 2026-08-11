package de.myhornets.rise1.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.myhornets.rise1.StatusActivity
import de.myhornets.rise1.catalog.CatalogMetaEntity
import de.myhornets.rise1.catalog.CatalogRole
import de.myhornets.rise1.catalog.IdentityEntity
import de.myhornets.rise1.catalog.RulesTextParser
import de.myhornets.rise1.sitzung.TischActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// T-017 — Katalog-Browser. Die erste echte Oberfläche des Projekts.
//
// Was diese Ansicht tut: 62 Identitäten zeigen, nach Rolle filtern, suchen,
// Bild und Regeltext lesbar machen.
//
// Gefiltert wird ausschließlich nach der Rolle. Ein Filter nach `Undercover`
// und `Unveil` war kurzzeitig vorhanden und ist am 2026-07-30 wieder entfernt
// worden: Das sind keine primären Kartenmerkmale, sondern abgeleitete
// Spielbegriffe, und der Katalog filtert nach dem, was die Karte beschreibt.
// Die Marken bleiben als Angabe in der Einzelansicht und im Datenbestand.
//
// Was sie ausdrücklich nicht tut: irgendetwas über das Spiel aussagen. Es gibt
// keinen Hinweis, ob eine Identität aufgedeckt werden darf, keine Rechnung über
// Kosten, keine Wertung. Die Karte wird gezeigt, nicht gedeutet — dieselbe
// Grenze wie im Katalog und in der Datenbank.
//
// Diese Activity ist seit T-017 der Einstiegspunkt der App. Die Statusseite aus
// T-005 bleibt erhalten und ist über die Kopfzeile erreichbar; sie verschwindet
// mit T-140. Umkehrbar durch genau einen Block im Manifest.

class CatalogBrowserActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    KatalogApp()
                }
            }
        }
    }
}

// ── Zustand ──────────────────────────────────────────────────────────────────

internal data class Filter(
    val rolle: String? = null,
    val suchtext: String = "",
)

private data class Startstand(
    val gesamt: Int,
    val herkunft: CatalogMetaEntity?,
    val bilderVorhanden: Boolean,
)

private val ROLLEN = listOf(
    CatalogRole.LEADER to "Leader",
    CatalogRole.GUARDIAN to "Guardian",
    CatalogRole.ASSASSIN to "Assassin",
    CatalogRole.TRAITOR to "Traitor",
)

// ── Oberfläche ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KatalogApp() {
    val context = LocalContext.current

    var start by remember { mutableStateOf<Startstand?>(null) }
    var fehler by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(Filter()) }
    var treffer by remember { mutableStateOf<List<IdentityEntity>>(emptyList()) }
    var gewaehlt by remember { mutableStateOf<IdentityEntity?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                val dao = KatalogZugriff.dao(context)
                val alle = dao.alleIdentitaeten()
                Startstand(
                    gesamt = alle.size,
                    herkunft = dao.herkunft(),
                    bilderVorhanden = alle.firstOrNull()?.imageAsset
                        ?.let { Kartenbilder.lade(context, it) != null } ?: false,
                )
            }
        }.onSuccess { start = it }.onFailure { fehler = it.toString() }
    }

    LaunchedEffect(filter, start) {
        if (start == null) return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) { filtere(context, filter) }
        }.onSuccess { treffer = it }.onFailure { fehler = it.toString() }
    }

    BackHandler(enabled = gewaehlt != null) { gewaehlt = null }

    val ausgewaehlt = gewaehlt
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ausgewaehlt?.name ?: "Katalog")
                        val unterzeile = when {
                            ausgewaehlt != null -> ausgewaehlt.typeLine
                            start != null -> "${treffer.size} von ${start?.gesamt} Identitäten"
                            else -> "wird geladen"
                        }
                        Text(unterzeile, style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    if (ausgewaehlt != null) {
                        TextButton(onClick = { gewaehlt = null }) { Text("Zurück") }
                    } else {
                        // ADR-004 — befristeter Einstieg in den Prototyp-Modus.
                        // Verschwindet mit T-029/T-030.
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(context, TischActivity::class.java),
                                )
                            },
                        ) { Text("Partie") }
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(context, StatusActivity::class.java))
                            },
                        ) { Text("Status") }
                    }
                },
            )
        },
    ) { rand ->
        Box(Modifier.padding(rand).fillMaxSize()) {
            when {
                fehler != null -> Fehlerflaeche(fehler!!)
                start == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                ausgewaehlt != null -> IdentitaetDetail(ausgewaehlt)
                else -> Trefferliste(
                    start = start!!,
                    filter = filter,
                    treffer = treffer,
                    aufFilter = { filter = it },
                    aufAuswahl = { gewaehlt = it },
                )
            }
        }
    }
}

/**
 * Setzt Rollenfilter und Suche zusammen.
 *
 * Beide kommen aus dem DAO — die Zusammenführung ist Schnittmenge, keine Regel.
 * Bei 62 Zeilen wäre eine Filterung im Speicher ohnehin sofort da; der Grund,
 * trotzdem über das DAO zu gehen, ist ein anderer: Damit laufen `nachRolle` und
 * `suche` aus T-014 in der App und nicht nur im Test.
 */
private fun filtere(context: Context, filter: Filter): List<IdentityEntity> {
    val dao = KatalogZugriff.dao(context)
    var liste = if (filter.rolle != null) dao.nachRolle(filter.rolle) else dao.alleIdentitaeten()
    if (filter.suchtext.isNotBlank()) {
        val gesucht = dao.suche(filter.suchtext.trim()).map { it.identityUid }.toSet()
        liste = liste.filter { it.identityUid in gesucht }
    }
    return liste
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Trefferliste(
    start: Startstand,
    filter: Filter,
    treffer: List<IdentityEntity>,
    aufFilter: (Filter) -> Unit,
    aufAuswahl: (IdentityEntity) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = filter.suchtext,
                    onValueChange = { aufFilter(filter.copy(suchtext = it)) },
                    label = { Text("Suche in Name und Regeltext") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter.rolle == null,
                    onClick = { aufFilter(filter.copy(rolle = null)) },
                    label = { Text("Alle Rollen") },
                )
                ROLLEN.forEach { (schluessel, beschriftung) ->
                    FilterChip(
                        selected = filter.rolle == schluessel,
                        onClick = {
                            aufFilter(filter.copy(rolle = if (filter.rolle == schluessel) null else schluessel))
                        },
                        label = { Text(beschriftung) },
                    )
                }
            }
        }

        if (!start.bilderVorhanden) {
            item { Hinweisflaeche() }
        }

        items(treffer, key = { it.identityUid }) { identitaet ->
            IdentitaetZeile(identitaet) { aufAuswahl(identitaet) }
        }

        if (treffer.isEmpty()) {
            item {
                Text(
                    "Kein Treffer.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        start.herkunft?.let { herkunft ->
            item {
                Text(
                    "Katalog ${herkunft.sourceSetCode} · Stand ${herkunft.importedAt.take(10)} · " +
                        "Quelle ${herkunft.sourceChecksum.take(12)}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun IdentitaetZeile(identitaet: IdentityEntity, aufKlick: () -> Unit) {
    val context = LocalContext.current
    var bild by remember(identitaet.identityUid) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(identitaet.identityUid) {
        val datei = identitaet.imageAsset ?: return@LaunchedEffect
        bild = withContext(Dispatchers.IO) { Kartenbilder.lade(context, datei) }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = aufKlick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 48.dp, height = 66.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val geladen = bild
                if (geladen != null) {
                    Image(
                        bitmap = geladen,
                        contentDescription = identitaet.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        identitaet.name.take(1),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(Modifier.padding(start = 12.dp)) {
                Text(identitaet.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${identitaet.typeLine} · ${identitaet.color}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                identitaet.unveilCost?.let { kosten ->
                    TokenZeile(
                        tokens = RulesTextParser.parseInline(kosten),
                        stil = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun Hinweisflaeche() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "Keine Kartenbilder im Paket",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Die Liste zeigt Anfangsbuchstaben statt Bildern. Der Bezug ist ein " +
                    "ausdrücklicher Schritt aus T-012: ./gradlew images im Import-Werkzeug.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Fehlerflaeche(meldung: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Der Katalog lässt sich nicht öffnen",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            "Room prüft beim Öffnen Schema-Hash und Version der ausgelieferten " +
                "catalog.db. Passt etwas nicht, wird die Datei nicht stillschweigend " +
                "ersetzt — sie wird gemeldet.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Text(
                meldung,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}
