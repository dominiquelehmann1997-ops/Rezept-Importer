# Rezept-Vault Schema (TS-Interface + JSON-Schema)

Maschinenlesbare Form des `recipe-vault-import-contract.md`. Zwei Sichten:

- **`RecipeFrontmatter`** = was der Importer in den YAML-Frontmatter **schreibt**
  (inkl. Obsidian-Zusatzfelder wie `servings`, die das Dashboard ignoriert).
- **`ParsedRecipe`** = die **normalisierte** Form, die Mains Parser daraus erzeugt
  (Referenz; so „sieht" die App das Rezept nach Defaults/Coercion).

Pflichtfeld in beiden: `name`. Der Importer sollte zusätzlich immer ein stabiles
`id` setzen. Validiere die emittierte YAML gegen das **JSON-Schema** weiter unten,
bevor du die Datei schreibst.

---

## TypeScript

```ts
// ── Was der Importer schreibt (YAML-Frontmatter) ──────────────────────────────

export type Rating = "favorit" | "ok" | "selten";
export type Freshness = "frisch" | "haltbar";

export interface FrontmatterIngredient {
  /** Pflicht, nicht leer. Wird getrimmt. */
  name: string;
  /** Menge. Zahl ODER String. Brüche/Bereiche als String ("1/2", "2-3"). */
  amount?: string | number | null;
  /** Einheit, z.B. "g", "ml", "Stk", "EL". Zahl wird zu String gezwungen. */
  unit?: string | number | null;
  /** Explizite Haltbarkeit; alles andere ⇒ null ⇒ App rät per Heuristik. */
  freshness?: Freshness | null;
}

export interface RecipeFrontmatter {
  /** Stabiler eindeutiger Slug (kebab-case). Identitäts-Anker — NIE ändern.
   *  Fehlt er, leitet der Ingest den Slug aus dem Dateinamen ab. */
  id?: string;
  /** Pflicht, nicht leer. Fehlt er ⇒ ganze Datei verworfen. */
  name: string;
  /** Default "ok" bei Fehlen/ungültig. */
  rating?: Rating;
  /** Default true bei Fehlen/nicht-Boolean. */
  simple?: boolean;
  /** Default false bei Fehlen/nicht-Boolean. */
  reheatable?: boolean;
  /** YAML-Liste. Wird als JSON-String gespeichert. */
  tags?: string[];
  ingredients?: FrontmatterIngredient[];

  // ── Nur für die Obsidian-Kochansicht; vom Dashboard-Ingest IGNORIERT ──
  /** Basis-Portionen — Referenz fürs Skalieren der Mengen. */
  servings?: number;
  prepMinutes?: number;
  cookMinutes?: number;
  nutrition?: { kcal?: number; protein?: number; [k: string]: number | undefined };
}

// Hinweis: Die Kochanleitung steht NICHT im Frontmatter, sondern als
// Markdown-Body unter dem zweiten `---` (z.B. nummerierte Schritte unter
// "## Zubereitung"). Der Ingest verwirft den Body; die Obsidian-Kochansicht
// rendert ihn.

// ── Was Mains Parser daraus macht (normalisierte Referenz) ────────────────────

export interface ParsedIngredient {
  name: string;                 // getrimmt
  amount: string | null;        // per String() gezwungen
  unit: string | null;          // per String() gezwungen
  category: Freshness | null;   // aus `freshness`
}

export interface ParsedRecipe {
  id: string | null;            // Frontmatter `id` getrimmt, sonst null
  name: string;                 // getrimmt
  rating: Rating;               // Default "ok"
  simple: boolean;              // Default true
  reheatable: boolean;          // Default false
  tags: string | null;          // JSON-String des Arrays, sonst null
  ingredients: ParsedIngredient[];
}
```

---

## JSON-Schema (Draft 2020-12) — Validierungsziel für die emittierte YAML

`additionalProperties` bleibt offen, weil der Ingest unbekannte Felder ohnehin
ignoriert (Obsidian-Zusatzfelder schaden nicht). Strikt geprüft wird der vom
Parser konsumierte Kern.

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

---

## Validierungs-Hinweise für den Importer

- YAML zuerst sicher serialisieren (z.B. `js-yaml`/`gray-matter`), dann das
  geparste Objekt gegen das JSON-Schema prüfen (z.B. `ajv`). So fängst du
  YAML-Fehler (ungültige Mengen, falsche Enums) **vor** dem Schreiben ab.
- Schema deckt den konsumierten Kern strikt ab; auf Zutaten-Ebene ist
  `additionalProperties: false` gesetzt, damit Tippfehler in Zutaten-Keys
  (z.B. `freshnes`) auffallen statt still verloren zu gehen.
- Für 1:1-Verhalten mit der App kannst du zusätzlich Mains reinen Parser
  (`recipeVault.ts`, nur `gray-matter`) als ~100-Zeilen-Validator vendoren und
  deine `.md` damit testweise parsen — gleiche Defaults/Coercion wie produktiv.
