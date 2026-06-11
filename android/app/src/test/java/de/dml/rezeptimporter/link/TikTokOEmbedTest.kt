package de.dml.rezeptimporter.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TikTokOEmbedTest {

    @Test fun parsesTitle() {
        val json = """{"title":"Schnelles Ofengemüse #rezept","author_name":"koch"}"""
        assertEquals("Schnelles Ofengemüse #rezept", TikTokOEmbed.caption(json))
    }

    @Test fun nullWhenNoTitle() {
        assertNull(TikTokOEmbed.caption("""{"error":"nope"}"""))
    }

    @Test fun nullWhenBlankTitle() {
        assertNull(TikTokOEmbed.caption("""{"title":"   "}"""))
    }

    @Test fun nullOnGarbage() {
        assertNull(TikTokOEmbed.caption("kein json"))
    }
}
