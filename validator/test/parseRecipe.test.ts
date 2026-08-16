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

  it("ignores ingredient sections (Obsidian-only field)", () => {
    const md = `---
id: nuggets
name: Nuggets
ingredients:
  - { name: Ketchup, amount: 3, unit: EL, section: Für die Soße }
---
`;
    const { recipe, errors } = parseRecipeMarkdown(md);
    expect(errors).toEqual([]);
    expect(recipe!.ingredients).toEqual([
      { name: "Ketchup", amount: "3", unit: "EL", category: null },
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
