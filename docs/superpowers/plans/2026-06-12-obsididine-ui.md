# ObsidiDine UI-Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rezept-Importer wird "ObsidiDine": Arcane-Terminal-Theme (Parchment/Dungeon), Launcher-Icon aus JPEG, Settings (Keys + Vault) in eigenen Screen hinter Zahnrad.

**Architecture:** Neues Paket `ui/theme` (Farben, Typografie, ArcaneTheme, ArcaneCard/Buttons/Tags). MainActivity bekommt Home/Settings-Screens via Compose-State. ShareActivity + PreviewScreen werden nur umgestylt. Null Logik-Änderungen — Pipeline, AppSettings, Tests unberührt.

**Tech Stack:** Jetpack Compose (BOM 2024.10.01, Material3), gebündelte TTFs in `res/font`, PowerShell/System.Drawing für Icon-Generierung.

**Hinweis Tests:** Reines UI-Styling, keine neue Logik → keine neuen Unit-Tests (Projekt hat keine Instrumented-Test-Infrastruktur). Verifikation = `gradlew test` (Bestand bleibt grün) + `assembleDebug` + Sichtprüfung. Spec: `docs/superpowers/specs/2026-06-12-obsididine-ui-design.md`.

Alle Gradle-Befehle aus `android/` ausführen: `.\gradlew.bat ...`

---

### Task 1: Fonts bündeln

**Files:**
- Create: `android/app/src/main/res/font/space_mono_regular.ttf`
- Create: `android/app/src/main/res/font/space_mono_bold.ttf`
- Create: `android/app/src/main/res/font/plus_jakarta_sans_regular.ttf`
- Create: `android/app/src/main/res/font/plus_jakarta_sans_semibold.ttf`

- [ ] **Step 1: Font-Ordner anlegen + TTFs herunterladen**

```powershell
New-Item -ItemType Directory -Force android/app/src/main/res/font
curl.exe -L -o android/app/src/main/res/font/space_mono_regular.ttf "https://github.com/google/fonts/raw/main/ofl/spacemono/SpaceMono-Regular.ttf"
curl.exe -L -o android/app/src/main/res/font/space_mono_bold.ttf "https://github.com/google/fonts/raw/main/ofl/spacemono/SpaceMono-Bold.ttf"
curl.exe -L -o android/app/src/main/res/font/plus_jakarta_sans_regular.ttf "https://github.com/tokotype/PlusJakartaSans/raw/master/fonts/ttf/PlusJakartaSans-Regular.ttf"
curl.exe -L -o android/app/src/main/res/font/plus_jakarta_sans_semibold.ttf "https://github.com/tokotype/PlusJakartaSans/raw/master/fonts/ttf/PlusJakartaSans-SemiBold.ttf"
```

- [ ] **Step 2: Downloads prüfen**

Jede Datei muss > 50 KB sein und mit TTF-Magic-Bytes beginnen:

```powershell
Get-ChildItem android/app/src/main/res/font | Select-Object Name, Length
```

Erwartung: 4 Dateien, jeweils ≥ 50000 Bytes. Falls eine Datei winzig ist (HTML-404-Seite): bei Space Mono Pfad prüfen (`ofl/spacemono/`), bei Plus Jakarta Sans Fallback auf Variable Font:
`https://github.com/google/fonts/raw/main/ofl/plusjakartasans/PlusJakartaSans%5Bwght%5D.ttf` — dann dieselbe Datei als beide PJS-Ressourcen speichern und in Task 2 bei den `Font(...)`-Einträgen `variationSettings = FontVariation.Settings(FontVariation.weight(400))` bzw. `weight(600)` ergänzen (`@OptIn(ExperimentalTextApi::class)`).

- [ ] **Step 3: Commit**

```powershell
git add android/app/src/main/res/font
git commit -m "feat: bundle Space Mono + Plus Jakarta Sans fonts"
```

---

### Task 2: Arcane-Theme-Paket

**Files:**
- Create: `android/app/src/main/java/de/dml/rezeptimporter/ui/theme/Color.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/ui/theme/Type.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/ui/theme/Theme.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/ui/theme/Components.kt`

- [ ] **Step 1: Color.kt schreiben**

```kotlin
package de.dml.rezeptimporter.ui.theme

import androidx.compose.ui.graphics.Color

// Parchment Mode (light)
val ParchmentBackground = Color(0xFFFBF9F5)
val ParchmentSurface = Color(0xFFFFFFFF)
val ArcaneAbyss = Color(0xFF180F25)
val MutedPurpleGray = Color(0xFF5D546D)
val CardEdgeKhaki = Color(0xFFE5DDC8)
val ManaViolet = Color(0xFF8B5CF6)
val RupeeGreen = Color(0xFF10B981)
val LegendaryGold = Color(0xFFF59E0B)

// Dungeon Mode (dark)
val DeepVoidPurple = Color(0xFF0D0814)
val ShadowedCrypt = Color(0xFF150E22)
val FadedLavender = Color(0xFFEDE9FE)
val NeutralGray = Color(0xFF9CA3AF)
val DarkArcane = Color(0xFF312144)
val GlowingMana = Color(0xFFA78BFA)
val GlowingRupee = Color(0xFF34D399)
```

- [ ] **Step 2: Type.kt schreiben**

```kotlin
package de.dml.rezeptimporter.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.dml.rezeptimporter.R

val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
)

// Headings + Code: Space Mono (Letter-Spacing −0.02em). Body + Labels: Plus Jakarta Sans.
val ArcaneTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, letterSpacing = (-0.02).em,
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, letterSpacing = (-0.02).em,
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, letterSpacing = (-0.02).em,
    ),
    bodyLarge = TextStyle(fontFamily = PlusJakartaSans, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = PlusJakartaSans, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = PlusJakartaSans, fontSize = 12.sp),
    labelLarge = TextStyle(
        fontFamily = PlusJakartaSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PlusJakartaSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
    ),
)
```

- [ ] **Step 3: Theme.kt schreiben**

```kotlin
package de.dml.rezeptimporter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ParchmentScheme = lightColorScheme(
    primary = ManaViolet,
    onPrimary = Color.White,
    secondary = RupeeGreen,
    onSecondary = Color.White,
    tertiary = LegendaryGold,
    onTertiary = ArcaneAbyss,
    background = ParchmentBackground,
    onBackground = ArcaneAbyss,
    surface = ParchmentSurface,
    onSurface = ArcaneAbyss,
    surfaceVariant = ParchmentBackground,
    onSurfaceVariant = MutedPurpleGray,
    outline = CardEdgeKhaki,
    outlineVariant = CardEdgeKhaki,
)

private val DungeonScheme = darkColorScheme(
    primary = GlowingMana,
    onPrimary = Color.White,
    secondary = GlowingRupee,
    onSecondary = DeepVoidPurple,
    tertiary = LegendaryGold,
    onTertiary = DeepVoidPurple,
    background = DeepVoidPurple,
    onBackground = FadedLavender,
    surface = ShadowedCrypt,
    onSurface = FadedLavender,
    surfaceVariant = ShadowedCrypt,
    onSurfaceVariant = NeutralGray,
    outline = DarkArcane,
    outlineVariant = DarkArcane,
)

@Composable
fun ArcaneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DungeonScheme else ParchmentScheme,
        typography = ArcaneTypography,
        content = content,
    )
}
```

- [ ] **Step 4: Components.kt schreiben**

```kotlin
package de.dml.rezeptimporter.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 4dp-Radius überall — keine Pills (DESIGN.md). */
val ArcaneShape = RoundedCornerShape(4.dp)

/** MTG-Card: 1dp Border, harter 3dp-Offset-Schatten ohne Blur, 16dp Padding. */
@Composable
fun ArcaneCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier.padding(end = 3.dp, bottom = 3.dp)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(3.dp, 3.dp)
                .background(MaterialTheme.colorScheme.outline, ArcaneShape)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, ArcaneShape)
                .border(1.dp, MaterialTheme.colorScheme.outline, ArcaneShape)
                .padding(16.dp),
            content = content,
        )
    }
}

/** Primary Action "Cast Spell": gefüllt Mana Violet. */
@Composable
fun ArcanePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ArcaneShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) { Text(text) }
}

/** Secondary Action "Scroll": transparent mit Border. */
@Composable
fun ArcaneSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = ArcaneShape,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Text(text) }
}

/** Mono-Tag neben Headings, z. B. [FOTOS: 2]. */
@Composable
fun ArcaneTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = SpaceMono),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Input-Farben: flache Border, Hintergrund = Seitenhintergrund, Fokus = Mana Violet. */
@Composable
fun arcaneTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedContainerColor = MaterialTheme.colorScheme.background,
    unfocusedContainerColor = MaterialTheme.colorScheme.background,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
)
```

- [ ] **Step 5: Kompilieren**

```powershell
cd android; .\gradlew.bat compileDebugKotlin
```

Erwartung: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```powershell
git add android/app/src/main/java/de/dml/rezeptimporter/ui/theme
git commit -m "feat: Arcane Terminal theme (colors, fonts, card components)"
```

---

### Task 3: Branding — Name, Icon, Manifest, Window-Theme

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/res/values/themes.xml`
- Create: `android/app/src/main/res/values-night/themes.xml`
- Create: `android/app/src/main/res/values/colors.xml`
- Create: `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (+ `ic_launcher_round.xml`)
- Create: `android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`, `ic_launcher_round.png`, `ic_launcher_foreground.png`
- Modify: `android/app/src/main/AndroidManifest.xml`

- [ ] **Step 1: App-Name**

`strings.xml`:

```xml
<resources>
    <string name="app_name">ObsidiDine</string>
</resources>
```

- [ ] **Step 2: Window-Theme (kein weißer Flash im Dark Mode, Status-Bar passend)**

`values/themes.xml`:

```xml
<resources>
    <style name="Theme.RezeptImporter" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">#FBF9F5</item>
        <item name="android:statusBarColor">#FBF9F5</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>
</resources>
```

`values-night/themes.xml` (neu):

```xml
<resources>
    <style name="Theme.RezeptImporter" parent="android:Theme.Material.NoActionBar">
        <item name="android:windowBackground">#0D0814</item>
        <item name="android:statusBarColor">#0D0814</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>
</resources>
```

- [ ] **Step 3: Icon-Generierung per Skript**

`tools/make-icons.ps1` (neu, im Repo-Root ausführen):

```powershell
Add-Type -AssemblyName System.Drawing

$srcPath = (Resolve-Path "App-Symbol_ObsidiDine.jpeg").Path
$src = [System.Drawing.Bitmap]::FromFile($srcPath)

# Bounding-Box der Icon-Kachel finden: Hintergrund = Eckpixel, alles deutlich
# Abweichende gehört zur Kachel.
$bg = $src.GetPixel(2, 2)
$minX = $src.Width; $minY = $src.Height; $maxX = 0; $maxY = 0
for ($y = 0; $y -lt $src.Height; $y += 2) {
    for ($x = 0; $x -lt $src.Width; $x += 2) {
        $p = $src.GetPixel($x, $y)
        $d = [Math]::Abs($p.R - $bg.R) + [Math]::Abs($p.G - $bg.G) + [Math]::Abs($p.B - $bg.B)
        if ($d -gt 60) {
            if ($x -lt $minX) { $minX = $x }; if ($x -gt $maxX) { $maxX = $x }
            if ($y -lt $minY) { $minY = $y }; if ($y -gt $maxY) { $maxY = $y }
        }
    }
}
# Quadratisch machen (Kachel ist rund-quadratisch)
$w = $maxX - $minX; $h = $maxY - $minY; $side = [Math]::Max($w, $h)
$cx = ($minX + $maxX) / 2; $cy = ($minY + $maxY) / 2
$left = [int]($cx - $side / 2); $top = [int]($cy - $side / 2)
$tile = $src.Clone([System.Drawing.Rectangle]::new($left, $top, $side, $side), $src.PixelFormat)
Write-Host "Tile: ${side}x${side} @ $left,$top"

function New-Icon([int]$canvas, [double]$scale, [string]$outPath) {
    $bmp = New-Object System.Drawing.Bitmap($canvas, $canvas)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = "AntiAlias"
    $g.InterpolationMode = "HighQualityBicubic"
    $size = [int]($canvas * $scale)
    $off = [int](($canvas - $size) / 2)
    # Rounded-Rect-Clip (Radius 22% wie iOS/Play-Kacheln), Ecken transparent
    $r = [int]($size * 0.22)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($off, $off, 2 * $r, 2 * $r, 180, 90)
    $path.AddArc($off + $size - 2 * $r, $off, 2 * $r, 2 * $r, 270, 90)
    $path.AddArc($off + $size - 2 * $r, $off + $size - 2 * $r, 2 * $r, 2 * $r, 0, 90)
    $path.AddArc($off, $off + $size - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $path.CloseFigure()
    $g.SetClip($path)
    $g.DrawImage($tile, $off, $off, $size, $size)
    $g.Dispose()
    New-Item -ItemType Directory -Force (Split-Path $outPath) | Out-Null
    $bmp.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "OK: $outPath"
}

$res = "android/app/src/main/res"
$densities = @{ mdpi = 1.0; hdpi = 1.5; xhdpi = 2.0; xxhdpi = 3.0; xxxhdpi = 4.0 }
foreach ($d in $densities.Keys) {
    $f = $densities[$d]
    # Legacy-Icon: Kachel füllt Canvas (48dp-Basis)
    New-Icon ([int](48 * $f)) 1.0 "$res/mipmap-$d/ic_launcher.png"
    Copy-Item "$res/mipmap-$d/ic_launcher.png" "$res/mipmap-$d/ic_launcher_round.png"
    # Adaptive Foreground: 108dp-Canvas, Kachel auf 60% (Safe Zone 66/108)
    New-Icon ([int](108 * $f)) 0.6 "$res/mipmap-$d/ic_launcher_foreground.png"
}
$src.Dispose(); $tile.Dispose()
```

Ausführen:

```powershell
powershell -ExecutionPolicy Bypass -File tools/make-icons.ps1
```

Erwartung: "Tile: NxN ..." plausibel (Kachel ≈ 40–60% der Bildbreite) + 15 "OK:"-Zeilen. Danach ein PNG visuell prüfen (Read-Tool auf `android/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`): Obsidian-Topf sichtbar, Ecken transparent, nicht der graue JPEG-Rand.

- [ ] **Step 4: Adaptive-Icon-XML + Hintergrundfarbe**

`values/colors.xml` (neu):

```xml
<resources>
    <color name="ic_launcher_background">#0D0814</color>
</resources>
```

`mipmap-anydpi-v26/ic_launcher.xml` (neu):

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
```

`mipmap-anydpi-v26/ic_launcher_round.xml` (neu): identischer Inhalt.

- [ ] **Step 5: Manifest-Icon**

In `AndroidManifest.xml` das `<application>`-Element erweitern:

```xml
    <application
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:theme="@style/Theme.RezeptImporter"
        android:allowBackup="true">
```

- [ ] **Step 6: Build prüfen**

```powershell
cd android; .\gradlew.bat assembleDebug
```

Erwartung: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```powershell
git add android/app/src/main/res android/app/src/main/AndroidManifest.xml tools/make-icons.ps1
git commit -m "feat: rename app to ObsidiDine, add launcher icon + window themes"
```

---

### Task 4: MainActivity — Home + Settings-Screen

**Files:**
- Modify: `android/app/src/main/java/de/dml/rezeptimporter/ui/MainActivity.kt` (kompletter Ersatz des Inhalts)

- [ ] **Step 1: MainActivity.kt ersetzen**

Activity-Logik (Foto-Launcher, SAF-Picker, Intents) bleibt identisch — nur `setContent` und Composables neu:

```kotlin
package de.dml.rezeptimporter.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
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
```

- [ ] **Step 2: Kompilieren**

```powershell
cd android; .\gradlew.bat compileDebugKotlin
```

Erwartung: BUILD SUCCESSFUL. Falls `Icons.AutoMirrored` nicht auflöst (ältere material-icons-core): stattdessen `Icons.Filled.ArrowBack` verwenden (deprecated-Warnung OK).

- [ ] **Step 3: Commit**

```powershell
git add android/app/src/main/java/de/dml/rezeptimporter/ui/MainActivity.kt
git commit -m "feat: home/settings split with Arcane Terminal styling"
```

---

### Task 5: ShareActivity + PreviewScreen restylen

**Files:**
- Modify: `android/app/src/main/java/de/dml/rezeptimporter/ui/ShareActivity.kt` (nur `setContent`-Block)
- Modify: `android/app/src/main/java/de/dml/rezeptimporter/ui/PreviewScreen.kt` (kompletter Ersatz)

- [ ] **Step 1: ShareActivity-`setContent` ersetzen**

Imports ergänzen:

```kotlin
import androidx.compose.foundation.background
import de.dml.rezeptimporter.ui.theme.ArcaneCard
import de.dml.rezeptimporter.ui.theme.ArcanePrimaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneTheme
```

`setContent`-Block (alter `MaterialTheme { ... }`-Block) ersetzen durch:

```kotlin
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
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.height(16.dp))
                                    Text(
                                        "Rezept wird extrahiert …",
                                        style = MaterialTheme.typography.bodyMedium,
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
                                Text(
                                    "Import fehlgeschlagen",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
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
```

- [ ] **Step 2: PreviewScreen.kt ersetzen**

```kotlin
package de.dml.rezeptimporter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.ui.theme.ArcaneCard
import de.dml.rezeptimporter.ui.theme.ArcanePrimaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneSecondaryButton
import de.dml.rezeptimporter.ui.theme.ArcaneShape
import de.dml.rezeptimporter.ui.theme.ArcaneTag
import de.dml.rezeptimporter.ui.theme.arcaneTextFieldColors

@Composable
fun PreviewScreen(
    initial: RecipeDraft,
    onSave: (RecipeDraft) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Rezept-Vorschau",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                ArcaneTag("[PREVIEW]")
            }
        }

        item {
            ArcaneCard {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("Name") },
                    colors = arcaneTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bewertung:", style = MaterialTheme.typography.bodyMedium)
                    listOf("favorit", "ok", "selten").forEach { r ->
                        Spacer(Modifier.width(4.dp))
                        FilterChip(
                            selected = draft.rating == r,
                            onClick = { draft = draft.copy(rating = r) },
                            label = { Text(r) },
                            shape = ArcaneShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        draft.simple, { draft = draft.copy(simple = it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Text("einfach", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(16.dp))
                    Checkbox(
                        draft.reheatable, { draft = draft.copy(reheatable = it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Text("aufwärmbar", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            ArcaneCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Zutaten",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ArcaneTag("[${draft.ingredients.size}]")
                }
                Spacer(Modifier.height(8.dp))
                draft.ingredients.forEachIndexed { i, ing ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = ing.amount ?: "",
                            onValueChange = { v ->
                                draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                                    .also { it[i] = ing.copy(amount = v.ifEmpty { null }) })
                            },
                            label = { Text("Menge") },
                            colors = arcaneTextFieldColors(),
                            modifier = Modifier.width(90.dp),
                        )
                        OutlinedTextField(
                            value = ing.unit ?: "",
                            onValueChange = { v ->
                                draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                                    .also { it[i] = ing.copy(unit = v.ifEmpty { null }) })
                            },
                            label = { Text("Einh.") },
                            colors = arcaneTextFieldColors(),
                            modifier = Modifier.width(80.dp),
                        )
                        OutlinedTextField(
                            value = ing.name,
                            onValueChange = { v ->
                                draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                                    .also { it[i] = ing.copy(name = v) })
                            },
                            label = { Text("Zutat") },
                            colors = arcaneTextFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        draft.nutrition?.takeIf { !it.isEmpty }?.let { n ->
            item {
                ArcaneCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Nährwerte",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        n.basis?.let { ArcaneTag("[$it]") }
                    }
                    Spacer(Modifier.height(8.dp))
                    val parts = listOfNotNull(
                        n.kcal?.let { "$it kcal" },
                        n.protein?.let { "Eiweiß ${it.fmtG()}" },
                        n.carbs?.let { "KH ${it.fmtG()}" },
                        n.fat?.let { "Fett ${it.fmtG()}" },
                    )
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            ArcaneCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Zubereitung",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ArcaneTag("[${draft.steps.size} SCHRITTE]")
                }
                Spacer(Modifier.height(8.dp))
                draft.steps.forEachIndexed { i, step ->
                    Text(
                        "${i + 1}. $step",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArcanePrimaryButton(
                    "In Vault speichern",
                    { onSave(draft) },
                    enabled = draft.name.isNotBlank(),
                )
                ArcaneSecondaryButton("Abbrechen", onCancel)
            }
        }
    }
}

/** 28.0 → "28 g", 28.5 → "28.5 g". */
private fun Double.fmtG(): String =
    (if (this % 1.0 == 0.0) toLong().toString() else toString()) + " g"
```

- [ ] **Step 3: Kompilieren**

```powershell
cd android; .\gradlew.bat compileDebugKotlin
```

Erwartung: BUILD SUCCESSFUL. Hinweis: `IngredientDraft`-Import in PreviewScreen entfällt (wird nicht mehr direkt referenziert) — bei "unused import"-Fehler einfach weglassen wie oben gezeigt.

- [ ] **Step 4: Commit**

```powershell
git add android/app/src/main/java/de/dml/rezeptimporter/ui/ShareActivity.kt android/app/src/main/java/de/dml/rezeptimporter/ui/PreviewScreen.kt
git commit -m "feat: Arcane Terminal styling for import flow and preview"
```

---

### Task 6: Verifikation + APK-Auslieferung

- [ ] **Step 1: Tests + Build**

```powershell
cd android; .\gradlew.bat test assembleDebug
```

Erwartung: BUILD SUCCESSFUL, alle bestehenden Unit-Tests grün.

- [ ] **Step 2: APK kopieren**

```powershell
Copy-Item android/app/build/outputs/apk/debug/app-debug.apk "G:\Meine Ablage\AI-Stuff\Rezept-Importer\ObsidiDine-debug.apk"
```

- [ ] **Step 3: Abschluss-Commit (falls noch uncommittete Reste, z. B. Plan-Checkboxen)**

```powershell
git status
git add -A
git commit -m "chore: finalize ObsidiDine UI redesign"
```

Nur committen wenn `git status` Änderungen zeigt.
