package de.dml.rezeptimporter.pipeline

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.llm.FakeLlmExtractor
import de.dml.rezeptimporter.llm.LlmException
import de.dml.rezeptimporter.llm.LlmExtractor
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ImportPipelineTest {
    private val schemaJson = File("../../shared/recipe-vault-frontmatter.schema.json").readText()
    private val validator = RecipeValidator(schemaJson)
    private val writer = RecipeMarkdownWriter()

    private val good = RecipeDraft(
        name = "Curry",
        ingredients = listOf(IngredientDraft("Reis", "250", "g")),
        steps = listOf("Kochen."),
    )

    private val english = RecipeDraft(
        name = "Chicken Curry",
        ingredients = listOf(IngredientDraft("Rice", "250", "g")),
        steps = listOf("Cook the chicken and add the rice."),
    )

    @Test
    fun singleCallWhenFirstResultValid() = runTest {
        val fake = FakeLlmExtractor(good)
        val pipeline = ImportPipeline(fake, validator, writer)
        val draft = pipeline.extractValidated("text")
        assertEquals("Curry", draft.name)
        assertEquals(1, fake.calls)
    }

    @Test
    fun translatesWhenResultIsEnglish() = runTest {
        var calls = 0
        // Erst-Extraktion (repairHint == null) liefert Englisch, der Übersetzungs-Pass Deutsch.
        val switching = object : LlmExtractor {
            override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft {
                calls++
                return if (repairHint == null) english else good
            }
        }
        val draft = ImportPipeline(switching, validator, writer).extractValidated("text")
        assertEquals("Curry", draft.name)
        assertEquals(2, calls)   // 1 Extraktion + 1 Übersetzungs-Pass
    }

    @Test
    fun setsVegetarianFromIngredients() = runTest {
        val veg = pipelineFor(good).extractValidated("text")
        assertEquals(true, veg.vegetarian)

        val meat = RecipeDraft(
            name = "Hähnchenpfanne",
            ingredients = listOf(IngredientDraft("Hähnchenbrust", "300", "g")),
            steps = listOf("Braten."),
        )
        val meatResult = pipelineFor(meat).extractValidated("text")
        assertEquals(false, meatResult.vegetarian)
    }

    private fun pipelineFor(draft: RecipeDraft) =
        ImportPipeline(FakeLlmExtractor(draft), validator, writer)

    @Test
    fun keepsOriginalWhenTranslationStaysEnglish() = runTest {
        // Übersetzungs-Pass scheitert (bleibt englisch) ⇒ valider Original-Draft bleibt erhalten.
        val draft = ImportPipeline(FakeLlmExtractor(english), validator, writer)
            .extractValidated("text")
        assertEquals("Chicken Curry", draft.name)
    }

    @Test
    fun retriesOnceWithRepairHintThenSucceeds() = runTest {
        var call = 0
        val flaky = object : LlmExtractor {
            override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft {
                call++
                return if (call == 1) good.copy(name = "!!!") else good  // "!!!" ⇒ leerer Slug ⇒ invalide
            }
        }
        val pipeline = ImportPipeline(flaky, validator, writer)
        val draft = pipeline.extractValidated("text")
        assertEquals("Curry", draft.name)
        assertEquals(2, call)
    }

    @Test(expected = LlmException::class)
    fun givesUpAfterTwoFailedCalls() = runTest {
        val alwaysBad = object : LlmExtractor {
            override suspend fun extract(rawText: String, repairHint: String?) =
                good.copy(name = "!!!")
        }
        ImportPipeline(alwaysBad, validator, writer).extractValidated("text")
    }

    @Test
    fun retriesWhenFirstCallThrowsSemanticLlmException() = runTest {
        var call = 0
        val flaky = object : LlmExtractor {
            override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft {
                call++
                if (call == 1) throw LlmException("LLM-Antwort mit leerem 'name'")
                return good
            }
        }
        val draft = ImportPipeline(flaky, validator, writer).extractValidated("text")
        assertEquals("Curry", draft.name)
        assertEquals(2, call)
    }

    @Test(expected = LlmException::class)
    fun doesNotRetryMoreThanOnceOnThrows() = runTest {
        val alwaysThrows = object : LlmExtractor {
            override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft =
                throw LlmException("kaputt")
        }
        ImportPipeline(alwaysThrows, validator, writer).extractValidated("text")
    }
}
