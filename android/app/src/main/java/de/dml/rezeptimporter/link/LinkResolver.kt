package de.dml.rezeptimporter.link

/** Wandelt einen geteilten Link in Roh-Text um, den die bestehende LLM-Pipeline weiterverarbeitet. */
interface LinkResolver {
    suspend fun resolve(url: String): String
}

/** Link ließ sich nicht zu Rezept-Text auflösen (kein Recipe-Markup, HTTP-Fehler, nicht erreichbar …). */
class LinkResolveException(message: String, cause: Throwable? = null) : Exception(message, cause)
