package de.dml.rezeptimporter.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaTagParserTest {

    @Test fun propertyBeforeContent() {
        val html = """<meta property="og:description" content="Bestes Curry: 400ml Kokosmilch" />"""
        assertEquals("Bestes Curry: 400ml Kokosmilch", MetaTagParser.ogDescription(html))
    }

    @Test fun contentBeforeProperty() {
        val html = """<meta content="Pasta mit 200g Mehl" property="og:description">"""
        assertEquals("Pasta mit 200g Mehl", MetaTagParser.ogDescription(html))
    }

    @Test fun decodesHtmlEntities() {
        val html =
            """<meta property="og:description" content="12 likes - koch: &quot;Reis &amp; Bohnen&quot; &#39;lecker&#39;">"""
        assertEquals("""12 likes - koch: "Reis & Bohnen" 'lecker'""", MetaTagParser.ogDescription(html))
    }

    @Test fun missingReturnsNull() {
        assertNull(MetaTagParser.ogDescription("<html><head><title>x</title></head></html>"))
    }
}
