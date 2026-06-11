package de.dml.rezeptimporter.link

import kotlinx.serialization.json.*

/** Zieht die Caption aus einer TikTok-oEmbed-Antwort (`title`). Reine Parserei, voll testbar. */
object TikTokOEmbed {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun caption(body: String): String? =
        runCatching { json.parseToJsonElement(body).jsonObject["title"] }
            .getOrNull()
            ?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?.trim()
            ?.ifBlank { null }
}
