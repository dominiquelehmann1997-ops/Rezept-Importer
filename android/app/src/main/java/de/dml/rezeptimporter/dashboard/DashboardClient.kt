package de.dml.rezeptimporter.dashboard

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.NutritionDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.math.roundToInt

class DashboardException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class SaveResult(val id: String, val name: String, val updated: Boolean)

private const val VEGETARIAN_TAG = "vegetarisch"

// Der Import läuft serverseitig als Job (siehe Task 7): die App startet ihn und
// pollt den Status, statt an einer einzigen HTTP-Verbindung bis zu 71s zu hängen —
// gefährlich nah am ~100s-Limit der Cloudflare-Edge.
private const val MAX_POLL_MS = 150_000L

/**
 * Client für die beiden Import-Endpunkte des Haushalts-Dashboards. Die App
 * extrahiert nicht mehr selbst: `parse` schickt Rohtext (OCR, Caption) oder
 * eine Quell-URL hin und bekommt den fertigen Entwurf zurück, `save` schreibt
 * den — nach der Bearbeitung im Preview — in die Rezept-DB.
 */
class DashboardClient(
    private val baseUrl: String,
    private val token: String,
    private val cfClientId: String,
    private val cfClientSecret: String,
    private val client: OkHttpClient,
    private val pollDelayMs: Long = 2_000,
) {

    suspend fun parse(text: String, sourceUrl: String?): RecipeDraft = withContext(Dispatchers.IO) {
        val start = post("/api/recipes/parse", buildJsonObject {
            put("text", text)
            put("sourceUrl", sourceUrl)
            put("async", true)
        })
        val jobId = start["jobId"]?.jsonPrimitive?.contentOrNull
            ?: throw DashboardException("Antwort ohne Job-Id")

        val deadline = System.currentTimeMillis() + MAX_POLL_MS
        while (true) {
            val job = get("/api/recipes/parse?job=$jobId")
            when (job["status"]?.jsonPrimitive?.contentOrNull) {
                "done" -> return@withContext toDraft(
                    job["recipe"]?.jsonObject ?: throw DashboardException("Antwort ohne Rezept")
                )
                "error" -> throw DashboardException(
                    job["error"]?.jsonPrimitive?.contentOrNull ?: "Import fehlgeschlagen."
                )
            }
            if (System.currentTimeMillis() > deadline) {
                throw DashboardException("Import dauert zu lange — bitte erneut versuchen.")
            }
            delay(pollDelayMs)
        }
        @Suppress("UNREACHABLE_CODE") throw DashboardException("unerreichbar")
    }

    suspend fun save(draft: RecipeDraft): SaveResult = withContext(Dispatchers.IO) {
        val response = post("/api/recipes/import", buildJsonObject { put("recipe", toJson(draft)) })
        SaveResult(
            id = response["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = response["name"]?.jsonPrimitive?.contentOrNull ?: draft.name,
            updated = response["updated"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    // ---- HTTP ----

    private fun post(path: String, body: JsonObject): JsonObject =
        execute(
            Request.Builder()
                .url("$baseUrl$path")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
        )

    private fun get(path: String): JsonObject =
        execute(Request.Builder().url("$baseUrl$path"))

    /**
     * Gemeinsame HTTP-Logik für `post`/`get`: setzt Bearer- und Cloudflare-Access-
     * Header, schickt die Anfrage ab und wertet Fehler/JSON einheitlich aus.
     */
    private fun execute(builder: Request.Builder): JsonObject {
        builder.header("Authorization", "Bearer $token")
        // Im Heimnetz (http://192.168.178.91:3001) steht kein Cloudflare Access
        // davor — dann bleiben die Felder leer und die Header entfallen.
        if (cfClientId.isNotBlank() && cfClientSecret.isNotBlank()) {
            builder.header("CF-Access-Client-Id", cfClientId)
            builder.header("CF-Access-Client-Secret", cfClientSecret)
        }

        val raw = try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
                if (!resp.isSuccessful) {
                    val message = json?.get("error")?.jsonPrimitive?.contentOrNull
                    // Access schickt bei falschem Service-Token HTML, kein JSON.
                    throw DashboardException(
                        message ?: "Dashboard HTTP ${resp.code}: ${text.take(200)}"
                    )
                }
                json ?: throw DashboardException("Dashboard-Antwort ist kein JSON")
            }
        } catch (e: IOException) {
            throw DashboardException("Dashboard nicht erreichbar: ${e.message}", e)
        }
        return raw
    }

    // ---- Mapping ----

    private fun toDraft(r: JsonObject): RecipeDraft {
        fun int(key: String) = r[key]?.jsonPrimitive?.intOrNull
        val tags = r["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val nutrition = NutritionDraft(
            basis = "pro Portion",
            kcal = int("kcal"),
            protein = r["protein"]?.jsonPrimitive?.doubleOrNull,
            carbs = r["carbs"]?.jsonPrimitive?.doubleOrNull,
            fat = r["fat"]?.jsonPrimitive?.doubleOrNull,
        )
        return RecipeDraft(
            name = r["name"]?.jsonPrimitive?.content.orEmpty(),
            tags = tags,
            servings = int("servings"),
            prepMinutes = int("prepMinutes"),
            cookMinutes = int("cookMinutes"),
            ingredients = r["ingredients"]?.jsonArray?.map { element ->
                val i = element.jsonObject
                IngredientDraft(
                    name = i["name"]?.jsonPrimitive?.content.orEmpty(),
                    amount = i["amount"]?.jsonPrimitive?.contentOrNull,
                    unit = i["unit"]?.jsonPrimitive?.contentOrNull,
                    freshness = null,   // die DB kennt das Feld nicht mehr
                    section = i["section"]?.jsonPrimitive?.contentOrNull,
                )
            } ?: emptyList(),
            steps = r["steps"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            nutrition = nutrition.takeUnless { it.isEmpty },
            rating = r["rating"]?.jsonPrimitive?.contentOrNull ?: "ok",
            simple = r["simple"]?.jsonPrimitive?.booleanOrNull ?: true,
            reheatable = r["reheatable"]?.jsonPrimitive?.booleanOrNull ?: false,
            vegetarian = tags.any { it.equals(VEGETARIAN_TAG, ignoreCase = true) },
            slug = r["slug"]?.jsonPrimitive?.contentOrNull,
            sourceUrl = r["source"]?.jsonPrimitive?.contentOrNull,
            imageUrl = r["imageUrl"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun toJson(draft: RecipeDraft): JsonObject = buildJsonObject {
        put("slug", draft.slug)          // leer: der Server leitet ihn aus dem Namen ab
        put("name", draft.name)
        put("rating", draft.rating)
        put("simple", draft.simple)
        put("reheatable", draft.reheatable)
        // Der Vegetarisch-Schalter im Preview ist die Entscheidung des Nutzers und
        // sticht die Serverheuristik — er wirkt hier auf den Tag.
        putJsonArray("tags") {
            val rest = draft.tags.filterNot { it.equals(VEGETARIAN_TAG, ignoreCase = true) }
            (if (draft.vegetarian) rest + VEGETARIAN_TAG else rest).forEach { add(it) }
        }
        put("source", draft.sourceUrl)
        put("imageUrl", draft.imageUrl)
        put("servings", draft.servings)
        put("prepMinutes", draft.prepMinutes)
        put("cookMinutes", draft.cookMinutes)
        put("kcal", draft.nutrition?.kcal)
        put("protein", draft.nutrition?.protein?.roundToInt())
        put("carbs", draft.nutrition?.carbs?.roundToInt())
        put("fat", draft.nutrition?.fat?.roundToInt())
        putJsonArray("ingredients") {
            draft.ingredients.forEach { i ->
                addJsonObject {
                    put("name", i.name)
                    put("amount", i.amount)
                    put("unit", i.unit)
                    put("section", i.section)
                }
            }
        }
        putJsonArray("steps") { draft.steps.forEach { add(it) } }
    }
}
