package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft

/**
 * Versucht primary; NUR bei LlmTransportException (HTTP/Netz/Timeout) wechselt er auf
 * secondary. Inhaltliche Fehlschläge werden durchgereicht — die kosten beim
 * Zweit-Provider nur Geld und scheitern genauso.
 */
class FallbackExtractor(
    private val primary: LlmExtractor,
    private val secondary: LlmExtractor,
) : LlmExtractor {
    override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft =
        try {
            primary.extract(rawText, repairHint)
        } catch (e: LlmTransportException) {
            secondary.extract(rawText, repairHint)
        }
}
