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

    @Test
    fun singleCallWhenFirstResultValid() = runTest {
        val fake = FakeLlmExtractor(good)
        val pipeline = ImportPipeline(fake, validator, writer)
        val draft = pipeline.extractValidated("text")
        assertEquals("Curry", draft.name)
        assertEquals(1, fake.calls)
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
