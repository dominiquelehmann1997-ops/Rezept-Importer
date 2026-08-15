package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.NutritionDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.serialization.json.*

object RecipeJsonMapper {
    private val FRESHNESS = setOf("frisch", "haltbar")

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.intOrNullSafe(): Int? =
        (this as? JsonPrimitive)?.intOrNull

    private fun JsonElement?.doubleOrNullSafe(): Double? =
        (this as? JsonPrimitive)?.doubleOrNull

    private fun parseNutrition(el: JsonElement?): NutritionDraft? {
        val o = el as? JsonObject ?: return null
        val n = NutritionDraft(
            basis = o["basis"].stringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            kcal = o["kcal"].intOrNullSafe(),
            protein = o["protein"].doubleOrNullSafe(),
            carbs = o["carbs"].doubleOrNullSafe(),
            fat = o["fat"].doubleOrNullSafe(),
        )
        return if (n.isEmpty) null else n
    }

    fun fromJson(obj: JsonObject): RecipeDraft = try {
        fromJsonUnsafe(obj)
    } catch (e: LlmException) {
        throw e
    } catch (e: Exception) {
        throw LlmException("LLM-Antwort hat unerwartete Struktur: ${e.message}", e)
    }

    private fun fromJsonUnsafe(obj: JsonObject): RecipeDraft {
        val name = obj["name"].stringOrNull()?.trim()
            ?: throw LlmException("LLM-Antwort ohne 'name'")
        if (name.isEmpty()) throw LlmException("LLM-Antwort mit leerem 'name'")

        val ingredients = obj["ingredients"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val ingName = o["name"].stringOrNull()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            IngredientDraft(
                name = ingName,
                amount = o["amount"].stringOrNull(),
                unit = o["unit"].stringOrNull(),
                freshness = o["freshness"].stringOrNull()
                    ?.takeIf { it in FRESHNESS },
                section = o["section"].stringOrNull()
                    ?.trim()?.trimEnd(':')?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        return RecipeDraft(
            name = name,
            description = obj["description"].stringOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            tags = obj["tags"]?.jsonArray.orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            servings = obj["servings"].intOrNullSafe(),
            prepMinutes = obj["prepMinutes"].intOrNullSafe(),
            cookMinutes = obj["cookMinutes"].intOrNullSafe(),
            ingredients = ingredients,
            steps = obj["steps"]?.jsonArray.orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            nutrition = parseNutrition(obj["nutrition"]),
        )
    }
}
