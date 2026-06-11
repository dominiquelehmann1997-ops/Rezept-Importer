package de.dml.rezeptimporter.link

import kotlinx.serialization.json.*

/**
 * Zieht das erste schema.org/Recipe aus den JSON-LD-Blöcken einer Webseite und flacht es zu
 * Text ab (Name, Beschreibung, Zutaten, Zubereitung). Reine Parserei — kein Netzwerk, voll
 * testbar. Der Text geht danach durch denselben LLM-Extractor wie geteilte Captions/OCR.
 */
object JsonLdRecipeParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val scriptBlock = Regex(
        """<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** Flacher Rezept-Text, oder null wenn die Seite kein Recipe-JSON-LD enthält. */
    fun parse(html: String): String? {
        for (match in scriptBlock.findAll(html)) {
            val raw = match.groupValues[1].trim()
            val element = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: continue
            val recipe = findRecipe(element) ?: continue
            return flatten(recipe)
        }
        return null
    }

    private fun findRecipe(el: JsonElement): JsonObject? = when (el) {
        is JsonArray -> el.firstNotNullOfOrNull { findRecipe(it) }
        is JsonObject -> when {
            isRecipe(el) -> el
            el["@graph"] != null -> findRecipe(el["@graph"]!!)
            else -> null
        }
        else -> null
    }

    private fun isRecipe(obj: JsonObject): Boolean = when (val type = obj["@type"]) {
        is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull == "Recipe" }
        is JsonPrimitive -> type.contentOrNull == "Recipe"
        else -> false
    }

    private fun flatten(recipe: JsonObject): String {
        val sb = StringBuilder()
        str(recipe["name"])?.let { sb.appendLine(it).appendLine() }
        str(recipe["description"])?.let { sb.appendLine(it).appendLine() }

        val ingredients = strings(recipe["recipeIngredient"])
        if (ingredients.isNotEmpty()) {
            sb.appendLine("Zutaten:")
            ingredients.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        val steps = instructions(recipe["recipeInstructions"])
        if (steps.isNotEmpty()) {
            sb.appendLine("Zubereitung:")
            steps.forEachIndexed { i, s -> sb.appendLine("${i + 1}. $s") }
        }
        return sb.toString().trim()
    }

    private fun str(el: JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifBlank { null }

    private fun strings(el: JsonElement?): List<String> = when (el) {
        is JsonArray -> el.mapNotNull { str(it) }
        else -> str(el)?.let { listOf(it) } ?: emptyList()
    }

    /** recipeInstructions: String | [String] | [HowToStep{text}] | [HowToSection{itemListElement:[HowToStep]}]. */
    private fun instructions(el: JsonElement?): List<String> = when (el) {
        null -> emptyList()
        is JsonArray -> el.flatMap { instructions(it) }
        is JsonPrimitive -> str(el)?.let { listOf(it) } ?: emptyList()
        is JsonObject -> when (val section = el["itemListElement"]) {
            null -> str(el["text"])?.let { listOf(it) } ?: emptyList()
            else -> instructions(section)
        }
    }
}
