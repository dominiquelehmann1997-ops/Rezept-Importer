package de.dml.rezeptimporter.link

import de.dml.rezeptimporter.domain.ImportSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Tier 1: holt eine Rezept-Webseite und extrahiert das schema.org/Recipe-JSON-LD als Text.
 * Reine Rezept-Blogs/-Portale (Chefkoch & Co.) liefern dieses Markup zuverlässig. Social-Links
 * (TikTok/Instagram) tragen es nicht und fallen daher mit Fehlermeldung durch — die behandelt
 * Tier 2 gesondert.
 */
class WebRecipeLinkResolver(
    private val client: OkHttpClient,
) : LinkResolver {

    override suspend fun resolve(url: String): ImportSource = withContext(Dispatchers.IO) {
        val html = fetch(url)
        val text = JsonLdRecipeParser.parse(html)
            ?: throw LinkResolveException(
                "Auf dieser Seite wurden keine maschinenlesbaren Rezeptdaten gefunden.\n" +
                "Tipp: Screenshot der Zutaten/Schritte teilen."
            )
        // Portale wie Cookidoo veröffentlichen Zutaten/Nährwerte, aber keine Schritte
        // (abo-gated) — dann wenigstens die Quelle als Zubereitungsverweis mitgeben.
        val complete =
            if ("Zubereitung:" in text) text
            else "$text\n\nZubereitung:\n1. Zubereitung siehe Quelle: $url"
        ImportSource.ofText(ImportSource.LABEL_WEBPAGE, complete, sourceUrl = url)
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw LinkResolveException("Seite nicht erreichbar (HTTP ${resp.code}).")
                }
                body
            }
        } catch (e: IOException) {
            throw LinkResolveException("Seite nicht erreichbar: ${e.message}", e)
        }
    }

    private companion object {
        // Manche Portale liefern ohne Browser-UA leeres HTML.
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) RezeptImporter"
    }
}
