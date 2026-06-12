package de.dml.rezeptimporter.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import de.dml.rezeptimporter.R
import de.dml.rezeptimporter.settings.AppSettings
import de.dml.rezeptimporter.settings.Provider
import de.dml.rezeptimporter.ui.theme.ArcaneCard
import de.dml.rezeptimporter.ui.theme.ArcaneCardTitle
import de.dml.rezeptimporter.ui.theme.ArcaneChip
import de.dml.rezeptimporter.ui.theme.ArcanePrimaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneSecondaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneSlotRow
import de.dml.rezeptimporter.ui.theme.ArcaneStatBar
import de.dml.rezeptimporter.ui.theme.ArcaneTag
import de.dml.rezeptimporter.ui.theme.ArcaneTheme
import de.dml.rezeptimporter.ui.theme.BlinkingCursor
import de.dml.rezeptimporter.ui.theme.arcaneTextFieldColors
import java.io.File

/** Zufälliger Tipp pro App-Start. */
private val StartTips = listOf(
    "Screenshots mit gut lesbarem Text liefern die beste OCR-Qualität.",
    "Cookidoo-Links liefern Zutaten und Nährwerte — die Schritte bleiben im Abo.",
    "YouTube-Rezepte stehen meist in der Videobeschreibung.",
    "Mehrseitige Rezepte: erst alle Seiten fotografieren, dann importieren.",
    "Jedes Rezept wird eine eigene Markdown-Datei im Vault.",
    "Instagram ohne Rezept in der Caption: Screenshot der Zutaten teilen.",
)

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
            var darkMode by remember { mutableStateOf(settings.darkMode) }
            ArcaneTheme(darkOverride = darkMode) {
                val dark = darkMode ?: isSystemInDarkTheme()
                var screen by remember { mutableStateOf(Screen.HOME) }
                var provider by remember { mutableStateOf(settings.provider) }
                var geminiKey by remember { mutableStateOf(settings.geminiKey) }
                var anthropicKey by remember { mutableStateOf(settings.anthropicKey) }
                val vaultUri by vaultUriState

                val vaultOk = vaultUri.isNotEmpty()
                val keyOk = when (provider) {
                    Provider.GEMINI -> geminiKey.isNotBlank()
                    Provider.HAIKU -> anthropicKey.isNotBlank()
                }

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
                        dark = dark,
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
                            vaultOk = vaultOk,
                            keyOk = keyOk,
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
                            dark = dark,
                            onDarkMode = { darkMode = it; settings.darkMode = it },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(screen: Screen, dark: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.obsididine_logo),
            contentDescription = null,
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ObsidiDine", style = MaterialTheme.typography.headlineSmall)
                BlinkingCursor()
            }
            val mode = if (dark) "[DUNKEL]" else "[HELL]"
            ArcaneTag(
                if (screen == Screen.HOME) "[REZEPT-IMPORTER] $mode" else "[EINSTELLUNGEN] $mode"
            )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(
    photoCount: Int,
    vaultOk: Boolean,
    keyOk: Boolean,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onDiscard: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Setup-Check: ohne Vault + Key kein Import.
    if (!vaultOk || !keyOk) {
        val done = listOf(vaultOk, keyOk).count { it }
        ArcaneCard {
            ArcaneCardTitle(
                "Setup unvollständig",
                tag = "[$done/2]",
                titleColor = MaterialTheme.colorScheme.tertiary,
                tagColor = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(12.dp))
            ArcaneStatBar(done / 2f, color = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.height(12.dp))
            EquipmentRow("Vault-Ordner", vaultOk)
            EquipmentRow("API-Key für gewählten Provider", keyOk)
            Spacer(Modifier.height(16.dp))
            ArcaneSecondaryButton("Zu den Einstellungen", onOpenSettings)
        }
    }

    ArcaneCard {
        ArcaneCardTitle("Rezept fotografieren", tag = "[FOTOS: $photoCount]")
        Spacer(Modifier.height(12.dp))
        ArcaneSlotRow(filled = photoCount)
        Spacer(Modifier.height(12.dp))
        Text(
            if (photoCount == 0)
                "Kochbuch-Seite, Rezeptkarte oder Bildschirm abfotografieren. " +
                    "Fotos bleiben im App-Cache — nie in der Galerie."
            else
                "$photoCount Foto(s) aufgenommen — weitere Seite fotografieren " +
                    "oder Import starten.",
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
        ArcaneCardTitle("Teilen-Import", tag = "[QUELLEN]")
        Spacer(Modifier.height(8.dp))
        Text(
            "Rezept aus einer anderen App mit ObsidiDine teilen — Link, Text " +
                "oder Screenshot genügt. Unterstützte Quellen:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("WEB", "COOKIDOO", "YOUTUBE", "TIKTOK", "INSTAGRAM", "SCREENSHOT", "TEXT")
                .forEach { ArcaneChip("[$it]") }
        }
    }

    // Zufalls-Tipp pro App-Start, unaufdringlich unter den Karten.
    val tip = remember { StartTips.random() }
    Column {
        ArcaneTag("[TIPP]")
        Spacer(Modifier.height(4.dp))
        Text(
            tip,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Eine Zeile des Ausrüstungs-Checks: Status-Raute + Beschriftung. */
@Composable
private fun EquipmentRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ArcaneTag(
            if (ok) "◆" else "◇",
            color = if (ok) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        ArcaneTag(
            if (ok) "[OK]" else "[FEHLT]",
            color = if (ok) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.tertiary,
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
    dark: Boolean,
    onDarkMode: (Boolean) -> Unit,
) {
    ArcaneCard {
        ArcaneCardTitle("Darstellung", tag = if (dark) "[DUNKEL]" else "[HELL]")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Dark Mode",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = dark,
                onCheckedChange = onDarkMode,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedTrackColor = MaterialTheme.colorScheme.background,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }

    ArcaneCard {
        ArcaneCardTitle(
            "Vault-Ordner",
            tag = if (vaultUri.isEmpty()) "[FEHLT]" else "[OK]",
            tagColor = if (vaultUri.isEmpty()) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.secondary,
        )
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
        ArcaneCardTitle("LLM-Provider", tag = "[KI]")
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
        ArcaneCardTitle("API-Keys", tag = "[GEHEIM]")
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
