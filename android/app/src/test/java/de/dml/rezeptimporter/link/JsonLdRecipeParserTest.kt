package de.dml.rezeptimporter.link

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonLdRecipeParserTest {

    @Test fun parsesSimpleRecipeObject() {
        val html = """
            <html><head>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Recipe",
             "name":"Spaghetti Carbonara",
             "description":"Klassisch römisch.",
             "recipeIngredient":["400 g Spaghetti","200 g Guanciale","4 Eigelb"],
             "recipeInstructions":"Nudeln kochen. Guanciale braten. Alles mischen."}
            </script>
            </head><body></body></html>
        """.trimIndent()

        val text = JsonLdRecipeParser.parse(html)!!
        assertTrue(text.contains("Spaghetti Carbonara"))
        assertTrue(text.contains("400 g Spaghetti"))
        assertTrue(text.contains("Guanciale braten"))
    }

    @Test fun parsesHowToStepInstructions() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Recipe","name":"Pfannkuchen",
             "recipeIngredient":["Mehl","Milch"],
             "recipeInstructions":[
               {"@type":"HowToStep","text":"Teig anrühren."},
               {"@type":"HowToStep","text":"In der Pfanne backen."}]}
            </script>
        """.trimIndent()

        val text = JsonLdRecipeParser.parse(html)!!
        assertTrue(text.contains("Teig anrühren."))
        assertTrue(text.contains("In der Pfanne backen."))
    }

    @Test fun parsesHowToSectionInstructions() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Recipe","name":"Lasagne",
             "recipeIngredient":["Nudelplatten"],
             "recipeInstructions":[
               {"@type":"HowToSection","name":"Soße",
                "itemListElement":[{"@type":"HowToStep","text":"Soße kochen."}]}]}
            </script>
        """.trimIndent()

        val text = JsonLdRecipeParser.parse(html)!!
        assertTrue(text.contains("Soße kochen."))
    }

    @Test fun findsRecipeInsideGraph() {
        val html = """
            <script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[
              {"@type":"WebSite","name":"Foodblog"},
              {"@type":"Recipe","name":"Chili","recipeIngredient":["Bohnen"],
               "recipeInstructions":"Köcheln lassen."}]}
            </script>
        """.trimIndent()

        val text = JsonLdRecipeParser.parse(html)!!
        assertTrue(text.contains("Chili"))
        assertTrue(text.contains("Bohnen"))
    }

    @Test fun findsRecipeInTopLevelArray() {
        val html = """
            <script type="application/ld+json">
            [{"@type":"BreadcrumbList"},
             {"@type":"Recipe","name":"Suppe","recipeIngredient":["Wasser"],"recipeInstructions":"Erhitzen."}]
            </script>
        """.trimIndent()

        assertTrue(JsonLdRecipeParser.parse(html)!!.contains("Suppe"))
    }

    @Test fun typeAsArrayContainingRecipe() {
        val html = """
            <script type="application/ld+json">
            {"@type":["Recipe","NewsArticle"],"name":"Brot","recipeIngredient":["Mehl"],"recipeInstructions":"Backen."}
            </script>
        """.trimIndent()

        assertTrue(JsonLdRecipeParser.parse(html)!!.contains("Brot"))
    }

    @Test fun returnsNullWhenNoRecipe() {
        val html = """
            <script type="application/ld+json">
            {"@type":"WebSite","name":"Nur ein Blog"}
            </script>
        """.trimIndent()

        assertNull(JsonLdRecipeParser.parse(html))
    }

    @Test fun returnsNullWhenNoJsonLd() {
        assertNull(JsonLdRecipeParser.parse("<html><body>kein json-ld</body></html>"))
    }

    @Test fun ignoresMalformedJsonLdBlock() {
        val html = """
            <script type="application/ld+json">{ kaputt json </script>
            <script type="application/ld+json">
            {"@type":"Recipe","name":"Gulasch","recipeIngredient":["Rind"],"recipeInstructions":"Schmoren."}
            </script>
        """.trimIndent()

        assertTrue(JsonLdRecipeParser.parse(html)!!.contains("Gulasch"))
    }
}
