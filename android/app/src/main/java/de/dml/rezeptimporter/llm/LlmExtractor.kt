package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft

interface LlmExtractor {
    /**
     * Genau ein API-Call. [repairHint] nur beim einen erlaubten Repair-Retry gesetzt
     * (enthält die Validierungsfehler des ersten Versuchs).
     */
    suspend fun extract(rawText: String, repairHint: String? = null): RecipeDraft
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
