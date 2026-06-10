# Design: Rezept-Importer (Android) + Obsidian-Kochansicht

Datum: 2026-06-10 · Status: vom Nutzer freigegeben (abschnittsweise)

## Ziel

Zwei Komponenten:

1. **Importer-App (Android, Kotlin, als APK sideloadbar):** Beliebige Quellen
   (geteilter Text/Link, Fotos/Screenshots, Instagram-/TikTok-URLs) über das
   Android-Teilen-Menü empfangen, per On-Device-Vorverarbeitung + genau einem
   LLM-Call in ein strukturiertes Rezept wandeln und als contract-konforme
   `.md`-Datei in den Obsidian-Vault-Ordner auf dem Telefon schreiben.
2. **Obsidian-Plugin „Kochansicht" (TypeScript, mobile-first):** Rezept-Notizen
   in einer Ansicht mit zwei unabhängig scrollbaren Panes (Zutaten ⟷
   Zubereitung) rendern, mit Portions-Stepper, der alle Zutatenmengen live
   gegen `servings` skaliert.

Verbindlicher Ausgabe-Vertrag: `recipe-vault-import-contract.md` und
`recipe-vault-schema.md` (TS-Interface `RecipeFrontmatter` + JSON-Schema
Draft 2020-12) im Projekt-Root. `id` ist Identitäts-Anker und wird nach dem
ersten Schreiben nie geändert.

## Entscheidungen (geklärt im Brainstorming)

| Frage | Entscheidung |
|---|---|
| Tech-Stack App | **Kotlin nativ** (bester Share-Intent- und SAF-Support, ML Kit nativ, kleine APK). Flutter/React Native verworfen: SAF + Share-Target dort nur über fragile Plugins. |
| LLM-Provider | **Beide, umschaltbar:** Gemini Flash (Free Tier, JSON-Mode mit `responseSchema`) und Anthropic Haiku (Tool-Use). Interface `LlmExtractor`, Wahl in Settings. |
| Reel/TikTok-Tiefe | **Caption + On-Screen-Text:** HTTP-Fetch (oEmbed / `og:description`). Kein Video-Download, kein Audio-Transkript. Fallback bei Misserfolg: Screenshot teilen → OCR-Pfad. |
| Kochansicht-Gerät | **Obsidian Mobile (Handy/Tablet):** Plugin mobile-kompatibel (`isDesktopOnly: false`, keine Node/Electron-APIs), Panes gestapelt mit getrennten Scroll-Containern. |
| Token-Sparzwang | First-Class-Constraint: On-Device-OCR (ML Kit, 0 Tokens), max. 1 LLM-Call pro Import (+ max. 1 Repair-Retry), Input-/Output-Caps, kein automatischer Vision-Call. |

## Architektur Importer-App

Single-Activity + Worker, Module:

```
share-entry   ShareActivity: empfängt ACTION_SEND / ACTION_SEND_MULTIPLE
              (text/plain, image/*), Mini-UI, übergibt an Pipeline
extract       SourceExtractor: Quelle → Rohtext (alles on-device, 0 Tokens)
              ├─ TextSource:  geteilter Text/URL direkt
              ├─ ImageSource: ML Kit OCR, mehrere Bilder konkateniert
              └─ UrlSource:   HTTP-Fetch → oEmbed/og:description (Phase 2)
llm           LlmExtractor-Interface: Rohtext → RecipeFrontmatter-JSON
              ├─ GeminiExtractor (JSON-Mode, responseSchema)
              └─ HaikuExtractor (Tool-Use, ein Tool = Schema)
validate      RecipeValidator: networknt/json-schema-validator gegen das
              gebündelte JSON-Schema + Slug-Generierung + Kollisionscheck
write         VaultWriter: SAF (DocumentFile), YAML-Serialisierung per
              Library (kein Hand-YAML), UTF-8, Dateiname ohne führenden _
settings      Vault-Ordner-Picker (ACTION_OPEN_DOCUMENT_TREE +
              takePersistableUriPermission), Provider-Wahl, API-Keys in
              EncryptedSharedPreferences
```

Fluss pro Import:

```
Teilen → ShareActivity → SourceExtractor → 1 LLM-Call → JSON
       → RecipeValidator → Vorschau-Screen (editierbar) → VaultWriter → Toast
```

Der Vorschau-Screen zeigt Name, Zutaten, Toggles und erlaubt Korrektur vor dem
Write — verhindert LLM-Müll im Vault, kostet bei gutem Ergebnis einen Tap.

### Slug / `id`

- `id` wird deterministisch app-seitig aus `name` erzeugt (kebab-case,
  `[a-z0-9-]`), nie vom LLM.
- Kollisionscheck vor dem Write: Frontmatter-Scan der `.md` im Vault-Ordner.
  Bei Treffer Dialog: überschreiben (Update, gleicher Slug) oder Suffix `-2`
  (neues Rezept). Kein stilles last-write-wins.
- Einmal geschriebenes `id` wird nie geändert (Contract Abschnitt 3).

## LLM-Stufe & Token-Budget

- System-Prompt kurz (~200 Tokens), deutsch: Rezept als JSON nach Schema,
  Mengen als Zahl + separate Einheit, Brüche/Bereiche als String, Unbekanntes
  weglassen.
- Struktur erzwungen über Gemini `responseSchema` bzw. Haiku Tool-Use — kein
  Freitext-Parsen.
- Input-Kappung ~6000 Zeichen (Hashtag-/OCR-Rauschen am Ende zuerst kürzen),
  Output-Cap ~1500 Tokens.
- LLM füllt nur: `name`, `tags`, `ingredients[]`, `servings`,
  Zubereitungs-Body, optional `prepMinutes`/`cookMinutes`. Nicht
  `rating`/`simple`/`reheatable` (Defaults laut Contract, im Vorschau-Screen
  togglebar). Nicht `id`.
- Kein Vision-Call in Phase 1–2; Bilder gehen immer durch ML Kit OCR. Vision
  nur als expliziter manueller Fallback-Knopf (Phase 4), nie automatisch.
- Harte Obergrenze: max. 2 LLM-Calls pro Import (1 Extraktion + 1
  Repair-Retry bei Schema-Fehler).

## Fehlerbehandlung

| Fehler | Verhalten |
|---|---|
| OCR leer / Caption-Fetch scheitert | Meldung + Tipp (Screenshot statt Link), kein LLM-Call |
| LLM-Antwort schlägt Validierung fehl | Ein Repair-Retry mit Fehlerliste im Prompt, dann Abbruch mit Meldung |
| Kein Netz | Import als Entwurf lokal gequeued (Rohtext), später manuell auslösbar |
| Vault-URI-Rechte verloren | Erkennen → Settings → Ordner neu wählen |
| YAML-Sonderzeichen | YAML-Library quotet automatisch |

Doppelte Absicherung vor jedem Write: (1) JSON gegen Schema (networknt),
(2) emittierte Datei einmal YAML-roundtrip-parsen. Nur valide Dateien
erreichen den Vault.

## Obsidian-Plugin „Kochansicht"

- TS, esbuild, `isDesktopOnly: false`, keine Node-APIs.
- Einstieg: Button in der Notiz-Titelzeile + Command „Kochansicht öffnen".
  Rezept-Erkennung: `ingredients` im Frontmatter via `metadataCache` (kein
  eigener YAML-Parser).
- Eigener View-Typ (`ItemView`):

```
┌─────────────────────────┐
│ Name        [− 4 +]     │  Header: Rezeptname + Portions-Stepper
├─────────────────────────┤
│ ZUTATEN     (scrollbar) │  Pane 1: eigener Scroll-Container, einklappbar
├─────────────────────────┤
│ ZUBEREITUNG (scrollbar) │  Pane 2: Markdown-Body via MarkdownRenderer,
└─────────────────────────┘  eigener Scroll-Container
```

  Mobile gestapelt, Trennlinie draggbar, beide Panes `overflow-y: auto`.

### Portions-Skalierung (nur in der View, schreibt nie in die Datei)

- Basis = `servings` aus Frontmatter; fehlt es → Stepper deaktiviert mit
  Hinweis.
- Faktor = gewählte Portionen / Basis (Stepper ±1, Minimum 1).
- `amount` pro Typ:
  - Zahl → multiplizieren, Rundung auf ≤1 Dezimale, glatte Werte bevorzugt
  - Bruch-String (`"1/2"`, `"1 1/2"`) → Dezimal → skalieren → als Bruch im
    ¼-Raster zurückformatieren, sonst Dezimal
  - Bereich (`"2-3"`) → beide Enden skalieren → `"4-6"`
  - Nicht parsebar (`"etwas"`, `null`) → unverändert anzeigen, kein Fehler
- Wake-Lock (Display an) als Nice-to-have in Phase 4.

## Repo-Struktur (Monorepo, dieses Verzeichnis)

```
android/                  Kotlin-App (Gradle)
obsidian-plugin/          TS-Plugin (esbuild)
shared/                   recipe-vault-frontmatter.schema.json (eine Quelle)
validator/                Vendorter Parser-Nachbau (gray-matter) + ajv-Check
                          als CLI: npm run validate -- datei.md
docs/superpowers/specs/   Design-Docs
```

## Phasen

| Phase | Inhalt | Fertig wenn |
|---|---|---|
| 1 | Share-Target (Text + Bild) → OCR → 1 LLM-Call (beide Provider) → Vorschau → SAF-Write | Geteiltes Foto landet als contract-valide `.md` im gewählten Ordner; Validator-CLI grün |
| 2 | URL-Quelle (oEmbed/og:description für Instagram/TikTok), Offline-Queue | Geteilter Reel-Link → `.md`; Fetch-Fehler mit sauberer Meldung |
| 3 | Obsidian-Plugin: Kochansicht + Skalierung | Auf Obsidian Mobile: zwei Scroll-Panes, Stepper skaliert korrekt inkl. Brüche/Bereiche |
| 4 | Politur: Duplikat-Dialog, Vision-Fallback-Knopf, Wake-Lock, APK-Signierung, Installation | Signierte Release-APK auf dem Gerät, End-to-End ab Instagram |

## Testing

- **Kotlin:** Unit-Tests für Slug-Generierung, YAML-Emission,
  Validator-Anbindung. Fake-LlmExtractor mit kanonischen JSON-Antworten →
  Pipeline-Tests ohne Token-Kosten.
- **Validator-CLI** als Vertrags-Gate: jede emittierte Test-`.md` durchläuft
  den vendorten Parser + ajv.
- **Plugin:** Unit-Tests für Skalierungs-Mathematik (Brüche, Bereiche,
  Rundung); manueller Test auf Obsidian Mobile.
- **LLM-Prompt:** 3–4 echte Captions/OCR-Texte einmalig gegen beide Provider,
  Ergebnisse als Fixtures eingefroren (keine wiederholten Live-Calls in CI).

## Verteilung

- APK: `assembleRelease`, selbst signiert (eigener Keystore), Sideload.
- Plugin: manuell nach `<vault>/.obsidian/plugins/`, später optional BRAT.
