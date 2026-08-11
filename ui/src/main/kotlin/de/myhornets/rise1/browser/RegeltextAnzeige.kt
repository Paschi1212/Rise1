package de.myhornets.rise1.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.myhornets.rise1.catalog.RulesLine
import de.myhornets.rise1.catalog.RulesText
import de.myhornets.rise1.catalog.RulesToken

// T-017 — die Darstellung dessen, was T-016 zerlegt hat.
//
// Die Arbeitsteilung ist dieselbe wie zwischen :projection und :ui: `:catalog`
// sagt, WAS dasteht, dieses Modul sagt, WIE es aussieht. Deshalb steht hier
// keine einzige Regel über Karten, sondern nur über Schriftschnitte.
//
// Symbole erscheinen als hinterlegter Code — `{3}` als `3` auf farbigem Grund.
// Sie werden nicht gedeutet, nicht summiert und nicht in Mana-Grafiken
// übersetzt: Was `{a}` bedeutet, sagt der Erinnerungstext auf der Karte, und
// der wird mitangezeigt.

/** Der vollständige Regeltext einer Identität. */
@Composable
internal fun RegeltextAnzeige(
    text: RulesText,
    modifier: Modifier = Modifier,
    stil: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val farben = SymbolFarben.ausThema()
    Column(modifier = modifier) {
        text.lines.forEach { zeile -> RegeltextZeile(zeile, stil, farben) }
    }
}

/** Eine einzelne Zeile mit Symbolen — etwa der Unveil-Cost. */
@Composable
internal fun TokenZeile(
    tokens: List<RulesToken>,
    modifier: Modifier = Modifier,
    stil: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Text(annotiere(tokens, SymbolFarben.ausThema()), style = stil, modifier = modifier)
}

@Composable
private fun RegeltextZeile(zeile: RulesLine, stil: TextStyle, farben: SymbolFarben) {
    if (zeile.isBullet) {
        Row {
            Text("•", style = stil)
            Spacer(Modifier.width(8.dp))
            Text(annotiere(zeile.tokens, farben), style = stil)
        }
    } else {
        Text(annotiere(zeile.tokens, farben), style = stil)
    }
}

internal data class SymbolFarben(
    val symbolGrund: Color,
    val symbolVordergrund: Color,
    val erinnerung: Color,
) {
    companion object {
        @Composable
        fun ausThema(): SymbolFarben = SymbolFarben(
            symbolGrund = MaterialTheme.colorScheme.secondaryContainer,
            symbolVordergrund = MaterialTheme.colorScheme.onSecondaryContainer,
            erinnerung = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun annotiere(tokens: List<RulesToken>, farben: SymbolFarben): AnnotatedString =
    buildAnnotatedString { schreibe(tokens, farben) }

private fun AnnotatedString.Builder.schreibe(tokens: List<RulesToken>, farben: SymbolFarben) {
    tokens.forEach { token ->
        when (token) {
            is RulesToken.Text -> append(token.text)

            is RulesToken.Symbol -> withStyle(
                SpanStyle(
                    background = farben.symbolGrund,
                    color = farben.symbolVordergrund,
                    fontWeight = FontWeight.Bold,
                ),
            ) {
                // Die Leerzeichen geben dem hinterlegten Code Luft; ohne sie
                // klebt die Färbung am Nachbartext.
                append(" ${token.code} ")
            }

            is RulesToken.Reminder -> withStyle(
                SpanStyle(fontStyle = FontStyle.Italic, color = farben.erinnerung),
            ) {
                append("(")
                schreibe(token.tokens, farben)
                append(")")
            }
        }
    }
}
