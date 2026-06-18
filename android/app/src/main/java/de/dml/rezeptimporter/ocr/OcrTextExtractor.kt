package de.dml.rezeptimporter.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OcrTextExtractor(private val context: Context) {
    suspend fun extract(uris: List<Uri>): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val parts = mutableListOf<String>()
        for (uri in uris) {
            val image = InputImage.fromFilePath(context, uri)
            parts.add(columnAwareText(recognizer.process(image).await()))
        }
        return parts.joinToString("\n\n").trim()
    }

    /**
     * ML Kit reads multi-column layouts (e.g. HelloFresh 3×2 step grids) left-to-right
     * across all columns simultaneously, interleaving lines from different steps.
     * Re-sorting by x-column bucket first, then y within each column ensures each
     * step's title and description lines stay together in the output.
     */
    private fun columnAwareText(result: Text): String {
        val lines = result.textBlocks.flatMap { it.lines }
        if (lines.isEmpty()) return result.text

        val maxX = lines
            .mapNotNull { it.boundingBox?.let { b -> (b.left + b.right) / 2 } }
            .maxOrNull()
            ?.coerceAtLeast(1) ?: return result.text

        return lines
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val xCenter = (box.left + box.right) / 2
                Triple(xCenter * 10 / maxX, box.top, line.text)
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("\n") { it.third }
    }
}

