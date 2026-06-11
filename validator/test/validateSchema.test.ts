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
