package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VegetarianHeuristicTest {
    private fun draft(vararg ings: String) =
        RecipeDraft(name = "X", ingredients = ings.map { IngredientDraft(it) })

    @Test
    fun detectsMeat() {
        assertFalse(VegetarianHeuristic.isVegetarian(draft("Hähnchenbrust", "Reis")))
        assertFalse(VegetarianHeuristic.isVegetarian(draft("mageres Rinderhack", "Zwiebel")))
        assertFalse(VegetarianHeuristic.isVegetarian(draft("Cabanossi", "Kartoffeln")))
        assertFalse(VegetarianHeuristic.isVegetarian(draft("Puten Geschnetzeltes")))
        assertFalse(VegetarianHeuristic.isVegetarian(draft("Salami", "Rindertartar")))
    }

    @Test
    fun detectsFish() {
        assertFalse(VegetarianHeuristic.isVegetarian(draft("Lachsfilet", "Dill")))
        assertFalse(VegetarianHeuristic.isVegetarian(draft("Thunfisch", "Mais")))
    }

    @Test
    fun acceptsVegetarian() {
        assertTrue(VegetarianHeuristic.isVegetarian(draft("Brokkoli", "Orzo", "Cheddar")))
        assertTrue(VegetarianHeuristic.isVegetarian(draft("Weizenmehl", "Wasser", "Hefe")))
    }

    @Test
    fun noFalsePositivesOnLookalikeWords() {
        // "gehackt" (≠ hack), "Champignon" (≠ ham), "Tamarinde"/"Rinde" (≠ rind),
        // "Flammkuchen" (≠ lamm) dürfen NICHT als Fleisch zählen.
        assertTrue(VegetarianHeuristic.isVegetarian(
            draft("gehackte Erdnüsse", "Champignons", "Tamarinde", "Käserinde")
        ))
    }
}
