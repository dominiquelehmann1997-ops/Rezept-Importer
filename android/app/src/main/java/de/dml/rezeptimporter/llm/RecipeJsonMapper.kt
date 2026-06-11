package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.serialization.json.*

object RecipeJsonMapper {
    private val FRESHNESS = setOf("frisch", "haltbar")

    fun fromJson(obj: JsonObject): RecipeDraft {
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: throw LlmException("LLM-Antwort ohne 'name'")
        if (name.isEmpty()) throw LlmException("LLM-Antwort mit leerem 'name'")

        val ingredients = obj["ingredients"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val ingName = o["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            IngredientDraft(
                name = ingName,
                amount = o["amount"]?.jsonPrimitive?.contentOrNull,
                unit = o["unit"]?.jsonPrimitive?.contentOrNull,
                freshness = o["freshness"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it in FRESHNESS },
            )
        }

        return RecipeDraft(
            name = name,
            tags = obj["tags"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull },
            servings = obj["servings"]?.jsonPrimitive?.intOrNull,
            prepMinutes = obj["prepMinutes"]?.jsonPrimitive?.intOrNull,
            cookMinutes = obj["cookMinutes"]?.jsonPrimitive?.intOrNull,
            ingredients = ingredients,
            steps = obj["steps"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull },
        )
    }
}
