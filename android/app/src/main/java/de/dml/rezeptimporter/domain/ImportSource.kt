package de.dml.rezeptimporter.domain

import java.io.File

/**
 * Eine beschriftete Textquelle. Das Label geht mit in den Prompt — das Modell muss
 * wissen, ob eine Menge aus einer geschriebenen Caption oder aus gesprochenem Ton
 * stammt, um Widersprüche auflösen zu können.
 */
data class SourceText(val label: String, val text: String)

/** Video als Quelle — entweder lokale Datei (Upload nötig) oder öffentliche URL (Modell ruft selbst ab). */
sealed interface SourceVideo {
    /** Geteilte Videodatei, liegt im App-Cache. */
    data class Local(val file: File, val mimeType: String) : SourceVideo

    /** Öffentlich abrufbare Video-URL (YouTube) — Gemini lädt sie selbst, kein Upload. */
    data class Remote(val url: String) : SourceVideo
}

/**
 * Alle Quellen eines Imports zusammen. Ein Reel besteht regelmäßig aus Caption *und*
 * Video: die Caption trägt die Zutatenliste, das Video die Schritte. Beides muss in
 * denselben LLM-Call, sonst fehlt am Ende die Hälfte.
 */
data class ImportSource(
    val texts: List<SourceText> = emptyList(),
    val video: SourceVideo? = null,
    /** Ursprungslink; landet als `source` im Frontmatter der Obsidian-Notiz. */
    val sourceUrl: String? = null,
) {
    val hasContent: Boolean
        get() = video != null || texts.any { it.text.isNotBlank() }

    /** Nur die nicht-leeren Textquellen — leere Labels sollen nicht in den Prompt. */
    val nonEmptyTexts: List<SourceText>
        get() = texts.filter { it.text.isNotBlank() }

    fun withSourceUrl(url: String?): ImportSource =
        if (url.isNullOrBlank()) this else copy(sourceUrl = url)

    fun plusText(label: String, text: String): ImportSource =
        if (text.isBlank()) this else copy(texts = texts + SourceText(label, text))

    companion object {
        const val LABEL_CAPTION = "Caption des Beitrags"
        const val LABEL_VIDEO_DESCRIPTION = "Videobeschreibung"
        const val LABEL_WEBPAGE = "Rezeptdaten der Webseite"
        const val LABEL_SCREENSHOT = "Text aus geteilten Bildern (OCR)"
        const val LABEL_SHARED_TEXT = "Geteilter Text"

        fun ofText(label: String, text: String, sourceUrl: String? = null) =
            ImportSource(texts = listOf(SourceText(label, text)), sourceUrl = sourceUrl)
    }
}
