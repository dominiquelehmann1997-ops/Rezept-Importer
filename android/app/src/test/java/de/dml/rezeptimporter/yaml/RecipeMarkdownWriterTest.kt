package de.dml.rezeptimporter.yaml

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

class RecipeMarkdownWriterTest {
    private val writer = RecipeMarkdownWriter()

    private fun frontmatterOf(md: String): Map<String, Any?> {
        val yaml = md.substringAfter("---\n").substringBefore("\n---")
        @Suppress("UNCHECKED_CAST")
        return Yaml().load(yaml) as Map<String, Any?>
    }

    @Test
    fun rendersFullRecipe() {
        val draft = RecipeDraft(
            name = "Gemüse-Curry",
            tags = listOf("vegetarisch"),
            servings = 4,
            ingredients = listOf(
                IngredientDraft("Kokosmilch", "400", "ml", "haltbar"),
                IngredientDraft("Brühe", "1/2", "l", null),
            ),
            steps = listOf("Würfeln.", "Köcheln."),
            rating = "favorit",
            reheatable = true,
        )
        val md = writer.render("gemuese-curry", draft)
        val fm = frontmatterOf(md)

        assertEquals("gemuese-curry", fm["id"])
        assertEquals("Gemüse-Curry", fm["name"])
        assertEquals("favorit", fm["rating"])
        assertEquals(true, fm["reheatable"])
        assertEquals(4, fm["servings"])
        assertEquals(listOf("vegetarisch"), fm["tags"])

        @Suppress("UNCHECKED_CAST")
        val ings = fm["ingredients"] as List<Map<String, Any?>>
        assertEquals(400, ings[0]["amount"])          // Zahl bleibt Zahl
        assertEquals("1/2", ings[1]["amount"])        // Bruch bleibt String
        assertEquals("haltbar", ings[0]["freshness"])
        assertEquals(null, ings[1]["freshness"])       // null ⇒ Key weggelassen
        assertTrue(!ings[1].containsKey("freshness"))

        assertTrue(md.contains("## Zubereitung"))
        assertTrue(md.contains("1. Würfeln."))
        assertTrue(md.contains("2. Köcheln."))
    }

    @Test
    fun quotesSpecialCharacters() {
        val draft = RecipeDraft(
            name = "Öl: kaltgepresst & gut",
            ingredients = listOf(IngredientDraft("Öl, kaltgepresst")),
        )
        val md = writer.render("oel-kaltgepresst-gut", draft)
        val fm = frontmatterOf(md)   // Roundtrip beweist gültiges YAML
        assertEquals("Öl: kaltgepresst & gut", fm["name"])
        @Suppress("UNCHECKED_CAST")
        val ings = fm["ingredients"] as List<Map<String, Any?>>
        assertEquals("Öl, kaltgepresst", ings[0]["name"])
    }

    @Test
    fun amountEdgeCasesStayStringsOrNormalize() {
        val draft = RecipeDraft(
            name = "X",
            ingredients = listOf(
                IngredientDraft("Sahne", "1,5", "Becher"),   // deutsches Komma ⇒ bleibt String
                IngredientDraft("Zucker", "2-3", "EL"),      // Bereich ⇒ bleibt String
            ),
        )
        val fm = frontmatterOf(writer.render("x", draft))
        @Suppress("UNCHECKED_CAST")
        val ings = fm["ingredients"] as List<Map<String, Any?>>
        assertEquals("1,5", ings[0]["amount"])
        assertEquals("2-3", ings[1]["amount"])
    }

    @Test
    fun startsWithFrontmatterDelimiter() {
        val md = writer.render("x", RecipeDraft(name = "X"))
        assertTrue(md.startsWith("---\n"))
    }
}
