package de.dml.rezeptimporter.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.dml.rezeptimporter.settings.AppSettings
import de.dml.rezeptimporter.settings.Provider
import de.dml.rezeptimporter.ui.theme.ArcaneCard
import de.dml.rezeptimporter.ui.theme.ArcanePrimaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneSecondaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneTag
import de.dml.rezeptimporter.ui.theme.ArcaneTheme
import de.dml.rezeptimporter.ui.theme.arcaneTextFieldColors
import java.io.File

private enum class Screen { HOME, SETTINGS }

class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings

    private val photoUris = mutableStateListOf<Uri>()
    private var pendingPhotoUri: Uri? = null

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = pendingPhotoUri
            if (ok && uri != null) photoUris.add(uri)
            pendingPhotoUri = null
        }

    private fun newPhotoUri(): Uri {
        val dir = File(cacheDir, "fotos").apply { mkdirs() }
        val file = File(dir, "rezept-${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(this, "de.dml.rezeptimporter.fileprovider", file)
    }

    private fun capturePhoto() {
        val uri = newPhotoUri()
        pendingPhotoUri = uri
        takePicture.launch(uri)
    }

    private fun startImportFromPhotos() {
        val intent = Intent(this, ShareActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(photoUris))
        }
        startActivity(intent)
        photoUris.clear()
    }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                settings.vaultUri = uri
                vaultUriState.value = uri.toString()
            }
        }

    private val vaultUriState = mutableStateOf("")

    /** Synchroner Startfehler: Stacktrace lesbar anzeigen statt stiller Crash. */
    private fun showFatalError(e: Exception) {
        setContentView(ScrollView(this).apply {
            addView(TextView(context).apply {
                text = "Start fehlgeschlagen:\n\n${e.stackTraceToString()}"
                setTextIsSelectable(true)
                setPadding(48, 96, 48, 96)
            })
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            settings = AppSettings(this)
        } catch (e: Exception) {
            showFatalError(e)
            return
        }
        vaultUriState.value = settings.vaultUri?.toString() ?: ""

        setContent {
            ArcaneTheme {
                var screen by remember { mutableStateOf(Screen.HOME) }
                var provider by remember { mutableStateOf(settings.provider) }
                var geminiKey by remember { mutableStateOf(settings.geminiKey) }
                var anthropicKey by remember { mutableStateOf(settings.anthropicKey) }
                val vaultUri by vaultUriState

                val configMissing = vaultUri.isEmpty() ||
                    (provider == Provider.GEMINI && geminiKey.isBlank()) ||
                    (provider == Provider.HAIKU && anthropicKey.isBlank())

                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .safeDrawingPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Header(
                        screen = screen,
                        onToggle = {
                            screen = if (screen == Screen.HOME) Screen.SETTINGS else Screen.HOME
                        },
                    )
                    // Vom Crash-Handler (ObsidiDineApp) gespeicherter Stacktrace des
                    // letzten Absturzes — Nutzer kann ihn screenshotten und teilen.
                    if (screen == Screen.HOME) {
                        val crashFile = File(filesDir, "last-crash.txt")
                        var crashLog by remember {
                            mutableStateOf(if (crashFile.exists()) crashFile.readText() else null)
                        }
                        crashLog?.let { log ->
                            ArcaneCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "Letzter Absturz",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ArcaneTag("[CRASH]")
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(log.take(3000), style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.height(16.dp))
                                ArcaneSecondaryButton("Verwerfen", {
                                    crashFile.delete()
                                    crashLog = null
                                })
                            }
                        }
                    }
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            photoCount = photoUris.size,
                            configMissing = configMissing,
                            onCapture = ::capturePhoto,
                            onImport = ::startImportFromPhotos,
                            onDiscard = { photoUris.clear() },
                            onOpenSettings = { screen = Screen.SETTINGS },
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            vaultUri = vaultUri,
                            onPickFolder = { pickFolder.launch(null) },
                            provider = provider,
                            onProvider = { provider = it; settings.provider = it },
                            geminiKey = geminiKey,
                            onGeminiKey = { geminiKey = it; settings.geminiKey = it },
                            anthropicKey = anthropicKey,
                            onAnthropicKey = { anthropicKey = it; settings.anthropicKey = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(screen: Screen, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("ObsidiDine", style = MaterialTheme.typography.headlineSmall)
            ArcaneTag(if (screen == Screen.HOME) "[REZEPT-IMPORTER]" else "[CONFIG]")
        }
        IconButton(onClick = onToggle) {
            Icon(
                if (screen == Screen.HOME) Icons.Filled.Settings
                else Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = if (screen == Screen.HOME) "Einstellungen" else "Zurück",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun HomeScreen(
    photoCount: Int,
    configMissing: Boolean,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onDiscard: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (configMissing) {
        ArcaneCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Setup unvollständig",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.weight(1f),
                )
                ArcaneTag("[!]")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Vault-Ordner oder API-Key fehlt — ohne beides kein Import.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            ArcaneSecondaryButton("Zu den Einstellungen", onOpenSettings)
        }
    }

    ArcaneCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Rezept fotografieren",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            ArcaneTag("[FOTOS: $photoCount]")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (photoCount == 0) "Fotos landen nur im App-Cache, nie in der Galerie."
            else "$photoCount Foto(s) aufgenommen — weitere Seite knipsen oder Import starten.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArcanePrimaryButton(
                if (photoCount == 0) "Foto aufnehmen" else "Weiteres Foto",
                onCapture,
            )
            if (photoCount > 0) {
                ArcanePrimaryButton("Rezept erstellen", onImport)
            }
        }
        if (photoCount > 0) {
            Spacer(Modifier.height(8.dp))
            ArcaneSecondaryButton("Verwerfen", onDiscard)
        }
    }

    ArcaneCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Teilen-Import",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            ArcaneTag("[SHARE]")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Foto, Text oder Link (Web, YouTube, TikTok, Instagram) aus einer " +
                "anderen App mit ObsidiDine teilen — das Rezept landet nach " +
                "Vorschau im Vault.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SettingsScreen(
    vaultUri: String,
    onPickFolder: () -> Unit,
    provider: Provider,
    onProvider: (Provider) -> Unit,
    geminiKey: String,
    onGeminiKey: (String) -> Unit,
    anthropicKey: String,
    onAnthropicKey: (String) -> Unit,
) {
    ArcaneCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Vault-Ordner",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            ArcaneTag(if (vaultUri.isEmpty()) "[LEER]" else "[OK]")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (vaultUri.isEmpty()) "— nicht gewählt —" else vaultUri,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        ArcaneSecondaryButton("Ordner wählen", onPickFolder)
    }

    ArcaneCard {
        Text("LLM-Provider", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = provider == Provider.GEMINI,
                onClick = { onProvider(Provider.GEMINI) },
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Text("Gemini Flash (Free Tier)", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = provider == Provider.HAIKU,
                onClick = { onProvider(Provider.HAIKU) },
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Text("Claude Haiku", style = MaterialTheme.typography.bodyMedium)
        }
    }

    ArcaneCard {
        var showKeys by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "API-Keys",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            ArcaneTag("[SECRET]")
        }
        Spacer(Modifier.height(8.dp))
        val transformation =
            if (showKeys) VisualTransformation.None else PasswordVisualTransformation()
        OutlinedTextField(
            value = geminiKey,
            onValueChange = onGeminiKey,
            label = { Text("Gemini API-Key") },
            singleLine = true,
            visualTransformation = transformation,
            colors = arcaneTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = anthropicKey,
            onValueChange = onAnthropicKey,
            label = { Text("Anthropic API-Key") },
            singleLine = true,
            visualTransformation = transformation,
            colors = arcaneTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = showKeys,
                onCheckedChange = { showKeys = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Text("Keys anzeigen", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
