@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package de.myhornets.rise1.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import de.myhornets.rise1.catalog.IdentityEntity
import de.myhornets.rise1.catalog.IdentityRulingEntity
import de.myhornets.rise1.catalog.RulesTextParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// T-017 — die Einzelansicht einer Identität.
//
// Sie zeigt, was auf der Karte steht, und sonst nichts. Kein Hinweis, ob
// aufgedeckt werden darf; keine Rechnung über den Unveil-Cost; keine Bewertung
// der Karte. Wer hier eine Aussage über das Spiel unterbringen möchte, hat die
// Grenze aus dem README vor sich.

private data class Beiwerk(
    val rulings: List<IdentityRulingEntity> = emptyList(),
    val schluesselwoerter: List<String> = emptyList(),
    val bild: ImageBitmap? = null,
)

@Composable
internal fun IdentitaetDetail(identitaet: IdentityEntity) {
    val context = LocalContext.current
    var beiwerk by remember(identitaet.identityUid) { mutableStateOf(Beiwerk()) }

    LaunchedEffect(identitaet.identityUid) {
        beiwerk = withContext(Dispatchers.IO) {
            val dao = KatalogZugriff.dao(context)
            Beiwerk(
                rulings = dao.rulings(identitaet.identityUid),
                schluesselwoerter = dao.schluesselwoerter(identitaet.identityUid),
                bild = identitaet.imageAsset?.let { Kartenbilder.lade(context, it) },
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Kartenbild(beiwerk.bild, identitaet.name)

        Column {
            Text(identitaet.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${identitaet.typeLine} · ${identitaet.color} · Seltenheit ${identitaet.rarity} · " +
                    "Nr. ${identitaet.cardNumber}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (beiwerk.schluesselwoerter.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                beiwerk.schluesselwoerter.forEach { wort ->
                    AssistChip(
                        onClick = {},
                        label = { Text(wort.replaceFirstChar { it.uppercase() }) },
                    )
                }
            }
        }

        Abschnitt("Unveil-Cost") {
            val kosten = identitaet.unveilCost
            if (kosten == null) {
                Text(
                    "nicht angegeben",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TokenZeile(RulesTextParser.parseInline(kosten))
            }
        }

        Abschnitt("Regeltext") {
            RegeltextAnzeige(RulesTextParser.parse(identitaet.textRaw))
        }

        if (beiwerk.rulings.isNotEmpty()) {
            Abschnitt("Rulings (${beiwerk.rulings.size})") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    beiwerk.rulings.forEach { ruling ->
                        Row {
                            Text(
                                "${ruling.ordinal}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            TokenZeile(
                                tokens = RulesTextParser.parseInline(ruling.text),
                                stil = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        Column {
            Text(
                "Illustration: ${identitaet.artist}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                identitaet.sourceUri,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Kartenbild(bild: ImageBitmap?, name: String) {
    Box(
        Modifier
            .fillMaxWidth()
            // Seitenverhältnis einer Magic-Karte.
            .aspectRatio(488f / 680f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bild != null) {
            Image(
                bitmap = bild,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "Kein Bild im Paket",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Abschnitt(titel: String, inhalt: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                titel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            inhalt()
        }
    }
}
