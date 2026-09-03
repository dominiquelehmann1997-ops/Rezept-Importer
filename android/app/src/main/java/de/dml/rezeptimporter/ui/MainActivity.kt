package de.dml.rezeptimporter.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import de.dml.rezeptimporter.R
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.draft.DraftStore
import de.dml.rezeptimporter.settings.AppSettings
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
    "Jedes importierte Rezept landet direkt in der Rezept-Datenbank des Dashboards.",
    "Instagram ohne Rezept in der Caption: Screenshot der Zutaten teilen.",
)

private enum class Screen { HOME, SETTINGS }

private const val KEY_PHOTO_URIS = "photo_uris"
private const val KEY_PENDING_URI = "pending_photo_uri"

class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings

    private val photoUris = mutableStateListOf<Uri>()
    private var pendingPhotoUri: Uri? = null

    private val drafts: DraftStore by lazy { DraftStore.of(this) }

    // Fertige, noch nicht abgenickte Importe. Ohne diese Liste wäre ein Rezept nur
    // über die Benachrichtigung erreichbar — ist die verweigert oder abgeschaltet,
    // läge es unsichtbar im Store, bis `sweep` es nach 7 Tagen wegwirft.
    private val pendingDrafts = mutableStateOf<List<Pair<String, RecipeDraft>>>(emptyList())

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val uri = pendingPhotoUri
            if (ok && uri != null) photoUris.add(uri)
            pendingPhotoUri = null
        }

    // Steuert den Hinweis in den Einstellungen, wenn POST_NOTIFICATIONS verweigert
    // wurde — der Import läuft trotzdem, nur ohne Benachrichtigung am Ende.
    private val notificationsDenied = mutableStateOf(false)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsDenied.value = !granted
        }

    /**
     * Fragt POST_NOTIFICATIONS an, wenn nötig — bewusst hier beim Öffnen der
     * Einstellungen statt mitten im Teilen-Ablauf, wo ein Berechtigungsdialog
     * nur stören würde. Ab Android 13 (Tiramisu) ist das eine Laufzeit-
     * Berechtigung, davor läuft die Benachrichtigung ohne Abfrage.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            notificationsDenied.value = false
            return
        }
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        // Neu einlesen statt einmalig: ein in der Vorschau gespeicherter oder
        // verworfener Entwurf muss beim Zurückkommen aus der Liste verschwinden.
        pendingDrafts.value = drafts.pending()
    }

    /** Öffnet dieselbe Vorschau wie die Benachrichtigung, nur ohne sie. */
    private fun openDraft(jobId: String) {
        startActivity(
            Intent(this, ShareActivity::class.java)
                .putExtra(ShareActivity.EXTRA_JOB_ID, jobId)
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_PHOTO_URIS, ArrayList(photoUris.map { it.toString() }))
        pendingPhotoUri?.let { outState.putString(KEY_PENDING_URI, it.toString()) }
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

        savedInstanceState?.getStringArrayList(KEY_PHOTO_URIS)
            ?.forEach { photoUris.add(Uri.parse(it)) }
        savedInstanceState?.getString(KEY_PENDING_URI)
            ?.let { pendingPhotoUri = Uri.parse(it) }

        setContent {
            var darkMode by remember { mutableStateOf(settings.darkMode) }
            ArcaneTheme(darkOverride = darkMode) {
                val dark = darkMode ?: isSystemInDarkTheme()
                var screen by remember { mutableStateOf(Screen.HOME) }
                var dashboardUrl by remember { mutableStateOf(settings.dashboardUrl) }
                var importToken by remember { mutableStateOf(settings.importToken) }
                var cfClientId by remember { mutableStateOf(settings.cfClientId) }
                var cfClientSecret by remember { mutableStateOf(settings.cfClientSecret) }
                // Berechtigung nur beim Öffnen der Einstellungen anfragen, nie mitten
                // im Teilen-Ablauf (siehe requestNotificationPermissionIfNeeded).
                val openSettings = {
                    screen = Screen.SETTINGS
                    requestNotificationPermissionIfNeeded()
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
                            if (screen == Screen.HOME) openSettings() else screen = Screen.HOME
                        },
                    )
                    // Vom Crash-Handler (ObsiDineApp) gespeicherter Stacktrace des
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
                    if (screen == Screen.HOME && pendingDrafts.value.isNotEmpty()) {
                        PendingDraftsCard(pendingDrafts.value, onOpen = ::openDraft)
                    }
                    when (screen) {
                        Screen.HOME -> HomeScreen(
                            photoCount = photoUris.size,
                            dashboardOk = dashboardUrl.isNotBlank(),
                            tokenOk = importToken.isNotBlank(),
                            onCapture = ::capturePhoto,
                            onImport = ::startImportFromPhotos,
                            onDiscard = { photoUris.clear() },
                            onOpenSettings = openSettings,
                        )
                        Screen.SETTINGS -> SettingsScreen(
                            dashboardUrl = dashboardUrl,
                            onDashboardUrl = { dashboardUrl = it; settings.dashboardUrl = it },
                            importToken = importToken,
                            onImportToken = { importToken = it; settings.importToken = it },
                            cfClientId = cfClientId,
                            onCfClientId = { cfClientId = it; settings.cfClientId = it },
                            cfClientSecret = cfClientSecret,
                            onCfClientSecret = { cfClientSecret = it; settings.cfClientSecret = it },
                            dark = dark,
                            onDarkMode = { darkMode = it; settings.darkMode = it },
                            notificationsDenied = notificationsDenied.value,
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
                Text("ObsiDine", style = MaterialTheme.typography.headlineSmall)
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

/**
 * Fertige Importe, die noch auf das Abnicken warten — je Eintrag eine Zeile, die
 * dieselbe Vorschau öffnet wie die Benachrichtigung. Bewusst ohne Fortschritt,
 * Status oder Wiederholen: laufende Importe zeigt diese Liste nicht.
 */
@Composable
private fun PendingDraftsCard(drafts: List<Pair<String, RecipeDraft>>, onOpen: (String) -> Unit) {
    ArcaneCard {
        ArcaneCardTitle("Fertige Importe", tag = "[${drafts.size}]")
        Spacer(Modifier.height(8.dp))
        Text(
            "Antippen zum Prüfen und Speichern.",
            style = MaterialTheme.typography.bodyMedium,
        )
        drafts.forEach { (jobId, draft) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(jobId) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArcaneTag("◆", color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text(
                    draft.name.ifBlank { "Ohne Namen" },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                ArcaneTag("[PRÜFEN]", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(
    photoCount: Int,
    dashboardOk: Boolean,
    tokenOk: Boolean,
    onCapture: () -> Unit,
    onImport: () -> Unit,
    onDiscard: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // Setup-Check: ohne Dashboard-Adresse + Token kein Import.
    if (!dashboardOk || !tokenOk) {
        val done = listOf(dashboardOk, tokenOk).count { it }
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
            EquipmentRow("Dashboard", dashboardOk)
            EquipmentRow("Token", tokenOk)
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
            "Rezept aus einer anderen App mit ObsiDine teilen — Link, Text " +
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    dashboardUrl: String,
    onDashboardUrl: (String) -> Unit,
    importToken: String,
    onImportToken: (String) -> Unit,
    cfClientId: String,
    onCfClientId: (String) -> Unit,
    cfClientSecret: String,
    onCfClientSecret: (String) -> Unit,
    dark: Boolean,
    onDarkMode: (Boolean) -> Unit,
    notificationsDenied: Boolean,
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
            "Dashboard-Verbindung",
            tag = if (dashboardUrl.isBlank() || importToken.isBlank()) "[FEHLT]" else "[OK]",
            tagColor = if (dashboardUrl.isBlank() || importToken.isBlank()) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.secondary,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = dashboardUrl,
            onValueChange = onDashboardUrl,
            label = { Text("Dashboard-Adresse") },
            placeholder = { Text("https://cockpit.domelehmann.org") },
            singleLine = true,
            colors = arcaneTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        var showToken by remember { mutableStateOf(false) }
        val tokenTransformation =
            if (showToken) VisualTransformation.None else PasswordVisualTransformation()
        OutlinedTextField(
            value = importToken,
            onValueChange = onImportToken,
            label = { Text("Import-Token") },
            singleLine = true,
            visualTransformation = tokenTransformation,
            colors = arcaneTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = cfClientId,
            onValueChange = onCfClientId,
            label = { Text("Cloudflare Client-Id (optional)") },
            singleLine = true,
            colors = arcaneTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = cfClientSecret,
            onValueChange = onCfClientSecret,
            label = { Text("Cloudflare Client-Secret (optional)") },
            singleLine = true,
            visualTransformation = tokenTransformation,
            colors = arcaneTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = showToken,
                onCheckedChange = { showToken = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Text("Geheimnisse anzeigen", style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (notificationsDenied) {
        Text(
            "Ohne Benachrichtigungen läuft der Import trotzdem — das Rezept wartet " +
                "dann in der App.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
