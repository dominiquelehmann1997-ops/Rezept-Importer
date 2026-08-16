package de.dml.rezeptimporter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.domain.moved
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
    // Im Preview angelegte Kategorien, die noch keine Zutat enthalten (nur UI-Zustand).
    var extraSections by remember { mutableStateOf(emptyList<String>()) }

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

                // Zutaten nach Gruppe ("Für die Soße") gebündelt, Reihenfolge des ersten
                // Auftretens. `extraSections` sind im Preview neu angelegte, noch leere
                // Gruppen — sie existieren erst im Draft, sobald eine Zutat drin liegt.
                val sections = (draft.ingredients.map { it.section } + extraSections).distinct()
                sections.forEach { section ->
                    IngredientSectionHeader(
                        section = section,
                        onRename = { renamed ->
                            draft = draft.copy(
                                ingredients = draft.ingredients.map {
                                    if (it.section == section) it.copy(section = renamed) else it
                                },
                            )
                            extraSections = extraSections.map { if (it == section) renamed else it }
                                .filterNotNull().distinct()
                        },
                    )
                    draft.ingredients.forEachIndexed { i, ing ->
                        if (ing.section != section) return@forEachIndexed
                        IngredientRow(
                            ing = ing,
                            sections = sections,
                            onChange = { updated ->
                                draft = draft.copy(
                                    ingredients = draft.ingredients.toMutableList()
                                        .also { it[i] = updated },
                                )
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                }

                Spacer(Modifier.height(4.dp))
                ArcaneSecondaryButton("+ Kategorie", {
                    // Neue, zunächst leere Gruppe — Name eintragen, Zutaten per Menü hineinschieben.
                    var name = "Neue Kategorie"
                    var n = 2
                    while (name in sections.filterNotNull()) { name = "Neue Kategorie $n"; n++ }
                    extraSections = extraSections + name
                })
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
                    // Kopfzeile: Nummer + Reihenfolge/Löschen. Das Textfeld darunter bekommt
                    // so die volle Breite — auf dem Handy sonst zu schmal für lange Schritte.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { draft = draft.copy(steps = draft.steps.moved(i, i - 1)) },
                            enabled = i > 0,
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowUp,
                                contentDescription = "Schritt nach oben",
                                tint = if (i > 0) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            )
                        }
                        IconButton(
                            onClick = { draft = draft.copy(steps = draft.steps.moved(i, i + 1)) },
                            enabled = i < draft.steps.lastIndex,
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Schritt nach unten",
                                tint = if (i < draft.steps.lastIndex) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                            )
                        }
                        IconButton(onClick = {
                            draft = draft.copy(
                                steps = draft.steps.filterIndexed { idx, _ -> idx != i },
                            )
                        }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Schritt löschen",
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = step,
                        onValueChange = { v ->
                            draft = draft.copy(steps = draft.steps.toMutableList()
                                .also { it[i] = v })
                        },
                        minLines = 2,
                        colors = arcaneTextFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                ArcaneSecondaryButton("+ Schritt", { draft = draft.copy(steps = draft.steps + "") })
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
                    {
                        // Leer gelassene Schritte/Zutaten fliegen raus — sonst landen leere
                        // Listenpunkte in der Notiz bzw. scheitert die Schema-Validierung.
                        onSave(
                            draft.copy(
                                steps = draft.steps.map { it.trim() }.filter { it.isNotEmpty() },
                                ingredients = draft.ingredients.filter { it.name.isNotBlank() },
                            ),
                            folder,
                        )
                    },
                    enabled = draft.name.isNotBlank(),
                )
                ArcaneSecondaryButton("Abbrechen", onCancel)
            }
        }
    }
}

/**
 * Kopfzeile einer Zutaten-Gruppe. Leerer Text = „ohne Kategorie"; wer hier etwas
 * einträgt, verschiebt alle Zutaten der Gruppe unter diese Überschrift.
 */
@Composable
private fun IngredientSectionHeader(section: String?, onRename: (String?) -> Unit) {
    OutlinedTextField(
        value = section ?: "",
        // Kein trim() beim Tippen — sonst verschluckt das Feld Leerzeichen. Der Writer trimmt.
        onValueChange = { onRename(it.ifEmpty { null }) },
        label = { Text("Kategorie") },
        placeholder = { Text("ohne Kategorie — z. B. Für die Soße") },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleSmall,
        colors = arcaneTextFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
}

/** Eine Zutatenzeile: Menge / Einheit / Name, plus Kategorie-Menü sobald es Gruppen gibt. */
@Composable
private fun IngredientRow(
    ing: IngredientDraft,
    sections: List<String?>,
    onChange: (IngredientDraft) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = ing.amount ?: "",
            onValueChange = { onChange(ing.copy(amount = it.ifEmpty { null })) },
            label = { Text("Menge") },
            colors = arcaneTextFieldColors(),
            modifier = Modifier.width(90.dp),
        )
        OutlinedTextField(
            value = ing.unit ?: "",
            onValueChange = { onChange(ing.copy(unit = it.ifEmpty { null })) },
            label = { Text("Einh.") },
            colors = arcaneTextFieldColors(),
            modifier = Modifier.width(80.dp),
        )
        OutlinedTextField(
            value = ing.name,
            onValueChange = { onChange(ing.copy(name = it)) },
            label = { Text("Zutat") },
            colors = arcaneTextFieldColors(),
            modifier = Modifier.weight(1f),
        )
        if (sections.size > 1) {
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Kategorie wählen",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    sections.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s ?: "ohne Kategorie") },
                            onClick = {
                                onChange(ing.copy(section = s))
                                menuOpen = false
                            },
                            trailingIcon = if (s == ing.section) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

/** 28.0 → "28 g", 28.5 → "28.5 g". */
private fun Double.fmtG(): String =
    (if (this % 1.0 == 0.0) toLong().toString() else toString()) + " g"
