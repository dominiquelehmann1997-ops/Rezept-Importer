package de.dml.rezeptimporter.link

import de.dml.rezeptimporter.domain.ImportSource
import de.dml.rezeptimporter.domain.SourceVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * YouTube (inkl. Shorts): liefert die Videobeschreibung als Text und — wenn die Beschreibung
 * das Rezept offensichtlich nicht trägt — zusätzlich das Video selbst. Gemini ruft YouTube-URLs
 * direkt ab, es ist also kein Download nötig.
 *
 * Warum nicht immer das Video mitschicken: ein 20-Minuten-Kochvideo kostet ein Vielfaches an
 * Tokens gegenüber der Beschreibung, in der das Rezept bei langen Videos ohnehin meist komplett
 * steht. Bei Shorts ist die Beschreibung dagegen typischerweise leer oder eine Hashtag-Wand —
 * genau dann lohnt das Video.
 */
class YouTubeLinkResolver(
    private val client: OkHttpClient,
) : LinkResolver {

    override suspend fun resolve(url: String): ImportSource = withContext(Dispatchers.IO) {
        val description = runCatching { YouTubeDescriptionParser.parse(fetch(url)) }.getOrNull()
        val source = ImportSource(sourceUrl = url)
            .plusText(ImportSource.LABEL_VIDEO_DESCRIPTION, description.orEmpty())
        if (carriesRecipe(description)) source
        else source.copy(video = SourceVideo.Remote(url))
    }

    /**
     * Grobe Schwelle: erst ab einer gewissen Länge kann eine Beschreibung Zutaten *und* Schritte
     * enthalten. Kürzeres ist Kanal-Boilerplate — dann entscheidet das Video.
     */
    private fun carriesRecipe(description: String?): Boolean =
        (description?.length ?: 0) >= MIN_DESCRIPTION_CHARS

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

    internal companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) RezeptImporter"

        /** Unterhalb dieser Länge gilt die Beschreibung als nicht rezepttragend. */
        const val MIN_DESCRIPTION_CHARS = 400
    }
}
