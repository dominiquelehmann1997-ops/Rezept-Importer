package de.dml.rezeptimporter.link

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubeLinkResolverTest {
    private val server = MockWebServer()
    private lateinit var resolver: YouTubeLinkResolver

    @Before fun setUp() {
        server.start()
        resolver = YouTubeLinkResolver(OkHttpClient())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun fetchesDescription() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """<script>var ytInitialPlayerResponse = {"videoDetails":{"shortDescription":"Rezept: 200g Mehl\n1. Backen."}};</script>"""
            )
        )
        val text = resolver.resolve(server.url("/watch?v=abc").toString())
        assertTrue(text.contains("200g Mehl"))
        assertTrue(text.contains("Backen."))
    }

    @Test(expected = LinkResolveException::class)
    fun throwsWhenNoDescription() = runTest {
        server.enqueue(MockResponse().setBody("<html><body>Consent-Wall</body></html>"))
        resolver.resolve(server.url("/watch").toString())
    }

    @Test(expected = LinkResolveException::class)
    fun throwsOnHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("too many"))
        resolver.resolve(server.url("/watch").toString())
    }
}
