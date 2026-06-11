package de.dml.rezeptimporter.yaml

import de.dml.rezeptimporter.domain.RecipeDraft
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

class RecipeMarkdownWriter {

    fun render(id: String, draft: RecipeDraft): String {
        val fm = linkedMapOf<String, Any>()
        fm["id"] = id
        fm["name"] = draft.name
        fm["rating"] = draft.rating
        fm["simple"] = draft.simple
        fm["reheatable"] = draft.reheatable
        if (draft.tags.isNotEmpty()) fm["tags"] = draft.tags
        draft.servings?.let { fm["servings"] = it }
        draft.prepMinutes?.let { fm["prepMinutes"] = it }
        draft.cookMinutes?.let { fm["cookMinutes"] = it }
        if (draft.ingredients.isNotEmpty()) {
            fm["ingredients"] = draft.ingredients.map { ing ->
                val m = linkedMapOf<String, Any>("name" to ing.name)
                ing.amount?.let { m["amount"] = coerceAmount(it) }
                ing.unit?.let { m["unit"] = it }
                ing.freshness?.let { m["freshness"] = it }
                m
            }
        }
        draft.nutrition?.takeIf { !it.isEmpty }?.let { n ->
            fm["nutrition"] = linkedMapOf<String, Any>().apply {
                n.basis?.let { put("basis", it) }
                n.kcal?.let { put("kcal", it) }
                n.protein?.let { put("protein", it) }
                n.carbs?.let { put("carbs", it) }
                n.fat?.let { put("fat", it) }
            }
        }

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
        }
        val yaml = Yaml(options).dump(fm)

        val body = buildString {
            if (draft.ingredients.isNotEmpty()) {
                appendLine("## Zutaten")
                // Basis-Portionen sichtbar im Text — die Kochansicht skaliert gegen Frontmatter-servings,
                // aber der Nutzer muss den Ausgangswert auch ohne aktive View sehen.
                draft.servings?.let { appendLine("*Für $it ${if (it == 1) "Portion" else "Portionen"}*") }
                draft.ingredients.forEach { appendLine("- ${ingredientLine(it)}") }
                appendLine()
            }
            draft.nutrition?.takeIf { !it.isEmpty }?.let { n ->
                // Überschrift exakt "Nährwerte" -> matcht Recipe-View-Seitenspalten-Einstellung.
                appendLine("## Nährwerte")
                n.basis?.let { appendLine("*$it*") }
                n.kcal?.let { appendLine("- Energie: $it kcal") }
                n.protein?.let { appendLine("- Eiweiß: ${num(it)} g") }
                n.carbs?.let { appendLine("- Kohlenhydrate: ${num(it)} g") }
                n.fat?.let { appendLine("- Fett: ${num(it)} g") }
                appendLine()
            }
            if (draft.steps.isNotEmpty()) {
                appendLine("## Zubereitung")
                draft.steps.forEachIndexed { i, step -> appendLine("${i + 1}. $step") }
            }
        }
        return "---\n$yaml---\n\n$body"
    }

    /** "200 g Grillkäse", "1/2 l Brühe", "Salz" — null-Teile entfallen. */
    private fun ingredientLine(ing: de.dml.rezeptimporter.domain.IngredientDraft): String =
        listOfNotNull(ing.amount, ing.unit, ing.name).joinToString(" ")

    /** 28.0 → "28", 28.5 → "28.5". */
    private fun num(d: Double): String =
        if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

    /** "400" → 400, "1.5" → 1.5; "1/2", "2-3", "1,5" (deutsches Komma) und Freitext bleiben String (SnakeYAML quotet bei Bedarf). */
    private fun coerceAmount(amount: String): Any {
        amount.toIntOrNull()?.let { return it }
        amount.toDoubleOrNull()?.let { return it }
        return amount
    }
}
