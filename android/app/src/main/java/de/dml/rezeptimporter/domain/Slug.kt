package de.dml.rezeptimporter.domain

object Slug {
    private val TRANSLIT = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'ß' to "ss")

    fun fromName(name: String): String =
        name.lowercase()
            .map { TRANSLIT[it] ?: it.toString() }
            .joinToString("")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
