package de.dml.rezeptimporter.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeDescriptionParserTest {

    @Test fun extractsDescriptionWithNewlines() {
        val html =
            """<script>var ytInitialPlayerResponse = {"videoDetails":{"shortDescription":"Zutaten:\n- 250g Reis\n- 400ml Kokosmilch\n\nZubereitung:\n1. Kochen.","lengthSeconds":"60"}};</script>"""
        val text = YouTubeDescriptionParser.parse(html)!!
        assertTrue(text.contains("250g Reis"))
        assertTrue(text.contains("Zubereitung:"))
        assertTrue(text.contains("\n"))   // \n wurde zu echtem Zeilenumbruch dekodiert
    }

    @Test fun handlesEscapedQuotes() {
        val html = """{"shortDescription":"Omas \"bestes\" Curry"}"""
        assertEquals("Omas \"bestes\" Curry", YouTubeDescriptionParser.parse(html))
    }

    @Test fun nullWhenAbsent() {
        assertNull(YouTubeDescriptionParser.parse("<html><body>Consent-Wall, kein playerResponse</body></html>"))
    }

    @Test fun nullWhenBlank() {
        assertNull(YouTubeDescriptionParser.parse("""{"shortDescription":"   "}"""))
    }
}
