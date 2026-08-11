package de.myhornets.rise1.sitzung

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.myhornets.rise1.browser.CatalogBrowserActivity
import de.myhornets.rise1.session.Sitzungsstand
import de.myhornets.rise1.transport.Dienst

/**
 * Der Tisch — der erste Bildschirm, auf dem Rise ein Mehrspieler-Spiel ist.
 *
 * ## Was hier steht und was nicht
 *
 * Anzeige und Bedienung. **Keine Logik.** Jede Handlung geht an
 * [TischViewModel], jeder Zustand kommt als [TischViewModel.Tischansicht]
 * zurück (ADR-005). Ein `if` über einem Sitzungszustand wäre hier schon eine
 * Regel — und Regeln stehen in `:session`, wo sie ohne Gerät geprüft sind.
 *
 * ## Genau ein senkrechter Scroller
 *
 * Der ganze Bildschirm ist **eine** [LazyColumn]. Jeder Abschnitt trägt seine
 * Zeilen als `item` darin ein; deshalb sind die Abschnitte hier
 * `LazyListScope`-Erweiterungen und keine `@Composable`-Funktionen.
 *
 * **Das ist die Lehre aus einem Absturz.** Vorher lag alles in einem
 * `Column(Modifier.verticalScroll(…))`, und die Liste der gefundenen Tische war
 * eine `LazyColumn` darin. Ein senkrechter Scroller misst sein Kind mit
 * *unbegrenzter* Höhe; eine `LazyColumn` kann damit nichts anfangen — sie weiß
 * dann nicht, wie viele Zeilen sichtbar wären, und genau das ist ihr ganzer
 * Zweck. Sie wirft deshalb `IllegalStateException: Vertically scrollable
 * component was measured with an infinity maximum height constraints`, und die
 * Beitrittsansicht stürzte beim Öffnen ab.
 *
 * Die Regel, die daraus folgt und die hier eingehalten wird: **Ein senkrechter
 * Scroller je Bildschirm.** Kopf und Formular werden zu `item`-Blöcken derselben
 * Liste, statt die Liste in einen zweiten Scroller zu setzen. Eine feste Höhe
 * oder `wrapContentSize(unbounded = true)` hätte den Absturz auch beendet — und
 * dafür eine Liste erzeugt, die auf einem anderen Gerät falsch aussieht.
 *
 * ## Warum diese Ansicht der Einstieg ist
 *
 * Bis hierher startete der Katalog-Browser (`T-017`). Er bleibt vollständig
 * erhalten und ist über die Kopfzeile erreichbar — er verliert nur seinen Platz
 * im Startmenü, genau wie zuvor die Statusseite aus `T-005`. Ein Spiel beginnt
 * am Tisch, nicht in der Kartenliste.
 */
class TischActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Tischbildschirm()
            }
        }
    }

    companion object {
        /** Kennungen für den Gerätetest — er tippt, was ein Nutzer tippt. */
        const val MARKE_BEITRETEN = "tisch-beitreten"
        const val MARKE_ERSTELLEN = "tisch-erstellen"
        const val MARKE_SUCHE = "tisch-suche"
    }
}

@Composable
private fun Tischbildschirm() {
    val kontext = LocalContext.current
    val viewModel: TischViewModel = viewModel(factory = TischViewModel.fabrik(kontext))
    val ansicht by viewModel.ansicht.collectAsState()

    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }

    // Der einzige senkrechte Scroller dieses Bildschirms — siehe Klassenkommentar.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Rise 1.0 — Tisch", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = {
                    kontext.startActivity(Intent(kontext, CatalogBrowserActivity::class.java))
                }) { Text("Katalog") }
            }
        }

        if (ansicht.laeuft) {
            item { CircularProgressIndicator() }
        }

        ansicht.fehler?.let { meldung ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Das ging nicht", fontWeight = FontWeight.Bold)
                        Text(meldung)
                        TextButton(onClick = { viewModel.fehlerGesehen() }) { Text("Verstanden") }
                    }
                }
            }
        }

        when (ansicht.schritt) {
            TischViewModel.Schritt.START -> start(
                name = name,
                aufName = { name = it },
                aufErstellen = { viewModel.eroeffnePartie(name) },
                aufSuchen = { viewModel.sucheTische() },
            )

            TischViewModel.Schritt.HOST -> hostansicht(ansicht) { viewModel.beende() }

            TischViewModel.Schritt.SUCHE -> suchansicht(
                ansicht = ansicht,
                name = name,
                code = code,
                aufName = { name = it },
                aufCode = { code = it },
                aufBeitreten = { dienst -> viewModel.tritteBei(dienst, code, name) },
                aufZurueck = { viewModel.beende() },
            )

            TischViewModel.Schritt.GAST -> gastansicht(ansicht) { viewModel.beende() }
        }
    }
}

private fun LazyListScope.start(
    name: String,
    aufName: (String) -> Unit,
    aufErstellen: () -> Unit,
    aufSuchen: () -> Unit,
) {
    item {
        Text(
            "Alle Geräte müssen im selben WLAN sein. Ohne gemeinsames Netz kann keine Partie " +
                "stattfinden — dann kann der Host einen Hotspot öffnen, der braucht kein Internet.",
            fontSize = 13.sp,
        )
    }
    item {
        OutlinedTextField(
            value = name,
            onValueChange = aufName,
            label = { Text("Dein Name am Tisch") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        Button(
            onClick = aufErstellen,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TischActivity.MARKE_ERSTELLEN),
        ) { Text("Partie erstellen") }
    }
    item {
        Button(
            onClick = aufSuchen,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TischActivity.MARKE_BEITRETEN),
        ) { Text("Partie beitreten") }
    }
}

private fun LazyListScope.hostansicht(ansicht: TischViewModel.Tischansicht, aufBeenden: () -> Unit) {
    item { Text("Du bist HOST", fontSize = 18.sp, fontWeight = FontWeight.Bold) }

    item {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Tischcode", fontWeight = FontWeight.Bold)
                Text(
                    ansicht.tischcode ?: "—",
                    fontSize = 26.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "Diesen Code sagen die anderen Geräte an. Er wird nicht über das Netz " +
                        "verschickt — wer geprüft wird, darf die Prüfgrundlage nicht liefern.",
                    fontSize = 12.sp,
                )
            }
        }
    }

    item { Zeile("Partie", ansicht.stand?.matchUid) }
    item { Zeile("Port", if (ansicht.port > 0) ansicht.port.toString() else "—") }
    item { Zeile("Im Netz angekündigt", if (ansicht.angekuendigt) "ja" else "noch nicht") }

    sitzungsteil(ansicht.stand)

    item {
        Button(onClick = aufBeenden, modifier = Modifier.fillMaxWidth()) { Text("Partie beenden") }
    }
}

private fun LazyListScope.suchansicht(
    ansicht: TischViewModel.Tischansicht,
    name: String,
    code: String,
    aufName: (String) -> Unit,
    aufCode: (String) -> Unit,
    aufBeitreten: (Dienst) -> Unit,
    aufZurueck: () -> Unit,
) {
    item {
        Text(
            "Tische im lokalen Netz",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag(TischActivity.MARKE_SUCHE),
        )
    }

    item {
        OutlinedTextField(
            value = name,
            onValueChange = aufName,
            label = { Text("Dein Name am Tisch") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item {
        OutlinedTextField(
            value = code,
            onValueChange = aufCode,
            label = { Text("Tischcode vom Bildschirm des Hosts") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (ansicht.gefundene.isEmpty()) {
        item { Text("Es horcht — bisher wurde nichts gefunden.", fontSize = 13.sp) }
    }

    // Die gefundenen Tische sind Zeilen **derselben** Liste. Genau hier stand
    // vorher eine zweite `LazyColumn` — und genau daran ist die Ansicht
    // abgestürzt.
    items(ansicht.gefundene) { dienst ->
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { aufBeitreten(dienst) },
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(dienst.gegenstelle.anzeigename, fontWeight = FontWeight.Bold)
                Text(
                    "${dienst.adresse ?: "Adresse offen"}:${dienst.port}",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text("Antippen zum Beitreten", fontSize = 12.sp)
            }
        }
    }

    item { TextButton(onClick = aufZurueck) { Text("Zurück") } }
}

private fun LazyListScope.gastansicht(ansicht: TischViewModel.Tischansicht, aufBeenden: () -> Unit) {
    item { Text("Du bist GAST", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
    item { Zeile("Erwarteter Tischcode", ansicht.tischcode) }
    sitzungsteil(ansicht.stand)
    item {
        Button(onClick = aufBeenden, modifier = Modifier.fillMaxWidth()) { Text("Tisch verlassen") }
    }
}

/** Der gemeinsame Teil: Was beide Seiten von der Sitzung sehen. */
private fun LazyListScope.sitzungsteil(stand: Sitzungsstand?) {
    if (stand == null) {
        item { Text("Noch kein Sitzungszustand.", fontSize = 13.sp) }
        return
    }

    item {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Sitzung", fontWeight = FontWeight.Bold)
                Zeile("Rolle", stand.rollenname)
                Zeile("Partie", stand.matchUid)
                Zeile("Eigener Teilnehmer", stand.eigenerParticipantUid ?: "noch keiner")
                Zeile("Eigener Sitzplatz", stand.eigenerSitzplatz?.toString() ?: "—")
                Zeile("Leitung", if (stand.leitungSteht) "steht" else "weg")
                Zeile("Zustand", stand.verbindungszustand?.name ?: "—")
                stand.meldung?.let { Text(it, fontSize = 13.sp) }
            }
        }
    }

    item { Text("Gegenstellen (${stand.gegenstellen.size})", fontWeight = FontWeight.Bold) }

    if (stand.gegenstellen.isEmpty()) {
        item { Text("Noch niemand da.", fontSize = 13.sp) }
    }

    items(stand.gegenstellen) { gegen ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(gegen.anzeigename, fontWeight = FontWeight.Bold)
                Zeile("Teilnehmer", gegen.participantUid ?: "noch keiner")
                Zeile("Sitzplatz", gegen.sitzplatz?.toString() ?: "—")
                Zeile("Leitung", if (gegen.steht) "steht" else "weg")
            }
        }
    }
}

@Composable
private fun Zeile(bezeichnung: String, wert: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(bezeichnung, fontSize = 13.sp)
        Text(wert ?: "—", fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
