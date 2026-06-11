package de.dml.rezeptimporter.link

import okhttp3.OkHttpClient

/**
 * Router über alle geteilten Links: YouTube (→ Beschreibung), Social (TikTok/Instagram → Caption)
 * oder Web-Portal (Rezept-Blogs → JSON-LD). Alle Wege liefern Roh-Text für die bestehende
 * LLM-Pipeline.
 */
class RecipeLinkResolver(client: OkHttpClient) : LinkResolver {

    private val youtube = YouTubeLinkResolver(client)
    private val social = SocialCaptionLinkResolver(client)
    private val web = WebRecipeLinkResolver(client)

    override suspend fun resolve(url: String): String = when {
        LinkHosts.isYouTube(url) -> youtube.resolve(url)
        LinkHosts.isSocial(url) -> social.resolve(url)
        else -> web.resolve(url)
    }
}
