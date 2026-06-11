# Phase 1: Rezept-Importer End-to-End-Minimum — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android-App (Kotlin), die geteilten Text oder Fotos per Share-Intent empfängt, on-device per ML Kit OCR in Text wandelt, mit genau einem LLM-Call (Gemini Flash oder Claude Haiku, umschaltbar) in ein strukturiertes Rezept extrahiert, gegen das JSON-Schema validiert und als contract-konforme `.md`-Datei per Storage Access Framework in den Obsidian-Vault-Ordner schreibt — plus eine Node-Validator-CLI als Vertrags-Gate.

**Architecture:** Monorepo. `validator/` = TypeScript-CLI (gray-matter + ajv), vendort die Parser-Semantik aus `recipe-vault-import-contract.md` und prüft emittierte `.md`-Dateien. `shared/` = eine Quelle für das JSON-Schema, von Validator (Node) und App (Android-Asset) eingebunden. `android/` = Single-Activity-App: ShareActivity → SourceExtractor (Text/OCR) → LlmExtractor (Interface, 2 REST-Implementierungen via OkHttp) → RecipeValidator (networknt) → Vorschau (Compose) → VaultWriter (SAF, Auto-Suffix bei Slug-Kollision).

**Tech Stack:** Kotlin 2.0, AGP 8.7, Compose (BOM), kotlinx-serialization, OkHttp 4.12 (+ MockWebServer für Tests), ML Kit Text Recognition, SnakeYAML 2.2, networknt json-schema-validator 1.5 (Draft 2020-12), androidx.security-crypto (EncryptedSharedPreferences). Validator: Node 24, TypeScript, gray-matter, ajv (2020-Klasse), vitest, tsx.

**Spec:** `docs/superpowers/specs/2026-06-10-rezept-importer-design.md`. Contract: `recipe-vault-import-contract.md`. Schema: `recipe-vault-schema.md`.

**LLM-Wire-Formate (verbindlich):**
- Anthropic: `POST https://api.anthropic.com/v1/messages`, Headers `x-api-key`, `anthropic-version: 2023-06-01`, Modell `claude-haiku-4-5` (vom Nutzer gewählt: Haiku-Klasse), Tool-Use mit `tool_choice: {"type": "tool", "name": "save_recipe"}`, `max_tokens: 1500`. Antwort: `content[]`-Block mit `type: "tool_use"`, Feld `input`.
- Gemini: `POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent`, Header `x-goog-api-key`, `generationConfig: {responseMimeType: "application/json", responseSchema: <OpenAPI-Subset, GROSSGESCHRIEBENE Typen>, maxOutputTokens: 1500}`. Antwort: `candidates[0].content.parts[0].text` = JSON-String.
- Begründung REST statt Anthropic-Java-SDK: Android-Laufzeit-Kompatibilität des SDK unverifiziert; zwei symmetrische OkHttp-Implementierungen, beide mit MockWebServer testbar.

**Token-Sparzwang (First-Class):** Max. 2 LLM-Calls pro Import (1 Extraktion + 1 Repair-Retry). Input auf 6000 Zeichen gekappt. Output-Cap 1500 Tokens. Kein Vision-Call. Alle Tests laufen gegen Fake/MockWebServer — 0 echte Tokens.

---

## Prerequisites (einmalig, manuell — kein Task-Code)

Auf dieser Maschine fehlen JDK und Android SDK (geprüft 2026-06-11: `java` nicht gefunden, `ANDROID_HOME` nicht gesetzt). Vor Task 3:

```powershell
winget install Microsoft.OpenJDK.17
winget install Google.AndroidStudio
```

Dann Android Studio einmal starten → SDK-Setup-Wizard durchlaufen (installiert Platform 35, Build-Tools, Platform-Tools nach `%LOCALAPPDATA%\Android\Sdk`). Danach in PowerShell-Profil oder Systemumgebung:

```powershell
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk", "User")
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Microsoft\jdk-17*", "User")  # exakten Pfad einsetzen
```

Gradle-Wrapper-Bootstrap: Task 3 legt `gradle/wrapper/gradle-wrapper.properties` + Wrapper-Skripte an; der erste `gradlew`-Aufruf lädt Gradle selbst. Die Wrapper-JAR kommt per `gradle wrapper` aus einer einmaligen Gradle-Installation (`winget install Gradle.Gradle`) ODER durch Öffnen von `android/` in Android Studio (empfohlen — Studio generiert den Wrapper beim Sync).

Node 24 + npm 11 sind vorhanden — Tasks 1–2 laufen sofort.

---

### Task 1: Shared Schema + Repo-Hygiene

**Files:**
- Create: `shared/recipe-vault-frontmatter.schema.json`
- Create: `.gitignore`

- [ ] **Step 1: Schema-Datei anlegen**

Inhalt = exakt der JSON-Schema-Block aus `recipe-vault-schema.md` (Zeilen 93–154 dort), als reines JSON:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://haushalts-dashboard/recipe-vault-frontmatter.schema.json",
  "title": "RecipeFrontmatter",
  "type": "object",
  "required": ["name"],
  "additionalProperties": true,
  "properties": {
    "id": {
      "type": "string",
      "minLength": 1,
      "pattern": "^[a-z0-9]+(?:-[a-z0-9]+)*$",
      "description": "Stabiler eindeutiger Slug (kebab-case). Identitäts-Anker, nie ändern."
    },
    "name": {
      "type": "string",
      "minLength": 1,
      "description": "Pflicht. Leer/fehlend ⇒ Datei verworfen."
    },
    "rating": {
      "type": "string",
      "enum": ["favorit", "ok", "selten"],
      "description": "Default 'ok' bei Fehlen/ungültig."
    },
    "simple": { "type": "boolean", "description": "Default true." },
    "reheatable": { "type": "boolean", "description": "Default false." },
    "tags": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Wird als JSON-String gespeichert."
    },
    "ingredients": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name"],
        "additionalProperties": false,
        "properties": {
          "name": { "type": "string", "minLength": 1 },
          "amount": {
            "type": ["string", "number", "null"],
            "description": "Brüche/Bereiche als String ('1/2', '2-3')."
          },
          "unit": { "type": ["string", "number", "null"] },
          "freshness": {
            "type": ["string", "null"],
            "enum": ["frisch", "haltbar", null],
            "description": "Alles andere ⇒ null ⇒ Heuristik."
          }
        }
      }
    },
    "servings": { "type": "number", "exclusiveMinimum": 0, "description": "Nur Obsidian-Kochansicht; Ingest ignoriert." },
    "prepMinutes": { "type": "number", "minimum": 0 },
    "cookMinutes": { "type": "number", "minimum": 0 },
    "nutrition": {
      "type": "object",
      "additionalProperties": { "type": "number" }
    }
  }
}
```

- [ ] **Step 2: .gitignore anlegen**

```gitignore
node_modules/
dist/
*.log

# Android
android/.gradle/
android/build/
android/app/build/
android/local.properties
android/.idea/
*.keystore
!debug.keystore

# OS
Thumbs.db
.DS_Store
```

- [ ] **Step 3: Commit**

```powershell
git add shared/ .gitignore
git commit -m "feat: add shared recipe frontmatter JSON schema"
```

---

### Task 2: Validator-CLI (Vertrags-Gate)

**Files:**
- Create: `validator/package.json`
- Create: `validator/tsconfig.json`
- Create: `validator/src/parseRecipe.ts`
- Create: `validator/src/validateSchema.ts`
- Create: `validator/src/cli.ts`
- Test: `validator/test/parseRecipe.test.ts`
- Test: `validator/test/validateSchema.test.ts`

- [ ] **Step 1: Paket-Setup**

`validator/package.json`:

```json
{
  "name": "recipe-validator",
  "private": true,
  "type": "module",
  "scripts": {
    "validate": "tsx src/cli.ts",
    "test": "vitest run"
  },
  "dependencies": {
    "ajv": "^8.17.1",
    "gray-matter": "^4.0.3"
  },
  "devDependencies": {
    "tsx": "^4.19.0",
    "typescript": "^5.6.0",
    "vitest": "^2.1.0",
    "@types/node": "^22.0.0"
  }
}
```

`validator/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "strict": true,
    "skipLibCheck": true,
    "noEmit": true
  },
  "include": ["src", "test"]
}
```

Dann: `cd validator; npm install`

- [ ] **Step 2: Failing Tests für den Parser schreiben**

`validator/test/parseRecipe.test.ts` — Semantik exakt nach Contract Abschnitt 2/5:

```ts
import { describe, it, expect } from "vitest";
import { parseRecipeMarkdown } from "../src/parseRecipe.js";

const MINIMAL = `---
id: pasta-al-pomodoro
name: Pasta al Pomodoro
---
`;

const FULL = `---
id: gemuese-curry
name: Gemüse-Curry mit Kokosmilch
rating: favorit
simple: true
reheatable: true
tags: [vegetarisch, mealprep]
servings: 4
ingredients:
  - { name: Kokosmilch, amount: 400, unit: ml, freshness: haltbar }
  - { name: Süßkartoffel, amount: 2, unit: Stk, freshness: frisch }
---

## Zubereitung
1. Würfeln.
`;

describe("parseRecipeMarkdown", () => {
  it("parses the minimal contract example with defaults", () => {
    const { recipe, errors } = parseRecipeMarkdown(MINIMAL);
    expect(errors).toEqual([]);
    expect(recipe).toEqual({
      id: "pasta-al-pomodoro",
      name: "Pasta al Pomodoro",
      rating: "ok",
      simple: true,
      reheatable: false,
      tags: null,
      ingredients: [],
    });
  });

  it("parses the full reference recipe", () => {
    const { recipe, errors } = parseRecipeMarkdown(FULL);
    expect(errors).toEqual([]);
    expect(recipe!.rating).toBe("favorit");
    expect(recipe!.reheatable).toBe(true);
    expect(recipe!.tags).toBe(JSON.stringify(["vegetarisch", "mealprep"]));
    expect(recipe!.ingredients).toEqual([
      { name: "Kokosmilch", amount: "400", unit: "ml", category: "haltbar" },
      { name: "Süßkartoffel", amount: "2", unit: "Stk", category: "frisch" },
    ]);
  });

  it("rejects the whole file when name is missing", () => {
    const { recipe, errors } = parseRecipeMarkdown(`---\nid: x\n---\n`);
    expect(recipe).toBeNull();
    expect(errors.length).toBeGreaterThan(0);
  });

  it("skips only the broken ingredient, keeps the recipe", () => {
    const md = `---
name: Test
ingredients:
  - { amount: 5 }
  - { name: Reis }
---
`;
    const { recipe, errors } = parseRecipeMarkdown(md);
    expect(recipe!.ingredients).toEqual([
      { name: "Reis", amount: null, unit: null, category: null },
    ]);
    expect(errors.length).toBe(1);
  });

  it("defaults invalid enum values per contract", () => {
    const md = `---
name: Test
rating: superlecker
simple: "ja"
ingredients:
  - { name: Milch, freshness: kuehl }
---
`;
    const { recipe } = parseRecipeMarkdown(md);
    expect(recipe!.rating).toBe("ok");
    expect(recipe!.simple).toBe(true);
    expect(recipe!.ingredients[0].category).toBeNull();
  });
});
```

- [ ] **Step 3: Tests laufen lassen — müssen fehlschlagen**

Run: `cd validator; npm test`
Expected: FAIL — `Cannot find module '../src/parseRecipe.js'`

- [ ] **Step 4: Parser implementieren**

`validator/src/parseRecipe.ts`:

```ts
import matter from "gray-matter";

export type Rating = "favorit" | "ok" | "selten";
export type Freshness = "frisch" | "haltbar";

export interface ParsedIngredient {
  name: string;
  amount: string | null;
  unit: string | null;
  category: Freshness | null;
}

export interface ParsedRecipe {
  id: string | null;
  name: string;
  rating: Rating;
  simple: boolean;
  reheatable: boolean;
  tags: string | null;
  ingredients: ParsedIngredient[];
}

export interface ParseResult {
  recipe: ParsedRecipe | null;
  errors: string[];
}

const RATINGS: readonly string[] = ["favorit", "ok", "selten"];
const FRESHNESS: readonly string[] = ["frisch", "haltbar"];

export function parseRecipeMarkdown(raw: string): ParseResult {
  const errors: string[] = [];
  let data: Record<string, unknown>;
  try {
    data = matter(raw).data as Record<string, unknown>;
  } catch (e) {
    return { recipe: null, errors: [`invalid YAML: ${String(e)}`] };
  }

  const name = typeof data.name === "string" ? data.name.trim() : "";
  if (!name) {
    return { recipe: null, errors: ["missing or empty 'name' — file rejected"] };
  }

  const id =
    typeof data.id === "string" && data.id.trim() !== "" ? data.id.trim() : null;
  const rating = RATINGS.includes(data.rating as string)
    ? (data.rating as Rating)
    : "ok";
  const simple = typeof data.simple === "boolean" ? data.simple : true;
  const reheatable =
    typeof data.reheatable === "boolean" ? data.reheatable : false;
  const tags = Array.isArray(data.tags) ? JSON.stringify(data.tags) : null;

  const ingredients: ParsedIngredient[] = [];
  if (Array.isArray(data.ingredients)) {
    (data.ingredients as unknown[]).forEach((ing, i) => {
      if (typeof ing !== "object" || ing === null) {
        errors.push(`ingredient[${i}]: not an object — skipped`);
        return;
      }
      const o = ing as Record<string, unknown>;
      const ingName = typeof o.name === "string" ? o.name.trim() : "";
      if (!ingName) {
        errors.push(`ingredient[${i}]: missing/empty name — skipped`);
        return;
      }
      ingredients.push({
        name: ingName,
        amount: o.amount === null || o.amount === undefined ? null : String(o.amount),
        unit: o.unit === null || o.unit === undefined ? null : String(o.unit),
        category: FRESHNESS.includes(o.freshness as string)
          ? (o.freshness as Freshness)
          : null,
      });
    });
  }

  return {
    recipe: { id, name, rating, simple, reheatable, tags, ingredients },
    errors,
  };
}
```

- [ ] **Step 5: Parser-Tests grün**

Run: `cd validator; npm test`
Expected: PASS (5 Tests in parseRecipe.test.ts)

- [ ] **Step 6: Failing Tests für Schema-Validierung**

`validator/test/validateSchema.test.ts`:

```ts
import { describe, it, expect } from "vitest";
import { validateFrontmatter } from "../src/validateSchema.js";

describe("validateFrontmatter", () => {
  it("accepts the full reference recipe", () => {
    const md = `---
id: gemuese-curry
name: Gemüse-Curry
rating: favorit
ingredients:
  - { name: Reis, amount: 250, unit: g, freshness: haltbar }
---
`;
    expect(validateFrontmatter(md)).toEqual([]);
  });

  it("rejects an invalid id pattern (no kebab-case)", () => {
    const md = `---\nid: "Gemüse Curry"\nname: X\n---\n`;
    expect(validateFrontmatter(md).length).toBeGreaterThan(0);
  });

  it("rejects ingredient typo keys (freshnes)", () => {
    const md = `---
name: X
ingredients:
  - { name: Reis, freshnes: haltbar }
---
`;
    expect(validateFrontmatter(md).length).toBeGreaterThan(0);
  });

  it("rejects invalid rating enum", () => {
    const md = `---\nname: X\nrating: lecker\n---\n`;
    expect(validateFrontmatter(md).length).toBeGreaterThan(0);
  });
});
```

Run: `cd validator; npm test` → Expected: FAIL (`Cannot find module '../src/validateSchema.js'`)

- [ ] **Step 7: Schema-Validierung implementieren**

`validator/src/validateSchema.ts`:

```ts
import Ajv2020 from "ajv/dist/2020.js";
import matter from "gray-matter";
import { readFileSync } from "node:fs";

const schema = JSON.parse(
  readFileSync(
    new URL("../../shared/recipe-vault-frontmatter.schema.json", import.meta.url),
    "utf8",
  ),
);

const ajv = new Ajv2020({ allErrors: true });
const validateFn = ajv.compile(schema);

export function validateFrontmatter(raw: string): string[] {
  let data: unknown;
  try {
    data = matter(raw).data;
  } catch (e) {
    return [`invalid YAML: ${String(e)}`];
  }
  if (validateFn(data)) return [];
  return (validateFn.errors ?? []).map(
    (e) => `${e.instancePath || "/"}: ${e.message ?? "invalid"}`,
  );
}
```

Hinweis: Falls `Ajv2020` als Default-Import einen TS-Fehler wirft (`This expression is not constructable`), stattdessen: `import { Ajv2020 } from "ajv/dist/2020.js";` — je nach ajv-Version ist der Export benannt oder default. Eine der beiden Formen compiliert; die Tests entscheiden.

- [ ] **Step 8: Alle Tests grün**

Run: `cd validator; npm test`
Expected: PASS (9 Tests)

- [ ] **Step 9: CLI implementieren**

`validator/src/cli.ts`:

```ts
import { readFileSync } from "node:fs";
import { basename } from "node:path";
import { parseRecipeMarkdown } from "./parseRecipe.js";
import { validateFrontmatter } from "./validateSchema.js";

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error("usage: npm run validate -- <file.md> [more.md ...]");
  process.exit(2);
}

let failed = false;
for (const file of files) {
  const problems: string[] = [];
  if (basename(file).startsWith("_")) {
    problems.push("filename starts with '_' — ingest skips this file");
  }
  const raw = readFileSync(file, "utf8");
  problems.push(...validateFrontmatter(raw));
  const { recipe, errors } = parseRecipeMarkdown(raw);
  problems.push(...errors);
  if (recipe && !recipe.id) {
    problems.push("no explicit 'id' — contract requires a stable id");
  }

  if (problems.length === 0) {
    console.log(`OK   ${file} (id: ${recipe!.id})`);
  } else {
    failed = true;
    console.log(`FAIL ${file}`);
    for (const p of problems) console.log(`  - ${p}`);
  }
}
process.exit(failed ? 1 : 0);
```

- [ ] **Step 10: CLI manuell verifizieren**

Beispieldatei `validator/test/fixtures/ok.md` anlegen (Inhalt = voll ausgestattetes Rezept aus Contract Abschnitt 8, exakt kopieren). Dann:

Run: `cd validator; npm run validate -- test/fixtures/ok.md`
Expected: `OK   test/fixtures/ok.md (id: gemuese-curry)`, Exit 0

Run: `cd validator; npm run validate -- nicht-da.md; echo $LASTEXITCODE` mit kaputter Datei `validator/test/fixtures/bad.md` (`---\nrating: lecker\n---`) →
`npm run validate -- test/fixtures/bad.md` → Expected: `FAIL`, Exit 1

- [ ] **Step 11: Commit**

```powershell
git add validator/
git commit -m "feat: add contract validator CLI (vendored parser + ajv schema check)"
```

---

### Task 3: Android-Projekt-Gerüst

**Files:**
- Create: `android/settings.gradle.kts`
- Create: `android/build.gradle.kts`
- Create: `android/gradle.properties`
- Create: `android/app/build.gradle.kts`
- Create: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/ui/MainActivity.kt`
- Create: `android/app/src/main/res/values/strings.xml`
- Create: `android/app/src/main/res/values/themes.xml`

Voraussetzung: Prerequisites oben erledigt (JDK 17, Android SDK).

- [ ] **Step 1: Gradle-Dateien**

`android/settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "RezeptImporter"
include(":app")
```

`android/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}
```

`android/gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
```

`android/app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "de.dml.rezeptimporter"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.dml.rezeptimporter"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.yaml:snakeyaml:2.2")
    implementation("com.networknt:json-schema-validator:1.5.2")

    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

- [ ] **Step 2: Manifest + Minimal-UI**

`android/app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:label="@string/app_name"
        android:theme="@style/Theme.RezeptImporter"
        android:allowBackup="true">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`android/app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Rezept-Importer</string>
</resources>
```

`android/app/src/main/res/values/themes.xml`:

```xml
<resources>
    <style name="Theme.RezeptImporter" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

`android/app/src/main/java/de/dml/rezeptimporter/ui/MainActivity.kt`:

```kotlin
package de.dml.rezeptimporter.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Rezept-Importer — Settings folgen") }
    }
}
```

- [ ] **Step 3: Wrapper generieren + Build verifizieren**

In `android/`: `gradle wrapper --gradle-version 8.10.2` (oder Projekt in Android Studio öffnen → Sync). Dann:

Run: `cd android; .\gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`, APK unter `android/app/build/outputs/apk/debug/`

- [ ] **Step 4: Commit**

```powershell
git add android/
git commit -m "feat: scaffold Android app (Compose, deps, manifest)"
```

---

### Task 4: Domain-Modell + Slug-Generator

**Files:**
- Create: `android/app/src/main/java/de/dml/rezeptimporter/domain/RecipeDraft.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/domain/Slug.kt`
- Test: `android/app/src/test/java/de/dml/rezeptimporter/domain/SlugTest.kt`

- [ ] **Step 1: Failing Test**

`SlugTest.kt`:

```kotlin
package de.dml.rezeptimporter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SlugTest {
    @Test fun simpleName() = assertEquals("pasta-al-pomodoro", Slug.fromName("Pasta al Pomodoro"))
    @Test fun umlauts() = assertEquals("gemuese-curry-mit-suesskartoffel", Slug.fromName("Gemüse-Curry mit Süßkartoffel"))
    @Test fun specialChars() = assertEquals("oel-zitronen-pasta", Slug.fromName("Öl & Zitronen!! Pasta"))
    @Test fun collapsesDashes() = assertEquals("a-b", Slug.fromName("a --- b"))
    @Test fun trimsDashes() = assertEquals("abc", Slug.fromName("  abc  "))
    @Test fun digitsKept() = assertEquals("5-minuten-brot", Slug.fromName("5-Minuten-Brot"))
}
```

Run: `cd android; .\gradlew test`
Expected: FAIL — `Unresolved reference: Slug`

- [ ] **Step 2: Implementieren**

`Slug.kt`:

```kotlin
package de.dml.rezeptimporter.domain

object Slug {
    private val TRANSLIT = mapOf('ä' to "ae", 'ö' to "oe", 'ü' to "ue", 'ß' to "ss")

    fun fromName(name: String): String =
        name.lowercase()
            .map { TRANSLIT[it] ?: it.toString() }
            .joinToString("")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
```

`RecipeDraft.kt`:

```kotlin
package de.dml.rezeptimporter.domain

import kotlinx.serialization.Serializable

@Serializable
data class IngredientDraft(
    val name: String,
    /** Menge als String; Zahlen ("400"), Brüche ("1/2"), Bereiche ("2-3"). Writer re-typisiert. */
    val amount: String? = null,
    val unit: String? = null,
    /** "frisch" | "haltbar" | null */
    val freshness: String? = null,
)

@Serializable
data class RecipeDraft(
    val name: String,
    val tags: List<String> = emptyList(),
    val servings: Int? = null,
    val prepMinutes: Int? = null,
    val cookMinutes: Int? = null,
    val ingredients: List<IngredientDraft> = emptyList(),
    val steps: List<String> = emptyList(),
    // Nicht vom LLM befüllt — Defaults laut Contract, im Preview togglebar:
    val rating: String = "ok",
    val simple: Boolean = true,
    val reheatable: Boolean = false,
)
```

- [ ] **Step 3: Tests grün**

Run: `cd android; .\gradlew test`
Expected: PASS (6 Slug-Tests)

- [ ] **Step 4: Commit**

```powershell
git add android/app/src
git commit -m "feat: add RecipeDraft domain model and slug generator"
```

---

### Task 5: YAML-Emission (RecipeMarkdownWriter)

**Files:**
- Create: `android/app/src/main/java/de/dml/rezeptimporter/yaml/RecipeMarkdownWriter.kt`
- Test: `android/app/src/test/java/de/dml/rezeptimporter/yaml/RecipeMarkdownWriterTest.kt`

- [ ] **Step 1: Failing Tests (inkl. YAML-Roundtrip via SnakeYAML)**

```kotlin
package de.dml.rezeptimporter.yaml

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.Yaml

class RecipeMarkdownWriterTest {
    private val writer = RecipeMarkdownWriter()

    private fun frontmatterOf(md: String): Map<String, Any?> {
        val yaml = md.substringAfter("---\n").substringBefore("\n---")
        @Suppress("UNCHECKED_CAST")
        return Yaml().load(yaml) as Map<String, Any?>
    }

    @Test
    fun rendersFullRecipe() {
        val draft = RecipeDraft(
            name = "Gemüse-Curry",
            tags = listOf("vegetarisch"),
            servings = 4,
            ingredients = listOf(
                IngredientDraft("Kokosmilch", "400", "ml", "haltbar"),
                IngredientDraft("Brühe", "1/2", "l", null),
            ),
            steps = listOf("Würfeln.", "Köcheln."),
            rating = "favorit",
            reheatable = true,
        )
        val md = writer.render("gemuese-curry", draft)
        val fm = frontmatterOf(md)

        assertEquals("gemuese-curry", fm["id"])
        assertEquals("Gemüse-Curry", fm["name"])
        assertEquals("favorit", fm["rating"])
        assertEquals(true, fm["reheatable"])
        assertEquals(4, fm["servings"])

        @Suppress("UNCHECKED_CAST")
        val ings = fm["ingredients"] as List<Map<String, Any?>>
        assertEquals(400, ings[0]["amount"])          // Zahl bleibt Zahl
        assertEquals("1/2", ings[1]["amount"])        // Bruch bleibt String
        assertEquals("haltbar", ings[0]["freshness"])
        assertEquals(null, ings[1]["freshness"])       // null ⇒ Key weggelassen
        assertTrue(!ings[1].containsKey("freshness"))

        assertTrue(md.contains("## Zubereitung"))
        assertTrue(md.contains("1. Würfeln."))
        assertTrue(md.contains("2. Köcheln."))
    }

    @Test
    fun quotesSpecialCharacters() {
        val draft = RecipeDraft(
            name = "Öl: kaltgepresst & gut",
            ingredients = listOf(IngredientDraft("Öl, kaltgepresst")),
        )
        val md = writer.render("oel-kaltgepresst-gut", draft)
        val fm = frontmatterOf(md)   // Roundtrip beweist gültiges YAML
        assertEquals("Öl: kaltgepresst & gut", fm["name"])
        @Suppress("UNCHECKED_CAST")
        val ings = fm["ingredients"] as List<Map<String, Any?>>
        assertEquals("Öl, kaltgepresst", ings[0]["name"])
    }

    @Test
    fun startsWithFrontmatterDelimiter() {
        val md = writer.render("x", RecipeDraft(name = "X"))
        assertTrue(md.startsWith("---\n"))
    }
}
```

Run: `cd android; .\gradlew test` → Expected: FAIL (`Unresolved reference: RecipeMarkdownWriter`)

- [ ] **Step 2: Implementieren**

`RecipeMarkdownWriter.kt`:

```kotlin
package de.dml.rezeptimporter.yaml

import de.dml.rezeptimporter.domain.RecipeDraft
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml

class RecipeMarkdownWriter {

    fun render(id: String, draft: RecipeDraft): String {
        val fm = linkedMapOf<String, Any>()
        fm["id"] = id
        fm["name"] = draft.name
        fm["rating"] = draft.rating
        fm["simple"] = draft.simple
        fm["reheatable"] = draft.reheatable
        if (draft.tags.isNotEmpty()) fm["tags"] = draft.tags
        draft.servings?.let { fm["servings"] = it }
        draft.prepMinutes?.let { fm["prepMinutes"] = it }
        draft.cookMinutes?.let { fm["cookMinutes"] = it }
        if (draft.ingredients.isNotEmpty()) {
            fm["ingredients"] = draft.ingredients.map { ing ->
                val m = linkedMapOf<String, Any>("name" to ing.name)
                ing.amount?.let { m["amount"] = coerceAmount(it) }
                ing.unit?.let { m["unit"] = it }
                ing.freshness?.let { m["freshness"] = it }
                m
            }
        }

        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isAllowUnicode = true
        }
        val yaml = Yaml(options).dump(fm)

        val body = buildString {
            if (draft.steps.isNotEmpty()) {
                appendLine("## Zubereitung")
                draft.steps.forEachIndexed { i, step -> appendLine("${i + 1}. $step") }
            }
        }
        return "---\n$yaml---\n\n$body"
    }

    /** "400" → 400, "1.5" → 1.5, "1/2"/"2-3"/"etwas" → String (SnakeYAML quotet bei Bedarf). */
    private fun coerceAmount(amount: String): Any {
        amount.toIntOrNull()?.let { return it }
        amount.toDoubleOrNull()?.let { return it }
        return amount
    }
}
```

- [ ] **Step 3: Tests grün**

Run: `cd android; .\gradlew test` → Expected: PASS

- [ ] **Step 4: Commit**

```powershell
git add android/app/src
git commit -m "feat: add YAML frontmatter + body markdown writer with roundtrip-safe quoting"
```

---

### Task 6: Schema-Validierung in der App (RecipeValidator)

**Files:**
- Create: `android/app/src/main/assets/recipe-vault-frontmatter.schema.json` (Kopie aus `shared/` — Gradle-Task hält sie synchron)
- Create: `android/app/src/main/java/de/dml/rezeptimporter/validate/RecipeValidator.kt`
- Modify: `android/app/build.gradle.kts` (Copy-Task)
- Test: `android/app/src/test/java/de/dml/rezeptimporter/validate/RecipeValidatorTest.kt`

- [ ] **Step 1: Copy-Task in `android/app/build.gradle.kts` ergänzen** (ans Datei-Ende):

```kotlin
// Schema aus shared/ ist die eine Quelle der Wahrheit — vor jedem Build in assets spiegeln.
val syncSchema by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("../shared/recipe-vault-frontmatter.schema.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
}
tasks.named("preBuild") { dependsOn(syncSchema) }
```

- [ ] **Step 2: Failing Test**

`RecipeValidatorTest.kt` — lädt das Schema im JVM-Test direkt aus `shared/` (Pfad relativ zum Modul):

```kotlin
package de.dml.rezeptimporter.validate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecipeValidatorTest {
    private val schemaJson =
        File("../../shared/recipe-vault-frontmatter.schema.json").readText()
    private val validator = RecipeValidator(schemaJson)

    @Test
    fun acceptsValidFrontmatter() {
        val md = """
            ---
            id: gemuese-curry
            name: Gemüse-Curry
            rating: favorit
            ingredients:
              - { name: Reis, amount: 250, unit: g, freshness: haltbar }
            ---
        """.trimIndent()
        assertEquals(emptyList<String>(), validator.validateMarkdown(md))
    }

    @Test
    fun rejectsBadIdPattern() {
        val md = "---\nid: Hat Leerzeichen\nname: X\n---\n"
        assertTrue(validator.validateMarkdown(md).isNotEmpty())
    }

    @Test
    fun rejectsIngredientTypoKey() {
        val md = """
            ---
            name: X
            ingredients:
              - { name: Reis, freshnes: haltbar }
            ---
        """.trimIndent()
        assertTrue(validator.validateMarkdown(md).isNotEmpty())
    }

    @Test
    fun rejectsMissingName() {
        val md = "---\nid: x\n---\n"
        assertTrue(validator.validateMarkdown(md).isNotEmpty())
    }
}
```

Run: `cd android; .\gradlew test` → Expected: FAIL

- [ ] **Step 3: Implementieren**

`RecipeValidator.kt` — parst das fertige Markdown zurück (Roundtrip!) und validiert das Frontmatter gegen das Schema:

```kotlin
package de.dml.rezeptimporter.validate

import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.yaml.snakeyaml.Yaml

class RecipeValidator(schemaJson: String) {

    private val schema: JsonSchema = JsonSchemaFactory
        .getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(schemaJson)
    private val mapper = ObjectMapper()
    private val yaml = Yaml()

    /** Volles Markdown rein → Frontmatter-Roundtrip + Schema-Check. Leere Liste = valide. */
    fun validateMarkdown(markdown: String): List<String> {
        if (!markdown.startsWith("---\n")) return listOf("missing frontmatter delimiter")
        val fmText = markdown.removePrefix("---\n").substringBefore("\n---")
        val data: Any? = try {
            yaml.load(fmText)
        } catch (e: Exception) {
            return listOf("invalid YAML: ${e.message}")
        }
        if (data !is Map<*, *>) return listOf("frontmatter is not a mapping")
        val node = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(data)
        return schema.validate(node).map { it.message }
    }
}
```

(Jackson kommt transitiv mit `json-schema-validator` — kein extra Dependency-Eintrag nötig.)

- [ ] **Step 4: Tests grün**

Run: `cd android; .\gradlew test` → Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add android/app
git commit -m "feat: validate emitted markdown against shared JSON schema (networknt)"
```

---

### Task 7: LlmExtractor-Interface, Prompt, Mapper, Fake

**Files:**
- Create: `android/app/src/main/java/de/dml/rezeptimporter/llm/LlmExtractor.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/llm/ExtractionPrompt.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/llm/RecipeJsonMapper.kt`
- Test: `android/app/src/test/java/de/dml/rezeptimporter/llm/RecipeJsonMapperTest.kt`
- Test (Fake für spätere Tasks): `android/app/src/test/java/de/dml/rezeptimporter/llm/FakeLlmExtractor.kt`

- [ ] **Step 1: Failing Test für den Mapper**

`RecipeJsonMapperTest.kt`:

```kotlin
package de.dml.rezeptimporter.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RecipeJsonMapperTest {

    @Test
    fun mapsFullPayload() {
        val json = Json.parseToJsonElement(
            """
            {
              "name": "Gemüse-Curry",
              "tags": ["vegetarisch"],
              "servings": 4,
              "ingredients": [
                {"name": "Kokosmilch", "amount": "400", "unit": "ml", "freshness": "haltbar"},
                {"name": "Salz"}
              ],
              "steps": ["Würfeln.", "Köcheln."]
            }
            """
        ).jsonObject
        val draft = RecipeJsonMapper.fromJson(json)
        assertEquals("Gemüse-Curry", draft.name)
        assertEquals(4, draft.servings)
        assertEquals("400", draft.ingredients[0].amount)
        assertEquals(null, draft.ingredients[1].amount)
        assertEquals(2, draft.steps.size)
        assertEquals("ok", draft.rating)        // Default, nie vom LLM
    }

    @Test
    fun toleratesNumericAmountAndUnknownKeys() {
        val json = Json.parseToJsonElement(
            """{"name":"X","ingredients":[{"name":"Reis","amount":250,"extra":"weg"}],"steps":[],"unknown":1}"""
        ).jsonObject
        val draft = RecipeJsonMapper.fromJson(json)
        assertEquals("250", draft.ingredients[0].amount)   // Zahl → String
    }

    @Test
    fun rejectsInvalidFreshness() {
        val json = Json.parseToJsonElement(
            """{"name":"X","ingredients":[{"name":"Milch","freshness":"kuehl"}],"steps":[]}"""
        ).jsonObject
        val draft = RecipeJsonMapper.fromJson(json)
        assertEquals(null, draft.ingredients[0].freshness)
    }
}
```

Run: `cd android; .\gradlew test` → Expected: FAIL

- [ ] **Step 2: Interface + Prompt + Mapper implementieren**

`LlmExtractor.kt`:

```kotlin
package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft

interface LlmExtractor {
    /**
     * Genau ein API-Call. [repairHint] nur beim einen erlaubten Repair-Retry gesetzt
     * (enthält die Validierungsfehler des ersten Versuchs).
     */
    suspend fun extract(rawText: String, repairHint: String? = null): RecipeDraft
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

`ExtractionPrompt.kt`:

```kotlin
package de.dml.rezeptimporter.llm

object ExtractionPrompt {
    const val MAX_INPUT_CHARS = 6000
    const val MAX_OUTPUT_TOKENS = 1500

    val INSTRUCTION = """
        Du extrahierst Kochrezepte aus rohem Text (OCR-Ergebnisse, Social-Media-Captions).
        Antworte ausschließlich im vorgegebenen JSON-Format. Sprache: Deutsch.
        Regeln:
        - "amount" immer als String: ganze Zahlen "400", Dezimal "1.5", Brüche "1/2", Bereiche "2-3".
        - "unit" separat: g, kg, ml, l, EL, TL, Stk, Prise, Bund.
        - "freshness" nur wenn eindeutig: "frisch" (Gemüse, Obst, Fleisch, Fisch, Milchprodukte, Kräuter),
          "haltbar" (Trockenvorrat, Konserven, Gewürze, Öl). Im Zweifel weglassen.
        - "steps": die Zubereitungsschritte als einzelne, vollständige Sätze.
        - Unbekannte Felder weglassen. Nichts erfinden.
    """.trimIndent()

    /** JSON-Schema (Draft-Stil) für Anthropic-Tool-Use. */
    const val SCHEMA_JSON = """
    {
      "type": "object",
      "properties": {
        "name": {"type": "string"},
        "tags": {"type": "array", "items": {"type": "string"}},
        "servings": {"type": "integer"},
        "prepMinutes": {"type": "integer"},
        "cookMinutes": {"type": "integer"},
        "ingredients": {
          "type": "array",
          "items": {
            "type": "object",
            "properties": {
              "name": {"type": "string"},
              "amount": {"type": "string"},
              "unit": {"type": "string"},
              "freshness": {"type": "string", "enum": ["frisch", "haltbar"]}
            },
            "required": ["name"],
            "additionalProperties": false
          }
        },
        "steps": {"type": "array", "items": {"type": "string"}}
      },
      "required": ["name", "ingredients", "steps"],
      "additionalProperties": false
    }
    """

    fun userMessage(rawText: String, repairHint: String?): String {
        val capped = rawText.take(MAX_INPUT_CHARS)
        val repair = repairHint?.let {
            "\n\nDein letzter Versuch war ungültig. Fehler: $it\nKorrigiere genau diese Punkte."
        } ?: ""
        return "Extrahiere das Rezept aus folgendem Text:\n\n$capped$repair"
    }
}
```

`RecipeJsonMapper.kt`:

```kotlin
package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.serialization.json.*

object RecipeJsonMapper {
    private val FRESHNESS = setOf("frisch", "haltbar")

    fun fromJson(obj: JsonObject): RecipeDraft {
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: throw LlmException("LLM-Antwort ohne 'name'")
        if (name.isEmpty()) throw LlmException("LLM-Antwort mit leerem 'name'")

        val ingredients = obj["ingredients"]?.jsonArray.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val ingName = o["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            IngredientDraft(
                name = ingName,
                amount = o["amount"]?.jsonPrimitive?.contentOrNull,
                unit = o["unit"]?.jsonPrimitive?.contentOrNull,
                freshness = o["freshness"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it in FRESHNESS },
            )
        }

        return RecipeDraft(
            name = name,
            tags = obj["tags"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull },
            servings = obj["servings"]?.jsonPrimitive?.intOrNull,
            prepMinutes = obj["prepMinutes"]?.jsonPrimitive?.intOrNull,
            cookMinutes = obj["cookMinutes"]?.jsonPrimitive?.intOrNull,
            ingredients = ingredients,
            steps = obj["steps"]?.jsonArray.orEmpty()
                .mapNotNull { it.jsonPrimitive.contentOrNull },
        )
    }
}
```

Hinweis: `jsonPrimitive.contentOrNull` liefert bei `amount: 250` (Zahl) den String `"250"` — genau das gewollte Verhalten aus dem Test.

`FakeLlmExtractor.kt` (in `src/test`):

```kotlin
package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft

class FakeLlmExtractor(private val result: RecipeDraft) : LlmExtractor {
    var calls = 0
        private set
    var lastRepairHint: String? = null
        private set

    override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft {
        calls++
        lastRepairHint = repairHint
        return result
    }
}
```

- [ ] **Step 3: Tests grün**

Run: `cd android; .\gradlew test` → Expected: PASS

- [ ] **Step 4: Commit**

```powershell
git add android/app/src
git commit -m "feat: add LlmExtractor interface, extraction prompt, JSON mapper, test fake"
```

---

### Task 8: GeminiExtractor (MockWebServer-TDD)

**Files:**
- Create: `android/app/src/main/java/de/dml/rezeptimporter/llm/GeminiExtractor.kt`
- Test: `android/app/src/test/java/de/dml/rezeptimporter/llm/GeminiExtractorTest.kt`

- [ ] **Step 1: Failing Test**

```kotlin
package de.dml.rezeptimporter.llm

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiExtractorTest {
    private val server = MockWebServer()
    private lateinit var extractor: GeminiExtractor

    @Before fun setUp() {
        server.start()
        extractor = GeminiExtractor(
            apiKey = "test-key",
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun parsesRecipeFromJsonModeResponse() = runTest {
        // Gemini liefert das Rezept-JSON als String in parts[0].text
        val recipeJson = """{"name":"Curry","ingredients":[{"name":"Reis","amount":"250","unit":"g"}],"steps":["Kochen."]}"""
        server.enqueue(
            MockResponse().setBody(
                """{"candidates":[{"content":{"parts":[{"text":${recipeJson.let { "\"" + it.replace("\"", "\\\"") + "\"" }}}]}}]}"""
            )
        )

        val draft = extractor.extract("roher text")

        assertEquals("Curry", draft.name)
        assertEquals("250", draft.ingredients[0].amount)

        val req = server.takeRequest()
        assertEquals("test-key", req.getHeader("x-goog-api-key"))
        assertTrue(req.path!!.contains("gemini-2.5-flash:generateContent"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"responseMimeType\":\"application/json\""))
        assertTrue(body.contains("\"maxOutputTokens\":1500"))
    }

    @Test
    fun throwsLlmExceptionOnHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"quota"}"""))
        try {
            extractor.extract("text")
            throw AssertionError("expected LlmException")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("429"))
        }
    }
}
```

Run: `cd android; .\gradlew test` → Expected: FAIL

- [ ] **Step 2: Implementieren**

`GeminiExtractor.kt`:

```kotlin
package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiExtractor(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://generativelanguage.googleapis.com",
    private val model: String = "gemini-2.5-flash",
) : LlmExtractor {

    // Gemini responseSchema = OpenAPI-Subset, Typen GROSS, kein additionalProperties.
    private val responseSchema = buildJsonObject {
        put("type", "OBJECT")
        putJsonObject("properties") {
            putJsonObject("name") { put("type", "STRING") }
            putJsonObject("tags") {
                put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") }
            }
            putJsonObject("servings") { put("type", "INTEGER") }
            putJsonObject("prepMinutes") { put("type", "INTEGER") }
            putJsonObject("cookMinutes") { put("type", "INTEGER") }
            putJsonObject("ingredients") {
                put("type", "ARRAY")
                putJsonObject("items") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "STRING") }
                        putJsonObject("amount") { put("type", "STRING") }
                        putJsonObject("unit") { put("type", "STRING") }
                        putJsonObject("freshness") {
                            put("type", "STRING")
                            putJsonArray("enum") { add("frisch"); add("haltbar") }
                        }
                    }
                    putJsonArray("required") { add("name") }
                }
            }
            putJsonObject("steps") {
                put("type", "ARRAY"); putJsonObject("items") { put("type", "STRING") }
            }
        }
        putJsonArray("required") { add("name"); add("ingredients"); add("steps") }
    }

    override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                putJsonArray("contents") {
                    addJsonObject {
                        putJsonArray("parts") {
                            addJsonObject {
                                put("text", ExtractionPrompt.INSTRUCTION + "\n\n" +
                                    ExtractionPrompt.userMessage(rawText, repairHint))
                            }
                        }
                    }
                }
                putJsonObject("generationConfig") {
                    put("responseMimeType", "application/json")
                    put("responseSchema", responseSchema)
                    put("maxOutputTokens", ExtractionPrompt.MAX_OUTPUT_TOKENS)
                }
            }

            val request = Request.Builder()
                .url("$baseUrl/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw LlmException("Gemini HTTP ${resp.code}: ${text.take(300)}")
                }
                val root = Json.parseToJsonElement(text).jsonObject
                val payload = root["candidates"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("text")?.jsonPrimitive?.content
                    ?: throw LlmException("Gemini-Antwort ohne candidates/parts/text")
                RecipeJsonMapper.fromJson(Json.parseToJsonElement(payload).jsonObject)
            }
        }
}
```

- [ ] **Step 3: Tests grün**

Run: `cd android; .\gradlew test` → Expected: PASS

- [ ] **Step 4: Commit**

```powershell
git add android/app/src
git commit -m "feat: add Gemini Flash extractor (JSON mode + responseSchema)"
```

---

### Task 9: HaikuExtractor (MockWebServer-TDD)

**Files:**
- Create: `android/app/src/main/java/de/dml/rezeptimporter/llm/HaikuExtractor.kt`
- Test: `android/app/src/test/java/de/dml/rezeptimporter/llm/HaikuExtractorTest.kt`

- [ ] **Step 1: Failing Test**

```kotlin
package de.dml.rezeptimporter.llm

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HaikuExtractorTest {
    private val server = MockWebServer()
    private lateinit var extractor: HaikuExtractor

    @Before fun setUp() {
        server.start()
        extractor = HaikuExtractor(
            apiKey = "sk-test",
            client = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun parsesRecipeFromToolUseResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "content": [
                    {"type": "text", "text": "Ich extrahiere das Rezept."},
                    {"type": "tool_use", "id": "toolu_1", "name": "save_recipe",
                     "input": {"name": "Curry", "ingredients": [{"name": "Reis", "amount": "250", "unit": "g"}], "steps": ["Kochen."]}}
                  ],
                  "stop_reason": "tool_use"
                }
                """
            )
        )

        val draft = extractor.extract("roher text")

        assertEquals("Curry", draft.name)
        assertEquals("g", draft.ingredients[0].unit)

        val req = server.takeRequest()
        assertEquals("sk-test", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
        assertEquals("/v1/messages", req.path)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"claude-haiku-4-5\""))
        assertTrue(body.contains("\"save_recipe\""))
        assertTrue(body.contains("\"max_tokens\":1500"))
    }

    @Test
    fun throwsWhenNoToolUseBlock() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"content":[{"type":"text","text":"kann nicht"}],"stop_reason":"end_turn"}""")
        )
        try {
            extractor.extract("text")
            throw AssertionError("expected LlmException")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("tool_use"))
        }
    }
}
```

Run: `cd android; .\gradlew test` → Expected: FAIL

- [ ] **Step 2: Implementieren**

`HaikuExtractor.kt`:

```kotlin
package de.dml.rezeptimporter.llm

import de.dml.rezeptimporter.domain.RecipeDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class HaikuExtractor(
    private val apiKey: String,
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.anthropic.com",
    private val model: String = "claude-haiku-4-5",
) : LlmExtractor {

    override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", model)
                put("max_tokens", ExtractionPrompt.MAX_OUTPUT_TOKENS)
                put("system", ExtractionPrompt.INSTRUCTION)
                putJsonArray("tools") {
                    addJsonObject {
                        put("name", "save_recipe")
                        put("description", "Speichert das aus dem Text extrahierte Rezept strukturiert ab.")
                        put("input_schema", Json.parseToJsonElement(ExtractionPrompt.SCHEMA_JSON))
                    }
                }
                putJsonObject("tool_choice") { put("type", "tool"); put("name", "save_recipe") }
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", ExtractionPrompt.userMessage(rawText, repairHint))
                    }
                }
            }

            val request = Request.Builder()
                .url("$baseUrl/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    throw LlmException("Anthropic HTTP ${resp.code}: ${text.take(300)}")
                }
                val content = Json.parseToJsonElement(text).jsonObject["content"]?.jsonArray
                    ?: throw LlmException("Anthropic-Antwort ohne content[]")
                val toolUse = content.firstOrNull {
                    it.jsonObject["type"]?.jsonPrimitive?.content == "tool_use"
                }?.jsonObject
                    ?: throw LlmException("Anthropic-Antwort ohne tool_use-Block")
                RecipeJsonMapper.fromJson(toolUse["input"]!!.jsonObject)
            }
        }
}
```

- [ ] **Step 3: Tests grün**

Run: `cd android; .\gradlew test` → Expected: PASS

- [ ] **Step 4: Commit**

```powershell
git add android/app/src
git commit -m "feat: add Claude Haiku extractor (forced tool use via REST)"
```

---

### Task 10: VaultStorage + VaultWriter (Kollisions-Logik)

**Files:**
- Create: `android/app/src/main/java/de/dml/rezeptimporter/vault/VaultStorage.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/vault/VaultWriter.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/vault/SafVaultStorage.kt`
- Test: `android/app/src/test/java/de/dml/rezeptimporter/vault/VaultWriterTest.kt`

- [ ] **Step 1: Failing Test (mit In-Memory-Fake)**

`VaultWriterTest.kt`:

```kotlin
package de.dml.rezeptimporter.vault

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private class FakeVaultStorage : VaultStorage {
    val files = mutableMapOf<String, String>()
    override fun listMarkdownFiles(): List<VaultFile> =
        files.map { (name, content) ->
            VaultFile(name, Regex("(?m)^id:\\s*(\\S+)").find(content)?.groupValues?.get(1))
        }
    override fun write(fileName: String, content: String) { files[fileName] = content }
}

class VaultWriterTest {
    private val schemaJson = File("../../shared/recipe-vault-frontmatter.schema.json").readText()

    private fun writer(storage: VaultStorage) =
        VaultWriter(storage, RecipeMarkdownWriter(), RecipeValidator(schemaJson))

    private val draft = RecipeDraft(
        name = "Gemüse-Curry",
        ingredients = listOf(IngredientDraft("Reis", "250", "g", "haltbar")),
        steps = listOf("Kochen."),
    )

    @Test
    fun writesValidatedFile() {
        val storage = FakeVaultStorage()
        val result = writer(storage).write(draft)
        assertEquals("gemuese-curry", result.id)
        assertEquals("Gemüse-Curry.md", result.fileName)
        assertTrue(storage.files["Gemüse-Curry.md"]!!.contains("id: gemuese-curry"))
    }

    @Test
    fun autoSuffixesOnIdCollision() {
        val storage = FakeVaultStorage()
        storage.files["alt.md"] = "---\nid: gemuese-curry\nname: Alt\n---\n"
        val result = writer(storage).write(draft)
        assertEquals("gemuese-curry-2", result.id)
    }

    @Test
    fun incrementsSuffixPastExisting() {
        val storage = FakeVaultStorage()
        storage.files["a.md"] = "---\nid: gemuese-curry\nname: A\n---\n"
        storage.files["b.md"] = "---\nid: gemuese-curry-2\nname: B\n---\n"
        val result = writer(storage).write(draft)
        assertEquals("gemuese-curry-3", result.id)
    }

    @Test
    fun stripsForbiddenFilenameChars() {
        val storage = FakeVaultStorage()
        val result = writer(storage).write(draft.copy(name = "Was? Curry/Reis: Test"))
        assertTrue(!result.fileName.contains("?"))
        assertTrue(!result.fileName.contains("/"))
        assertTrue(!result.fileName.startsWith("_"))
        assertTrue(result.fileName.endsWith(".md"))
    }

    @Test(expected = IllegalStateException::class)
    fun refusesToWriteInvalidOutput() {
        // Name nur aus Sonderzeichen ⇒ leerer Slug ⇒ id verletzt Schema-Pattern ⇒ Write verweigert
        val storage = FakeVaultStorage()
        writer(storage).write(draft.copy(name = "!!!"))
    }
}
```

Run: `cd android; .\gradlew test` → Expected: FAIL

- [ ] **Step 2: Implementieren**

`VaultStorage.kt`:

```kotlin
package de.dml.rezeptimporter.vault

data class VaultFile(val fileName: String, val id: String?)

interface VaultStorage {
    fun listMarkdownFiles(): List<VaultFile>
    fun write(fileName: String, content: String)
}
```

`VaultWriter.kt`:

```kotlin
package de.dml.rezeptimporter.vault

import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.domain.Slug
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter

data class WriteResult(val id: String, val fileName: String)

class VaultWriter(
    private val storage: VaultStorage,
    private val markdownWriter: RecipeMarkdownWriter,
    private val validator: RecipeValidator,
) {
    /**
     * Slug erzeugen, bei Kollision automatisch -2/-3… suffixen (Phase 1; Dialog kommt in Phase 4),
     * rendern, validieren (Schema + Roundtrip), erst dann schreiben.
     */
    fun write(draft: RecipeDraft): WriteResult {
        val base = Slug.fromName(draft.name)
        val existing = storage.listMarkdownFiles().mapNotNull { it.id }.toSet()
        var id = base
        var n = 2
        while (id in existing) { id = "$base-$n"; n++ }

        val content = markdownWriter.render(id, draft)
        val problems = validator.validateMarkdown(content)
        check(problems.isEmpty()) {
            "Validierung fehlgeschlagen, Datei NICHT geschrieben: ${problems.joinToString("; ")}"
        }

        val fileName = draft.name
            .replace(Regex("[\\\\/:*?\"<>|]"), "-")
            .trim()
            .removePrefix("_")
            .ifEmpty { id } + ".md"
        storage.write(fileName, content)
        return WriteResult(id, fileName)
    }
}
```

`SafVaultStorage.kt` (Android-Seite, kein Unit-Test — manuell in Task 13 verifiziert):

```kotlin
package de.dml.rezeptimporter.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class SafVaultStorage(private val context: Context, private val treeUri: Uri) : VaultStorage {

    private val dir: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Vault-Ordner nicht erreichbar — in Settings neu wählen")

    override fun listMarkdownFiles(): List<VaultFile> =
        dir.listFiles()
            .filter { it.isFile && (it.name ?: "").endsWith(".md", ignoreCase = true) }
            .map { file ->
                val head = context.contentResolver.openInputStream(file.uri)?.use { ins ->
                    ins.readNBytes(2048).toString(Charsets.UTF_8)
                } ?: ""
                VaultFile(
                    fileName = file.name ?: "",
                    id = Regex("(?m)^id:\\s*\"?([A-Za-z0-9-]+)\"?").find(head)?.groupValues?.get(1),
                )
            }

    override fun write(fileName: String, content: String) {
        dir.findFile(fileName)?.delete()
        val file = dir.createFile("text/markdown", fileName)
            ?: throw IllegalStateException("Datei konnte nicht angelegt werden: $fileName")
        context.contentResolver.openOutputStream(file.uri)?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("OutputStream null für $fileName")
    }
}
```

- [ ] **Step 3: Tests grün**

Run: `cd android; .\gradlew test` → Expected: PASS

- [ ] **Step 4: Commit**

```powershell
git add android/app/src
git commit -m "feat: add vault storage abstraction, collision-safe writer, SAF implementation"
```

---

### Task 11: Settings (Vault-Picker, Provider, Keys)

**Files:**
- Create: `android/app/src/main/java/de/dml/rezeptimporter/settings/AppSettings.kt`
- Modify: `android/app/src/main/java/de/dml/rezeptimporter/ui/MainActivity.kt`

Keine Unit-Tests (reine Android-Framework-Verkabelung; manuelle Verifikation in Task 13).

- [ ] **Step 1: AppSettings implementieren**

```kotlin
package de.dml.rezeptimporter.settings

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Provider { GEMINI, HAIKU }

class AppSettings(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "rezept_importer_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var vaultUri: Uri?
        get() = prefs.getString("vault_uri", null)?.let(Uri::parse)
        set(v) = prefs.edit().putString("vault_uri", v?.toString()).apply()

    var provider: Provider
        get() = Provider.valueOf(prefs.getString("provider", Provider.GEMINI.name)!!)
        set(v) = prefs.edit().putString("provider", v.name).apply()

    var geminiKey: String
        get() = prefs.getString("gemini_key", "")!!
        set(v) = prefs.edit().putString("gemini_key", v).apply()

    var anthropicKey: String
        get() = prefs.getString("anthropic_key", "")!!
        set(v) = prefs.edit().putString("anthropic_key", v).apply()
}
```

- [ ] **Step 2: MainActivity als Settings-Screen**

```kotlin
package de.dml.rezeptimporter.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dml.rezeptimporter.settings.AppSettings
import de.dml.rezeptimporter.settings.Provider

class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings

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
            MaterialTheme {
                var provider by remember { mutableStateOf(settings.provider) }
                var geminiKey by remember { mutableStateOf(settings.geminiKey) }
                var anthropicKey by remember { mutableStateOf(settings.anthropicKey) }
                val vaultUri by vaultUriState

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Rezept-Importer", style = MaterialTheme.typography.headlineSmall)

                    Text("Vault-Ordner", style = MaterialTheme.typography.titleMedium)
                    Text(if (vaultUri.isEmpty()) "— nicht gewählt —" else vaultUri,
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { pickFolder.launch(null) }) { Text("Ordner wählen") }

                    HorizontalDivider()

                    Text("LLM-Provider", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(provider == Provider.GEMINI, onClick = {
                            provider = Provider.GEMINI; settings.provider = Provider.GEMINI
                        })
                        Text("Gemini Flash (Free Tier)")
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(provider == Provider.HAIKU, onClick = {
                            provider = Provider.HAIKU; settings.provider = Provider.HAIKU
                        })
                        Text("Claude Haiku")
                    }

                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it; settings.geminiKey = it },
                        label = { Text("Gemini API-Key") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = anthropicKey,
                        onValueChange = { anthropicKey = it; settings.anthropicKey = it },
                        label = { Text("Anthropic API-Key") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        "Teile ein Foto oder einen Text mit dieser App, um ein Rezept zu importieren.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Build verifizieren**

Run: `cd android; .\gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```powershell
git add android/app/src
git commit -m "feat: add settings screen (SAF vault picker, provider toggle, encrypted keys)"
```

---

### Task 12: ImportPipeline + ShareActivity + Preview

**Files:**
- Create: `android/app/src/main/java/de/dml/rezeptimporter/ocr/OcrTextExtractor.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/pipeline/ImportPipeline.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/ui/ShareActivity.kt`
- Create: `android/app/src/main/java/de/dml/rezeptimporter/ui/PreviewScreen.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Test: `android/app/src/test/java/de/dml/rezeptimporter/pipeline/ImportPipelineTest.kt`

- [ ] **Step 1: Failing Test für Pipeline (Repair-Retry-Logik, max 2 Calls)**

`ImportPipelineTest.kt`:

```kotlin
package de.dml.rezeptimporter.pipeline

import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.llm.FakeLlmExtractor
import de.dml.rezeptimporter.llm.LlmException
import de.dml.rezeptimporter.llm.LlmExtractor
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ImportPipelineTest {
    private val schemaJson = File("../../shared/recipe-vault-frontmatter.schema.json").readText()
    private val validator = RecipeValidator(schemaJson)
    private val writer = RecipeMarkdownWriter()

    private val good = RecipeDraft(
        name = "Curry",
        ingredients = listOf(IngredientDraft("Reis", "250", "g")),
        steps = listOf("Kochen."),
    )

    @Test
    fun singleCallWhenFirstResultValid() = runTest {
        val fake = FakeLlmExtractor(good)
        val pipeline = ImportPipeline(fake, validator, writer)
        val draft = pipeline.extractValidated("text")
        assertEquals("Curry", draft.name)
        assertEquals(1, fake.calls)
    }

    @Test
    fun retriesOnceWithRepairHintThenSucceeds() = runTest {
        // Erster Call liefert Draft, der invalide rendert (leerer Name nach Trim unmöglich —
        // wir simulieren über einen Extractor, der erst Schrott, dann Gutes liefert)
        var call = 0
        val flaky = object : LlmExtractor {
            override suspend fun extract(rawText: String, repairHint: String?): RecipeDraft {
                call++
                return if (call == 1) good.copy(name = "!!!") else good  // "!!!" ⇒ leerer Slug ⇒ invalide
            }
        }
        val pipeline = ImportPipeline(flaky, validator, writer)
        val draft = pipeline.extractValidated("text")
        assertEquals("Curry", draft.name)
        assertEquals(2, call)
    }

    @Test(expected = LlmException::class)
    fun givesUpAfterTwoFailedCalls() = runTest {
        val alwaysBad = object : LlmExtractor {
            override suspend fun extract(rawText: String, repairHint: String?) =
                good.copy(name = "!!!")
        }
        ImportPipeline(alwaysBad, validator, writer).extractValidated("text")
    }
}
```

Run: `cd android; .\gradlew test` → Expected: FAIL

- [ ] **Step 2: Pipeline implementieren**

`ImportPipeline.kt`:

```kotlin
package de.dml.rezeptimporter.pipeline

import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.domain.Slug
import de.dml.rezeptimporter.llm.LlmException
import de.dml.rezeptimporter.llm.LlmExtractor
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter

class ImportPipeline(
    private val extractor: LlmExtractor,
    private val validator: RecipeValidator,
    private val writer: RecipeMarkdownWriter,
) {
    /**
     * Rohtext → validierter RecipeDraft. Harte Obergrenze: 2 LLM-Calls
     * (1 Extraktion + 1 Repair-Retry mit Fehlerliste). Danach LlmException.
     */
    suspend fun extractValidated(rawText: String): RecipeDraft {
        val first = extractor.extract(rawText)
        val firstProblems = problemsOf(first)
        if (firstProblems.isEmpty()) return first

        val second = extractor.extract(rawText, repairHint = firstProblems.joinToString("; "))
        val secondProblems = problemsOf(second)
        if (secondProblems.isEmpty()) return second

        throw LlmException("Extraktion nach Repair-Retry weiterhin ungültig: ${secondProblems.joinToString("; ")}")
    }

    private fun problemsOf(draft: RecipeDraft): List<String> {
        val problems = mutableListOf<String>()
        val slug = Slug.fromName(draft.name)
        if (slug.isEmpty()) problems.add("Name '${draft.name}' ergibt keinen gültigen Slug")
        // Probe-Rendering mit Probe-Slug — prüft Schema-Konformität des kompletten Outputs
        val probeId = slug.ifEmpty { "probe" }
        problems.addAll(validator.validateMarkdown(writer.render(probeId, draft)))
        if (slug.isEmpty()) return problems  // probe-Render war nur für Zusatzdiagnose
        return problems
    }
}
```

- [ ] **Step 3: Pipeline-Tests grün**

Run: `cd android; .\gradlew test` → Expected: PASS

- [ ] **Step 4: OCR + ShareActivity + Preview implementieren**

`OcrTextExtractor.kt`:

```kotlin
package de.dml.rezeptimporter.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

class OcrTextExtractor(private val context: Context) {
    suspend fun extract(uris: List<Uri>): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return uris.joinToString("\n\n") { uri ->
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image).await().text
        }.trim()
    }
}
```

`PreviewScreen.kt`:

```kotlin
package de.dml.rezeptimporter.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dml.rezeptimporter.domain.IngredientDraft
import de.dml.rezeptimporter.domain.RecipeDraft

@Composable
fun PreviewScreen(
    initial: RecipeDraft,
    onSave: (RecipeDraft) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bewertung:")
                listOf("favorit", "ok", "selten").forEach { r ->
                    Spacer(Modifier.width(4.dp))
                    FilterChip(
                        selected = draft.rating == r,
                        onClick = { draft = draft.copy(rating = r) },
                        label = { Text(r) },
                    )
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draft.simple, { draft = draft.copy(simple = it) }); Text("einfach")
                Spacer(Modifier.width(16.dp))
                Checkbox(draft.reheatable, { draft = draft.copy(reheatable = it) }); Text("aufwärmbar")
            }
        }
        item { Text("Zutaten", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(draft.ingredients) { i, ing ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = ing.amount ?: "",
                    onValueChange = { v ->
                        draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                            .also { it[i] = ing.copy(amount = v.ifEmpty { null }) })
                    },
                    label = { Text("Menge") }, modifier = Modifier.width(90.dp),
                )
                OutlinedTextField(
                    value = ing.unit ?: "",
                    onValueChange = { v ->
                        draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                            .also { it[i] = ing.copy(unit = v.ifEmpty { null }) })
                    },
                    label = { Text("Einh.") }, modifier = Modifier.width(80.dp),
                )
                OutlinedTextField(
                    value = ing.name,
                    onValueChange = { v ->
                        draft = draft.copy(ingredients = draft.ingredients.toMutableList()
                            .also { it[i] = ing.copy(name = v) })
                    },
                    label = { Text("Zutat") }, modifier = Modifier.weight(1f),
                )
            }
        }
        item { Text("Zubereitung (${draft.steps.size} Schritte)", style = MaterialTheme.typography.titleMedium) }
        items(draft.steps) { step -> Text("• $step", style = MaterialTheme.typography.bodySmall) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(draft) }, enabled = draft.name.isNotBlank()) {
                    Text("In Vault speichern")
                }
                OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            }
        }
    }
}
```

`ShareActivity.kt`:

```kotlin
package de.dml.rezeptimporter.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import de.dml.rezeptimporter.domain.RecipeDraft
import de.dml.rezeptimporter.llm.GeminiExtractor
import de.dml.rezeptimporter.llm.HaikuExtractor
import de.dml.rezeptimporter.ocr.OcrTextExtractor
import de.dml.rezeptimporter.pipeline.ImportPipeline
import de.dml.rezeptimporter.settings.AppSettings
import de.dml.rezeptimporter.settings.Provider
import de.dml.rezeptimporter.validate.RecipeValidator
import de.dml.rezeptimporter.vault.SafVaultStorage
import de.dml.rezeptimporter.vault.VaultWriter
import de.dml.rezeptimporter.yaml.RecipeMarkdownWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        validator = RecipeValidator(
            assets.open("recipe-vault-frontmatter.schema.json").readBytes().toString(Charsets.UTF_8)
        )

        setContent {
            MaterialTheme {
                when (val s = state.value) {
                    is ImportState.Working -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Rezept wird extrahiert …")
                        }
                    }
                    is ImportState.Preview -> PreviewScreen(
                        initial = s.draft,
                        onSave = ::save,
                        onCancel = { finish() },
                    )
                    is ImportState.Error -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                               modifier = Modifier.padding(24.dp)) {
                            Text(s.message)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { finish() }) { Text("Schließen") }
                        }
                    }
                }
            }
        }

        runImport()
    }

    private fun buildExtractor() = when (settings.provider) {
        Provider.GEMINI -> {
            if (settings.geminiKey.isBlank())
                throw IllegalStateException("Kein Gemini-API-Key — in der App unter Settings eintragen")
            GeminiExtractor(settings.geminiKey, OkHttpClient())
        }
        Provider.HAIKU -> {
            if (settings.anthropicKey.isBlank())
                throw IllegalStateException("Kein Anthropic-API-Key — in der App unter Settings eintragen")
            HaikuExtractor(settings.anthropicKey, OkHttpClient())
        }
    }

    private fun runImport() {
        lifecycleScope.launch {
            try {
                if (settings.vaultUri == null) {
                    state.value = ImportState.Error("Kein Vault-Ordner gewählt — erst App öffnen und Ordner wählen.")
                    return@launch
                }
                val rawText = withContext(Dispatchers.Default) { collectSourceText() }
                if (rawText.isBlank()) {
                    state.value = ImportState.Error("Kein Text gefunden (OCR leer?). Tipp: Screenshot mit gut lesbarem Text teilen.")
                    return@launch
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
                finish()
            } catch (e: Exception) {
                state.value = ImportState.Error("Speichern fehlgeschlagen: ${e.message}")
            }
        }
    }
}
```

- [ ] **Step 5: Manifest erweitern** — innerhalb `<application>` ergänzen:

```xml
<activity
    android:name=".ui.ShareActivity"
    android:exported="true"
    android:excludeFromRecents="true">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="text/plain" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <action android:name="android.intent.action.SEND_MULTIPLE" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="image/*" />
    </intent-filter>
</activity>
```

- [ ] **Step 6: Build + alle Tests**

Run: `cd android; .\gradlew test assembleDebug`
Expected: alle Tests PASS, `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```powershell
git add android/app/src
git commit -m "feat: wire share intents through OCR, LLM pipeline, preview, and vault write"
```

---

### Task 13: End-to-End-Verifikation (manuell, gegen Vertrags-Gate)

**Files:** keine neuen — Checkliste.

- [ ] **Step 1: APK aufs Gerät**

Gerät per USB, Entwickleroptionen + USB-Debugging an. Dann:

Run: `cd android; .\gradlew installDebug`
Expected: `Installed on 1 device`

- [ ] **Step 2: Setup auf dem Gerät**

App öffnen → Vault-Ordner wählen (Obsidian-Vault-Unterordner, z.B. `Rezepte/`) → Provider wählen → API-Key eintragen (Gemini-Key: kostenlos via aistudio.google.com).

- [ ] **Step 3: Text-Pfad testen**

Beliebige Rezept-Caption in einer Notiz-App markieren → Teilen → Rezept-Importer → Vorschau prüfen → Speichern → Toast mit Dateiname.

- [ ] **Step 4: Foto-Pfad testen**

Foto einer Rezeptseite (Kochbuch/Screenshot) → Teilen → Rezept-Importer → Vorschau → Speichern.

- [ ] **Step 5: Vertrags-Gate**

Erzeugte `.md` vom Gerät auf den PC kopieren (oder via Sync), dann:

Run: `cd validator; npm run validate -- pfad\zur\datei.md`
Expected: `OK   ... (id: ...)`, Exit 0

- [ ] **Step 6: Kollisionstest**

Gleiches Rezept ein zweites Mal importieren → zweite Datei muss `id: <slug>-2` tragen (Vorschau/Datei prüfen).

- [ ] **Step 7: Abschluss-Commit + Phase-1-Tag**

```powershell
git add -A
git commit -m "docs: record phase 1 E2E verification" --allow-empty
git tag phase-1-complete
```

---

## Self-Review-Notizen (beim Schreiben geprüft)

- Spec-Abdeckung Phase 1: Share-Target Text+Bild ✓ (Task 12), OCR ✓ (12), beide Provider ✓ (8, 9), 1 Call + 1 Repair-Retry hart ✓ (12/Pipeline), Schema-Validierung vor Write ✓ (6, 10), YAML-Roundtrip ✓ (5, 6), Auto-Suffix-Kollision ✓ (10), SAF-Picker mit persistenten Rechten ✓ (11), Vorschau editierbar mit rating/simple/reheatable-Toggles ✓ (12), Validator-CLI als Gate ✓ (2, 13), Token-Caps (6000 Zeichen / 1500 Tokens) ✓ (7).
- Bewusst NICHT in Phase 1 (laut Spec spätere Phasen): URL/Caption-Fetch, Offline-Queue, Vision-Fallback, Duplikat-Dialog, Release-Signierung.
- Typ-Konsistenz: `RecipeDraft`/`IngredientDraft` (Task 4) überall identisch verwendet; `validateMarkdown` einheitlicher Name (Tasks 6, 10, 12); `extractValidated` (12); `WriteResult(id, fileName)` (10, 12).
- Bekannte Unsicherheiten, im jeweiligen Task markiert: ajv-2020-Importform (Task 2 Step 7 nennt beide Varianten); exakte Versionsnummern (AGP/Kotlin/Compose-BOM) können beim ersten Sync minimal angepasst werden müssen — Tests sind die Wahrheit, nicht die Versionspins.
