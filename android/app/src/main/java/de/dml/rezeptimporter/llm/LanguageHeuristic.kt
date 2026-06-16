package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft

/**
 * Grobe Heuristik: Ist der extrahierte Draft (noch) Englisch? Prüft Name, Zutaten
 * und Schritte auf typische englische Funktions- und Kochwörter, die es im Deutschen
 * so nicht gibt. Bewusst konservativ und ohne Lehnwörter (Protein, Topping, Bowl …),
 * damit deutsche Rezepte nicht fälschlich als Englisch gelten.
 */
object LanguageHeuristic {
    private val ENGLISH_MARKERS = setOf(
        "the", "and", "with", "until", "minutes", "minute", "cup", "cups",
        "tbsp", "tsp", "tablespoon", "teaspoon", "pound", "pounds", "ounce", "ounces",
        "preheat", "oven", "bake", "baking", "chicken", "beef", "cheese", "chopped",
        "sliced", "slices", "combine", "season", "skillet", "dough", "remove", "add",
        "stir", "heat", "into", "over", "your", "then", "salt", "pepper", "garlic",
        "onion", "sugar", "flour", "water", "taste", "fresh", "boil", "fry", "mix",
        "serve", "cook", "cooked", "of", "for",
    )

    fun isLikelyEnglish(draft: RecipeDraft): Boolean {
        val words = buildString {
            append(draft.name).append(' ')
            draft.ingredients.forEach { append(it.name).append(' ') }
            draft.steps.forEach { append(it).append(' ') }
        }.lowercase().split(Regex("[^a-z]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return false
        val hits = words.count { it in ENGLISH_MARKERS }
        // Mehrere Treffer ODER hoher Anteil ⇒ Englisch.
        return hits >= 3 || hits.toDouble() / words.size >= 0.15
    }
}
