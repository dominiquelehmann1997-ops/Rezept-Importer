package de.dml.rezeptimporter.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import de.dml.rezeptimporter.R
import de.dml.rezeptimporter.dashboard.DashboardClient
import de.dml.rezeptimporter.dashboard.StartResult
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.draft.DraftStore
import de.dml.rezeptimporter.link.LinkHosts
import de.dml.rezeptimporter.link.RecipeLinkResolver
import de.dml.rezeptimporter.link.extractShareUrl
import de.dml.rezeptimporter.ocr.OcrTextExtractor
import de.dml.rezeptimporter.settings.AppSettings
import de.dml.rezeptimporter.ui.theme.ArcaneCard
import de.dml.rezeptimporter.ui.theme.ArcanePrimaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneTag
import de.dml.rezeptimporter.ui.theme.ArcaneTheme
import de.dml.rezeptimporter.work.ImportStatusWorker
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

private const val KEY_DRAFT = "recipe_draft"

/** Schlüssel der alten (vor `DraftStore`) Entwurfs-Persistenz — nur noch für das
 *  einmalige Aufräumen unten in `onCreate` gebraucht. */
private const val KEY_DRAFT_PREFS = "draft_json"

sealed interface ImportState {
    data class Preview(val draft: RecipeDraft) : ImportState
    data class Error(val message: String) : ImportState
}

class ShareActivity : ComponentActivity() {

    // null, solange startImport() noch läuft (kollektiert Text, ruft das Dashboard) —
    // die eigentliche Wartezeit auf die Extraktion übernimmt jetzt der Worker.
    private val state = mutableStateOf<ImportState?>(null)
    private val showDiscardDialog = mutableStateOf(false)
    // Fehler beim Speichern (im Unterschied zu ImportState.Error): die Preview mit
    // den Bearbeitungen bleibt stehen, nur ein Dialog meldet den Fehler — sonst
    // wären Namens-/Zutaten-Korrekturen nach einem fehlgeschlagenen Save weg.
    private val saveError = mutableStateOf<String?>(null)
    private lateinit var settings: AppSettings
    // Job-Id, wenn dieser Aufruf aus der Benachrichtigung kommt — steuert das
    // Aufräumen in save() (Entwurf aus dem DraftStore, Benachrichtigung wegwischen).
    private var reviewJobId: String? = null

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

        // Alter Schlüssel aus der Zeit vor DraftStore (ersetzt in Task 5) — einmaliges
        // Aufräumen, damit er nicht für immer ungenutzt in den Prefs liegt. Kann in
        // einer späteren Version ganz entfernt werden.
        getSharedPreferences("import_draft", MODE_PRIVATE).edit().remove(KEY_DRAFT_PREFS).apply()

        val restoredDraft = savedInstanceState?.getString(KEY_DRAFT)
            ?.let { runCatching { Json.decodeFromString(RecipeDraft.serializer(), it) }.getOrNull() }

        val jobId = intent.getStringExtra(EXTRA_JOB_ID)
        reviewJobId = jobId
        when {
            restoredDraft != null -> state.value = ImportState.Preview(restoredDraft)
            jobId != null -> {
                // Einstieg aus der Benachrichtigung: Entwurf liegt lokal.
                val draft = DraftStore(settings.notificationPrefs).get(jobId)
                state.value = if (draft != null) ImportState.Preview(draft)
                else ImportState.Error("Import abgelaufen — bitte erneut teilen.")
            }
            else -> startImport()
        }

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
                        // Kein eigener "Working"-Zustand mehr: startImport() übergibt
                        // schnell an den Worker (kein Warten auf die Extraktion), bis
                        // dahin genügt ein schlichter Ladehinweis.
                        null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            ArcaneCard(Modifier.padding(24.dp)) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Image(
                                        painterResource(R.drawable.obsididine_logo),
                                        contentDescription = null,
                                        modifier = Modifier.size(80.dp),
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.outline,
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        "Rezept wird übergeben",
                                        style = MaterialTheme.typography.titleMedium,
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (state.value as? ImportState.Preview)?.let {
            outState.putString(KEY_DRAFT, Json.encodeToString(RecipeDraft.serializer(), it.draft))
        }
    }

    /**
     * Kollektiert den Quelltext, stößt den Import an und übergibt sofort an den
     * Worker (Task 4) — kein Warten mehr auf die Extraktion im Vordergrund. Der
     * Nutzer bekommt einen Toast und die Activity schließt sich; das Ergebnis
     * meldet eine Benachrichtigung.
     */
    private fun startImport() {
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
                    state.value = ImportState.Error(
                        "Kein Text gefunden (OCR leer?). Tipp: Screenshot mit gut lesbarem Text teilen."
                    )
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
                val socialUrl = shareUrl?.takeIf { LinkHosts.isSocial(it) || LinkHosts.isYouTube(it) }
                val start = when {
                    socialUrl != null ->
                        dashboard.startParse(RecipeLinkResolver(httpClient).resolve(socialUrl), socialUrl)
                    shareUrl != null -> dashboard.startParse("", shareUrl)
                    else -> dashboard.startParse(source, null)
                }

                when (start) {
                    is StartResult.Started -> {
                        ImportStatusWorker.enqueue(this@ShareActivity, start.jobId, settings)
                        DraftStore(settings.notificationPrefs).sweep()
                        Toast.makeText(
                            this@ShareActivity,
                            "An Cockpit übergeben — Benachrichtigung folgt",
                            Toast.LENGTH_SHORT,
                        ).show()
                        finish()
                    }
                    // Alter Server: Rezept ist schon da, direkt in die Vorschau.
                    is StartResult.Immediate -> state.value = ImportState.Preview(start.draft)
                }
            } catch (e: Exception) {
                state.value = ImportState.Error(e.message ?: "Import fehlgeschlagen.")
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
                val result = DashboardClient(
                    baseUrl = settings.dashboardUrl,
                    token = settings.importToken,
                    cfClientId = settings.cfClientId,
                    cfClientSecret = settings.cfClientSecret,
                    client = httpClient,
                ).save(draft)
                reviewJobId?.let {
                    DraftStore(settings.notificationPrefs).remove(it)
                    NotificationManagerCompat.from(this@ShareActivity).cancel(it.hashCode())
                }
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
        File(cacheDir, "fotos").listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** Job-Id im Intent, mit dem die Benachrichtigung die Activity wieder öffnet. */
        const val EXTRA_JOB_ID = "job_id"
    }
}
