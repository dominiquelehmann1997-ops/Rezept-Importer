package de.dml.rezeptimporter.link

/** Liest Open-Graph-Meta-Tags aus rohem HTML. Reine Parserei, voll testbar. */
object MetaTagParser {

    private val propertyFirst = Regex(
        """property=["']og:description["'][^>]*?content=["'](.*?)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val contentFirst = Regex(
        """content=["'](.*?)["'][^>]*?property=["']og:description["']""",
        RegexOption.IGNORE_CASE,
    )

    /** Inhalt von `<meta property="og:description">` (beide Attribut-Reihenfolgen), entityt-dekodiert. */
    fun ogDescription(html: String): String? {
        val raw = (propertyFirst.find(html) ?: contentFirst.find(html))
            ?.groupValues?.get(1) ?: return null
        return decodeEntities(raw).trim().ifBlank { null }
    }

    private fun decodeEntities(s: String): String =
        s.replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
}
