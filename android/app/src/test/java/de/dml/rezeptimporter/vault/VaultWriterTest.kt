package de.dml.rezeptimporter.vault

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.NutritionDraft
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

    @Test
    fun writesRecipeWithNutritionIncludingStringBasis() {
        // Regression: nutrition.basis ist String; Schema darf nicht "number" erzwingen.
        val storage = FakeVaultStorage()
        val withNutrition = draft.copy(
            nutrition = NutritionDraft(
                basis = "pro Portion", kcal = 520, protein = 28.0, carbs = 31.5, fat = 30.0,
            ),
        )
        val result = writer(storage).write(withNutrition)
        assertEquals("gemuese-curry", result.id)
        assertTrue(storage.files[result.fileName]!!.contains("## Nährwerte"))
    }

    @Test
    fun sameNameTwiceProducesDistinctFilesAndIds() {
        val storage = FakeVaultStorage()
        val w = writer(storage)
        val first = w.write(draft)
        val second = w.write(draft)
        assertEquals("gemuese-curry", first.id)
        assertEquals("gemuese-curry-2", second.id)
        assertEquals("Gemüse-Curry.md", first.fileName)
        assertEquals("Gemüse-Curry-2.md", second.fileName)
        assertEquals(2, storage.files.size)   // nichts überschrieben
    }
}
