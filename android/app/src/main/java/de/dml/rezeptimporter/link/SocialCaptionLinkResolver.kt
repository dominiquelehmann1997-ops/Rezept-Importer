package de.dml.rezeptimporter.link

import de.dml.rezeptimporter.domain.ImportSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/**
 * Tier 2: holt die Caption eines TikTok-/Instagram-Links als Text für die LLM-Pipeline.
 *
 * - TikTok: öffentlicher oEmbed-Endpunkt (`title` = Caption); fällt bei Blockade auf og:description zurück.
 * - Instagram: kein nutzbarer öffentlicher oEmbed → og:description der Seite (best effort, bricht ohne
 *   Login auf manchen Beiträgen weg).
 *
 * Das Video selbst ist über den Link nicht erreichbar (Download wäre ToS-grau und bräuchte ein
 * Backend). Die Caption wird deshalb geparkt: teilt der Nutzer danach die gespeicherte Videodatei,
 * gehen Caption und Video gemeinsam in denselben LLM-Call.
 */
class SocialCaptionLinkResolver(
    private val client: OkHttpClient,
    private val tiktokOEmbedBase: String = "https://www.tiktok.com",
) : LinkResolver {

    override suspend fun resolve(url: String): ImportSource = withContext(Dispatchers.IO) {
        val caption = if (LinkHosts.isTikTok(url)) {
            tiktokCaption(url) ?: ogCaption(url)
        } else {
            ogCaption(url)
        }
        val text = caption?.trim()?.ifBlank { null }
            ?: throw LinkResolveException(
                "Keine Caption gefunden — das Rezept steht vermutlich nur im Video, nicht im Text.\n" +
                "Tipp: Video speichern und mit ObsidiDine teilen, dann liest die App das Video selbst."
            )
        ImportSource.ofText(ImportSource.LABEL_CAPTION, text, sourceUrl = url)
    }

    private fun tiktokCaption(url: String): String? {
        val endpoint = "$tiktokOEmbedBase/oembed?url=" + URLEncoder.encode(url, "UTF-8")
        val body = get(endpoint) ?: return null
        return TikTokOEmbed.caption(body)
    }

    private fun ogCaption(url: String): String? {
        val html = get(url) ?: return null
        return MetaTagParser.ogDescription(html)
    }

    /** GET → Body-String, oder null bei HTTP-Fehler/Netzproblem (Aufrufer fällt dann weiter durch). */
    private fun get(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) RezeptImporter"
    }
}
