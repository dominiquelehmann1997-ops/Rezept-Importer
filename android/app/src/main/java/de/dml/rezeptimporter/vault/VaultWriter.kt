package de.dml.rezeptimporter.vault

import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.domain.Slug
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter

data class WriteResult(val id: String, val fileName: String)

class VaultWriter(
    private val storage: VaultStorage,
    private val markdownWriter: RecipeMarkdownWriter,
    private val validator: RecipeValidator,
) {
    /**
     * Slug erzeugen, bei Kollision automatisch -2/-3… suffixen (Phase 1; Dialog kommt in Phase 4),
     * rendern, validieren (Schema + Roundtrip), erst dann schreiben.
     */
    fun write(draft: RecipeDraft): WriteResult {
        val base = Slug.fromName(draft.name)
        val files = storage.listMarkdownFiles()
        val existing = files.mapNotNull { it.id }.toSet()
        var id = base
        var n = 2
        while (id in existing) { id = "$base-$n"; n++ }

        val content = markdownWriter.render(id, draft)
        val problems = validator.validateMarkdown(content)
        check(problems.isEmpty()) {
            "Validierung fehlgeschlagen, Datei NICHT geschrieben: ${problems.joinToString("; ")}"
        }

        val stem = draft.name
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .trim()
            .removePrefix("_")
            .ifEmpty { id }
        val existingNames = files.map { it.fileName }.toSet()
        var fileName = "$stem.md"
        var m = 2
        while (fileName in existingNames) { fileName = "$stem-$m.md"; m++ }
        storage.write(fileName, content)
        return WriteResult(id, fileName)
    }
}
