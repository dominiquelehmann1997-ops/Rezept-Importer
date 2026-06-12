package de.dml.rezeptimporter.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.llm.FallbackExtractor
import de.dml.rezeptimporter.llm.GeminiExtractor
import de.dml.rezeptimporter.llm.HaikuExtractor
import de.dml.rezeptimporter.llm.LlmExtractor
import de.dml.rezeptimporter.link.RecipeLinkResolver
import de.dml.rezeptimporter.ocr.OcrTextExtractor
import de.dml.rezeptimporter.pipeline.ImportPipeline
import de.dml.rezeptimporter.pipeline.extractShareUrl
import de.dml.rezeptimporter.settings.AppSettings
import de.dml.rezeptimporter.settings.Provider
import de.dml.rezeptimporter.ui.theme.ArcaneCard
import de.dml.rezeptimporter.ui.theme.ArcanePrimaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneTag
import de.dml.rezeptimporter.ui.theme.ArcaneTheme
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.vault.SafVaultStorage
import de.dml.rezeptimporter.vault.VaultWriter
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Rotierende Statuszeilen, damit der LLM-Call (10-30 s) nicht tot wirkt. */
private val CastingFlavor = listOf(
    "Runen werden entziffert …",
    "Zutaten werden identifiziert …",
    "Mengen werden ausgewogen …",
    "Nährwerte werden gewogen …",
    "Der Schreiber füllt die Schriftrolle …",
)

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
            ArcaneTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding(),
                ) {
                    when (val s = state.value) {
                        is ImportState.Working -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            ArcaneCard(Modifier.padding(24.dp)) {
                                var flavorIndex by remember { mutableIntStateOf(0) }
                                LaunchedEffect(Unit) {
                                    while (true) {
                                        delay(2200)
                                        flavorIndex = (flavorIndex + 1) % CastingFlavor.size
                                    }
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        "Zauber wird gewirkt",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        CastingFlavor[flavorIndex],
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        is ImportState.Preview -> PreviewScreen(
                            initial = s.draft,
                            onSave = ::save,
                            onCancel = { clearPhotoCache(); finish() },
                        )
                        is ImportState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            ArcaneCard(Modifier.padding(24.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Quest fehlgeschlagen",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ArcaneTag(
                                        "[RETRY?]",
                                        color = MaterialTheme.colorScheme.tertiary,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(s.message, style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(16.dp))
                                ArcanePrimaryButton("Schließen", { finish() })
                            }
                        }
                    }
                }
            }
        }

        runImport()
    }

    /** Gewählter Provider zuerst; bei Technik-Fehlern (HTTP/Netz) springt der andere ein, falls sein Key da ist. */
    private fun buildExtractor(): LlmExtractor {
        val gemini = settings.geminiKey.takeIf { it.isNotBlank() }?.let { GeminiExtractor(it, httpClient) }
        val haiku = settings.anthropicKey.takeIf { it.isNotBlank() }?.let { HaikuExtractor(it, httpClient) }
        val (primary, secondary) = when (settings.provider) {
            Provider.GEMINI -> gemini to haiku
            Provider.HAIKU -> haiku to gemini
        }
        checkNotNull(primary) { "Kein API-Key für den gewählten Provider — in der App unter Settings eintragen" }
        return if (secondary != null) FallbackExtractor(primary, secondary) else primary
    }

    private fun runImport() {
        lifecycleScope.launch {
            try {
                if (settings.vaultUri == null) {
                    state.value = ImportState.Error("Kein Vault-Ordner gewählt — erst App öffnen und Ordner wählen.")
                    return@launch
                }
                val source = collectSourceText()
                if (source.isBlank()) {
                    state.value = ImportState.Error("Kein Text gefunden (OCR leer?). Tipp: Screenshot mit gut lesbarem Text teilen.")
                    return@launch
                }
                // Link (auch mit Share-Boilerplate drumherum): erst zu Rezept-Text auflösen
                // (Web-Portale via JSON-LD, TikTok/Instagram via Caption), dann durch dieselbe
                // LLM-Pipeline. Schlägt das fehl, landet die LinkResolveException im äußeren
                // catch als Fehlermeldung.
                val shareUrl = extractShareUrl(source)
                val rawText = if (shareUrl != null) {
                    RecipeLinkResolver(httpClient).resolve(shareUrl)
                } else {
                    source
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
                clearPhotoCache()
                finish()
            } catch (e: Exception) {
                state.value = ImportState.Error("Speichern fehlgeschlagen: ${e.message}")
            }
        }
    }

    /** In-App aufgenommene Fotos (cache/fotos/) nach dem Import aufräumen. */
    private fun clearPhotoCache() {
        File(cacheDir, "fotos").listFiles()?.forEach { it.delete() }
    }
}
