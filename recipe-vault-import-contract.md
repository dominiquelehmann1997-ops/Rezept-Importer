# Rezept-Vault Import-Contract

Vertrag, den ein fertig exportiertes Rezept-Markdown erfüllen muss, damit der
Haushalts-Dashboard-Ingest (`recipeIngest.ts` → `recipeVault.ts`) es korrekt
einliest. Quelle der Wahrheit ist der Parser-Code, nicht das Template — diese
Datei spiegelt exakt `parseRecipeMarkdown` (Stand `main` @ `919ce67`).

> **Vault = Wahrheit, DB = Cache.** Der Importer schreibt `.md`-Dateien in einen
> Obsidian-Vault-Ordner. Das Dashboard liest diesen Ordner per Knopfdruck ein
> (`ingestVault`) und spiegelt ihn in SQLite. Der Importer redet **nie** mit der
> DB — er erzeugt nur Dateien.

---

## 1. Datei-Regeln (Ingest-Ordnerscan)

- Datei muss auf `.md` enden (case-insensitive).
- **Dateien, die mit `_` beginnen, werden übersprungen** (`_template.md` etc.).
- Ein Rezept = eine `.md`-Datei.
- Encoding: **UTF-8** (Umlaute ä/ö/ü/ß ohne Escaping erlaubt).
- Frontmatter ist ein YAML-Block zwischen `---`-Zeilen **ganz am Dateianfang**.
  Geparst mit `gray-matter` (js-yaml).

## 2. Frontmatter-Felder, die der App tatsächlich etwas bedeuten

Nur diese Felder werden vom Ingest gelesen. Alles andere ist für die App
unsichtbar (siehe Abschnitt 4).

| Feld | Typ | Pflicht | Default bei Fehlen/ungültig | Wirkung |
|---|---|---|---|---|
| `name` | string | **JA** | — (Datei wird komplett verworfen + Fehler) | Anzeigename. Wird getrimmt. Leer/fehlend ⇒ ganzes Rezept abgelehnt. |
| `id` | string | empfohlen | Slug aus Dateiname abgeleitet | **Stabiler eindeutiger Anker** (Upsert-Key). Getrimmt. Siehe Abschnitt 3. |
| `rating` | enum `favorit`\|`ok`\|`selten` | nein | `ok` | Gewichtung in der Essensplan-Auswahl. Ungültiger Wert ⇒ `ok`. |
| `simple` | boolean | nein | `true` | „Einfaches" Gericht (für Spät-Dienst-Tage). Nicht-Boolean ⇒ `true`. |
| `reheatable` | boolean | nein | `false` | Aufwärmbar (für Vorkoch-Logik). Nicht-Boolean ⇒ `false`. |
| `tags` | string-Array | nein | `null` | Wird als JSON-String gespeichert. Nicht-Array ⇒ `null`. |
| `ingredients` | Array von Objekten | nein* | `[]` | Zutatenliste. Siehe unten. (*ohne Zutaten kein Einkaufs-/Frisch-Nutzen.) |

### `ingredients[]` — pro Zutat

| Schlüssel | Typ | Pflicht | Default | Wirkung |
|---|---|---|---|---|
| `name` | string | **JA** | — (diese Zutat wird übersprungen + Fehler, Rezept bleibt) | Wird getrimmt. Leer/fehlend ⇒ nur diese Zeile fällt raus. |
| `amount` | string oder number | nein | `null` | Wird per `String()` zu Text gezwungen (`500` → `"500"`). |
| `unit` | string oder number | nein | `null` | Wie `amount`. |
| `freshness` | enum `frisch`\|`haltbar` | nein | `null` | **Explizite Haltbarkeits-Angabe** (schlägt Heuristik + gelernte Korrektur). Jeder andere Wert ⇒ `null` ⇒ App rät später per Keyword-Heuristik. |

## 3. Der `slug` / `id` — das Wichtigste für den Importer

Der Upsert-Key ist der **Slug**:

```
slug = frontmatter.id (getrimmt)  ODER (falls leer/fehlend)  slugFromFilename(dateiname)
```

`slugFromFilename`: `.md` weg → lowercase → alle Nicht-`[a-z0-9]` zu `-` →
führende/abschließende `-` weg. Beispiel: `Pasta al Pomodoro.md` → `pasta-al-pomodoro`.

Konsequenzen, die der Importer **zwingend** beachten muss:

- **Immer ein explizites `id` setzen** und es **nie wieder ändern.** Der Slug ist
  identitätsstiftend. Ändert sich der Slug (umbenannte Datei *ohne* `id`, oder
  geändertes `id`), entsteht beim nächsten Ingest ein **neues** Rezept und das
  alte wird `archived=true` (Soft-Delete) — Plan-Historie zeigt dann ins Leere.
- **Slug muss eindeutig im Vault sein.** Zwei Dateien mit gleichem Slug ⇒ die
  zweite überschreibt beim Upsert die erste (last-write-wins, stillschweigend).
- Slug-Empfehlung: kebab-case, nur `[a-z0-9-]`, deterministisch aus dem
  Rezeptnamen erzeugt **und einmal eingefroren** (z.B. beim ersten Export
  speichern/mitschreiben).

## 4. Felder, die NICHT eingelesen werden (aber erlaubt sind)

Das Template (`docs/recipe-vault-template.md`) zeigt zur Lesbarkeit zusätzliche
Felder. **Der Ingest ignoriert sie vollständig** — sie leben nur für Menschen in
Obsidian:

- Frontmatter: `servings`, `prepMinutes`, `cookMinutes`, `nutrition` (kcal/protein/…)
  → derzeit **kein** App-Effekt.
- **Der gesamte Markdown-Body** (`## Zubereitung`, Schritte, Fließtext, Bilder)
  → wird geparst aber verworfen. Die App speichert **keine** Kochanleitung.

Heißt für den Importer: Zubereitungsschritte/Nährwerte dürfen rein (gut für
Obsidian), ändern aber nichts in der App. Wer „importer-minimal" baut, braucht nur
die Felder aus Abschnitt 2.

## 5. Ingest-Verhalten (damit der Importer keine Überraschungen baut)

- **Upsert nach Slug**: existierendes Rezept wird aktualisiert, sonst neu angelegt.
- **Zutaten werden bei jedem Ingest komplett ersetzt** (delete-all + recreate).
  Teil-Updates gibt es nicht — die Datei ist immer die volle Wahrheit.
- **Orphan-Archivierung**: Vault-Rezepte (Slug bekannt), deren Slug beim Ingest
  nicht mehr auftaucht, werden `archived=true`. Gelöscht wird nichts.
  Sonderfall: ein **leerer** Vault archiviert NICHT (Schutz vor Total-Archivierung).
- Nicht-Vault-Rezepte (Slug `null`, z.B. Seed-Daten) bleiben unberührt.
- Parse-Fehler sind **nicht** fatal: fehlt `name`, fällt nur diese Datei weg;
  eine kaputte Zutat überspringt nur die Zeile. Alles landet im `errors[]`-Report.

## 6. YAML-Fallstricke für den generierenden Code

- Frontmatter muss **gültiges YAML** sein. Bei ungültigem YAML wirft `gray-matter`.
- Inline-Flow-Mapping für Zutaten ist gültig:
  `- { name: Nudeln, amount: 500, unit: g, freshness: haltbar }`
  Ebenso gültig die Block-Form:
  ```yaml
  ingredients:
    - name: Nudeln
      amount: 500
      unit: g
      freshness: haltbar
  ```
- **Werte mit Sonderzeichen quoten.** Enthält ein String `:` (gefolgt von Space),
  `#`, führendes `[`/`{`/`*`/`&`/`!`/`@`, oder beginnt mit Zahl-aber-soll-Text →
  in `"…"` setzen. Beispiel Mengen: `amount: "1/2"`, `amount: "2-3"`, `name: "Öl, kaltgepresst"`.
- Booleans als **`true`/`false`** schreiben (lowercase). (`yes/no` parst js-yaml
  zwar auch als Boolean, aber `true/false` ist eindeutig.)
- `tags` muss eine **YAML-Liste** sein: `tags: [schnell, vegetarisch]` oder Block-Liste.
- Zahlen dürfen Zahlen bleiben (`amount: 500`) — werden ohnehin zu String. Brüche/
  Bereiche aber als String quoten (s.o.), sonst macht YAML daraus evtl. Unsinn.

## 7. Minimal gültiges Rezept

```markdown
---
id: pasta-al-pomodoro
name: Pasta al Pomodoro
---
```

(genügt dem Parser: `name` da, `id` da. rating=ok, simple=true, reheatable=false,
keine Zutaten.)

## 8. Voll ausgestattetes Rezept (Referenz)

```markdown
---
id: gemuese-curry
name: Gemüse-Curry mit Kokosmilch
rating: favorit
simple: true
reheatable: true
tags: [vegetarisch, mealprep]
servings: 4
prepMinutes: 15
cookMinutes: 25
nutrition:
  kcal: 540
  protein: 18
ingredients:
  - { name: Kokosmilch, amount: 400, unit: ml, freshness: haltbar }
  - { name: Süßkartoffel, amount: 2, unit: Stk, freshness: frisch }
  - { name: Currypaste, amount: 2, unit: EL, freshness: haltbar }
  - { name: Reis, amount: 250, unit: g, freshness: haltbar }
---

## Zubereitung
1. Süßkartoffel würfeln, anbraten.
2. Currypaste + Kokosmilch dazu, köcheln.
3. Mit Reis servieren.
```

Vom Ingest übernommen: name, id→slug, rating=favorit, simple, reheatable, tags
(als JSON), 4 Zutaten mit amount/unit/freshness. Ignoriert: servings, prepMinutes,
cookMinutes, nutrition, der ganze Zubereitungs-Body.

---

## 9. Akzeptanz-Checkliste für den Importer

- [ ] Schreibt eine `.md`-Datei pro Rezept, UTF-8, Dateiname ohne führenden `_`.
- [ ] Setzt **immer** ein stabiles, eindeutiges `id` (kebab-case) und friert es ein.
- [ ] `name` ist nie leer.
- [ ] `rating` ∈ {favorit, ok, selten} oder weggelassen.
- [ ] `simple`/`reheatable` als echte Booleans oder weggelassen.
- [ ] `tags` als YAML-Liste oder weggelassen.
- [ ] Jede Zutat hat ein nicht-leeres `name`; `freshness` ∈ {frisch, haltbar} oder weg.
- [ ] Mengen/Strings mit Sonderzeichen sind gequotet → gültiges YAML.
- [ ] (Optional) Zubereitung als Body — rein kosmetisch für Obsidian.
