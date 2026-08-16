package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.ImportSource
import de.dml.rezeptimporter.domain.RecipeDraft

class FakeLlmExtractor(
    private val result: RecipeDraft,
    override val supportsVideo: Boolean = true,
) : LlmExtractor {
    var calls = 0
        private set
    var lastRepairHint: String? = null
        private set
    var lastSource: ImportSource? = null
        private set

    override suspend fun extract(source: ImportSource, repairHint: String?): RecipeDraft {
        calls++
        lastRepairHint = repairHint
        lastSource = source
        return result
    }
}
