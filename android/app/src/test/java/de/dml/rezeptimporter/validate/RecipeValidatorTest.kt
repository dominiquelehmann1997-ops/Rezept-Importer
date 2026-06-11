package de.dml.rezeptimporter.validate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecipeValidatorTest {
    private val schemaJson =
        File("../../shared/recipe-vault-frontmatter.schema.json").readText()
    private val validator = RecipeValidator(schemaJson)

    @Test
    fun acceptsValidFrontmatter() {
        val md = """
            ---
            id: gemuese-curry
            name: Gemüse-Curry
            rating: favorit
            ingredients:
              - { name: Reis, amount: 250, unit: g, freshness: haltbar }
            ---
        """.trimIndent()
        assertEquals(emptyList<String>(), validator.validateMarkdown(md))
    }

    @Test
    fun rejectsBadIdPattern() {
        val md = "---\nid: Hat Leerzeichen\nname: X\n---\n"
        assertTrue(validator.validateMarkdown(md).isNotEmpty())
    }

    @Test
    fun rejectsIngredientTypoKey() {
        val md = """
            ---
            name: X
            ingredients:
              - { name: Reis, freshnes: haltbar }
            ---
        """.trimIndent()
        assertTrue(validator.validateMarkdown(md).isNotEmpty())
    }

    @Test
    fun rejectsMissingName() {
        val md = "---\nid: x\n---\n"
        assertTrue(validator.validateMarkdown(md).isNotEmpty())
    }
}
