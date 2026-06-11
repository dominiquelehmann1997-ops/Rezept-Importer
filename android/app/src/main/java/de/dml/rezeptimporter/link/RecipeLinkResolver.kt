package de.dml.rezeptimporter.link

import okhttp3.OkHttpClient

/**
 * Router über alle geteilten Links: Social (TikTok/Instagram → Caption) vs. Web-Portal
 * (Rezept-Blogs → JSON-LD). Beide Wege liefern Roh-Text für die bestehende LLM-Pipeline.
 */
class RecipeLinkResolver(client: OkHttpClient) : LinkResolver {

    private val social = SocialCaptionLinkResolver(client)
    private val web = WebRecipeLinkResolver(client)

    override suspend fun resolve(url: String): String =
        if (LinkHosts.isSocial(url)) social.resolve(url) else web.resolve(url)
}
