package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.ImportSource
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

    /** Nur wenn beide Provider Video können, ist der Fallback bei Video-Quellen nutzbar. */
    override val supportsVideo: Boolean
        get() = primary.supportsVideo && secondary.supportsVideo

    override suspend fun extract(source: ImportSource, repairHint: String?): RecipeDraft =
        try {
            primary.extract(source, repairHint)
        } catch (e: LlmTransportException) {
            // Bei Video nur ausweichen, wenn der Zweit-Provider es überhaupt kann —
            // sonst käme ein Rezept ohne die Video-Hälfte heraus.
            if (source.video != null && !secondary.supportsVideo) throw e
            secondary.extract(source, repairHint)
        }
}
