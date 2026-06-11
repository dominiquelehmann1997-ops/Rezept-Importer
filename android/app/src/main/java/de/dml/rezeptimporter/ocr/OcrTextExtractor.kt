package de.dml.rezeptimporter.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OcrTextExtractor(private val context: Context) {
    suspend fun extract(uris: List<Uri>): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val parts = mutableListOf<String>()
        for (uri in uris) {
            val image = InputImage.fromFilePath(context, uri)
            parts.add(recognizer.process(image).await().text)
        }
        return parts.joinToString("\n\n").trim()
    }
}
