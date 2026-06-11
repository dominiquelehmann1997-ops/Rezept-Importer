package de.dml.rezeptimporter.pipeline

import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.domain.Slug
import de.dml.rezeptimporter.llm.LlmException
import de.dml.rezeptimporter.llm.LlmExtractor
import de.dml.rezeptimporter.llm.LlmTransportException
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter

/** true, wenn der geteilte Text nur aus einer einzelnen URL besteht (Reel/TikTok/Web-Link). */
fun isBareUrl(text: String): Boolean =
    Regex("^https?://\\S+$").matches(text.trim())

class ImportPipeline(
    private val extractor: LlmExtractor,
    private val validator: RecipeValidator,
    private val writer: RecipeMarkdownWriter,
) {
    /**
     * Rohtext → validierter RecipeDraft. Harte Obergrenze: 2 LLM-Calls
     * (1 Extraktion + 1 Repair-Retry mit Fehlerliste). Danach LlmException.
     */
    suspend fun extractValidated(rawText: String): RecipeDraft {
        val firstProblems: List<String>
        try {
            val first = extractor.extract(rawText)
            val problems = problemsOf(first)
            if (problems.isEmpty()) return first
            firstProblems = problems
        } catch (e: LlmTransportException) {
            throw e   // Technik-Fehler: Retry sinnlos, Fallback-Logik liegt im Extractor
        } catch (e: LlmException) {
            // Semantischer Fehlschlag (z.B. leerer Name) — ein Repair-Versuch
            val second = extractor.extract(rawText, repairHint = e.message ?: "ungültige Antwort")
            val secondProblems = problemsOf(second)
            if (secondProblems.isEmpty()) return second
            throw LlmException("Extraktion nach Repair-Retry weiterhin ungültig: ${secondProblems.joinToString("; ")}")
        }

        val second = extractor.extract(rawText, repairHint = firstProblems.joinToString("; "))
        val secondProblems = problemsOf(second)
        if (secondProblems.isEmpty()) return second
        throw LlmException("Extraktion nach Repair-Retry weiterhin ungültig: ${secondProblems.joinToString("; ")}")
    }

    private fun problemsOf(draft: RecipeDraft): List<String> {
        val problems = mutableListOf<String>()
        val slug = Slug.fromName(draft.name)
        if (slug.isEmpty()) problems.add("Name '${draft.name}' ergibt keinen gültigen Slug")
        // Probe-Rendering mit Probe-Slug — prüft Schema-Konformität des kompletten Outputs
        val probeId = slug.ifEmpty { "probe" }
        problems.addAll(validator.validateMarkdown(writer.render(probeId, draft)))
        return problems
    }
}
