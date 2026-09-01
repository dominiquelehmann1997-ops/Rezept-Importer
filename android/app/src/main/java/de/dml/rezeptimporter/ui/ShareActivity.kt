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
import de.dml.rezeptimporter.dashboard.DashboardClient
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.link.LinkHosts
import de.dml.rezeptimporter.link.RecipeLinkResolver
import de.dml.rezeptimporter.link.extractShareUrl
import de.dml.rezeptimporter.ocr.OcrTextExtractor
import de.dml.rezeptimporter.settings.AppSettings
import de.dml.rezeptimporter.ui.theme.ArcaneCard
import de.dml.rezeptimporter.ui.theme.ArcanePrimaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneTag
import de.dml.rezeptimporter.ui.theme.ArcaneTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    "Rezept wird geprüft …",
)

sealed interface ImportState {
    data object Working : ImportState
    data class Preview(val draft: RecipeDraft) : ImportState
    data class Error(val message: String) : ImportState
}

class ShareActivity : ComponentActivity() {

    private val state = mutableStateOf<ImportState>(ImportState.Working)
    private val showDiscardDialog = mutableStateOf(false)
    // Fehler beim Speichern (im Unterschied zu ImportState.Error): die Preview mit
    // den Bearbeitungen bleibt stehen, nur ein Dialog meldet den Fehler — sonst
    // wären Namens-/Zutaten-Korrekturen nach einem fehlgeschlagenen Save weg.
    private val saveError = mutableStateOf<String?>(null)
    private lateinit var settings: AppSettings
    private val draftPrefs by lazy { getSharedPreferences("import_draft", MODE_PRIVATE) }

    private fun persistDraft(draft: RecipeDraft?) = draftPrefs.edit().apply {
        if (draft == null) remove(KEY_DRAFT_PREFS)
        else putString(KEY_DRAFT_PREFS, Json.encodeToString(RecipeDraft.serializer(), draft))
    }.apply()

    private fun loadPersistedDraft(): RecipeDraft? =
        draftPrefs.getString(KEY_DRAFT_PREFS, null)
            ?.let { runCatching { Json.decodeFromString(RecipeDraft.serializer(), it) }.getOrNull() }

    // LLM-Calls können >10s dauern, die Extraktion macht bis zu zwei — der Server
    // begrenzt sie serverseitig auf ~90s (siehe recipeExtract.ts), damit SEINE
    // Fehlermeldung (401, "keine Rezeptdaten" o.ä.) uns erreicht, statt dass der
    // Client zuerst aufgibt und nur einen Netzwerkfehler zeigt, obwohl das
    // Abo-Kontingent schon verbraucht ist. 120s hier ist also eine Obergrenze,
    // kein Versprechen — ein Cloudflare-Tunnel kappt ohnehin bei ~100s.
    //
    // readTimeout MUSS mitgesetzt werden: callTimeout deckelt nur die Gesamtdauer,
    // der Default für die einzelne Socket-Lese bleibt sonst bei 10s. /api/recipes/parse
    // schickt bis zur fertigen Extraktion kein einziges Byte (gemessen: 42s für eine
    // HelloFresh-Karte) — mit dem Default bricht OkHttp nach 10s mit
    // SocketTimeoutException("timeout") ab, was in der App als
    // "Dashboard nicht erreichbar: timeout" landet.
    private val httpClient = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)

        val restoredDraft = savedInstanceState?.getString(KEY_DRAFT)
            ?.let { runCatching { Json.decodeFromString(RecipeDraft.serializer(), it) }.getOrNull() }
            ?: loadPersistedDraft()
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
                    saveError.value?.let { message ->
                        AlertDialog(
                            onDismissRequest = { saveError.value = null },
                            title = { Text("Speichern fehlgeschlagen") },
                            text = { Text(message) },
                            confirmButton = {
                                TextButton(onClick = { saveError.value = null }) { Text("OK") }
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

    private fun runImport() {
        lifecycleScope.launch {
            try {
                if (settings.dashboardUrl.isBlank() || settings.importToken.isBlank()) {
                    state.value = ImportState.Error(
                        "Dashboard nicht eingerichtet — erst App öffnen, Adresse und Token eintragen."
                    )
                    return@launch
                }
                val source = collectSourceText()
                if (source.isBlank()) {
                    state.value = ImportState.Error("Kein Text gefunden (OCR leer?). Tipp: Screenshot mit gut lesbarem Text teilen.")
                    return@launch
                }
                // Link (auch mit Share-Boilerplate drumherum): Instagram/TikTok/YouTube-Caption
                // holt weiter die App (sie hat den Link aus dem Share-Intent), ein reiner
                // Web-Link geht roh ans Dashboard, das ihn ohne LLM auflöst.
                val shareUrl = extractShareUrl(source)
                val dashboard = DashboardClient(
                    baseUrl = settings.dashboardUrl,
                    token = settings.importToken,
                    cfClientId = settings.cfClientId,
                    cfClientSecret = settings.cfClientSecret,
                    client = httpClient,
                )
                val socialUrl = shareUrl?.takeIf {
                    LinkHosts.isSocial(it) || LinkHosts.isYouTube(it)
                }
                val draft = when {
                    // Instagram/TikTok/YouTube: die Caption bzw. Beschreibung holt weiter
                    // die App — sie hat die Links aus dem Share-Intent.
                    socialUrl != null ->
                        dashboard.parse(RecipeLinkResolver(httpClient).resolve(socialUrl), socialUrl)
                    // Web-Portal: roh ans Dashboard, das löst es ohne LLM aus dem Markup.
                    shareUrl != null -> dashboard.parse("", shareUrl)
                    else -> dashboard.parse(source, null)
                }
                persistDraft(draft)
                state.value = ImportState.Preview(draft)
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
        // Sofort die bearbeitete Fassung sichern, nicht erst nach Erfolg: schlägt der
        // Request fehl (abgelaufener Token, Dashboard offline, Cloudflare-403, ein
        // ungültiger Slug), wäre sonst beim Wiederöffnen wieder nur der unbearbeitete
        // Parse-Entwurf da — alle Korrekturen wären weg.
        persistDraft(draft)
        lifecycleScope.launch {
            try {
                val result = DashboardClient(
                    baseUrl = settings.dashboardUrl,
                    token = settings.importToken,
                    cfClientId = settings.cfClientId,
                    cfClientSecret = settings.cfClientSecret,
                    client = httpClient,
                ).save(draft)
                Toast.makeText(
                    this@ShareActivity,
                    if (result.updated) "Aktualisiert: ${result.name}" else "Gespeichert: ${result.name}",
                    Toast.LENGTH_LONG,
                ).show()
                clearPhotoCache()
                finish()
            } catch (e: Exception) {
                // Preview bleibt stehen (nicht ImportState.Error): der Nutzer soll den
                // Namen/die Zutaten korrigieren und erneut speichern können, statt bei
                // "Schließen" alles zu verlieren.
                saveError.value = "Speichern fehlgeschlagen: ${e.message}"
            }
        }
    }

    /** In-App aufgenommene Fotos (cache/fotos/) nach dem Import aufräumen. */
    private fun clearPhotoCache() {
        persistDraft(null)
        File(cacheDir, "fotos").listFiles()?.forEach { it.delete() }
    }
}
