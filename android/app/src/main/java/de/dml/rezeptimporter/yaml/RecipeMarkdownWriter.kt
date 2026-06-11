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

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
        }
        val yaml = Yaml(options).dump(fm)

        val body = buildString {
            if (draft.steps.isNotEmpty()) {
                appendLine("## Zubereitung")
                draft.steps.forEachIndexed { i, step -> appendLine("${i + 1}. $step") }
            }
        }
        return "---\n$yaml---\n\n$body"
    }

    /** "400" → 400, "1.5" → 1.5, "1/2"/"2-3"/"etwas" → String (SnakeYAML quotet bei Bedarf). */
    private fun coerceAmount(amount: String): Any {
        amount.toIntOrNull()?.let { return it }
        amount.toDoubleOrNull()?.let { return it }
        return amount
    }
}
