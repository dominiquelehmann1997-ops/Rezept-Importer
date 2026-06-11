package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackExtractorTest {
    private val good = RecipeDraft(name = "Curry", steps = listOf("Kochen."))

    private class ThrowingExtractor(private val e: Exception) : LlmExtractor {
        var calls = 0
        override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft {
            calls++
            throw e
        }
    }

    @Test
    fun usesPrimaryWhenItWorks() = runTest {
        val primary = FakeLlmExtractor(good)
        val secondary = FakeLlmExtractor(good.copy(name = "Falsch"))
        val result = FallbackExtractor(primary, secondary).extract("text")
        assertEquals("Curry", result.name)
        assertEquals(1, primary.calls)
        assertEquals(0, secondary.calls)
    }

    @Test
    fun fallsBackOnTransportError() = runTest {
        val primary = ThrowingExtractor(LlmTransportException("Gemini HTTP 429: quota"))
        val secondary = FakeLlmExtractor(good)
        val result = FallbackExtractor(primary, secondary).extract("text")
        assertEquals("Curry", result.name)
        assertEquals(1, primary.calls)
        assertEquals(1, secondary.calls)
    }

    @Test
    fun doesNotFallBackOnSemanticError() = runTest {
        val primary = ThrowingExtractor(LlmException("LLM-Antwort mit leerem 'name'"))
        val secondary = FakeLlmExtractor(good)
        try {
            FallbackExtractor(primary, secondary).extract("text")
            throw AssertionError("expected LlmException")
        } catch (e: LlmException) {
            assertEquals(0, secondary.calls)
        }
    }

    @Test(expected = LlmTransportException::class)
    fun rethrowsWhenBothFailTechnically() = runTest {
        val primary = ThrowingExtractor(LlmTransportException("HTTP 503"))
        val secondary = ThrowingExtractor(LlmTransportException("HTTP 529"))
        FallbackExtractor(primary, secondary).extract("text")
    }
}
