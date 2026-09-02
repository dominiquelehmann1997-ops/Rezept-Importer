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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DashboardClientTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.shutdown()

    private fun client(server: MockWebServer) = DashboardClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        token = "geheim",
        cfClientId = "cf-id",
        cfClientSecret = "cf-secret",
        client = OkHttpClient(),
    )

    @Test
    fun `parse schickt Text und Header, liest Rezept`() = runBlocking {
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

        val dashboardClient = client(server)
        val start = dashboardClient.startParse("roher text", null) as StartResult.Started
        val draft = (dashboardClient.pollJob(start.jobId) as JobResult.Done).draft

        val request = server.takeRequest()
        assertEquals("/api/recipes/parse", request.path)
        assertEquals("Bearer geheim", request.getHeader("Authorization"))
        assertEquals("cf-id", request.getHeader("CF-Access-Client-Id"))
        assertTrue(request.body.readUtf8().contains("roher text"))
        assertEquals("Linsen-Dal", draft.name)
        assertEquals(4, draft.servings)
        assertEquals(55, draft.nutrition?.carbs?.toInt())
    }

    @Test
    fun `startParse gibt die Job-Id zurueck und schickt async`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"jobId":"j1"}"""))

        val result = client(server).startParse("roher text", null)

        assertEquals(StartResult.Started("j1"), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.body.readUtf8().contains("\"async\":true"))
    }

    @Test
    fun `startParse nimmt ein direkt geliefertes Rezept an`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"recipe":{"name":"Dal","steps":[],"ingredients":[]}}""")
        )

        val result = client(server).startParse("x", null)

        assertTrue(result is StartResult.Immediate)
        assertEquals("Dal", (result as StartResult.Immediate).draft.name)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `startParse wirft ohne Job-Id und ohne Rezept`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        val e = runCatching { client(server).startParse("x", null) }.exceptionOrNull()

        assertTrue(e is DashboardException)
    }

    @Test
    fun `pollJob meldet pending, done, error und weg`() = runBlocking {
        val c = client(server)

        server.enqueue(MockResponse().setBody("""{"ok":true,"status":"pending"}"""))
        assertEquals(JobResult.Pending, c.pollJob("j1"))

        server.enqueue(
            MockResponse().setBody("""{"ok":true,"status":"done","recipe":{"name":"Dal","steps":[],"ingredients":[]}}""")
        )
        assertEquals("Dal", (c.pollJob("j1") as JobResult.Done).draft.name)

        server.enqueue(MockResponse().setBody("""{"ok":true,"status":"error","error":"kein Rezept"}"""))
        assertEquals("kein Rezept", (c.pollJob("j1") as JobResult.Failed).message)

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"ok":false,"error":"weg"}"""))
        assertEquals(JobResult.Gone, c.pollJob("j1"))
    }

    @Test
    fun `pollJob haengt die Job-Id an die Adresse`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"status":"pending"}"""))

        client(server).pollJob("j-42")

        assertTrue(server.takeRequest().path!!.contains("job=j-42"))
    }

    @Test
    fun `Fehlermeldung des Servers landet in der Exception`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(502)
                .setBody("""{"ok":false,"error":"Aus dem Text liess sich kein Rezept lesen."}""")
        )

        val e = runCatching { client(server).startParse("murks", null) }.exceptionOrNull()
        assertTrue(e?.message!!.contains("kein Rezept lesen"))
    }

    /** JSON-Body der zuletzt aufgenommenen Anfrage, als "recipe"-Objekt. */
    private fun MockWebServer.lastRecipeBody(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(takeRequest().body.readUtf8()).jsonObject["recipe"]!!.jsonObject

    @Test
    fun `save schickt Rezept und liest Ergebnis`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"abc","name":"Linsen-Dal","updated":true}"""))

        val result = client(server).save(RecipeDraft(name = "Linsen-Dal"))

        val request = server.takeRequest()
        assertEquals("/api/recipes/import", request.path)
        assertEquals("Bearer geheim", request.getHeader("Authorization"))
        assertEquals("abc", result.id)
        assertEquals("Linsen-Dal", result.name)
        assertTrue(result.updated)
    }

    @Test
    fun `Vegetarisch-Schalter an haengt den Tag an`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"x","name":"x","updated":false}"""))

        client(server).save(RecipeDraft(name = "Curry", tags = listOf("curry"), vegetarian = true))

        val tags = server.lastRecipeBody()["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("curry", "vegetarisch"), tags)
    }

    @Test
    fun `Vegetarisch-Schalter aus entfernt den Tag, auch bei anderer Gross-Kleinschreibung`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"x","name":"x","updated":false}"""))

        client(server).save(
            RecipeDraft(name = "Gulasch", tags = listOf("Vegetarisch", "herzhaft"), vegetarian = false)
        )

        val tags = server.lastRecipeBody()["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("herzhaft"), tags)
    }

    @Test
    fun `Ohne Cloudflare-Zugangsdaten bleiben die CF-Header weg`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"x","name":"x","updated":false}"""))

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
    }

    @Test
    fun `imageUrl aus parse ueberlebt den Roundtrip in den save-Body`() = runBlocking {
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

        val dashboardClient = client(server)
        val start = dashboardClient.startParse("roher text", null) as StartResult.Started
        val draft = (dashboardClient.pollJob(start.jobId) as JobResult.Done).draft
        assertEquals("https://example.com/dal.jpg", draft.imageUrl)

        server.takeRequest() // Start-Request (POST) verbrauchen
        server.takeRequest() // Poll-Request (GET) verbrauchen, bevor der save-Body gelesen wird
        dashboardClient.save(draft)
        val recipe = server.lastRecipeBody()
        assertEquals("https://example.com/dal.jpg", recipe["imageUrl"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `Zutaten-Gruppe (section) landet im save-Body`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"x","name":"x","updated":false}"""))

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
    }

    @Test
    fun `slug und sourceUrl gehen als slug und source raus`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"x","name":"x","updated":false}"""))

        client(server).save(
            RecipeDraft(name = "Dal", slug = "linsen-dal", sourceUrl = "https://example.com/dal")
        )

        val recipe = server.lastRecipeBody()
        assertEquals("linsen-dal", recipe["slug"]?.jsonPrimitive?.contentOrNull)
        assertEquals("https://example.com/dal", recipe["source"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `parse liest die Kategorie, save schickt sie zurueck`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"jobId":"j1"}"""))
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"status":"done","recipe":{"name":"Kekse","category":"suesses","steps":[],"ingredients":[]}}""")
        )

        val start = client(server).startParse("x", null) as StartResult.Started
        val draft = (client(server).pollJob(start.jobId) as JobResult.Done).draft
        assertEquals("suesses", draft.category)

        server.enqueue(MockResponse().setBody("""{"ok":true,"id":"1","name":"Kekse"}"""))
        client(server).save(draft)
        server.takeRequest(); server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("\"category\":\"suesses\""))
    }

    @Test
    fun `unbekannte Kategorie wird zur Hauptmahlzeit`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"ok":true,"jobId":"j1"}"""))
        server.enqueue(
            MockResponse().setBody("""{"ok":true,"status":"done","recipe":{"name":"X","category":"nachtisch","steps":[],"ingredients":[]}}""")
        )

        val start = client(server).startParse("x", null) as StartResult.Started
        assertEquals("hauptmahlzeit", (client(server).pollJob(start.jobId) as JobResult.Done).draft.category)
    }
}
