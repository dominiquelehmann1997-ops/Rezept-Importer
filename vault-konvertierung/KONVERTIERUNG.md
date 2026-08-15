# Vault-Rezepte → Dashboard-Format konvertieren

Self-contained Auftragsdatei. Lege sie ins neue Projekt, das Zugriff auf den
Obsidian-Vault hat. Ein Agent liest **alle** bestehenden Rezepte und schreibt sie
ins unten definierte Zielformat um — **in-place** im Vault, mit Backup.

Quelle der Wahrheit für das Zielformat ist der Parser des Haushalts-Dashboards
(`recipeIngest.ts` → `recipeVault.ts`). Diese Datei spiegelt ihn 1:1.

---

## 0. Was du tust (Big Picture)

1. **Backup** des Vault-Rezeptordners anlegen, bevor du irgendwas änderst.
2. Jede bestehende Rezept-`.md` lesen (egal in welchem Alt-Format).
3. Inhalt auf das **Zielschema** unten mappen (name, id, rating, simple,
   reheatable, tags, ingredients[]).
4. Datei mit korrektem YAML-Frontmatter neu schreiben; Kochanleitung als Body
   erhalten (Obsidian-Lesbarkeit, App ignoriert sie).
5. Jede emittierte Datei gegen die **Akzeptanz-Checkliste** (§7) prüfen.
6. **Report** schreiben: was konvertiert, was geraten, was übersprungen.

> **Vault = Wahrheit, DB = Cache.** Du erzeugst nur `.md`-Dateien. Keine DB.
> Das Dashboard liest den Ordner später per Knopfdruck ein.

---

## 1. Datei-Regeln (so liest der Ingest den Ordner)

- Datei muss auf `.md` enden (case-insensitive).
- **Dateien mit führendem `_` werden übersprungen** (`_template.md`) — nicht anfassen.
- Ein Rezept = eine `.md`.
- Encoding **UTF-8** (ä/ö/ü/ß roh, kein Escaping).
- Frontmatter = YAML-Block zwischen `---`-Zeilen **ganz am Dateianfang**.

## 2. Zielschema — Frontmatter-Felder (nur diese liest die App)

| Feld | Typ | Pflicht | Default bei Fehlen/ungültig | Wirkung |
|---|---|---|---|---|
| `name` | string | **JA** | — (Datei verworfen) | Anzeigename, getrimmt. Leer ⇒ Rezept abgelehnt. |
| `id` | string | **immer setzen** | Slug aus Dateiname | Stabiler Upsert-Key (kebab-case). Siehe §3. |
| `rating` | `favorit`\|`ok`\|`selten` | nein | `ok` | Gewicht in Essensplan-Auswahl. |
| `simple` | boolean | nein | `true` | „Einfaches" Gericht (Spät-Dienst-Tage). |
| `reheatable` | boolean | nein | `false` | Aufwärmbar (Vorkoch-Logik). |
| `tags` | string-Array | nein | `null` | YAML-Liste. Als JSON-String gespeichert. |
| `ingredients` | Array von Objekten | nein | `[]` | Zutatenliste. Siehe unten. |

### `ingredients[]` — pro Zutat

| Schlüssel | Typ | Pflicht | Default | Wirkung |
|---|---|---|---|---|
| `name` | string | **JA** | — (nur diese Zeile fällt raus) | getrimmt. |
| `amount` | string\|number | nein | `null` | per `String()` zu Text. Brüche/Bereiche als String. |
| `unit` | string\|number | nein | `null` | wie amount (`g`, `ml`, `Stk`, `EL`). |
| `freshness` | `frisch`\|`haltbar` | nein | `null` | Explizite Haltbarkeit. Anderer Wert ⇒ App rät per Heuristik. |

**Ignoriert (aber erlaubt, für Obsidian gut):** `description`, `servings`,
`prepMinutes`, `cookMinutes`, `nutrition`, pro Zutat `section` (Gruppe wie
„Für die Soße"), und der **ganze Markdown-Body**. Übernimm vorhandene solche
Daten ruhig — sie schaden nicht.

## 3. Der `id`/Slug — wichtigste Regel

```
slug = frontmatter.id (getrimmt)  ODER (fehlt)  slugFromFilename
slugFromFilename: ".md" weg → lowercase → Nicht-[a-z0-9] zu "-" → Rand-"-" weg
```

- **Setze IMMER ein explizites `id`** und friere es ein. Slug = Identität.
  Ändert sich der Slug später (Umbenennen ohne `id`, geändertes `id`), entsteht
  beim nächsten Ingest ein **neues** Rezept, altes wird `archived` — Plan-Historie
  zeigt ins Leere.
- Falls eine Alt-Datei schon ein `id` hat: **behalte es unverändert.**
- Sonst: leite `id` deterministisch aus dem Rezeptnamen ab (kebab-case, `[a-z0-9-]`),
  Umlaute transliterieren (ä→ae, ö→oe, ü→ue, ß→ss).
- **Slug muss vaultweit eindeutig sein.** Kollision ⇒ zweite überschreibt erste
  still (last-write-wins). Bei Dublette: Suffix `-2`, `-3` … und im Report melden.

## 4. Mapping Alt-Format → Zielformat (heuristisch, robust)

Bestehende Rezepte können beliebig aussehen (Fließtext, andere Frontmatter-Keys,
fremde Sprache, lose Listen). Vorgehen pro Datei:

- **name**: Titel-Frontmatter ODER erste `#`-Überschrift ODER Dateiname.
- **ingredients**: Zutaten-Abschnitt erkennen (`## Zutaten`/`Ingredients`/Liste vor
  der Anleitung). Pro Zeile `amount`/`unit`/`name` trennen
  (z.B. `500 g Mehl` → amount 500, unit g, name Mehl). Unsicher ⇒ alles in `name`,
  `amount`/`unit` `null` lassen. **Nichts erfinden.**
- **freshness**: nur setzen, wenn klar (frische Ware: Gemüse, Fleisch, Milch →
  `frisch`; Vorrat: Mehl, Nudeln, Dosen, Gewürze → `haltbar`). **Im Zweifel weglassen**
  (App-Heuristik übernimmt). Geratene freshness im Report markieren.
- **rating/simple/reheatable**: nur aus klaren Signalen; sonst **weglassen**
  (Defaults greifen). Nicht raten.
- **tags**: vorhandene Tags/Kategorien übernehmen; keine erfinden.
- **Body**: bestehende Zubereitung als Markdown-Body unter `## Zubereitung`
  erhalten. Reiner Fließtext-Rezepttext bleibt als Body stehen.

Leitprinzip: **Daten erhalten, nicht halluzinieren.** Lieber Feld weglassen
(sauberer Default) als falsch raten.

## 5. YAML-Fallstricke (sonst wirft `gray-matter`)

- Frontmatter muss **gültiges YAML** sein.
- Zutaten Inline-Flow: `- { name: Nudeln, amount: 500, unit: g, freshness: haltbar }`
  ODER Block-Form (beides gültig).
- **Sonderzeichen quoten**: String mit `:` +Space, `#`, führendem `[`/`{`/`*`/`&`/`!`/`@`,
  oder Zahl-die-Text-sein-soll → in `"…"`. Mengen: `amount: "1/2"`, `amount: "2-3"`,
  Namen: `name: "Öl, kaltgepresst"`.
- Booleans lowercase **`true`/`false`**.
- `tags` als YAML-Liste: `tags: [schnell, vegetarisch]`.
- Ganze Zahlen dürfen Zahl bleiben (`amount: 500`). Brüche/Bereiche als String quoten.

## 6. Referenz — voll ausgestattetes Zielrezept

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

Minimal gültig genügt auch:

```markdown
---
id: pasta-al-pomodoro
name: Pasta al Pomodoro
---
```

## 7. Akzeptanz-Checkliste (pro emittierte Datei)

- [ ] `.md`, UTF-8, Dateiname ohne führenden `_`.
- [ ] **Immer** stabiles, eindeutiges `id` (kebab-case), vorhandenes id unverändert.
- [ ] `name` nie leer.
- [ ] `rating` ∈ {favorit, ok, selten} oder weggelassen.
- [ ] `simple`/`reheatable` echte Booleans oder weggelassen.
- [ ] `tags` YAML-Liste oder weggelassen.
- [ ] Jede Zutat hat nicht-leeres `name`; `freshness` ∈ {frisch, haltbar} oder weg.
- [ ] Mengen/Strings mit Sonderzeichen gequotet → gültiges YAML.
- [ ] Bestehende Zubereitung als Body erhalten.

## 8. Sicherheit & Report

- **Backup zuerst.** Kopiere den Rezeptordner nach `_backup-vor-konvertierung/`
  (führender `_` ⇒ Ingest ignoriert ihn) ODER nutze git. Erst dann schreiben.
- **In-place** überschreiben nur nach bestandener Checkliste.
- Am Ende `KONVERTIERUNG-REPORT.md` schreiben:
  - Anzahl konvertiert / übersprungen / fehlerhaft.
  - Pro Rezept: vergebener Slug, geratene Felder (v.a. `freshness`), Dubletten.
  - Dateien ohne erkennbaren `name` (nicht konvertierbar) explizit auflisten.
- **Nichts löschen.** Alt-Dateien mit `_`-Präfix oder via Backup erhalten bleiben.

## 9. Optional: echte Validierung statt nur Checkliste

Für 1:1-Verhalten mit der App: Mains reinen Parser (`recipeVault.ts`, nur
`gray-matter`) als ~100-Zeilen-Validator vendoren, YAML mit `js-yaml` serialisieren
und gegen das JSON-Schema (`additionalProperties` offen, Zutaten strikt) per `ajv`
prüfen **vor** dem Schreiben. So fallen ungültige Mengen/Enums vorher auf.
