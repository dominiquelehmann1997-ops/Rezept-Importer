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

        yieldText(recipe["recipeYield"])?.let { sb.appendLine("Portionen: $it").appendLine() }

        val ingredients = strings(recipe["recipeIngredient"])
        if (ingredients.isNotEmpty()) {
            sb.appendLine("Zutaten:")
            ingredients.forEach { sb.appendLine("- $it") }
            sb.appendLine()
        }

        nutrition(recipe["nutrition"])?.let { sb.appendLine(it).appendLine() }

        val steps = instructions(recipe["recipeInstructions"])
        if (steps.isNotEmpty()) {
            sb.appendLine("Zubereitung:")
            steps.forEachIndexed { i, s -> sb.appendLine("${i + 1}. $s") }
        }
        return sb.toString().trim()
    }

    /** recipeYield: String | Number | Array davon (erstes Element gewinnt). */
    private fun yieldText(el: JsonElement?): String? = when (el) {
        is JsonArray -> el.firstNotNullOfOrNull { yieldText(it) }
        is JsonPrimitive -> el.contentOrNull?.trim()?.ifBlank { null }
        else -> null
    }

    /** schema.org/NutritionInformation → "Nährwerte:"-Block, oder null wenn leer. */
    private fun nutrition(el: JsonElement?): String? {
        val n = el as? JsonObject ?: return null
        val parts = listOfNotNull(
            str(n["calories"])?.let { "Kalorien: $it" },
            str(n["proteinContent"])?.let { "Eiweiß: $it" },
            str(n["carbohydrateContent"])?.let { "Kohlenhydrate: $it" },
            str(n["fatContent"])?.let { "Fett: $it" },
        )
        if (parts.isEmpty()) return null
        return "Nährwerte:\n" + parts.joinToString("\n") { "- $it" }
    }

    private fun str(el: JsonElement?): String? =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let(::decodeEntities)?.trim()?.ifBlank { null }

    /** Häufige HTML-Entities in Portal-Markup (z. B. Cookidoo: "&frac12; TL"). */
    private fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return s
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("&frac12;", "½").replace("&frac14;", "¼").replace("&frac34;", "¾")
            .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&amp;", "&") // zuletzt, sonst würde "&amp;frac12;" doppelt dekodiert
    }

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
