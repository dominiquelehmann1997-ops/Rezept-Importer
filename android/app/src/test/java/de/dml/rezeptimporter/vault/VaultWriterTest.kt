package de.dml.rezeptimporter.vault

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class FakeVaultStorage : VaultStorage {
    val files = mutableMapOf<String, String>()
    override fun listMarkdownFiles(): List<VaultFile> =
        files.map { (name, content) ->
            VaultFile(name, Regex("(?m)^id:\\s*(\\S+)").find(content)?.groupValues?.get(1))
        }
    override fun write(fileName: String, content: String) { files[fileName] = content }
}

class VaultWriterTest {
    private val schemaJson = File("../../shared/recipe-vault-frontmatter.schema.json").readText()

    private fun writer(storage: VaultStorage) =
        VaultWriter(storage, RecipeMarkdownWriter(), RecipeValidator(schemaJson))

    private val draft = RecipeDraft(
        name = "Gemüse-Curry",
        ingredients = listOf(IngredientDraft("Reis", "250", "g", "haltbar")),
        steps = listOf("Kochen."),
    )

    @Test
    fun writesValidatedFile() {
        val storage = FakeVaultStorage()
        val result = writer(storage).write(draft)
        assertEquals("gemuese-curry", result.id)
        assertEquals("Gemüse-Curry.md", result.fileName)
        assertTrue(storage.files["Gemüse-Curry.md"]!!.contains("id: gemuese-curry"))
    }

    @Test
    fun autoSuffixesOnIdCollision() {
        val storage = FakeVaultStorage()
        storage.files["alt.md"] = "---\nid: gemuese-curry\nname: Alt\n---\n"
        val result = writer(storage).write(draft)
        assertEquals("gemuese-curry-2", result.id)
    }

    @Test
    fun incrementsSuffixPastExisting() {
        val storage = FakeVaultStorage()
        storage.files["a.md"] = "---\nid: gemuese-curry\nname: A\n---\n"
        storage.files["b.md"] = "---\nid: gemuese-curry-2\nname: B\n---\n"
        val result = writer(storage).write(draft)
        assertEquals("gemuese-curry-3", result.id)
    }

    @Test
    fun stripsForbiddenFilenameChars() {
        val storage = FakeVaultStorage()
        val result = writer(storage).write(draft.copy(name = "Was? Curry/Reis: Test"))
        assertTrue(!result.fileName.contains("?"))
        assertTrue(!result.fileName.contains("/"))
        assertTrue(!result.fileName.startsWith("_"))
        assertTrue(result.fileName.endsWith(".md"))
    }

    @Test(expected = IllegalStateException::class)
    fun refusesToWriteInvalidOutput() {
        // Name nur aus Sonderzeichen ⇒ leerer Slug ⇒ id verletzt Schema-Pattern ⇒ Write verweigert
        val storage = FakeVaultStorage()
        writer(storage).write(draft.copy(name = "!!!"))
    }
}
