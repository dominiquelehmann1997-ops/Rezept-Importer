package de.dml.rezeptimporter.link

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SocialCaptionLinkResolverTest {
    private val server = MockWebServer()
    private lateinit var resolver: SocialCaptionLinkResolver

    @Before fun setUp() {
        server.start()
        resolver = SocialCaptionLinkResolver(
            client = OkHttpClient(),
            tiktokOEmbedBase = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test fun tiktokCaptionViaOEmbed() = runTest {
        server.enqueue(MockResponse().setBody("""{"title":"Curry mit Kokosmilch und 250g Reis"}"""))
        val text = resolver.resolve("https://www.tiktok.com/@koch/video/123")
        assertTrue(text.contains("Curry mit Kokosmilch"))
    }

    @Test fun instagramCaptionViaOgDescription() = runTest {
        // Host ist nicht TikTok → og:description-Pfad, holt die übergebene (Mock-)URL.
        server.enqueue(
            MockResponse().setBody(
                """<meta property="og:description" content="50 likes - koch: Lasagne mit 500g Hack">"""
            )
        )
        val text = resolver.resolve(server.url("/p/abc").toString())
        assertTrue(text.contains("Lasagne mit 500g Hack"))
    }

    @Test(expected = LinkResolveException::class)
    fun throwsWhenNoCaption() = runTest {
        server.enqueue(MockResponse().setBody("<html><body>kein og-tag, keine Caption</body></html>"))
        resolver.resolve(server.url("/p/empty").toString())
    }
}
