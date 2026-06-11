package de.dml.rezeptimporter.domain

object Slug {
    private val TRANSLIT = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'ß' to "ss")

    /**
     * Kebab-case-Slug aus dem Rezeptnamen: lowercase, Umlaut-Transliteration
     * (ä→ae, ö→oe, ü→ue, ß→ss), Nicht-[a-z0-9]-Läufe zu "-", Ränder getrimmt.
     * Kann "" liefern (Name ohne alphanumerische Zeichen) — Aufrufer (Pipeline/
     * VaultWriter) müssen leere Slugs ablehnen, bevor geschrieben wird.
     */
    fun fromName(name: String): String =
        name.lowercase()
            .map { TRANSLIT[it] ?: it.toString() }
            .joinToString("")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
