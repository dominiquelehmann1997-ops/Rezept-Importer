package de.dml.rezeptimporter.domain

import kotlinx.serialization.Serializable

@Serializable
data class IngredientDraft(
    val name: String,
    /** Menge als String; Zahlen ("400"), Brüche ("1/2"), Bereiche ("2-3"). Writer re-typisiert. */
    val amount: String? = null,
    val unit: String? = null,
    /** "frisch" | "haltbar" | null */
    val freshness: String? = null,
)

@Serializable
data class NutritionDraft(
    /** Bezugsgröße laut Quelle, z.B. "pro Portion" | "pro 100g". */
    val basis: String? = null,
    val kcal: Int? = null,
    /** Gramm */
    val protein: Double? = null,
    /** Gramm */
    val carbs: Double? = null,
    /** Gramm */
    val fat: Double? = null,
) {
    val isEmpty: Boolean
        get() = kcal == null && protein == null && carbs == null && fat == null
}

@Serializable
data class RecipeDraft(
    val name: String,
    val tags: List<String> = emptyList(),
    val servings: Int? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val ingredients: List<IngredientDraft> = emptyList(),
    val steps: List<String> = emptyList(),
    val nutrition: NutritionDraft? = null,
    // Nicht vom LLM befüllt — Defaults laut Contract, im Preview togglebar:
    val rating: String = "ok",
    val simple: Boolean = true,
    val reheatable: Boolean = false,
    /** Heuristisch aus den Zutaten gesetzt (kein Fleisch/Fisch ⇒ true), im Preview togglebar. */
    val vegetarian: Boolean = true,
    /**
     * Ursprungslink (Reel, Video, Rezeptseite). Kommt aus dem Quellen-Bündel, nie vom LLM.
     * Landet als `source` im Frontmatter und als Abschnitt "## Quelle" in der Notiz, damit
     * man sich das Video später nochmal ansehen kann.
     */
    val sourceUrl: String? = null,
)
