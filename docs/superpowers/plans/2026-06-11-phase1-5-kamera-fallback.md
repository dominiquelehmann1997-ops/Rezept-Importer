# Phase 1.5: URL-Hinweis, Provider-Fallback, In-App-Kamera — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Drei Verbesserungen aus dem ersten Geräte-Test: (A) geteilte reine URLs erzeugen sofort eine klare Meldung statt eines sinnlosen LLM-Calls, und semantische LLM-Fehlschläge bekommen den Repair-Retry; (B) bei Technik-Fehlern (HTTP/Netz) übernimmt automatisch der jeweils andere Provider; (C) Fotos können direkt aus der App aufgenommen werden (System-Kamera, mehrere pro Rezept, nur App-Cache — nie Galerie).

**Architecture:** (A) URL-Regex-Gate in ShareActivity vor der Pipeline + try/catch um den ersten Extract-Call in ImportPipeline. (B) Neue Subklasse `LlmTransportException : LlmException` für HTTP-non-2xx und IO-Fehler in beiden Extractors; neuer `FallbackExtractor(primary, secondary)` der NUR bei LlmTransportException auf secondary wechselt; ShareActivity baut ihn, wenn der Key des Zweit-Providers vorhanden ist. (C) FileProvider + `ActivityResultContracts.TakePicture()`-Schleife in MainActivity, Übergabe an ShareActivity per internem ACTION_SEND_MULTIPLE-Intent (bestehender Pfad, null neuer Import-Code).

**Entscheidungen (User, 2026-06-11):** System-Kamera statt CameraX; Fallback nur bei Technik-Fehlern (429/5xx/Timeout/Netz), nicht bei inhaltlichen Fehlschlägen. Claude-Pro-Abo deckt API nicht ab — Haiku läuft über separates API-Guthaben; Gemini bleibt Free-Tier-Default.

**Token-Budget-Update:** Obergrenze jetzt max. 2 LLM-Calls **pro Provider** pro Import (Pipeline-Retry × Provider-Fallback; Worst Case 4 HTTP-Calls, nur wenn Primary technisch komplett ausfällt).

---

### Task A: URL-Gate + Repair-Retry bei semantischer LlmException

**Files:**
- Modify: `android/app/src/main/java/de/dml/rezeptimporter/pipeline/ImportPipeline.kt`
- Modify: `android/app/src/main/java/de/dml/rezeptimporter/ui/ShareActivity.kt`
- Test: `android/app/src/test/java/de/dml/rezeptimporter/pipeline/ImportPipelineTest.kt`

- [ ] **Step 1: Failing Tests** — in `ImportPipelineTest.kt` ergänzen:

```kotlin
    @Test
    fun retriesWhenFirstCallThrowsSemanticLlmException() = runTest {
        var call = 0
        val flaky = object : LlmExtractor {
            override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft {
                call++
                if (call == 1) throw LlmException("LLM-Antwort mit leerem 'name'")
                return good
            }
        }
        val draft = ImportPipeline(flaky, validator, writer).extractValidated("text")
        assertEquals("Curry", draft.name)
        assertEquals(2, call)
    }

    @Test(expected = LlmException::class)
    fun doesNotRetryMoreThanOnceOnThrows() = runTest {
        val alwaysThrows = object : LlmExtractor {
            override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft =
                throw LlmException("kaputt")
        }
        ImportPipeline(alwaysThrows, validator, writer).extractValidated("text")
    }
```

Außerdem in einer neuen Testklasse `android/app/src/test/java/de/dml/rezeptimporter/pipeline/UrlDetectionTest.kt`:

```kotlin
package de.dml.rezeptimporter.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlDetectionTest {
    @Test fun bareInstagramUrl() = assertTrue(isBareUrl("https://www.instagram.com/reel/Cxyz123/?igsh=abc"))
    @Test fun bareUrlWithWhitespace() = assertTrue(isBareUrl("  https://vm.tiktok.com/ZN123/ \n"))
    @Test fun captionWithUrlInside() = assertFalse(isBareUrl("Bestes Curry! Rezept: 400ml Kokosmilch... https://insta.gram/x"))
    @Test fun plainRecipeText() = assertFalse(isBareUrl("Zutaten: 250g Reis"))
}
```

- [ ] **Step 2:** Tests laufen lassen → FAIL (isBareUrl fehlt, Retry-Verhalten fehlt).

- [ ] **Step 3: Implementieren.** In `ImportPipeline.kt`:

1. Top-Level-Funktion (gleiche Datei, über der Klasse):

```kotlin
/** true, wenn der geteilte Text nur aus einer einzelnen URL besteht (Reel/TikTok/Web-Link). */
fun isBareUrl(text: String): Boolean =
    Regex("^https?://\\S+$").matches(text.trim())
```

2. `extractValidated` so umbauen, dass der erste Call auch bei geworfener (semantischer) LlmException genau einen Repair-Versuch bekommt — LlmTransportException (kommt in Task B) wird dabei NICHT als reparierbar behandelt, sondern durchgereicht:

```kotlin
    suspend fun extractValidated(rawText: String): RecipeDraft {
        val firstProblems: List<String>
        try {
            val first = extractor.extract(rawText)
            val problems = problemsOf(first)
            if (problems.isEmpty()) return first
            firstProblems = problems
        } catch (e: LlmTransportException) {
            throw e   // Technik-Fehler: Retry sinnlos, Fallback-Logik liegt im Extractor
        } catch (e: LlmException) {
            // Semantischer Fehlschlag (z.B. leerer Name) — ein Repair-Versuch
            val second = extractor.extract(rawText, repairHint = e.message ?: "ungültige Antwort")
            val secondProblems = problemsOf(second)
            if (secondProblems.isEmpty()) return second
            throw LlmException("Extraktion nach Repair-Retry weiterhin ungültig: ${secondProblems.joinToString("; ")}")
        }

        val second = extractor.extract(rawText, repairHint = firstProblems.joinToString("; "))
        val secondProblems = problemsOf(second)
        if (secondProblems.isEmpty()) return second
        throw LlmException("Extraktion nach Repair-Retry weiterhin ungültig: ${secondProblems.joinToString("; ")}")
    }
```

Hinweis: `LlmTransportException` existiert erst nach Task B. Damit Task A eigenständig kompiliert, lege sie JETZT in `LlmExtractor.kt` an (Task B nutzt sie dann):

```kotlin
/** Technischer Fehler (HTTP non-2xx, Timeout, Netz) — Kandidat für Provider-Fallback, kein Repair-Retry. */
class LlmTransportException(message: String, cause: Throwable? = null) : LlmException(message, cause)
```

Dafür `LlmException` als `open class` deklarieren.

3. In `ShareActivity.runImport()` direkt nach dem Blank-Check einfügen:

```kotlin
                if (isBareUrl(rawText)) {
                    state.value = ImportState.Error(
                        "Das ist nur ein Link. Reel-/Web-Links werden erst in Phase 2 unterstützt.\n" +
                        "Tipp: Screenshot der Caption teilen oder Text kopieren."
                    )
                    return@launch
                }
```

(Import von `de.dml.rezeptimporter.pipeline.isBareUrl` ergänzen. Kein LLM-Call für URLs ⇒ 0 Tokens.)

- [ ] **Step 4:** Alle Tests grün (37 + 6 neue = 43). `.\gradlew.bat :app:testDebugUnitTest`

- [ ] **Step 5: Commit** — `fix: detect bare URLs before LLM call and repair-retry semantic extraction failures`

---

### Task B: LlmTransportException in Extractors + FallbackExtractor

**Files:**
- Modify: `android/app/src/main/java/de/dml/rezeptimporter/llm/GeminiExtractor.kt`
- Modify: `android/app/src/main/java/de/dml/rezeptimporter/llm/HaikuExtractor.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/llm/FallbackExtractor.kt`
- Modify: `android/app/src/main/java/de/dml/rezeptimporter/ui/ShareActivity.kt`
- Test: `android/app/src/test/java/de/dml/rezeptimporter/llm/FallbackExtractorTest.kt`
- Modify: bestehende Extractor-Tests (HTTP-Fehler erwarten jetzt LlmTransportException)

- [ ] **Step 1: Failing Tests** — `FallbackExtractorTest.kt`:

```kotlin
package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackExtractorTest {
    private val good = RecipeDraft(name = "Curry", steps = listOf("Kochen."))

    private fun throwing(e: Exception) = object : LlmExtractor {
        var calls = 0
        override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft {
            calls++; throw e
        }
    }

    @Test
    fun usesPrimaryWhenItWorks() = runTest {
        val primary = FakeLlmExtractor(good)
        val secondary = FakeLlmExtractor(good.copy(name = "Falsch"))
        val result = FallbackExtractor(primary, secondary).extract("text")
        assertEquals("Curry", result.name)
        assertEquals(1, primary.calls)
        assertEquals(0, secondary.calls)
    }

    @Test
    fun fallsBackOnTransportError() = runTest {
        val primary = throwing(LlmTransportException("Gemini HTTP 429: quota"))
        val secondary = FakeLlmExtractor(good)
        val result = FallbackExtractor(primary, secondary).extract("text")
        assertEquals("Curry", result.name)
        assertEquals(1, secondary.calls)
    }

    @Test(expected = LlmException::class)
    fun doesNotFallBackOnSemanticError() = runTest {
        val primary = throwing(LlmException("LLM-Antwort mit leerem 'name'"))
        val secondary = FakeLlmExtractor(good)
        try {
            FallbackExtractor(primary, secondary).extract("text")
        } finally {
            assertEquals(0, secondary.calls)
        }
    }

    @Test(expected = LlmTransportException::class)
    fun rethrowsWhenBothFailTechnically() = runTest {
        val primary = throwing(LlmTransportException("HTTP 503"))
        val secondary = throwing(LlmTransportException("HTTP 529"))
        FallbackExtractor(primary, secondary).extract("text")
    }
}
```

(`FakeLlmExtractor` braucht dafür `calls` public — hat es schon.)

- [ ] **Step 2:** FAIL.

- [ ] **Step 3: Implementieren.**

`FallbackExtractor.kt`:

```kotlin
package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft

/**
 * Versucht primary; NUR bei LlmTransportException (HTTP/Netz/Timeout) wechselt er auf
 * secondary. Inhaltliche Fehlschläge werden durchgereicht — die kosten beim
 * Zweit-Provider nur Geld und scheitern genauso.
 */
class FallbackExtractor(
    private val primary: LlmExtractor,
    private val secondary: LlmExtractor,
) : LlmExtractor {
    override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft =
        try {
            primary.extract(rawText, repairHint)
        } catch (e: LlmTransportException) {
            secondary.extract(rawText, repairHint)
        }
}
```

In **beiden** Extractors (Gemini + Haiku):
- HTTP-Fehler-Throw ändern: `throw LlmTransportException("<Provider> HTTP ${resp.code}: ${text.take(300)}")`
- Im Wrap-Catch des public `extract`: `catch (e: IOException) { throw LlmTransportException("<Provider> nicht erreichbar: ${e.message}", e) }` VOR dem generischen `catch (e: Exception)` einfügen (Import `java.io.IOException`).
- Bestehende Tests anpassen: `throwsLlmExceptionOnHttpError` (Gemini) und `wrapsHttpErrorInLlmException` (Haiku) fangen weiter LlmException (Subklasse erfüllt das) — zusätzlich je eine Assertion `assertTrue(e is LlmTransportException)`.

In `ShareActivity.buildExtractor()` ersetzen:

```kotlin
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
```

- [ ] **Step 4:** Alle Tests grün (43 + 4 = 47). Build: `.\gradlew.bat :app:testDebugUnitTest assembleDebug`

- [ ] **Step 5: Commit** — `feat: auto-fallback to second provider on transport errors`

---

### Task C: In-App-Kamera (System-Kamera, Multi-Shot, nur Cache)

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml` (FileProvider)
- Create: `android/app/src/main/res/xml/file_paths.xml`
- Modify: `android/app/src/main/java/de/dml/rezeptimporter/ui/MainActivity.kt`
- Modify: `android/app/src/main/java/de/dml/rezeptimporter/ui/ShareActivity.kt` (Cache-Cleanup nach Import)

Kein Unit-Test (reines Framework-Wiring) — Verifikation: `assembleDebug` + manueller Gerätetest.

- [ ] **Step 1: FileProvider.** `res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="fotos" path="fotos/" />
</paths>
```

Manifest, innerhalb `<application>`:

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="de.dml.rezeptimporter.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

- [ ] **Step 2: MainActivity erweitern.** Felder + Launcher:

```kotlin
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
```

UI-Block in der Settings-Column (nach dem Vault-Block, vor dem Provider-Block):

```kotlin
                    HorizontalDivider()

                    Text("Rezept fotografieren", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (photoUris.isEmpty()) "Fotos landen nur im App-Cache, nie in der Galerie."
                        else "${photoUris.size} Foto(s) aufgenommen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { capturePhoto() }) {
                            Text(if (photoUris.isEmpty()) "Foto aufnehmen" else "Weiteres Foto")
                        }
                        if (photoUris.isNotEmpty()) {
                            Button(onClick = { startImportFromPhotos() }) { Text("Rezept erstellen") }
                            OutlinedButton(onClick = { photoUris.clear() }) { Text("Verwerfen") }
                        }
                    }
```

Imports ergänzen: `android.net.Uri`, `androidx.core.content.FileProvider`, `java.io.File`, `androidx.compose.runtime.mutableStateListOf`, `de.dml.rezeptimporter.ui.ShareActivity` (gleiche Package — entfällt), `Intent` ist schon da.

- [ ] **Step 3: Cache-Cleanup.** In `ShareActivity` am Ende von `save()` (nach Toast, vor finish) und in `onCancel` des Preview-Zweigs den Foto-Cache leeren:

```kotlin
    private fun clearPhotoCache() {
        File(cacheDir, "fotos").listFiles()?.forEach { it.delete() }
    }
```

Aufruf: in `save()` nach erfolgreichem Write und im `onCancel = { clearPhotoCache(); finish() }`. (Import `java.io.File`. Hinweis: ShareActivity und MainActivity teilen dieselbe App — `cacheDir` ist identisch.)

- [ ] **Step 4:** `.\gradlew.bat :app:testDebugUnitTest assembleDebug` → 47 Tests grün, BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** — `feat: in-app multi-photo capture via system camera (cache-only, no gallery)`

---

### Task D: Geräte-Verifikation (manuell, User)

- [ ] `gradlew installDebug`
- [ ] Reel-Link teilen → sofortige klare Meldung, KEIN LLM-Call
- [ ] Foto-Button: 2 Fotos einer Rezeptseite → „Rezept erstellen" → Vorschau → Speichern → `.md` im Vault, Galerie leer
- [ ] Gemini-Key absichtlich kaputt machen → Import → Haiku übernimmt (Toast/Ergebnis prüfen) → Key wieder fixen
- [ ] Validator-Gate: neue `.md` durch `npm run validate`

## Self-Review-Notizen

- Worst-Case-Callzahl dokumentiert (2 pro Provider). isBareUrl bewusst konservativ (nur reine URLs — Caption mit URL läuft normal durch die Pipeline).
- LlmTransportException in Task A angelegt (Compile-Reihenfolge), in Task B benutzt — Tasks bleiben einzeln baubar.
- Kamera: TakePicture-Contract liefert Bool; URI-Buchhaltung über pendingPhotoUri, da der Contract die URI nicht zurückgibt.
