package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.NutritionDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.serialization.json.*

object RecipeJsonMapper {
    private val FRESHNESS = setOf("frisch", "haltbar")

    /** Einzelne Wörter, die in einer Zutatenliste immer eine Gruppe benennen, nie eine Zutat. */
    private val SECTION_WORDS = setOf(
        "dip", "sauce", "soße", "sosse", "topping", "teig", "marinade", "dressing",
        "füllung", "fuellung", "belag", "panade", "beilage", "garnitur", "glasur", "creme",
    )

    /** "Für die Soße", "Zum Servieren" — Überschriften-Phrasen. */
    private val SECTION_PHRASE = Regex("^(für|zum|zur)\\s+\\S+.*", RegexOption.IGNORE_CASE)

    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.intOrNullSafe(): Int? =
        (this as? JsonPrimitive)?.intOrNull

    private fun JsonElement?.doubleOrNullSafe(): Double? =
        (this as? JsonPrimitive)?.doubleOrNull

    /**
     * "Dip", "Für die Soße:" — eine Zeile ohne Menge, die eine Teil-Zubereitung benennt.
     * Das LLM soll daraus "section" machen; liefert es die Zeile doch als Zutat, fängt das
     * [applySectionHeadings] ab. Bewusst eng gefasst, damit "Salz & Pfeffer" Zutat bleibt.
     */
    private fun IngredientDraft.looksLikeSectionHeading(): Boolean {
        if (amount != null || unit != null) return false
        val n = name.trim()
        return n.endsWith(":") || n.lowercase() in SECTION_WORDS || SECTION_PHRASE.matches(n)
    }

    /** Überschriften-Zeilen aus der Zutatenliste ziehen und den folgenden Zutaten zuordnen. */
    private fun applySectionHeadings(ingredients: List<IngredientDraft>): List<IngredientDraft> {
        val out = mutableListOf<IngredientDraft>()
        var current: String? = null
        ingredients.forEachIndexed { i, ing ->
            // Nur als Überschrift werten, wenn danach überhaupt noch Zutaten kommen.
            if (ing.looksLikeSectionHeading() && i < ingredients.lastIndex) {
                current = ing.name.trim().trimEnd(':').trim().takeIf { it.isNotEmpty() }
                return@forEachIndexed
            }
            out.add(if (ing.section == null && current != null) ing.copy(section = current) else ing)
        }
        return out
    }

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

        val rawIngredients = obj["ingredients"]?.jsonArray.orEmpty().mapNotNull { el ->
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
            tags = obj["tags"]?.jsonArray.orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            servings = obj["servings"].intOrNullSafe(),
            prepMinutes = obj["prepMinutes"].intOrNullSafe(),
            cookMinutes = obj["cookMinutes"].intOrNullSafe(),
            ingredients = applySectionHeadings(rawIngredients),
            steps = obj["steps"]?.jsonArray.orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            nutrition = parseNutrition(obj["nutrition"]),
        )
    }
}
