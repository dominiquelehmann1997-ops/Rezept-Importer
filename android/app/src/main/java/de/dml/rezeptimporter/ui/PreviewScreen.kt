package de.dml.rezeptimporter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft

@Composable
fun PreviewScreen(
    initial: RecipeDraft,
    onSave: (RecipeDraft) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bewertung:")
                listOf("favorit", "ok", "selten").forEach { r ->
                    Spacer(Modifier.width(4.dp))
                    FilterChip(
                        selected = draft.rating == r,
                        onClick = { draft = draft.copy(rating = r) },
                        label = { Text(r) },
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draft.simple, { draft = draft.copy(simple = it) }); Text("einfach")
                Spacer(Modifier.width(16.dp))
                Checkbox(draft.reheatable, { draft = draft.copy(reheatable = it) }); Text("aufwärmbar")
            }
        }
        item { Text("Zutaten", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(draft.ingredients) { i, ing ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = ing.amount ?: "",
                    onValueChange = { v ->
                        draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                            .also { it[i] = ing.copy(amount = v.ifEmpty { null }) })
                    },
                    label = { Text("Menge") }, modifier = Modifier.width(90.dp),
                )
                OutlinedTextField(
                    value = ing.unit ?: "",
                    onValueChange = { v ->
                        draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                            .also { it[i] = ing.copy(unit = v.ifEmpty { null }) })
                    },
                    label = { Text("Einh.") }, modifier = Modifier.width(80.dp),
                )
                OutlinedTextField(
                    value = ing.name,
                    onValueChange = { v ->
                        draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                            .also { it[i] = ing.copy(name = v) })
                    },
                    label = { Text("Zutat") }, modifier = Modifier.weight(1f),
                )
            }
        }
        draft.nutrition?.takeIf { !it.isEmpty }?.let { n ->
            item {
                Text(
                    "Nährwerte" + (n.basis?.let { " ($it)" } ?: ""),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                val parts = listOfNotNull(
                    n.kcal?.let { "$it kcal" },
                    n.protein?.let { "Eiweiß ${it.fmtG()}" },
                    n.carbs?.let { "KH ${it.fmtG()}" },
                    n.fat?.let { "Fett ${it.fmtG()}" },
                )
                Text("• " + parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("Zubereitung (${draft.steps.size} Schritte)", style = MaterialTheme.typography.titleMedium) }
        items(draft.steps) { step -> Text("• $step", style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(draft) }, enabled = draft.name.isNotBlank()) {
                    Text("In Vault speichern")
                }
                OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            }
        }
    }
}

/** 28.0 → "28 g", 28.5 → "28.5 g". */
private fun Double.fmtG(): String =
    (if (this % 1.0 == 0.0) toLong().toString() else toString()) + " g"
