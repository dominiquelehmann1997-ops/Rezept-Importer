package de.dml.rezeptimporter.llm

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HaikuExtractorTest {
    private val server = MockWebServer()
    private lateinit var extractor: HaikuExtractor

    @Before fun setUp() {
        server.start()
        extractor = HaikuExtractor(
            apiKey = "sk-test",
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun parsesRecipeFromToolUseResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "content": [
                    {"type": "text", "text": "Ich extrahiere das Rezept."},
                    {"type": "tool_use", "id": "toolu_1", "name": "save_recipe",
                     "input": {"name": "Curry", "ingredients": [{"name": "Reis", "amount": "250", "unit": "g"}], "steps": ["Kochen."]}}
                  ],
                  "stop_reason": "tool_use"
                }
                """
            )
        )

        val draft = extractor.extract("roher text")

        assertEquals("Curry", draft.name)
        assertEquals("g", draft.ingredients[0].unit)

        val req = server.takeRequest()
        assertEquals("sk-test", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        assertEquals("/v1/messages", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"claude-haiku-4-5\""))
        assertTrue(body.contains("\"save_recipe\""))
        assertTrue(body.contains("\"max_tokens\":1500"))
    }

    @Test
    fun throwsWhenNoToolUseBlock() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"content":[{"type":"text","text":"kann nicht"}],"stop_reason":"end_turn"}""")
        )
        try {
            extractor.extract("text")
            throw AssertionError("expected LlmException")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("tool_use"))
        }
    }

    @Test
    fun wrapsHttpErrorInLlmException() = runTest {
        server.enqueue(MockResponse().setResponseCode(529).setBody("""{"type":"error","error":{"type":"overloaded_error"}}"""))
        try {
            extractor.extract("text")
            throw AssertionError("expected LlmException")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("529"))
        }
    }
}
