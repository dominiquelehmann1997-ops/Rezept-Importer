package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft

class FakeLlmExtractor(private val result: RecipeDraft) : LlmExtractor {
    var calls = 0
        private set
    var lastRepairHint: String? = null
        private set

    override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft {
        calls++
        lastRepairHint = repairHint
        return result
    }
}
