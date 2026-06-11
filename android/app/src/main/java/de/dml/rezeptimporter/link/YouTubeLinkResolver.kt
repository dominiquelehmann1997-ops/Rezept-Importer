package de.dml.rezeptimporter.link

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Tier 2b: holt die YouTube-Videobeschreibung (oft das volle Rezept) als Text für die LLM-Pipeline.
 * Kein Video, kein API-Key — Scrape von `videoDetails.shortDescription` aus der Watch-Seite.
 * Steht das Rezept nur im gesprochenen/eingeblendeten Video, gibt es keine Textquelle → Fehler
 * (Video-Transkription bewusst zurückgestellt, siehe phase3-video-transkription.md).
 */
class YouTubeLinkResolver(
    private val client: OkHttpClient,
) : LinkResolver {

    override suspend fun resolve(url: String): String = withContext(Dispatchers.IO) {
        val html = fetch(url)
        YouTubeDescriptionParser.parse(html)
            ?: throw LinkResolveException(
                "Keine Videobeschreibung gefunden — das Rezept steht vermutlich nur im Video.\n" +
                "Tipp: Beschreibung kopieren oder Screenshot teilen."
            )
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Cookie", "CONSENT=YES+")        // EU-Consent-Interstitial überspringen
            .header("Accept-Language", "de,en;q=0.8")
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw LinkResolveException("YouTube nicht erreichbar (HTTP ${resp.code}).")
                }
                body
            }
        } catch (e: IOException) {
            throw LinkResolveException("YouTube nicht erreichbar: ${e.message}", e)
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) RezeptImporter"
    }
}
