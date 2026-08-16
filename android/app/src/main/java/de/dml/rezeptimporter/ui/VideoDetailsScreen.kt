package de.dml.rezeptimporter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dml.rezeptimporter.ui.theme.ArcaneCard
import de.dml.rezeptimporter.ui.theme.ArcanePrimaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneSecondaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneTag
import de.dml.rezeptimporter.ui.theme.arcaneTextFieldColors

/**
 * Zwischenschritt beim Teilen einer Videodatei. Das Video allein reicht oft nicht — die Mengen
 * stehen meist in der Caption. Hier kann sie ergänzt werden; eine beim Link-Share geparkte
 * Caption ist bereits eingetragen.
 */
@Composable
fun VideoDetailsScreen(
    initialCaption: String,
    initialSourceUrl: String,
    captionFromPark: Boolean,
    onStart: (caption: String, sourceUrl: String) -> Unit,
    onCancel: () -> Unit,
) {
    // rememberSaveable: eine getippte Caption darf beim Drehen nicht verloren gehen.
    var caption by rememberSaveable { mutableStateOf(initialCaption) }
    var sourceUrl by rememberSaveable { mutableStateOf(initialSourceUrl) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Video erkannt",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            ArcaneTag("[VIDEO]")
        }

        ArcaneCard {
            Text(
                if (captionFromPark) {
                    "Die Caption des zuletzt geteilten Links wird mitverwendet. " +
                        "Bild, Ton und Caption gehen zusammen in die Auswertung."
                } else {
                    "Bild und Ton des Videos werden ausgewertet. Steht die Zutatenliste in der " +
                        "Caption, hier einfügen — das ergibt deutlich vollständigere Rezepte."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ArcaneCard {
            OutlinedTextField(
                value = caption,
                onValueChange = { caption = it },
                label = { Text("Caption (optional)") },
                colors = arcaneTextFieldColors(),
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sourceUrl,
                onValueChange = { sourceUrl = it },
                label = { Text("Link zum Beitrag (optional)") },
                supportingText = { Text("Wird im Rezept als Quelle gespeichert.") },
                colors = arcaneTextFieldColors(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArcanePrimaryButton(
                "Rezept extrahieren",
                { onStart(caption.trim(), sourceUrl.trim()) },
                Modifier.weight(1f),
            )
            ArcaneSecondaryButton("Abbrechen", onCancel, Modifier.weight(1f))
        }
    }
}
