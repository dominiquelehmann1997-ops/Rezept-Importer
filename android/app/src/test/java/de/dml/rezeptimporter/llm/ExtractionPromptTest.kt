package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.ImportSource
import de.dml.rezeptimporter.domain.SourceText
import de.dml.rezeptimporter.domain.SourceVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExtractionPromptTest {

    private val caption = SourceText(ImportSource.LABEL_CAPTION, "200 g Reis, 1 Zwiebel")
    private val description = SourceText(ImportSource.LABEL_VIDEO_DESCRIPTION, "Reis waschen, dann kochen")

    @Test
    fun labelsEverySourceSoTheModelCanTellThemApart() {
        val message = ExtractionPrompt.userMessage(
            ImportSource(texts = listOf(caption, description)), null,
        )
        assertTrue(message.contains("[Quelle: ${ImportSource.LABEL_CAPTION}]"))
        assertTrue(message.contains("[Quelle: ${ImportSource.LABEL_VIDEO_DESCRIPTION}]"))
        assertTrue(message.contains("200 g Reis"))
        assertTrue(message.contains("Reis waschen"))
    }

    @Test
    fun announcesAttachedVideoAsItsOwnSource() {
        val message = ExtractionPrompt.userMessage(
            ImportSource(texts = listOf(caption), video = SourceVideo.Remote("https://youtu.be/x")),
            null,
        )
        assertTrue(message.contains("Video selbst ist beigefügt"))
    }

    @Test
    fun asksForMergeOnlyWhenThereIsMoreThanOneSource() {
        val single = ExtractionPrompt.userMessage(ImportSource(texts = listOf(caption)), null)
        assertFalse(single.contains("Führe die Quellen zusammen"))

        val withVideo = ExtractionPrompt.userMessage(
            ImportSource(
                texts = listOf(caption),
                video = SourceVideo.Local(File("/tmp/v.mp4"), "video/mp4"),
            ),
            null,
        )
        assertTrue(withVideo.contains("Führe die Quellen zusammen"))
    }

    @Test
    fun skipsEmptySourcesInsteadOfEmittingBlankLabels() {
        val message = ExtractionPrompt.userMessage(
            ImportSource(texts = listOf(caption, SourceText(ImportSource.LABEL_SCREENSHOT, "   "))),
            null,
        )
        assertFalse(message.contains(ImportSource.LABEL_SCREENSHOT))
    }

    @Test
    fun capsEachSourceAndTheWholeBlock() {
        val huge = SourceText(ImportSource.LABEL_SCREENSHOT, "x".repeat(20_000))
        val message = ExtractionPrompt.userMessage(
            ImportSource(texts = listOf(huge, huge, huge)), null,
        )
        assertTrue(message.length < ExtractionPrompt.MAX_TOTAL_INPUT_CHARS + 500)
    }

    @Test
    fun carriesRepairHintIntoTheMessage() {
        val message = ExtractionPrompt.userMessage(
            ImportSource(texts = listOf(caption)), "Name ergibt keinen gültigen Slug",
        )
        assertTrue(message.contains("Name ergibt keinen gültigen Slug"))
    }

    @Test
    fun instructionStatesTheConflictAndDeduplicationRules() {
        // Diese Regeln sind der ganze Grund für das Quellen-Bündel — fehlen sie, doppelt das
        // Modell Schritte aus Caption und Ton.
        assertTrue(ExtractionPrompt.INSTRUCTION.contains("WIDERSPRÜCHE"))
        assertTrue(ExtractionPrompt.INSTRUCTION.contains("NICHT DOPPELN"))
    }

    @Test
    fun nonEmptyTextsDropsBlankOnes() {
        val source = ImportSource(texts = listOf(caption, SourceText("Leer", "")))
        assertEquals(1, source.nonEmptyTexts.size)
    }
}
