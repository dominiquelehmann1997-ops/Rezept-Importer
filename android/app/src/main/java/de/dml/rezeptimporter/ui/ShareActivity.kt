package de.dml.rezeptimporter.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.llm.GeminiExtractor
import de.dml.rezeptimporter.llm.HaikuExtractor
import de.dml.rezeptimporter.ocr.OcrTextExtractor
import de.dml.rezeptimporter.pipeline.ImportPipeline
import de.dml.rezeptimporter.pipeline.isBareUrl
import de.dml.rezeptimporter.settings.AppSettings
import de.dml.rezeptimporter.settings.Provider
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.vault.SafVaultStorage
import de.dml.rezeptimporter.vault.VaultWriter
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

sealed interface ImportState {
    data object Working : ImportState
    data class Preview(val draft: RecipeDraft) : ImportState
    data class Error(val message: String) : ImportState
}

class ShareActivity : ComponentActivity() {

    private val state = mutableStateOf<ImportState>(ImportState.Working)
    private lateinit var settings: AppSettings
    private lateinit var validator: RecipeValidator
    private val markdownWriter = RecipeMarkdownWriter()

    // LLM-Calls können >10s dauern — OkHttp-Default-Timeouts reichen nicht.
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        validator = RecipeValidator(
            assets.open("recipe-vault-frontmatter.schema.json").readBytes().toString(Charsets.UTF_8)
        )

        setContent {
            MaterialTheme {
                when (val s = state.value) {
                    is ImportState.Working -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Rezept wird extrahiert …")
                        }
                    }
                    is ImportState.Preview -> PreviewScreen(
                        initial = s.draft,
                        onSave = ::save,
                        onCancel = { finish() },
                    )
                    is ImportState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                               modifier = Modifier.padding(24.dp)) {
                            Text(s.message)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { finish() }) { Text("Schließen") }
                        }
                    }
                }
            }
        }

        runImport()
    }

    private fun buildExtractor() = when (settings.provider) {
        Provider.GEMINI -> {
            check(settings.geminiKey.isNotBlank()) { "Kein Gemini-API-Key — in der App unter Settings eintragen" }
            GeminiExtractor(settings.geminiKey, httpClient)
        }
        Provider.HAIKU -> {
            check(settings.anthropicKey.isNotBlank()) { "Kein Anthropic-API-Key — in der App unter Settings eintragen" }
            HaikuExtractor(settings.anthropicKey, httpClient)
        }
    }

    private fun runImport() {
        lifecycleScope.launch {
            try {
                if (settings.vaultUri == null) {
                    state.value = ImportState.Error("Kein Vault-Ordner gewählt — erst App öffnen und Ordner wählen.")
                    return@launch
                }
                val rawText = collectSourceText()
                if (rawText.isBlank()) {
                    state.value = ImportState.Error("Kein Text gefunden (OCR leer?). Tipp: Screenshot mit gut lesbarem Text teilen.")
                    return@launch
                }
                if (isBareUrl(rawText)) {
                    state.value = ImportState.Error(
                        "Das ist nur ein Link. Reel-/Web-Links werden erst in Phase 2 unterstützt.\n" +
                        "Tipp: Screenshot der Caption teilen oder Text kopieren."
                    )
                    return@launch
                }
                val pipeline = ImportPipeline(buildExtractor(), validator, markdownWriter)
                state.value = ImportState.Preview(pipeline.extractValidated(rawText))
            } catch (e: Exception) {
                state.value = ImportState.Error(e.message ?: "Unbekannter Fehler")
            }
        }
    }

    private suspend fun collectSourceText(): String {
        val ocr = OcrTextExtractor(this)
        return when (intent.action) {
            Intent.ACTION_SEND -> when {
                intent.type == "text/plain" ->
                    intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                intent.type?.startsWith("image/") == true -> {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    if (uri != null) ocr.extract(listOf(uri)) else ""
                }
                else -> ""
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                ocr.extract(uris)
            }
            else -> ""
        }
    }

    private fun save(draft: RecipeDraft) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val storage = SafVaultStorage(this@ShareActivity, settings.vaultUri!!)
                    VaultWriter(storage, markdownWriter, validator).write(draft)
                }
                Toast.makeText(
                    this@ShareActivity,
                    "Gespeichert: ${result.fileName} (id: ${result.id})",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            } catch (e: Exception) {
                state.value = ImportState.Error("Speichern fehlgeschlagen: ${e.message}")
            }
        }
    }
}
