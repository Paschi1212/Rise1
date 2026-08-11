@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.myhornets.rise1.prototyp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.myhornets.rise1.browser.Kartenbilder
import de.myhornets.rise1.browser.RegeltextAnzeige
import de.myhornets.rise1.catalog.IdentityEntity
import de.myhornets.rise1.catalog.RulesTextParser
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ADR-004 — die Oberfläche des lokalen Prototyp-Modus.
//
// Befristet, siehe PrototypSteuerung.kt. Der Hinweis oben in der App ist
// **Teil der Entscheidung** und keine Zierde: Wer den Modus benutzt, soll
// wissen, dass die Rollen auf diesem Gerät offen liegen. Eine Oberfläche, die
// den Prototyp wie das fertige Verfahren aussehen lässt, wäre die eigentliche
// Gefahr — nicht die fehlende Verschlüsselung.

class PrototypActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) { PrototypApp() }
            }
        }
    }
}

private sealed interface Ansicht {
    data object Aufbau : Ansicht
    data object Tisch : Ansicht
    data class Weitergabe(val sitz: Sitzplatz) : Ansicht
    data class Karte(val sitz: Sitzplatz, val identitaet: IdentityEntity) : Ansicht
}

@Composable
internal fun PrototypApp() {
    // S4 / ADR-005 — der Zustand wird beobachtet, nicht geladen.
    //
    // `ansicht`, `namen` und `neuerName` bleiben bewusst hier: Das ist
    // Bedieneingabe, kein Spielzustand. Sie ins ViewModel zu heben, weil es da
    // ist, wäre dieselbe Sorte Fehler wie umgekehrt.
    val viewModel: PrototypViewModel = viewModel(factory = PrototypViewModel.fabrik(LocalContext.current))
    val stand by viewModel.stand.collectAsState()
    val fehler by viewModel.fehler.collectAsState()

    var ansicht by remember { mutableStateOf<Ansicht>(Ansicht.Aufbau) }
    var namen by remember { mutableStateOf(listOf<String>()) }
    var neuerName by remember { mutableStateOf("") }

    // Der Sprung an den Tisch passiert genau einmal, sobald es Sitzplätze gibt.
    // Vorher hing er am Nachladezähler und wäre bei jedem Nachladen erneut
    // ausgelöst worden — was aus der Kartenansicht heraus zurück an den Tisch
    // gesprungen wäre.
    var tischGezeigt by remember { mutableStateOf(false) }
    LaunchedEffect(stand) {
        val jetzt = stand
        if (!tischGezeigt && jetzt != null && jetzt.sitzplaetze.isNotEmpty()) {
            ansicht = Ansicht.Tisch
            tischGezeigt = true
        }
    }

    BackHandler(enabled = ansicht is Ansicht.Weitergabe || ansicht is Ansicht.Karte) {
        ansicht = Ansicht.Tisch
    }

    val jetzigerStand = stand
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Partie — Prototyp")
                        Text(
                            "Lokal, unverschlüsselt · ADR-004",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
                actions = {
                    if (ansicht !is Ansicht.Aufbau && ansicht !is Ansicht.Tisch) {
                        TextButton(onClick = { ansicht = Ansicht.Tisch }) { Text("Zurück") }
                    }
                },
            )
        },
    ) { rand ->
        Box(Modifier.padding(rand).fillMaxSize()) {
            when {
                fehler != null -> Fehler(fehler!!) { viewModel.fehlerGesehen(); viewModel.lade() }
                jetzigerStand == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> when (val a = ansicht) {
                    is Ansicht.Aufbau -> Aufbau(
                        namen = namen,
                        neuerName = neuerName,
                        aufName = { neuerName = it },
                        aufHinzu = {
                            if (neuerName.isNotBlank()) {
                                namen = namen + neuerName.trim(); neuerName = ""
                            }
                        },
                        aufEntfernen = { i -> namen = namen.filterIndexed { j, _ -> j != i } },
                        aufTauschen = { i, j ->
                            namen = namen.toMutableList().apply {
                                val zwischen = this[i]
                                this[i] = this[j]
                                this[j] = zwischen
                            }
                        },
                        aufStart = { viewModel.neueRunde(namen) },
                    )

                    is Ansicht.Tisch -> Tisch(
                        stand = jetzigerStand,
                        // Eine aufgedeckte Karte ist öffentlich — für sie gibt
                        // es nichts weiterzureichen und nichts zu verbergen.
                        // Der Weitergabe-Zwischenschritt gilt nur für verdeckte.
                        aufAnsehen = { sitz ->
                            val offen = sitz.aufgedeckteIdentitaet
                            ansicht = if (sitz.istAufgedeckt && offen != null) {
                                Ansicht.Karte(sitz, offen)
                            } else {
                                Ansicht.Weitergabe(sitz)
                            }
                        },
                        aufAufdecken = { sitz -> viewModel.decke(sitz.participantUid) },
                        aufVerteilen = { viewModel.verteile(jetzigerStand.matchUid!!) },
                        aufZuruecksetzen = { viewModel.setzeZurueck(jetzigerStand.matchUid!!) },
                        aufNeueRunde = {
                            viewModel.verwirf(jetzigerStand.matchUid!!)
                            namen = emptyList()
                            ansicht = Ansicht.Aufbau
                        },
                        aufZugStarten = { sitz ->
                            viewModel.starteZug(jetzigerStand.matchUid!!, sitz.participantUid)
                        },
                        aufZugBeenden = { viewModel.beendeZug(jetzigerStand.matchUid!!) },
                        aufAusscheiden = { sitz ->
                            viewModel.scheideAus(jetzigerStand.matchUid!!, sitz.participantUid)
                        },
                    )

                    is Ansicht.Weitergabe -> Weitergabe(a.sitz) {
                        val karte = viewModel.karteVon(jetzigerStand.matchUid!!, a.sitz.participantUid)
                        ansicht = if (karte == null) Ansicht.Tisch else Ansicht.Karte(a.sitz, karte)
                    }

                    is Ansicht.Karte -> Kartenansicht(a.sitz, a.identitaet) { ansicht = Ansicht.Tisch }
                }
            }
        }
    }
}

// ── 1. bis 3. Runde anlegen, Spieler, Sitzordnung ───────────────────────────

@Composable
private fun Aufbau(
    namen: List<String>,
    neuerName: String,
    aufName: (String) -> Unit,
    aufHinzu: () -> Unit,
    aufEntfernen: (Int) -> Unit,
    aufTauschen: (Int, Int) -> Unit,
    aufStart: suspend () -> Unit,
) {
    var starte by remember { mutableStateOf(false) }
    LaunchedEffect(starte) { if (starte) { aufStart(); starte = false } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { Warnhinweis() }

        item {
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = neuerName,
                    onValueChange = aufName,
                    label = { Text("Spielername") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = aufHinzu, modifier = Modifier.padding(start = 8.dp)) { Text("+") }
            }
        }

        itemsIndexed(namen) { i, name ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}.", style = MaterialTheme.typography.titleMedium)
                    Text(name, Modifier.weight(1f).padding(start = 12.dp))
                    TextButton(onClick = { if (i > 0) aufTauschen(i, i - 1) }) { Text("▲") }
                    TextButton(onClick = { if (i < namen.size - 1) aufTauschen(i, i + 1) }) { Text("▼") }
                    TextButton(onClick = { aufEntfernen(i) }) { Text("✕") }
                }
            }
        }

        item {
            val genug = namen.size in UNTERSTUETZTE_SPIELERZAHLEN
            Column(Modifier.padding(top = 16.dp)) {
                Button(onClick = { starte = true }, enabled = genug, modifier = Modifier.fillMaxWidth()) {
                    Text("Runde anlegen (${namen.size} Spieler)")
                }
                if (!genug) {
                    Text(
                        "Die offizielle Verteilung ist für 4 bis 8 Spieler definiert (TDD 8.2).",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

// ── 4. bis 8. Der Tisch ─────────────────────────────────────────────────────

@Composable
private fun Tisch(
    stand: Rundenstand,
    aufAnsehen: (Sitzplatz) -> Unit,
    aufAufdecken: suspend (Sitzplatz) -> Unit,
    aufVerteilen: suspend () -> Unit,
    aufZuruecksetzen: suspend () -> Unit,
    aufNeueRunde: suspend () -> Unit,
    // S3 (D-003) — Zugzählung und Ausscheiden. Alle drei erzeugen Events; die
    // Anzeige liest ihren Zustand aus der Projektion zurück.
    aufZugStarten: suspend (Sitzplatz) -> Unit,
    aufZugBeenden: suspend () -> Unit,
    aufAusscheiden: suspend (Sitzplatz) -> Unit,
) {
    var auftrag by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
    LaunchedEffect(auftrag) { auftrag?.let { it(); auftrag = null } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item { Warnhinweis() }

        // ── S3: die Zugleiste ────────────────────────────────────────────────
        //
        // Sie zeigt, was `match_state` führt: die Anzahl der begonnenen Züge
        // und wer gerade dran ist. Rise schlägt niemanden vor und erzwingt
        // keine Reihenfolge — wer dran ist, entscheidet der Tisch.
        if (stand.verteilt) {
            item {
                Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            if (stand.zugnummer == 0) "Noch kein Zug begonnen" else "Zug ${stand.zugnummer}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            stand.amZug?.let { "am Zug: ${it.name}" } ?: "niemand am Zug",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (stand.amZug != null) {
                            OutlinedButton(
                                onClick = { auftrag = { aufZugBeenden() } },
                                modifier = Modifier.padding(top = 8.dp),
                            ) { Text("Zug beenden") }
                        }
                    }
                }
            }
        }

        if (!stand.verteilt) {
            item {
                Button(
                    onClick = { auftrag = { aufVerteilen() } },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                ) { Text("Rollen verteilen") }
            }
        }

        stand.leader?.let { (sitz, karte) ->
            item {
                Card(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        .clickable { aufAnsehen(sitz) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Leader — liegt offen", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text("${sitz.name} · ${karte.name}", style = MaterialTheme.typography.titleMedium)
                        Text(karte.typeLine, style = MaterialTheme.typography.labelMedium)
                        Text(
                            "Antippen, um die Karte zu sehen",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        items(stand.sitzplaetze, key = { it.participantUid }) { sitz ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${sitz.seatIndex + 1}.", style = MaterialTheme.typography.titleMedium)
                        Text(sitz.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                    }
                    sitz.aufgedeckteIdentitaet?.let { karte ->
                        Text(
                            "aufgedeckt: ${karte.name} · ${karte.typeLine}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (sitz.istAusgeschieden) {
                        Text(
                            "ausgeschieden",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (stand.verteilt) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Auch für aufgedeckte Sitzplätze: Sonst wäre die
                            // Karte nur als Name lesbar — und beim Leader, der
                            // automatisch aufdeckt, nie anders.
                            OutlinedButton(onClick = { aufAnsehen(sitz) }) {
                                Text(if (sitz.istAufgedeckt) "Karte zeigen" else "Ansehen")
                            }
                            if (!sitz.istAufgedeckt) {
                                OutlinedButton(onClick = { auftrag = { aufAufdecken(sitz) } }) { Text("Aufdecken") }
                            }
                        }
                        if (!sitz.istAusgeschieden) {
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (stand.amZug?.participantUid != sitz.participantUid) {
                                    TextButton(onClick = { auftrag = { aufZugStarten(sitz) } }) { Text("Ist am Zug") }
                                }
                                TextButton(onClick = { auftrag = { aufAusscheiden(sitz) } }) {
                                    Text("Scheidet aus")
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider()
                if (stand.verteilt) {
                    OutlinedButton(
                        onClick = { auftrag = { aufZuruecksetzen() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Runde zurücksetzen (neu verteilen)") }
                }
                TextButton(
                    onClick = { auftrag = { aufNeueRunde() } },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Andere Spieler — ganz neu beginnen") }
            }
        }
    }
}

// ── 5. Pass-around ──────────────────────────────────────────────────────────

@Composable
private fun Weitergabe(sitz: Sitzplatz, aufAnzeigen: suspend () -> Unit) {
    var los by remember { mutableStateOf(false) }
    LaunchedEffect(los) { if (los) aufAnzeigen() }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Gerät weitergeben an", style = MaterialTheme.typography.titleMedium)
        Text(
            sitz.name,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Text(
            "Erst antippen, wenn niemand sonst auf den Bildschirm sieht.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { los = true }, modifier = Modifier.padding(top = 24.dp)) {
            Text("Karte anzeigen")
        }
    }
}

@Composable
private fun Kartenansicht(sitz: Sitzplatz, karte: IdentityEntity, aufVerbergen: () -> Unit) {
    val context = LocalContext.current
    var bild by remember(karte.identityUid) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(karte.identityUid) {
        val datei = karte.imageAsset ?: return@LaunchedEffect
        bild = withContext(Dispatchers.IO) { Kartenbilder.lade(context, datei) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (sitz.istAufgedeckt) "${sitz.name} — aufgedeckt" else sitz.name,
            style = MaterialTheme.typography.labelLarge,
        )
        Box(
            Modifier.fillMaxWidth().aspectRatio(488f / 680f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            bild?.let {
                Image(bitmap = it, contentDescription = karte.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } ?: Text("Kein Bild im Paket")
        }
        Text(karte.name, style = MaterialTheme.typography.headlineSmall)
        Text(karte.typeLine, style = MaterialTheme.typography.labelMedium)
        RegeltextAnzeige(RulesTextParser.parse(karte.textRaw))
        Button(onClick = aufVerbergen, modifier = Modifier.fillMaxWidth()) {
            Text(if (sitz.istAufgedeckt) "Zurück zum Tisch" else "Verbergen und weitergeben")
        }
    }
}

// ── Gemeinsames ─────────────────────────────────────────────────────────────

@Composable
private fun Warnhinweis() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Prototyp — nicht geheim", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Dieses Gerät kennt alle Rollen und speichert sie unverschlüsselt. " +
                    "Zum Ausprobieren des Ablaufs, nicht für eine Partie, deren " +
                    "Geheimhaltung zählt. Das echte Verfahren kommt mit E09.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun Fehler(meldung: String, aufNeu: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Da ist etwas schiefgegangen", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text(meldung, style = MaterialTheme.typography.bodySmall)
        Button(onClick = aufNeu) { Text("Nochmal") }
    }
}
