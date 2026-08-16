package de.dml.rezeptimporter.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Lädt eine Videodatei zur Gemini Files API hoch und liefert die `file_uri` für `generateContent`.
 *
 * Warum überhaupt Upload statt Inline-Bytes: inline sind Requests auf ~20 MB begrenzt, ein Reel
 * liegt schnell darüber. Wichtiger noch — die Pipeline macht pro Import bis zu drei Calls
 * (Extraktion, Repair-Retry, Übersetzungs-Pass). Inline würde das Video jedes Mal erneut
 * hochladen. Deshalb cacht diese Klasse die URI pro Datei: einmal hoch, dreimal referenziert.
 */
class GeminiFileUploader(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
) {

    private val mutex = Mutex()
    private val uploaded = mutableMapOf<String, String>()

    /** Hochgeladene URI der Datei — beim zweiten Aufruf aus dem Cache. */
    suspend fun uploadedUri(file: File, mimeType: String): String = mutex.withLock {
        uploaded.getOrPut(file.absolutePath) { upload(file, mimeType) }
    }

    private suspend fun upload(file: File, mimeType: String): String {
        if (!file.isFile) throw LlmException("Videodatei nicht gefunden: ${file.name}")
        if (file.length() > MAX_BYTES) {
            throw LlmException(
                "Video ist zu groß (${file.length() / 1_000_000} MB, max. ${MAX_BYTES / 1_000_000} MB). " +
                    "Kürzeren Ausschnitt teilen."
            )
        }

        val uploadUrl = startResumable(file, mimeType)
        val name = finishUpload(uploadUrl, file, mimeType)
        return awaitActive(name)
    }

    /** Schritt 1: Upload anmelden, Ziel-URL kommt im Response-Header zurück. */
    private fun startResumable(file: File, mimeType: String): String {
        val metadata = buildJsonObject {
            putJsonObject("file") { put("display_name", file.name) }
        }
        val request = Request.Builder()
            .url("$baseUrl/upload/v1beta/files")
            .header("x-goog-api-key", apiKey)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mimeType)
            .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw LlmTransportException(
                    "Video-Upload konnte nicht gestartet werden (HTTP ${resp.code}): " +
                        (resp.body?.string()?.take(300) ?: "")
                )
            }
            resp.header("X-Goog-Upload-URL")
                ?: throw LlmTransportException("Video-Upload: Antwort ohne Upload-URL")
        }
    }

    /** Schritt 2: Bytes schicken und finalisieren. Liefert den Ressourcennamen (`files/…`). */
    private fun finishUpload(uploadUrl: String, file: File, mimeType: String): String {
        val request = Request.Builder()
            .url(uploadUrl)
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(file.asRequestBody(mimeType.toMediaType()))
            .build()

        return client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw LlmTransportException("Video-Upload fehlgeschlagen (HTTP ${resp.code}): ${body.take(300)}")
            }
            fileField(body, "name")
                ?: throw LlmTransportException("Video-Upload: Antwort ohne Dateinamen")
        }
    }

    /**
     * Schritt 3: Gemini verarbeitet das Video asynchron. Vor `state == ACTIVE` schlägt jede
     * Referenz auf die Datei fehl, also hier warten.
     */
    private suspend fun awaitActive(name: String): String {
        repeat(MAX_POLLS) {
            val body = getFile(name)
            when (fileField(body, "state")) {
                "ACTIVE" -> return fileUri(body)
                "FAILED" -> throw LlmException(
                    "Gemini konnte das Video nicht verarbeiten — anderes Format oder beschädigte Datei."
                )
            }
            delay(POLL_INTERVAL_MS)
        }
        throw LlmTransportException(
            "Video-Verarbeitung dauert zu lange (über ${MAX_POLLS * POLL_INTERVAL_MS / 1000} s). " +
                "Kürzeres Video versuchen."
        )
    }

    private fun getFile(name: String): String {
        val request = Request.Builder()
            .url("$baseUrl/v1beta/$name")
            .header("x-goog-api-key", apiKey)
            .get()
            .build()
        return client.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw LlmTransportException("Video-Status nicht abrufbar (HTTP ${resp.code})")
            }
            body
        }
    }

    private fun fileUri(body: String): String =
        fileField(body, "uri") ?: throw LlmTransportException("Video-Status: Antwort ohne uri")

    /**
     * Die Antworten sind mal `{"file": {...}}` (Upload) und mal das Objekt direkt (Status-GET) —
     * beide Formen hier abfangen.
     */
    private fun fileField(body: String, field: String): String? = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        val obj = root["file"]?.jsonObject ?: root
        obj[field]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Reels liegen weit darunter; die Grenze fängt versehentlich geteilte Langvideos ab. */
        const val MAX_BYTES = 100L * 1024 * 1024
        const val POLL_INTERVAL_MS = 1500L
        const val MAX_POLLS = 40
    }
}
