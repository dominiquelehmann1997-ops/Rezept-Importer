package de.dml.rezeptimporter.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareTextTest {
    @Test fun bareInstagramUrl() = assertTrue(isBareUrl("https://www.instagram.com/reel/Cxyz123/?igsh=abc"))
    @Test fun bareUrlWithWhitespace() = assertTrue(isBareUrl("  https://vm.tiktok.com/ZN123/ \n"))
    @Test fun captionWithUrlInside() = assertFalse(isBareUrl("Bestes Curry! Rezept: 400ml Kokosmilch... https://insta.gram/x"))
    @Test fun plainRecipeText() = assertFalse(isBareUrl("Zutaten: 250g Reis"))

    // extractShareUrl: Share-Boilerplate um den Link herum (Cookidoo & Co.) tolerieren,
    // lange Captions mit Link bleiben Text — die Caption selbst ist das Rezept.
    @Test fun extractsBareUrl() =
        assertEquals("https://cookidoo.de/recipes/recipe/de-DE/r802484",
            extractShareUrl("https://cookidoo.de/recipes/recipe/de-DE/r802484"))

    @Test fun extractsUrlFromShareBoilerplate() =
        assertEquals("https://cookidoo.de/recipes/recipe/de-DE/r802484",
            extractShareUrl("Schau dir dieses Rezept an: Apfeltarte https://cookidoo.de/recipes/recipe/de-DE/r802484"))

    @Test fun keepsLongCaptionWithUrlAsText() =
        assertNull(extractShareUrl(
            "Bestes Curry ever!! Ihr braucht: 400 ml Kokosmilch, 2 EL rote Currypaste, " +
            "500 g Hähnchen, 1 Paprika, Reis. Anbraten, ablöschen, 15 Min köcheln. " +
            "Mehr Rezepte auf meinem Kanal: https://insta.gram/x"))

    @Test fun returnsNullWithoutUrl() = assertNull(extractShareUrl("Zutaten: 250g Reis"))
}
