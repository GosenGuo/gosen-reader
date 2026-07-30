import fs from "node:fs/promises";
import { validatePackage } from "./schema.mjs";

export async function loadExistingArticles(filePath) {
  try {
    const payload = JSON.parse(await fs.readFile(filePath, "utf8"));
    const errors = validatePackage(payload);
    if (errors.length) {
      throw new Error(`Existing package is invalid:\n${errors.join("\n")}`);
    }
    return payload.articles;
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
}

export function mergeArticles(existingArticles, newArticles) {
  const merged = new Map();
  for (const article of [...existingArticles, ...newArticles]) {
    if (!article?.id) throw new Error("Cannot merge an article without an id");
    if (!merged.has(article.id)) merged.set(article.id, article);
  }
  return [...merged.values()];
}
