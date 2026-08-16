package de.dml.rezeptimporter.link

import de.dml.rezeptimporter.domain.ImportSource
import de.dml.rezeptimporter.domain.SourceVideo
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun playerResponse(description: String) =
        """<script>var ytInitialPlayerResponse = {"videoDetails":{"shortDescription":"$description"}};</script>"""

    /** Beschreibung lang genug, um Zutaten und Schritte zu tragen. */
    private fun longDescription() =
        "Rezept: 200g Mehl, 3 Eier, 100 ml Milch. " + "Schritt für Schritt erklärt. ".repeat(20)

    @Test fun fetchesDescriptionAsTextSource() = runTest {
        server.enqueue(MockResponse().setBody(playerResponse(longDescription())))
        val source = resolver.resolve(server.url("/watch?v=abc").toString())
        val text = source.texts.single()
        assertEquals(ImportSource.LABEL_VIDEO_DESCRIPTION, text.label)
        assertTrue(text.text.contains("200g Mehl"))
    }

    @Test fun keepsTheLinkAsSourceUrl() = runTest {
        server.enqueue(MockResponse().setBody(playerResponse(longDescription())))
        val url = server.url("/watch?v=abc").toString()
        assertEquals(url, resolver.resolve(url).sourceUrl)
    }

    @Test fun skipsTheVideoWhenTheDescriptionAlreadyCarriesTheRecipe() = runTest {
        // Lange Beschreibung = Rezept steht im Text; das Video mitzuschicken wäre nur teurer.
        server.enqueue(MockResponse().setBody(playerResponse(longDescription())))
        assertNull(resolver.resolve(server.url("/watch?v=abc").toString()).video)
    }

    @Test fun attachesTheVideoWhenTheDescriptionIsThin() = runTest {
        // Typischer Short: Hashtag-Wand statt Rezept — hier muss das Video ausgewertet werden.
        server.enqueue(MockResponse().setBody(playerResponse("#shorts #food #rezept")))
        val url = server.url("/shorts/abc").toString()
        val source = resolver.resolve(url)
        assertEquals(SourceVideo.Remote(url), source.video)
    }

    @Test fun attachesTheVideoWhenThereIsNoDescriptionAtAll() = runTest {
        server.enqueue(MockResponse().setBody("<html><body>Consent-Wall</body></html>"))
        val url = server.url("/watch").toString()
        assertEquals(SourceVideo.Remote(url), resolver.resolve(url).video)
    }

    @Test fun fallsBackToTheVideoWhenTheScrapeFails() = runTest {
        // Der Scrape ist nur die Abkürzung — das Video ruft Gemini selbst ab, ein HTTP-Fehler
        // beim Seitenabruf darf den Import deshalb nicht killen.
        server.enqueue(MockResponse().setResponseCode(429).setBody("too many"))
        val url = server.url("/watch").toString()
        val source = resolver.resolve(url)
        assertEquals(SourceVideo.Remote(url), source.video)
        assertTrue(source.nonEmptyTexts.isEmpty())
    }
}
