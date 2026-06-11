package de.dml.rezeptimporter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SlugTest {
    @Test fun simpleName() = assertEquals("pasta-al-pomodoro", Slug.fromName("Pasta al Pomodoro"))
    @Test fun umlauts() = assertEquals("gemuese-curry-mit-suesskartoffel", Slug.fromName("Gemüse-Curry mit Süßkartoffel"))
    @Test fun specialChars() = assertEquals("oel-zitronen-pasta", Slug.fromName("Öl & Zitronen!! Pasta"))
    @Test fun collapsesDashes() = assertEquals("a-b", Slug.fromName("a --- b"))
    @Test fun trimsDashes() = assertEquals("abc", Slug.fromName("  abc  "))
    @Test fun digitsKept() = assertEquals("5-minuten-brot", Slug.fromName("5-Minuten-Brot"))
    @Test fun emptyWhenNoAlphanumerics() = assertEquals("", Slug.fromName("!!!"))
    @Test fun underscoresBecomeDashes() = assertEquals("pasta-al-pomodoro", Slug.fromName("Pasta_al_Pomodoro"))
}
