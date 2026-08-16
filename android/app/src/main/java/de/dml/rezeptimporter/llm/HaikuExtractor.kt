package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.ImportSource
import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class HaikuExtractor(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.anthropic.com",
    private val model: String = "claude-haiku-4-5",
) : LlmExtractor {

    override suspend fun extract(source: ImportSource, repairHint: String?): RecipeDraft =
        withContext(Dispatchers.IO) {
            // Der Aufrufer wählt bei Video-Quellen bereits Gemini; das hier ist die Absicherung,
            // damit ein Video nicht stillschweigend unter den Tisch fällt.
            if (source.video != null) {
                throw LlmException(
                    "Video-Import läuft nur über Gemini — in den Einstellungen einen Gemini-Key eintragen."
                )
            }
            try {
                doExtract(source, repairHint)
            } catch (e: LlmException) {
                throw e
            } catch (e: IOException) {
                throw LlmTransportException("Anthropic nicht erreichbar: ${e.message}", e)
            } catch (e: Exception) {
                throw LlmException("Anthropic-Aufruf fehlgeschlagen: ${e.message}", e)
            }
        }

    private fun doExtract(source: ImportSource, repairHint: String?): RecipeDraft {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", ExtractionPrompt.MAX_OUTPUT_TOKENS)
            put("system", ExtractionPrompt.INSTRUCTION)
            putJsonArray("tools") {
                addJsonObject {
                    put("name", "save_recipe")
                    put("description", "Speichert das aus dem Text extrahierte Rezept strukturiert ab.")
                    put("input_schema", Json.parseToJsonElement(ExtractionPrompt.SCHEMA_JSON))
                }
            }
            putJsonObject("tool_choice") { put("type", "tool"); put("name", "save_recipe") }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", ExtractionPrompt.userMessage(source, repairHint))
                }
            }
        }

        val request = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw LlmTransportException("Anthropic HTTP ${resp.code}: ${text.take(300)}")
            }
            val content = Json.parseToJsonElement(text).jsonObject["content"]?.jsonArray
                ?: throw LlmException("Anthropic-Antwort ohne content[]")
            val toolUse = content.firstOrNull {
                it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
            }?.jsonObject
                ?: throw LlmException("Anthropic-Antwort ohne tool_use-Block")
            return RecipeJsonMapper.fromJson(toolUse["input"]!!.jsonObject)
        }
    }
}
