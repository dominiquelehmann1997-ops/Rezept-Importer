package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.ImportSource
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.domain.SourceVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GeminiExtractor(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val model: String = "gemini-2.5-flash",
    private val uploader: GeminiFileUploader = GeminiFileUploader(apiKey, client, baseUrl),
) : LlmExtractor {

    // Gemini responseSchema = OpenAPI-Subset, Typen GROSS, kein additionalProperties.
    private val responseSchema = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("name") { put("type", "STRING") }
            putJsonObject("tags") {
                put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") }
            }
            putJsonObject("servings") { put("type", "INTEGER") }
            putJsonObject("prepMinutes") { put("type", "INTEGER") }
            putJsonObject("cookMinutes") { put("type", "INTEGER") }
            putJsonObject("ingredients") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "STRING") }
                        putJsonObject("amount") { put("type", "STRING") }
                        putJsonObject("unit") { put("type", "STRING") }
                        putJsonObject("freshness") {
                            put("type", "STRING")
                            putJsonArray("enum") { add("frisch"); add("haltbar") }
                        }
                    }
                    putJsonArray("required") { add("name") }
                }
            }
            putJsonObject("steps") {
                put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") }
            }
            putJsonObject("nutrition") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("basis") { put("type", "STRING") }
                    putJsonObject("kcal") { put("type", "INTEGER") }
                    putJsonObject("protein") { put("type", "NUMBER") }
                    putJsonObject("carbs") { put("type", "NUMBER") }
                    putJsonObject("fat") { put("type", "NUMBER") }
                }
            }
        }
        putJsonArray("required") { add("name"); add("ingredients"); add("steps") }
    }

    override val supportsVideo: Boolean = true

    override suspend fun extract(source: ImportSource, repairHint: String?): RecipeDraft {
        // Upload vor dem IO-Block: er ist selbst suspend (Polling bis ACTIVE) und liefert
        // bei mehreren Calls dieselbe URI aus dem Cache.
        val uploadedVideo = videoPart(source.video)
        return withContext(Dispatchers.IO) {
            try {
                doExtract(source, uploadedVideo, repairHint)
            } catch (e: LlmException) {
                throw e
            } catch (e: IOException) {
                throw LlmTransportException("Gemini nicht erreichbar: ${e.message}", e)
            } catch (e: Exception) {
                throw LlmException("Gemini-Aufruf fehlgeschlagen: ${e.message}", e)
            }
        }
    }

    /**
     * Lokale Datei → Files-API-Upload; YouTube-URL → direkt referenzieren, Gemini ruft sie
     * selbst ab (mimeType entfällt dabei).
     */
    private suspend fun videoPart(video: SourceVideo?): JsonObject? = when (video) {
        null -> null
        is SourceVideo.Remote -> buildJsonObject {
            putJsonObject("fileData") { put("fileUri", video.url) }
        }
        is SourceVideo.Local -> {
            val uri = uploader.uploadedUri(video.file, video.mimeType)
            buildJsonObject {
                putJsonObject("fileData") {
                    put("mimeType", video.mimeType)
                    put("fileUri", uri)
                }
            }
        }
    }

    private fun doExtract(
        source: ImportSource,
        videoPart: JsonObject?,
        repairHint: String?,
    ): RecipeDraft {
        val body = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    putJsonArray("parts") {
                        // Video zuerst: Gemini verarbeitet Medien vor der Anweisung stabiler.
                        videoPart?.let { add(it) }
                        addJsonObject {
                            put("text", ExtractionPrompt.INSTRUCTION + "\n\n" +
                                ExtractionPrompt.userMessage(source, repairHint))
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", responseSchema)
                put("maxOutputTokens", ExtractionPrompt.MAX_OUTPUT_TOKENS)
                // gemini-2.5-flash denkt per Default (dynamic thinking) und verbraucht
                // damit das maxOutputTokens-Budget -> JSON wird abgeschnitten. Aus.
                putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw LlmTransportException("Gemini HTTP ${resp.code}: ${text.take(300)}")
            }
            val root = Json.parseToJsonElement(text).jsonObject
            val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            val finishReason = candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
            if (finishReason == "MAX_TOKENS") {
                throw LlmException("Rezept zu lang fuer das Token-Budget (abgeschnitten). " +
                    "Weniger Text teilen oder maxOutputTokens erhoehen.")
            }
            val payload = candidate
                ?.get("content")?.jsonObject
                ?.get("parts")?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?: throw LlmException("Gemini-Antwort ohne candidates/parts/text")
            RecipeJsonMapper.fromJson(Json.parseToJsonElement(payload).jsonObject)
        }
    }
}
