package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.ImportSource
import de.dml.rezeptimporter.domain.SourceText
import de.dml.rezeptimporter.domain.SourceVideo
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
    fun referencesYouTubeVideoDirectlyWithoutUploading() = runTest {
        // Gemini ruft YouTube-URLs selbst ab — ein Upload wäre unnötig und würde scheitern.
        val recipeJson = """{"name":"Curry","ingredients":[{"name":"Reis"}],"steps":["Kochen."]}"""
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":${recipeJson.let { "\"" + it.replace("\"", "\\\"") + "\"" }}}]}}]}"""
            )
        )

        val source = src("caption").copy(video = SourceVideo.Remote("https://youtu.be/abc"))
        extractor.extract(source)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("fileData"))
        assertTrue(body.contains("https://youtu.be/abc"))
        // Genau ein Request: kein Upload-Roundtrip für Remote-Videos.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sendsBothCaptionAndVideoInTheSameRequest() = runTest {
        val recipeJson = """{"name":"Curry","ingredients":[{"name":"Reis"}],"steps":["Kochen."]}"""
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":${recipeJson.let { "\"" + it.replace("\"", "\\\"") + "\"" }}}]}}]}"""
            )
        )

        val source = ImportSource(
            texts = listOf(SourceText(ImportSource.LABEL_CAPTION, "200 g Reis")),
            video = SourceVideo.Remote("https://youtu.be/abc"),
        )
        extractor.extract(source)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("https://youtu.be/abc"))
        assertTrue(body.contains("200 g Reis"))
        assertTrue(body.contains(ImportSource.LABEL_CAPTION))
    }

    @Test
    fun parsesRecipeFromJsonModeResponse() = runTest {
        // Gemini liefert das Rezept-JSON als String in parts[0].text
        val recipeJson = """{"name":"Curry","ingredients":[{"name":"Reis","amount":"250","unit":"g"}],"steps":["Kochen."]}"""
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":${recipeJson.let { "\"" + it.replace("\"", "\\\"") + "\"" }}}]}}]}"""
            )
        )

        val draft = extractor.extract(src("roher text"))

        assertEquals("Curry", draft.name)
        assertEquals("250", draft.ingredients[0].amount)

        val req = server.takeRequest()
        assertEquals("test-key", req.getHeader("x-goog-api-key"))
        assertTrue(req.path!!.contains("gemini-2.5-flash:generateContent"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"responseMimeType\":\"application/json\""))
        assertTrue(body.contains("\"maxOutputTokens\":4096"))
        assertTrue(body.contains("\"thinkingBudget\":0"))
    }

    @Test
    fun throwsLlmExceptionOnHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"quota"}"""))
        try {
            extractor.extract(src("text"))
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
            extractor.extract(src("text"))
            throw AssertionError("expected LlmException")
        } catch (e: LlmException) {
            // ok — kein roher SerializationException-Durchschlag
        }
    }
}
