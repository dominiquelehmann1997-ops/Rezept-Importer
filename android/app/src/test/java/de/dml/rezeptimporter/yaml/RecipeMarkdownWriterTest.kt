package de.dml.rezeptimporter.yaml

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.NutritionDraft
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
    fun bodyNormalizesDecimalCommaForRecipeViewScaling() {
        val draft = RecipeDraft(
            name = "X",
            ingredients = listOf(IngredientDraft("Sahne", "1,5", "Becher")),
        )
        val md = writer.render("x", draft)
        val body = md.substringAfter("\n---\n")
        // Body: Punkt-Dezimal, damit Recipe-View skalieren kann …
        assertTrue(body.contains("- 1.5 Becher Sahne"))
        // … Frontmatter behält das Original (Kontrakt: String bleibt String).
        @Suppress("UNCHECKED_CAST")
        val ings = frontmatterOf(md)["ingredients"] as List<Map<String, Any?>>
        assertEquals("1,5", ings[0]["amount"])
    }

    @Test
    fun bodyShieldsRangesFromRecipeViewScaling() {
        val draft = RecipeDraft(
            name = "X",
            ingredients = listOf(IngredientDraft("Zucker", "2-3", "EL")),
        )
        val body = writer.render("x", draft).substringAfter("\n---\n")
        // "2-3" sähe für Recipe-View wie ein Dash-Mixed-Number aus → vor Fehlparse schützen.
        assertTrue(body.contains("- <span data-qty-no-parse>2-3 EL</span> Zucker"))
    }

    @Test
    fun bodyKeepsDashFractionScalable() {
        val draft = RecipeDraft(
            name = "X",
            ingredients = listOf(IngredientDraft("Mehl", "1-1/2", "kg")),
        )
        val body = writer.render("x", draft).substringAfter("\n---\n")
        // "1-1/2" IST ein gültiges Dash-Mixed-Number für Recipe-View — nicht abschirmen.
        assertTrue(body.contains("- 1-1/2 kg Mehl"))
        assertTrue(!body.contains("data-qty-no-parse"))
    }

    @Test
    fun bodyNormalizesCommaInsideRange() {
        val draft = RecipeDraft(
            name = "X",
            ingredients = listOf(IngredientDraft("Hefe", "0,5-1", "Würfel")),
        )
        val body = writer.render("x", draft).substringAfter("\n---\n")
        assertTrue(body.contains("- <span data-qty-no-parse>0.5-1 Würfel</span> Hefe"))
    }

    @Test
    fun rendersIngredientsAsReadableBodySection() {
        val draft = RecipeDraft(
            name = "X",
            ingredients = listOf(
                IngredientDraft("Grillkäse", "200", "g"),
                IngredientDraft("Salz"),
            ),
            steps = listOf("Mischen."),
        )
        val md = writer.render("x", draft)
        val body = md.substringAfter("\n---\n")
        assertTrue(body.contains("## Zutaten"))
        assertTrue(body.contains("- 200 g Grillkäse"))
        assertTrue(body.contains("- Salz"))
        // Frontmatter bleibt zusätzlich erhalten
        assertTrue(frontmatterOf(md).containsKey("ingredients"))
    }

    @Test
    fun rendersServingsLineInBody() {
        val draft = RecipeDraft(
            name = "X",
            servings = 4,
            ingredients = listOf(IngredientDraft("Reis", "250", "g")),
            steps = listOf("Kochen."),
        )
        val body = writer.render("x", draft).substringAfter("\n---\n")
        assertTrue(body.contains("## Zutaten"))
        // data-qty-parse: Recipe-View skaliert die Portionszahl im Widget mit.
        assertTrue(body.contains("*Für <span data-qty-parse>4 Portionen</span>*"))
        // Frontmatter behält servings zusätzlich
        assertEquals(4, frontmatterOf(writer.render("x", draft))["servings"])
    }

    @Test
    fun servingsLineUsesSingularForOne() {
        val draft = RecipeDraft(
            name = "X",
            servings = 1,
            ingredients = listOf(IngredientDraft("Ei")),
            steps = listOf("Kochen."),
        )
        val body = writer.render("x", draft).substringAfter("\n---\n")
        assertTrue(body.contains("*Für <span data-qty-parse>1 Portion</span>*"))
    }

    @Test
    fun omitsServingsLineWhenAbsent() {
        val draft = RecipeDraft(name = "X", ingredients = listOf(IngredientDraft("Ei")))
        val body = writer.render("x", draft).substringAfter("\n---\n")
        assertTrue(!body.contains("Portion"))
    }

    @Test
    fun rendersNutritionInFrontmatterAndBody() {
        val draft = RecipeDraft(
            name = "X",
            nutrition = NutritionDraft(
                basis = "pro Portion", kcal = 520, protein = 28.0, carbs = 31.5, fat = 30.0,
            ),
        )
        val md = writer.render("x", draft)
        val body = md.substringAfter("\n---\n")
        assertTrue(body.contains("## Nährwerte"))
        assertTrue(body.contains("*pro Portion*"))
        assertTrue(body.contains("- Energie: 520 kcal"))
        assertTrue(body.contains("- Eiweiß: 28 g"))        // .0 getrimmt
        assertTrue(body.contains("- Kohlenhydrate: 31.5 g"))
        assertTrue(body.contains("- Fett: 30 g"))

        @Suppress("UNCHECKED_CAST")
        val nut = frontmatterOf(md)["nutrition"] as Map<String, Any?>
        assertEquals(520, nut["kcal"])
        assertEquals("pro Portion", nut["basis"])
    }

    @Test
    fun omitsNutritionWhenAbsent() {
        val md = writer.render("x", RecipeDraft(name = "X"))
        assertTrue(!md.contains("## Nährwerte"))
        assertTrue(!frontmatterOf(md).containsKey("nutrition"))
    }

    @Test
    fun startsWithFrontmatterDelimiter() {
        val md = writer.render("x", RecipeDraft(name = "X"))
        assertTrue(md.startsWith("---\n"))
    }
}
