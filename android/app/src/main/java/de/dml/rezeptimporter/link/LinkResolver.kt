package de.dml.rezeptimporter.link

import de.dml.rezeptimporter.domain.ImportSource

/**
 * Wandelt einen geteilten Link in ein Quellen-Bündel um, das die LLM-Pipeline weiterverarbeitet.
 * Ein Link kann mehrere Quellen liefern (z.B. Videobeschreibung *und* das Video selbst) —
 * deshalb ein Bündel und kein einzelner String.
 */
interface LinkResolver {
    suspend fun resolve(url: String): ImportSource
}

/** Link ließ sich nicht zu Rezept-Quellen auflösen (kein Recipe-Markup, HTTP-Fehler, nicht erreichbar …). */
class LinkResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)
