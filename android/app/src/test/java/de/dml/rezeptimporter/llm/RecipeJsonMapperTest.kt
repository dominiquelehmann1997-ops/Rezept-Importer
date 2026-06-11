package de.dml.rezeptimporter.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeJsonMapperTest {

    @Test
    fun mapsFullPayload() {
        val json = Json.parseToJsonElement(
            """
            {
              "name": "Gemüse-Curry",
              "tags": ["vegetarisch"],
              "servings": 4,
              "ingredients": [
                {"name": "Kokosmilch", "amount": "400", "unit": "ml", "freshness": "haltbar"},
                {"name": "Salz"}
              ],
              "steps": ["Würfeln.", "Köcheln."]
            }
            """
        ).jsonObject
        val draft = RecipeJsonMapper.fromJson(json)
        assertEquals("Gemüse-Curry", draft.name)
        assertEquals(4, draft.servings)
        assertEquals("400", draft.ingredients[0].amount)
        assertEquals(null, draft.ingredients[1].amount)
        assertEquals(2, draft.steps.size)
        assertEquals("ok", draft.rating)        // Default, nie vom LLM
    }

    @Test
    fun toleratesNumericAmountAndUnknownKeys() {
        val json = Json.parseToJsonElement(
            """{"name":"X","ingredients":[{"name":"Reis","amount":250,"extra":"weg"}],"steps":[],"unknown":1}"""
        ).jsonObject
        val draft = RecipeJsonMapper.fromJson(json)
        assertEquals("250", draft.ingredients[0].amount)   // Zahl → String
    }

    @Test
    fun rejectsInvalidFreshness() {
        val json = Json.parseToJsonElement(
            """{"name":"X","ingredients":[{"name":"Milch","freshness":"kuehl"}],"steps":[]}"""
        ).jsonObject
        val draft = RecipeJsonMapper.fromJson(json)
        assertEquals(null, draft.ingredients[0].freshness)
    }

    @Test(expected = LlmException::class)
    fun throwsLlmExceptionOnMissingName() {
        RecipeJsonMapper.fromJson(
            Json.parseToJsonElement("""{"ingredients":[],"steps":[]}""").jsonObject
        )
    }

    @Test
    fun toleratesWrongTypedElements() {
        val json = Json.parseToJsonElement(
            """{"name":"X","tags":[{"x":1}],"ingredients":[{"name":"Reis","amount":{"v":400}}],"steps":["ok",["nested"]]}"""
        ).jsonObject
        val draft = RecipeJsonMapper.fromJson(json)
        assertEquals(emptyList<String>(), draft.tags)            // Objekt-Element verworfen
        assertEquals(null, draft.ingredients[0].amount)           // Objekt-amount ⇒ null
        assertEquals(listOf("ok"), draft.steps)                   // verschachteltes Array verworfen
    }

    @Test(expected = LlmException::class)
    fun throwsLlmExceptionOnNonArrayIngredients() {
        RecipeJsonMapper.fromJson(
            Json.parseToJsonElement("""{"name":"X","ingredients":"keine","steps":[]}""").jsonObject
        )
    }
}
