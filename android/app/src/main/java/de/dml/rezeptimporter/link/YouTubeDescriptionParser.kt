package de.dml.rezeptimporter.link

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/**
 * Zieht die volle Videobeschreibung (`videoDetails.shortDescription` aus `ytInitialPlayerResponse`)
 * aus einer YouTube-Watch-Seite. Reine Parserei, voll testbar — kein Netzwerk, kein API-Key.
 * og:description reicht nicht (auf ~157 Zeichen gekürzt); shortDescription ist der ganze Text.
 */
object YouTubeDescriptionParser {

    private val json = Json { isLenient = true }

    // JSON-String-Wert: Folge aus Nicht-Quote/Nicht-Backslash ODER escapter Sequenz (\" \n \uXXXX …).
    private val shortDescription = Regex("\"shortDescription\":\"((?:[^\"\\\\]|\\\\.)*)\"")

    fun parse(html: String): String? {
        val raw = shortDescription.find(html)?.groupValues?.get(1) ?: return null
        // Über JSON-String-Literal entschärfen → \n, \", \uXXXX werden korrekt dekodiert.
        val text = runCatching { json.parseToJsonElement("\"$raw\"").jsonPrimitive.content }.getOrNull()
            ?: return null
        return text.trim().ifBlank { null }
    }
}
