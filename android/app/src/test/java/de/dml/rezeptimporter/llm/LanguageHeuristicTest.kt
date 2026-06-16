package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageHeuristicTest {
    @Test
    fun detectsEnglishRecipe() {
        val draft = RecipeDraft(
            name = "Chicken Curry",
            ingredients = listOf(IngredientDraft("Rice", "250", "g")),
            steps = listOf("Cook the chicken and add the rice."),
        )
        assertTrue(LanguageHeuristic.isLikelyEnglish(draft))
    }

    @Test
    fun acceptsGermanRecipe() {
        val draft = RecipeDraft(
            name = "Hähnchen-Curry",
            ingredients = listOf(IngredientDraft("Reis", "250", "g")),
            steps = listOf("Das Hähnchen anbraten und den Reis dazugeben."),
        )
        assertFalse(LanguageHeuristic.isLikelyEnglish(draft))
    }

    @Test
    fun doesNotFlagGermanRecipeWithLoanwords() {
        val draft = RecipeDraft(
            name = "Protein-Bowl",
            ingredients = listOf(
                IngredientDraft("Whey Protein", "30", "g"),
                IngredientDraft("Topping nach Wahl"),
            ),
            steps = listOf("Alles in die Bowl geben und vermengen."),
        )
        assertFalse(LanguageHeuristic.isLikelyEnglish(draft))
    }
}
