# ObsidiDine UI-Redesign — Design-Spec

Datum: 2026-06-12
Status: vom Nutzer freigegeben

## Ziel

Die Rezept-Importer-App wird zu **ObsidiDine** umbenannt und bekommt das
"Arcane Terminal"-Designsystem (Mana & Code, siehe Nutzer-DESIGN.md).
API-Keys und Vault-Pfad wandern vom Hauptscreen in einen eigenen
Settings-Screen. Keine Änderungen an Import-Pipeline, Storage oder Tests.

## Entscheidungen (vom Nutzer bestätigt)

| Frage | Entscheidung |
|---|---|
| Settings-Zugang | Zahnrad-Icon oben rechts → eigener Settings-Screen |
| Theme-Modus | System folgen: Parchment (light) / Dungeon (dark) |
| API-Key-Felder | Maskiert (PasswordVisualTransformation) mit Auge-Toggle |

## 1. Branding

- `strings.xml`: `app_name` → `ObsidiDine`.
- Launcher-Icon aus `App-Symbol_ObsidiDine.jpeg` (Obsidian-Topf, lila Besteck):
  - Innere Icon-Kachel quadratisch ausschneiden.
  - Adaptive Icon: `ic_launcher_foreground` (Kachel in Safe Zone skaliert)
    + Background-Farbe `#0D0814` (Deep Void Purple).
  - Legacy-Mipmaps mdpi–xxxhdpi.
  - Manifest: `android:icon="@mipmap/ic_launcher"`.

## 2. Theme — neues Paket `ui/theme`

### Farben (DESIGN.md → Material3 ColorScheme)

| Rolle | Parchment (light) | Dungeon (dark) |
|---|---|---|
| background | #FBF9F5 | #0D0814 |
| surface | #FFFFFF | #150E22 |
| onBackground/onSurface | #180F25 | #EDE9FE |
| onSurfaceVariant (secondary text) | #5D546D | #9CA3AF |
| outline (border) | #E5DDC8 | #312144 |
| primary (Mana Violet) | #8B5CF6 | #A78BFA |
| secondary (Rupee Green) | #10B981 | #34D399 |
| tertiary (Legendary Gold) | #F59E0B | #F59E0B |
| onPrimary | #FFFFFF | #FFFFFF |

### Typografie

- **Space Mono** (400, 700) — Headings, Titel, Mono-Tags. Letter-Spacing −0.02em.
- **Plus Jakarta Sans** (400, 600) — Body, Labels, Buttons.
- TTFs gebündelt in `res/font` (kein Downloadable-Fonts-Provider — offline-sicher,
  kein Play-Services-Zwang).

### Komponenten

- **ArcaneCard**: Composable. 1dp Border in `outline`, Hintergrund `surface`,
  harter Offset-Schatten (3dp x/y, Farbe `outline`, kein Blur), Radius 4dp,
  Innenabstand 16dp.
- **Buttons**: Radius 4dp. Primär: gefüllt `primary`, Text weiß.
  Sekundär: transparent, 1dp Border `outline`, Text `onSurface`. Keine Pills.
- **Inputs**: flache 1dp-Border, Hintergrund = Seitenhintergrund,
  Fokus-Ring `primary`.
- **Mono-Tags**: kleine Space-Mono-Labels neben Headings, z. B. `[FOTOS: 2]`.
- Spacing-Raster: 8/16/24/32 dp.

## 3. MainActivity

- Header: App-Titel in Space Mono + Zahnrad-IconButton rechts.
- Zwei Screens via Compose-State (`Home` / `Settings`), keine Nav-Library.
- **Home**:
  - Warn-Karte (Legendary Gold) wenn Vault-Ordner ODER API-Key des gewählten
    Providers fehlt — mit Button "Zu den Einstellungen".
  - Karte "Rezept fotografieren": Foto-Workflow wie bisher
    (aufnehmen / weiteres Foto / Rezept erstellen / verwerfen),
    Foto-Zähler als Mono-Tag.
  - Karte "Teilen-Import": Hinweistext (Foto/Text/Link mit App teilen).
- **Settings** (Zurück-Pfeil im Header):
  - Karte Vault-Ordner: Pfad-Anzeige + "Ordner wählen".
  - Karte LLM-Provider: Radio Gemini / Haiku.
  - Karte API-Keys: zwei maskierte Felder mit Auge-Toggle.
- Verhalten (Persistenz in `AppSettings`, SAF-Picker, Foto-Intents) unverändert.

## 4. ShareActivity + PreviewScreen

- Beide in `ArcaneTheme` gewrappt.
- Working-State: zentrierte Karte mit Progress + Text.
- Error-State: Karte mit Fehlertext + Schließen-Button.
- PreviewScreen: Sektionen als ArcaneCards — Meta (Name, Bewertung, Flags),
  Zutaten, Nährwerte, Zubereitung. Speichern = Primär-Button,
  Abbrechen = Sekundär-Button. Editier-Logik unverändert.

## 5. Nicht-Ziele

- Keine Änderung an Pipeline, Extraktoren, Vault-Schreiblogik, Schema.
- Keine neuen Abhängigkeiten außer Font-Ressourcen.
- Kein In-App-Theme-Umschalter (folgt System).

## Verifikation

- `gradlew test` grün (bestehende Unit-Tests unberührt).
- `gradlew assembleDebug` baut.
- APK nach `G:\Meine Ablage\AI-Stuff\Rezept-Importer` kopieren.
- Manuelle Sichtprüfung durch Nutzer auf Gerät.
