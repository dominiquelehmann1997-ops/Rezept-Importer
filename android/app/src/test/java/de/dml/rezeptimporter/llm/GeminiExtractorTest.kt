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

class GeminiExtractorTest {
    private val server = MockWebServer()
    private lateinit var extractor: GeminiExtractor

    @Before fun setUp() {
        server.start()
        extractor = GeminiExtractor(
            apiKey = "test-key",
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun parsesRecipeFromJsonModeResponse() = runTest {
        // Gemini liefert das Rezept-JSON als String in parts[0].text
        val recipeJson = """{"name":"Curry","ingredients":[{"name":"Reis","amount":"250","unit":"g"}],"steps":["Kochen."]}"""
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":${recipeJson.let { "\"" + it.replace("\"", "\\\"") + "\"" }}}]}}]}"""
            )
        )

        val draft = extractor.extract("roher text")

        assertEquals("Curry", draft.name)
        assertEquals("250", draft.ingredients[0].amount)

        val req = server.takeRequest()
        assertEquals("test-key", req.getHeader("x-goog-api-key"))
        assertTrue(req.path!!.contains("gemini-2.5-flash:generateContent"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"responseMimeType\":\"application/json\""))
        assertTrue(body.contains("\"maxOutputTokens\":1500"))
    }

    @Test
    fun throwsLlmExceptionOnHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"quota"}"""))
        try {
            extractor.extract("text")
            throw AssertionError("expected LlmException")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("429"))
            assertTrue("HTTP-Fehler muss LlmTransportException sein", e is LlmTransportException)
        }
    }

    @Test
    fun wrapsTruncatedJsonInLlmException() = runTest {
        // abgeschnittenes JSON in parts[0].text (MAX_TOKENS-Fall)
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":"{\"name\":\"Cur"}]}}]}"""
            )
        )
        try {
            extractor.extract("text")
            throw AssertionError("expected LlmException")
        } catch (e: LlmException) {
            // ok — kein roher SerializationException-Durchschlag
        }
    }
}
