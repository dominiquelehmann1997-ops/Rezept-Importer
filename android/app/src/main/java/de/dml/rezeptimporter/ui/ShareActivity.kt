package de.dml.rezeptimporter.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import de.dml.rezeptimporter.R
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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Rotierende Statuszeilen, damit der LLM-Call (10-30 s) nicht tot wirkt. */
private const val KEY_DRAFT = "recipe_draft"

private val ProgressLines = listOf(
    "Text wird gelesen …",
    "Zutaten werden erkannt …",
    "Mengen werden zugeordnet …",
    "Nährwerte werden übernommen …",
    "Markdown wird erstellt …",
)

sealed interface ImportState {
    data object Working : ImportState
    data class Preview(val draft: RecipeDraft) : ImportState
    data class Error(val message: String) : ImportState
}

class ShareActivity : ComponentActivity() {

    private val state = mutableStateOf<ImportState>(ImportState.Working)
    private val showDiscardDialog = mutableStateOf(false)
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

        val restoredDraft = savedInstanceState?.getString(KEY_DRAFT)
            ?.let { runCatching { Json.decodeFromString(RecipeDraft.serializer(), it) }.getOrNull() }
        if (restoredDraft != null) state.value = ImportState.Preview(restoredDraft)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (state.value is ImportState.Preview) showDiscardDialog.value = true
                else finish()
            }
        })

        setContent {
            ArcaneTheme(darkOverride = settings.darkMode) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding(),
                ) {
                    when (val s = state.value) {
                        is ImportState.Working -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            ArcaneCard(Modifier.padding(24.dp)) {
                                var lineIndex by remember { mutableIntStateOf(0) }
                                LaunchedEffect(Unit) {
                                    while (true) {
                                        delay(2200)
                                        lineIndex = (lineIndex + 1) % ProgressLines.size
                                    }
                                }
                                // App-Logo pulsiert sanft, solange das LLM arbeitet.
                                val pulse = rememberInfiniteTransition(label = "pulse")
                                val scale by pulse.animateFloat(
                                    initialValue = 0.92f,
                                    targetValue = 1.06f,
                                    animationSpec = infiniteRepeatable(
                                        tween(900), RepeatMode.Reverse,
                                    ),
                                    label = "pulseScale",
                                )
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Image(
                                        painterResource(R.drawable.obsididine_logo),
                                        contentDescription = null,
                                        modifier = Modifier.size(80.dp).scale(scale),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.outline,
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        "Rezept wird extrahiert",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        ProgressLines[lineIndex],
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        is ImportState.Preview -> PreviewScreen(
                            initial = s.draft,
                            folders = settings.saveFolders,
                            defaultFolder = settings.saveFolder,
                            onSave = ::save,
                            onCancel = { clearPhotoCache(); finish() },
                        )
                        is ImportState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            ArcaneCard(Modifier.padding(24.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Import fehlgeschlagen",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ArcaneTag(
                                        "[FEHLER]",
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
                    if (showDiscardDialog.value) {
                        AlertDialog(
                            onDismissRequest = { showDiscardDialog.value = false },
                            title = { Text("Rezept verwerfen?") },
                            text = { Text("Das extrahierte Rezept wird nicht gespeichert.") },
                            confirmButton = {
                                TextButton(onClick = { clearPhotoCache(); finish() }) {
                                    Text("Verwerfen")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDiscardDialog.value = false }) {
                                    Text("Zurück")
                                }
                            },
                        )
                    }
                }
            }
        }

        if (restoredDraft == null) runImport()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (state.value as? ImportState.Preview)?.let {
            outState.putString(KEY_DRAFT, Json.encodeToString(RecipeDraft.serializer(), it.draft))
        }
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

    private fun save(draft: RecipeDraft, folder: String) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val storage = SafVaultStorage(this@ShareActivity, settings.vaultUri!!, folder)
                    VaultWriter(storage, markdownWriter, validator).write(draft)
                }
                Toast.makeText(
                    this@ShareActivity,
                    "Gespeichert: /$folder/${result.fileName} (id: ${result.id})",
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
