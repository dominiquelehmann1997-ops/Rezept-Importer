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
