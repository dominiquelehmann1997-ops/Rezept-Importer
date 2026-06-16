package de.dml.rezeptimporter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.ui.theme.ArcaneCard
import de.dml.rezeptimporter.ui.theme.ArcanePrimaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneSecondaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneShape
import de.dml.rezeptimporter.ui.theme.ArcaneStatBar
import de.dml.rezeptimporter.ui.theme.ArcaneTag
import de.dml.rezeptimporter.ui.theme.SpaceMono
import de.dml.rezeptimporter.ui.theme.arcaneTextFieldColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreviewScreen(
    initial: RecipeDraft,
    folders: List<String>,
    defaultFolder: String,
    onSave: (RecipeDraft, String) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var folder by remember { mutableStateOf(defaultFolder) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Rezept-Vorschau",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                ArcaneTag("[VORSCHAU]")
            }
        }

        item {
            ArcaneCard {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("Name") },
                    colors = arcaneTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bewertung:", style = MaterialTheme.typography.bodyMedium)
                    listOf("favorit", "ok", "selten").forEach { r ->
                        Spacer(Modifier.width(4.dp))
                        FilterChip(
                            selected = draft.rating == r,
                            onClick = { draft = draft.copy(rating = r) },
                            label = { Text(r) },
                            shape = ArcaneShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        draft.simple, { draft = draft.copy(simple = it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Text("einfach", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(16.dp))
                    Checkbox(
                        draft.reheatable, { draft = draft.copy(reheatable = it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Text("aufwärmbar", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        draft.vegetarian, { draft = draft.copy(vegetarian = it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Text("vegetarisch", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            ArcaneCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Zutaten",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ArcaneTag("[${draft.ingredients.size}]")
                }
                Spacer(Modifier.height(8.dp))
                draft.ingredients.forEachIndexed { i, ing ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = ing.amount ?: "",
                            onValueChange = { v ->
                                draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                                    .also { it[i] = ing.copy(amount = v.ifEmpty { null }) })
                            },
                            label = { Text("Menge") },
                            colors = arcaneTextFieldColors(),
                            modifier = Modifier.width(90.dp),
                        )
                        OutlinedTextField(
                            value = ing.unit ?: "",
                            onValueChange = { v ->
                                draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                                    .also { it[i] = ing.copy(unit = v.ifEmpty { null }) })
                            },
                            label = { Text("Einh.") },
                            colors = arcaneTextFieldColors(),
                            modifier = Modifier.width(80.dp),
                        )
                        OutlinedTextField(
                            value = ing.name,
                            onValueChange = { v ->
                                draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                                    .also { it[i] = ing.copy(name = v) })
                            },
                            label = { Text("Zutat") },
                            colors = arcaneTextFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        draft.nutrition?.takeIf { !it.isEmpty }?.let { n ->
            item {
                ArcaneCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Nährwerte",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        n.basis?.let { ArcaneTag("[$it]") }
                    }
                    Spacer(Modifier.height(8.dp))
                    n.kcal?.let {
                        Text("$it kcal", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                    }
                    // Makros als flache Statbars, skaliert am größten Wert.
                    val macros = listOfNotNull(
                        n.protein?.let { "EIWEISS" to it },
                        n.carbs?.let { "KH" to it },
                        n.fat?.let { "FETT" to it },
                    )
                    val maxMacro = macros.maxOfOrNull { it.second } ?: 0.0
                    macros.forEach { (label, value) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = SpaceMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(72.dp),
                            )
                            ArcaneStatBar(
                                (value / maxMacro).toFloat(),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(value.fmtG(), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        item {
            ArcaneCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Zubereitung",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ArcaneTag("[${draft.steps.size} SCHRITTE]")
                }
                Spacer(Modifier.height(8.dp))
                draft.steps.forEachIndexed { i, step ->
                    Text(
                        "${i + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        item {
            ArcaneCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Speicherort",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ArcaneTag("[/$folder]")
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    folders.forEach { f ->
                        FilterChip(
                            selected = folder == f,
                            onClick = { folder = f },
                            label = { Text(f) },
                            shape = ArcaneShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArcanePrimaryButton(
                    "In Vault speichern",
                    { onSave(draft, folder) },
                    enabled = draft.name.isNotBlank(),
                )
                ArcaneSecondaryButton("Abbrechen", onCancel)
            }
        }
    }
}

/** 28.0 → "28 g", 28.5 → "28.5 g". */
private fun Double.fmtG(): String =
    (if (this % 1.0 == 0.0) toLong().toString() else toString()) + " g"
