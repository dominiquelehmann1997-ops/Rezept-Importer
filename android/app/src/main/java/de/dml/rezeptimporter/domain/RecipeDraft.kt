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
data class RecipeDraft(
    val name: String,
    val tags: List<String> = emptyList(),
    val servings: Int? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val ingredients: List<IngredientDraft> = emptyList(),
    val steps: List<String> = emptyList(),
    // Nicht vom LLM befüllt — Defaults laut Contract, im Preview togglebar:
    val rating: String = "ok",
    val simple: Boolean = true,
    val reheatable: Boolean = false,
)
