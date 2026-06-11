package de.dml.rezeptimporter.link

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebRecipeLinkResolverTest {
    private val server = MockWebServer()
    private lateinit var resolver: WebRecipeLinkResolver

    @Before fun setUp() {
        server.start()
        resolver = WebRecipeLinkResolver(OkHttpClient())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun fetchesAndExtractsRecipeText() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                <html><head><script type="application/ld+json">
                {"@type":"Recipe","name":"Risotto","recipeIngredient":["Reis"],"recipeInstructions":"Rühren."}
                </script></head></html>
                """.trimIndent()
            )
        )

        val text = resolver.resolve(server.url("/rezept").toString())
        assertTrue(text.contains("Risotto"))
        assertTrue(text.contains("Reis"))
    }

    @Test(expected = LinkResolveException::class)
    fun throwsWhenPageHasNoRecipe() = runTest {
        server.enqueue(MockResponse().setBody("<html><body>nur ein Reel-Embed</body></html>"))
        resolver.resolve(server.url("/reel").toString())
    }

    @Test(expected = LinkResolveException::class)
    fun throwsOnHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        resolver.resolve(server.url("/missing").toString())
    }
}
