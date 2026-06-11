package de.dml.rezeptimporter.link

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Host-Klassifizierung für geteilte Links — entscheidet, welcher Resolver greift. */
internal object LinkHosts {
    fun host(url: String): String? = url.toHttpUrlOrNull()?.host?.lowercase()

    fun isTikTok(url: String): Boolean = host(url)?.contains("tiktok") == true

    fun isInstagram(url: String): Boolean =
        host(url)?.let { it.contains("instagram") || it.contains("instagr.am") } == true

    fun isYouTube(url: String): Boolean =
        host(url)?.let { it.contains("youtube.") || it == "youtu.be" || it.endsWith(".youtu.be") } == true

    fun isSocial(url: String): Boolean = isTikTok(url) || isInstagram(url)
}
