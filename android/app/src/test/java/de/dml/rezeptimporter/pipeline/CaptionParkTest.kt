package de.dml.rezeptimporter.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nur die Gültigkeitsregel — das Speichern selbst hängt an SharedPreferences und damit an
 * einem Android-Context, den die JVM-Tests hier nicht haben.
 */
class CaptionParkTest {

    private val now = 1_700_000_000_000L

    @Test
    fun captionIsFreshRightAfterParking() {
        assertTrue(CaptionPark.isFresh(parkedAt = now, now = now))
    }

    @Test
    fun captionSurvivesTheTimeNeededToSaveAndShareTheVideo() {
        assertTrue(CaptionPark.isFresh(parkedAt = now - 10 * 60 * 1000L, now = now))
    }

    @Test
    fun captionExpiresSoItNeverAttachesToAnUnrelatedVideo() {
        assertFalse(CaptionPark.isFresh(parkedAt = now - 31 * 60 * 1000L, now = now))
    }

    @Test
    fun missingTimestampCountsAsExpired() {
        // Fehlender Zeitstempel (0) darf nicht als "gerade eben" durchgehen.
        assertFalse(CaptionPark.isFresh(parkedAt = 0L, now = now))
    }
}
