package de.dml.rezeptimporter.dashboard

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardClientTest {

    private fun client(server: MockWebServer) = DashboardClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        token = "geheim",
        cfClientId = "cf-id",
        cfClientSecret = "cf-secret",
        client = OkHttpClient(),
    )

    @Test
    fun `parse schickt Text und Header, liest Rezept`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"recipe":{"slug":"dal","name":"Linsen-Dal","rating":"ok",
                   "simple":true,"reheatable":false,"tags":["vegetarisch"],"source":null,
                   "imageUrl":null,"servings":4,"prepMinutes":10,"cookMinutes":25,
                   "kcal":420,"protein":18,"carbs":55,"fat":9,
                   "ingredients":[{"name":"Rote Linsen","amount":"200","unit":"g","section":null}],
                   "steps":["Linsen waschen."]}}"""
            )
        )
        server.start()

        val draft = client(server).parse("roher text", null)

        val request = server.takeRequest()
        assertEquals("/api/recipes/parse", request.path)
        assertEquals("Bearer geheim", request.getHeader("Authorization"))
        assertEquals("cf-id", request.getHeader("CF-Access-Client-Id"))
        assertTrue(request.body.readUtf8().contains("roher text"))
        assertEquals("Linsen-Dal", draft.name)
        assertEquals(4, draft.servings)
        assertEquals(55, draft.nutrition?.carbs?.toInt())
        server.shutdown()
    }

    @Test
    fun `Fehlermeldung des Servers landet in der Exception`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(502)
                .setBody("""{"ok":false,"error":"Aus dem Text liess sich kein Rezept lesen."}""")
        )
        server.start()

        val e = runCatching { client(server).parse("murks", null) }.exceptionOrNull()
        assertTrue(e?.message!!.contains("kein Rezept lesen"))
        server.shutdown()
    }
}
