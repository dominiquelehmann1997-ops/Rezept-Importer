import { Ajv2020 } from "ajv/dist/2020.js";
import matter from "gray-matter";
import { readFileSync } from "node:fs";

const schema = JSON.parse(
  readFileSync(
    new URL("../../shared/recipe-vault-frontmatter.schema.json", import.meta.url),
    "utf8",
  ),
);

const ajv = new Ajv2020({ allErrors: true, allowUnionTypes: true });
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
