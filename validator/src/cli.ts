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
