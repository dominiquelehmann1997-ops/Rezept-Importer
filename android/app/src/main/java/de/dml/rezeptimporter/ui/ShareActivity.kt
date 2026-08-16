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
import de.dml.rezeptimporter.domain.ImportSource
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.domain.SourceVideo
import de.dml.rezeptimporter.llm.FallbackExtractor
import de.dml.rezeptimporter.llm.GeminiExtractor
import de.dml.rezeptimporter.llm.HaikuExtractor
import de.dml.rezeptimporter.llm.LlmExtractor
import de.dml.rezeptimporter.link.LinkHosts
import de.dml.rezeptimporter.link.RecipeLinkResolver
import de.dml.rezeptimporter.ocr.OcrTextExtractor
import de.dml.rezeptimporter.pipeline.CaptionPark
import de.dml.rezeptimporter.pipeline.ImportPipeline
import de.dml.rezeptimporter.pipeline.extractShareUrl
import de.dml.rezeptimporter.pipeline.firstUrl
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
private const val KEY_DRAFT_PREFS = "draft_json"

private val ProgressLines = listOf(
    "Text wird gelesen …",
    "Zutaten werden erkannt …",
    "Mengen werden zugeordnet …",
    "Nährwerte werden übernommen …",
    "Markdown wird erstellt …",
)

/** Video-Importe dauern länger (Upload + Verarbeitung) — eigene Zeilen, damit der Wartebalken erklärt ist. */
private val VideoProgressLines = listOf(
    "Video wird übertragen …",
    "Bild und Ton werden ausgewertet …",
    "Caption und Video werden zusammengeführt …",
    "Mengen werden zugeordnet …",
    "Markdown wird erstellt …",
)

sealed interface ImportState {
    data class Working(val video: Boolean = false) : ImportState
    /** Vorschaltschritt beim Teilen einer Videodatei: Caption und Quelllink ergänzen. */
    data class VideoDetails(
        val file: File,
        val mimeType: String,
        val caption: String,
        val sourceUrl: String,
        val captionFromPark: Boolean,
    ) : ImportState
    data class Preview(val draft: RecipeDraft, val hint: String? = null) : ImportState
    data class Error(val message: String) : ImportState
}

class ShareActivity : ComponentActivity() {

    private val state = mutableStateOf<ImportState>(ImportState.Working())
    private val showDiscardDialog = mutableStateOf(false)
    private lateinit var settings: AppSettings
    private lateinit var validator: RecipeValidator
    private val markdownWriter = RecipeMarkdownWriter()
    private val draftPrefs by lazy { getSharedPreferences("import_draft", MODE_PRIVATE) }
    private val captionPark by lazy { CaptionPark(this) }

    private fun persistDraft(draft: RecipeDraft?) = draftPrefs.edit().apply {
        if (draft == null) remove(KEY_DRAFT_PREFS)
        else putString(KEY_DRAFT_PREFS, Json.encodeToString(RecipeDraft.serializer(), draft))
    }.apply()

    private fun loadPersistedDraft(): RecipeDraft? =
        draftPrefs.getString(KEY_DRAFT_PREFS, null)
            ?.let { runCatching { Json.decodeFromString(RecipeDraft.serializer(), it) }.getOrNull() }

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
            ?: loadPersistedDraft()
        if (restoredDraft != null) state.value = ImportState.Preview(restoredDraft)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (state.value is ImportState.Preview) {
                    showDiscardDialog.value = true
                } else {
                    // Video-Zwischenschritt abgebrochen: die Cache-Kopie soll nicht liegenbleiben.
                    if (state.value is ImportState.VideoDetails) clearImportCache()
                    finish()
                }
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
                            val lines = if (s.video) VideoProgressLines else ProgressLines
                            ArcaneCard(Modifier.padding(24.dp)) {
                                var lineIndex by remember { mutableIntStateOf(0) }
                                LaunchedEffect(Unit) {
                                    while (true) {
                                        delay(2200)
                                        lineIndex = (lineIndex + 1) % lines.size
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
                                        lines[lineIndex],
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        is ImportState.VideoDetails -> VideoDetailsScreen(
                            initialCaption = s.caption,
                            initialSourceUrl = s.sourceUrl,
                            captionFromPark = s.captionFromPark,
                            onStart = { caption, url ->
                                startVideoImport(s.file, s.mimeType, caption, url)
                            },
                            onCancel = { clearImportCache(); finish() },
                        )
                        is ImportState.Preview -> PreviewScreen(
                            initial = s.draft,
                            folders = settings.saveFolders,
                            defaultFolder = settings.saveFolder,
                            hint = s.hint,
                            onSave = ::save,
                            onCancel = { clearImportCache(); finish() },
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
                                TextButton(onClick = { clearImportCache(); finish() }) {
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

    /**
     * Gewählter Provider zuerst; bei Technik-Fehlern (HTTP/Netz) springt der andere ein, falls sein
     * Key da ist. Bei Video-Quellen entfällt die Wahl: nur Gemini verarbeitet Video, ein Ausweichen
     * auf Haiku würde ein Rezept ohne die Video-Hälfte liefern.
     */
    private fun buildExtractor(needsVideo: Boolean): LlmExtractor {
        val gemini = settings.geminiKey.takeIf { it.isNotBlank() }?.let { GeminiExtractor(it, httpClient) }
        val haiku = settings.anthropicKey.takeIf { it.isNotBlank() }?.let { HaikuExtractor(it, httpClient) }
        if (needsVideo) {
            return checkNotNull(gemini) {
                "Video-Import läuft nur über Gemini — Gemini-Key in den Einstellungen eintragen."
            }
        }
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
                // Videodatei: erst den Zwischenschritt zeigen, damit die Caption ergänzt werden
                // kann — ohne sie fehlen dem Video regelmäßig die Mengen.
                val videoUri = sharedVideoUri()
                if (videoUri != null) {
                    val file = withContext(Dispatchers.IO) { copyVideoToCache(videoUri) }
                    val parked = captionPark.peek()
                    state.value = ImportState.VideoDetails(
                        file = file,
                        mimeType = contentResolver.getType(videoUri) ?: intent.type ?: "video/mp4",
                        caption = parked?.caption.orEmpty(),
                        sourceUrl = parked?.sourceUrl.orEmpty(),
                        captionFromPark = parked != null,
                    )
                    return@launch
                }

                val source = collectSource()
                if (!source.hasContent) {
                    state.value = ImportState.Error("Kein Text gefunden (OCR leer?). Tipp: Screenshot mit gut lesbarem Text teilen.")
                    return@launch
                }
                runExtraction(source, hint = twoStepHint(source))
            } catch (e: Exception) {
                state.value = ImportState.Error(e.message ?: "Unbekannter Fehler")
            }
        }
    }

    /** Video + (optionale) Caption gehen gemeinsam in denselben LLM-Call. */
    private fun startVideoImport(file: File, mimeType: String, caption: String, sourceUrl: String) {
        val source = ImportSource(video = SourceVideo.Local(file, mimeType))
            .plusText(ImportSource.LABEL_CAPTION, caption)
            .withSourceUrl(sourceUrl)
        lifecycleScope.launch { runExtraction(source, hint = null) }
    }

    private suspend fun runExtraction(source: ImportSource, hint: String?) {
        try {
            state.value = ImportState.Working(video = source.video != null)
            val extractor = buildExtractor(needsVideo = source.video != null)
            val draft = ImportPipeline(extractor, validator, markdownWriter).extractValidated(source)
            persistDraft(draft)
            state.value = ImportState.Preview(draft, hint)
        } catch (e: Exception) {
            state.value = ImportState.Error(e.message ?: "Unbekannter Fehler")
        }
    }

    /**
     * Nach einem Caption-Import ohne Video: auf den zweiten Schritt hinweisen. Ohne den Hinweis
     * findet niemand den Weg, ein Reel doch noch vollständig zu importieren.
     */
    private fun twoStepHint(source: ImportSource): String? =
        if (source.video == null && source.sourceUrl?.let { LinkHosts.isSocial(it) } == true) {
            "Fehlen Schritte oder Mengen? Sie stehen dann im Video. Video speichern und mit " +
                "ObsidiDine teilen — die Caption von eben wird automatisch ergänzt."
        } else null

    private fun sharedVideoUri(): Uri? {
        if (intent.action != Intent.ACTION_SEND) return null
        if (intent.type?.startsWith("video/") != true) return null
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    /**
     * Die geteilte content:// -URI ist nur solange lesbar, wie diese Activity lebt — für den
     * Upload braucht es eine eigene Kopie im Cache.
     */
    private fun copyVideoToCache(uri: Uri): File {
        val dir = File(cacheDir, "videos").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // nur ein Import gleichzeitig
        val file = File(dir, "geteiltes-video")
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Video konnte nicht gelesen werden.")
        if (file.length() == 0L) throw IllegalStateException("Geteiltes Video ist leer.")
        return file
    }

    private suspend fun collectSource(): ImportSource {
        val ocr = OcrTextExtractor(this)
        return when (intent.action) {
            Intent.ACTION_SEND -> when {
                intent.type == "text/plain" ->
                    fromSharedText(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "")
                intent.type?.startsWith("image/") == true -> {
                    @Suppress("DEPRECATION")
                    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    val text = if (uri != null) ocr.extract(listOf(uri)) else ""
                    ImportSource.ofText(ImportSource.LABEL_SCREENSHOT, text)
                }
                else -> ImportSource()
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                ImportSource.ofText(ImportSource.LABEL_SCREENSHOT, ocr.extract(uris))
            }
            else -> ImportSource()
        }
    }

    /**
     * Link (auch mit Share-Boilerplate drumherum): erst zu Rezept-Quellen auflösen (Web-Portale
     * via JSON-LD, TikTok/Instagram via Caption, YouTube via Beschreibung + ggf. Video). Ist der
     * geteilte Text selbst die Caption, bleibt er Text — ein enthaltener Link dient dann nur als
     * Quellenangabe.
     */
    private suspend fun fromSharedText(text: String): ImportSource {
        val shareUrl = extractShareUrl(text)
        if (shareUrl == null) {
            return ImportSource.ofText(ImportSource.LABEL_SHARED_TEXT, text, sourceUrl = firstUrl(text))
        }
        val resolved = RecipeLinkResolver(httpClient).resolve(shareUrl)
        // Caption eines Reels parken: kommt gleich die Videodatei hinterher, werden beide
        // Quellen zusammengeführt.
        if (resolved.video == null && LinkHosts.isSocial(shareUrl)) {
            resolved.nonEmptyTexts.firstOrNull()?.let { captionPark.park(it.text, shareUrl) }
        }
        return resolved
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
                // Import abgeschlossen — geparkte Caption gehört nicht ans nächste Video.
                captionPark.clear()
                clearImportCache()
                finish()
            } catch (e: Exception) {
                state.value = ImportState.Error("Speichern fehlgeschlagen: ${e.message}")
            }
        }
    }

    /** In-App aufgenommene Fotos und die Videokopie nach dem Import aufräumen. */
    private fun clearImportCache() {
        persistDraft(null)
        File(cacheDir, "fotos").listFiles()?.forEach { it.delete() }
        File(cacheDir, "videos").listFiles()?.forEach { it.delete() }
    }
}
