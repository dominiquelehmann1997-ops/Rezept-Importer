package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.ImportSource
import de.dml.rezeptimporter.domain.RecipeDraft

interface LlmExtractor {
    /** Ob dieser Provider Video als Eingabe verarbeiten kann. */
    val supportsVideo: Boolean get() = false

    /**
     * Genau ein API-Call über alle Quellen des Bündels. [repairHint] nur beim einen erlaubten
     * Repair-Retry gesetzt (enthält die Validierungsfehler des ersten Versuchs).
     */
    suspend fun extract(source: ImportSource, repairHint: String? = null): RecipeDraft
}

open class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Technischer Fehler (HTTP non-2xx, Timeout, Netz) — Kandidat für Provider-Fallback, kein Repair-Retry. */
class LlmTransportException(message: String, cause: Throwable? = null) : LlmException(message, cause)
