package de.dml.rezeptimporter.pipeline

import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.domain.Slug
import de.dml.rezeptimporter.llm.LanguageHeuristic
import de.dml.rezeptimporter.llm.VegetarianHeuristic
import de.dml.rezeptimporter.llm.LlmException
import de.dml.rezeptimporter.llm.LlmExtractor
import de.dml.rezeptimporter.llm.LlmTransportException
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter

/** true, wenn der geteilte Text nur aus einer einzelnen URL besteht (Reel/TikTok/Web-Link). */
fun isBareUrl(text: String): Boolean =
    Regex("^https?://\\S+$").matches(text.trim())

/**
 * URL aus geteiltem Text, wenn der Text im Kern nur ein Link ist — manche Apps
 * (z. B. Cookidoo) hängen Boilerplate wie "Schau dir dieses Rezept an: …" davor.
 * Lange Captions mit Link bleiben Text: die Caption selbst ist das Rezept.
 */
fun extractShareUrl(text: String): String? {
    val trimmed = text.trim()
    val url = Regex("https?://\\S+").find(trimmed)?.value ?: return null
    val rest = trimmed.replace(url, "").trim()
    return if (rest.length <= 80) url else null
}

class ImportPipeline(
    private val extractor: LlmExtractor,
    private val validator: RecipeValidator,
    private val writer: RecipeMarkdownWriter,
) {
    /**
     * Rohtext → validierter, deutscher RecipeDraft. Extraktion (max. 2 Calls:
     * 1 Extraktion + 1 Repair-Retry). Anschließend Sprach-Check: ist das Ergebnis
     * noch Englisch, folgt EIN zusätzlicher Übersetzungs-Pass.
     */
    suspend fun extractValidated(rawText: String): RecipeDraft {
        val german = ensureGerman(rawText, extractCore(rawText))
        // Vegetarisch-Status immer heuristisch aus den (deutschen) Zutaten ableiten;
        // im Preview kann der Nutzer ihn überschreiben.
        return german.copy(vegetarian = VegetarianHeuristic.isVegetarian(german))
    }

    /**
     * Immer prüfen, ob der Draft (noch) Englisch ist; falls ja, ein gezielter
     * Übersetzungs-Pass durch dieselbe LLM-Pipeline. Schlägt der fehl oder bleibt
     * das Ergebnis englisch, wird der ursprüngliche (valide) Draft behalten.
     */
    private suspend fun ensureGerman(rawText: String, draft: RecipeDraft): RecipeDraft {
        if (!LanguageHeuristic.isLikelyEnglish(draft)) return draft
        return try {
            val translated = extractor.extract(rawText, repairHint = TRANSLATE_HINT)
            if (problemsOf(translated).isEmpty() && !LanguageHeuristic.isLikelyEnglish(translated)) {
                translated
            } else {
                draft
            }
        } catch (e: LlmException) {
            draft   // Übersetzung ist Best-Effort — technische/semantische Fehler nicht hochreichen
        }
    }

    private suspend fun extractCore(rawText: String): RecipeDraft {
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

    private companion object {
        const val TRANSLATE_HINT =
            "Das Rezept ist auf Englisch. Übersetze ALLES vollständig ins Deutsche " +
                "(Name, Zutaten, Schritte, Tags) und rechne sämtliche Mengen in " +
                "europäische metrische Einheiten um (g, kg, ml, l, °C, EL, TL)."
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
