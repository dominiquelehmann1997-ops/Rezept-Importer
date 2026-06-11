package de.dml.rezeptimporter.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlDetectionTest {
    @Test fun bareInstagramUrl() = assertTrue(isBareUrl("https://www.instagram.com/reel/Cxyz123/?igsh=abc"))
    @Test fun bareUrlWithWhitespace() = assertTrue(isBareUrl("  https://vm.tiktok.com/ZN123/ \n"))
    @Test fun captionWithUrlInside() = assertFalse(isBareUrl("Bestes Curry! Rezept: 400ml Kokosmilch... https://insta.gram/x"))
    @Test fun plainRecipeText() = assertFalse(isBareUrl("Zutaten: 250g Reis"))
}
