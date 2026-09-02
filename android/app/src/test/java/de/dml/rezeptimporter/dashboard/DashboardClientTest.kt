package de.dml.rezeptimporter.dashboard

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardClientTest {

    private fun client(server: MockWebServer) = DashboardClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        token = "geheim",
        cfClientId = "cf-id",
        cfClientSecret = "cf-secret",
        client = OkHttpClient(),
        pollDelayMs = 0,
    )

    @Test
    fun `parse schickt Text und Header, liest Rezept`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"jobId":"j1"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"status":"done","recipe":{"slug":"dal","name":"Linsen-Dal","rating":"ok",
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
    fun `parse startet einen Job und pollt bis done`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"jobId":"j1"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true,"status":"pending"}"""))
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"status":"done","recipe":{"name":"Dal","steps":["kochen"],"ingredients":[]}}""")
        )
        server.start()

        val draft = client(server).parse("roher text", null)

        assertEquals("Dal", draft.name)
        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertTrue(start.body.readUtf8().contains("\"async\":true"))
        assertTrue(server.takeRequest().path!!.contains("job=j1"))
        server.shutdown()
    }

    @Test
    fun `parse nimmt ein Rezept direkt aus der Startantwort, wenn jobId fehlt`() = runBlocking {
        // Server ohne asynchronen Modus: ignoriert `async`, antwortet nach dem
        // vollen Import direkt mit 200 {"ok":true,"recipe":{...}}. Reihenfolge-
        // unabhaengig heisst hier: das Rezept sofort nehmen, nicht pollen.
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"recipe":{"name":"Dal","steps":["kochen"],"ingredients":[]}}"""
            )
        )
        server.start()

        val draft = client(server).parse("roher text", null)

        assertEquals("Dal", draft.name)
        assertEquals(1, server.requestCount) // kein Poll-Request hinterher
        server.shutdown()
    }

    @Test
    fun `parse macht aus einem Job-Fehler eine DashboardException`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"jobId":"j1"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true,"status":"error","error":"kein Rezept"}"""))
        server.start()

        val e = runCatching { client(server).parse("x", null) }.exceptionOrNull()
        assertTrue(e is DashboardException)
        assertTrue(e?.message!!.contains("kein Rezept"))
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

    /** JSON-Body der zuletzt aufgenommenen Anfrage, als "recipe"-Objekt. */
    private fun MockWebServer.lastRecipeBody(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(takeRequest().body.readUtf8()).jsonObject["recipe"]!!.jsonObject

    @Test
    fun `save schickt Rezept und liest Ergebnis`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"abc","name":"Linsen-Dal","updated":true}"""))
        server.start()

        val result = client(server).save(RecipeDraft(name = "Linsen-Dal"))

        val request = server.takeRequest()
        assertEquals("/api/recipes/import", request.path)
        assertEquals("Bearer geheim", request.getHeader("Authorization"))
        assertEquals("abc", result.id)
        assertEquals("Linsen-Dal", result.name)
        assertTrue(result.updated)
        server.shutdown()
    }

    @Test
    fun `Vegetarisch-Schalter an haengt den Tag an`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"x","name":"x","updated":false}"""))
        server.start()

        client(server).save(RecipeDraft(name = "Curry", tags = listOf("curry"), vegetarian = true))

        val tags = server.lastRecipeBody()["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("curry", "vegetarisch"), tags)
        server.shutdown()
    }

    @Test
    fun `Vegetarisch-Schalter aus entfernt den Tag, auch bei anderer Gross-Kleinschreibung`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"x","name":"x","updated":false}"""))
        server.start()

        client(server).save(
            RecipeDraft(name = "Gulasch", tags = listOf("Vegetarisch", "herzhaft"), vegetarian = false)
        )

        val tags = server.lastRecipeBody()["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("herzhaft"), tags)
        server.shutdown()
    }

    @Test
    fun `Ohne Cloudflare-Zugangsdaten bleiben die CF-Header weg`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"x","name":"x","updated":false}"""))
        server.start()

        val lanClient = DashboardClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "geheim",
            cfClientId = "",
            cfClientSecret = "",
            client = OkHttpClient(),
        )
        lanClient.save(RecipeDraft(name = "Curry"))

        val request = server.takeRequest()
        assertNull(request.getHeader("CF-Access-Client-Id"))
        assertNull(request.getHeader("CF-Access-Client-Secret"))
        server.shutdown()
    }

    @Test
    fun `imageUrl aus parse ueberlebt den Roundtrip in den save-Body`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"jobId":"j1"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"ok":true,"status":"done","recipe":{"slug":"dal","name":"Linsen-Dal","rating":"ok",
                   "simple":true,"reheatable":false,"tags":[],"source":null,
                   "imageUrl":"https://example.com/dal.jpg","servings":null,"prepMinutes":null,
                   "cookMinutes":null,"ingredients":[{"name":"Linsen"}],"steps":["Kochen."]}}"""
            )
        )
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"x","name":"x","updated":false}"""))
        server.start()

        val dashboardClient = client(server)
        val draft = dashboardClient.parse("roher text", null)
        assertEquals("https://example.com/dal.jpg", draft.imageUrl)

        server.takeRequest() // Start-Request (POST) verbrauchen
        server.takeRequest() // Poll-Request (GET) verbrauchen, bevor der save-Body gelesen wird
        dashboardClient.save(draft)
        val recipe = server.lastRecipeBody()
        assertEquals("https://example.com/dal.jpg", recipe["imageUrl"]?.jsonPrimitive?.contentOrNull)
        server.shutdown()
    }

    @Test
    fun `Zutaten-Gruppe (section) landet im save-Body`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"x","name":"x","updated":false}"""))
        server.start()

        client(server).save(
            RecipeDraft(
                name = "Dal",
                ingredients = listOf(
                    IngredientDraft(name = "Skyr", amount = "150", unit = "g", section = "Dip"),
                ),
            )
        )

        val ingredient = server.lastRecipeBody()["ingredients"]!!.jsonArray[0].jsonObject
        assertEquals("Dip", ingredient["section"]?.jsonPrimitive?.contentOrNull)
        server.shutdown()
    }

    @Test
    fun `slug und sourceUrl gehen als slug und source raus`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"x","name":"x","updated":false}"""))
        server.start()

        client(server).save(
            RecipeDraft(name = "Dal", slug = "linsen-dal", sourceUrl = "https://example.com/dal")
        )

        val recipe = server.lastRecipeBody()
        assertEquals("linsen-dal", recipe["slug"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/dal", recipe["source"]?.jsonPrimitive?.contentOrNull)
        server.shutdown()
    }

    @Test
    fun `parse liest die Kategorie, save schickt sie zurueck`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"jobId":"j1"}"""))
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"status":"done","recipe":{"name":"Kekse","category":"suesses","steps":[],"ingredients":[]}}""")
        )
        server.start()

        val draft = client(server).parse("x", null)
        assertEquals("suesses", draft.category)

        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"1","name":"Kekse"}"""))
        client(server).save(draft)
        server.takeRequest(); server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("\"category\":\"suesses\""))
        server.shutdown()
    }

    @Test
    fun `unbekannte Kategorie wird zur Hauptmahlzeit`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"jobId":"j1"}"""))
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"status":"done","recipe":{"name":"X","category":"nachtisch","steps":[],"ingredients":[]}}""")
        )
        server.start()

        assertEquals("hauptmahlzeit", client(server).parse("x", null).category)
        server.shutdown()
    }
}
