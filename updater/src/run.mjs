import fs from "node:fs/promises";
import path from "node:path";
import { AiClient } from "./ai.mjs";
import { WebSearchClient, downloadReadablePage } from "./search.mjs";
import { normalizeArticle, validateArticle, validatePackage } from "./schema.mjs";
import {
  loadWordBank,
  mergeArticleIntoWordBank,
  repairArticleGlossary,
  validateWordBank
} from "./word-bank.mjs";

const targetCount = Number(process.env.TARGET_ARTICLE_COUNT || 30);
const maxSearchResults = Number(process.env.MAX_SEARCH_RESULTS || 80);
const outputPath = path.resolve(process.env.OUTPUT_PATH || "./dist/articles.json");
const wordBankPath = path.resolve(process.env.WORD_BANK_PATH || "./dist/word-bank.json");

const ai = new AiClient();
const search = new WebSearchClient();
const wordBank = await loadWordBank(wordBankPath);

console.log(`Starting monthly update; target=${targetCount}`);
const queries = await planQueries();
console.log(`Searching with ${queries.length} query phrases`);
const candidates = await collectCandidates(queries);
console.log(`Collected ${candidates.length} candidate pages`);
const articles = [];
const seenIds = new Set();

for (const [index, candidate] of candidates.entries()) {
  if (articles.length >= targetCount) break;
  console.log(`[${index + 1}/${candidates.length}] Reading ${candidate.url}`);
  try {
    const page = await downloadReadablePage(candidate);
    if (!page) continue;
    const extracted = await extractArticle(page);
    if (!extracted) continue;
    const enriched = await enrichArticle(extracted, page);
    const article = normalizeArticle(enriched, page);
    await repairArticleGlossary(article, ai, wordBank);
    const errors = validateArticle(article);
    if (seenIds.has(article.id) || errors.length) {
      console.log(`  rejected: ${seenIds.has(article.id) ? "duplicate" : errors.join("; ")}`);
      continue;
    }
    seenIds.add(article.id);
    articles.push(article);
    mergeArticleIntoWordBank(article, wordBank);
    console.log(`  accepted: ${article.title} (${article.wordCount} words)`);
  } catch (error) {
    console.warn(`  skipped: ${error.message}`);
  }
}

if (articles.length === 0) {
  throw new Error("No valid articles were produced; the previous package must remain published");
}
if (articles.length < targetCount) {
  console.warn(`Only ${articles.length}/${targetCount} passed validation; publishing quality over quota`);
}

const payload = {
  schemaVersion: 1,
  generatedAt: new Date().toISOString(),
  articles
};
const packageErrors = validatePackage(payload);
if (packageErrors.length) {
  throw new Error(`Package validation failed:\n${packageErrors.join("\n")}`);
}
const wordBankErrors = validateWordBank(wordBank);
if (wordBankErrors.length) {
  throw new Error(`Word bank validation failed:\n${wordBankErrors.join("\n")}`);
}

await fs.mkdir(path.dirname(outputPath), { recursive: true });
await fs.writeFile(outputPath, JSON.stringify(payload, null, 2), "utf8");
await fs.mkdir(path.dirname(wordBankPath), { recursive: true });
await fs.writeFile(wordBankPath, JSON.stringify(wordBank, null, 2), "utf8");
console.log(`Wrote ${articles.length} articles to ${outputPath}`);
console.log(`Wrote ${Object.keys(wordBank.words).length} words to ${wordBankPath}`);
await publishIfConfigured(payload);

async function planQueries() {
  const planned = await ai.json(
    `You plan web searches for existing Chinese high-school English reading-comprehension questions.
Return a JSON array of strings only. Do not add explanations or markdown.`,
    `Create 20 search phrases suitable for a Chinese web search engine.
Find pages containing a complete English passage, multiple-choice questions, answer choices, and confirmed answers.
Include Gaokao papers, provincial or city mock exams, joint exams, midterms, and final exams. The year does not matter.
Use Chinese search phrases and vary source types. Prefer HTML pages over PDF files.`,
    0.3
  );
  if (!Array.isArray(planned)) throw new Error("Query planner did not return an array");
  return planned.slice(0, 12).map(String).filter(Boolean);
}

async function collectCandidates(queries) {
  const found = new Map();
  for (const query of queries) {
    try {
      const results = await search.search(query, 10);
      console.log(`  search "${query}" -> ${results.length} result(s)`);
      for (const result of results) {
        if (!found.has(result.url)) found.set(result.url, result);
        if (found.size >= maxSearchResults) break;
      }
    } catch (error) {
      console.warn(`Search failed for "${query}": ${error.message}`);
    }
    if (found.size >= maxSearchResults) break;
  }
  return [...found.values()];
}

async function extractArticle(page) {
  const result = await ai.json(
    `Extract one already-existing high-school English multiple-choice reading question from a web page.
Never invent, complete, continue, or rewrite missing passage or question text.
Return JSON only. If the page lacks a complete passage, at least two questions, four choices per question, or confirmed answers, return {"usable":false}.
If usable, return:
{"usable":true,"title":"","source":"","region":"","year":2020,"difficulty":"基础|高考|较难","body":"complete original English passage","questions":[{"prompt":"original English question","options":["option A text","option B text","option C text","option D text"],"answer":0,"explanation":"brief Chinese explanation grounded in the passage"}]}
Use answer indexes 0 to 3. Keep navigation, advertisements, and Chinese commentary out of body.`,
    `Page title: ${page.title}
Source URL: ${page.url}

Page text:
${page.text}`,
    0
  );
  return result?.usable ? result : null;
}

async function enrichArticle(extracted, page) {
  return ai.json(
    `Convert an existing high-school English reading question into app data.
Preserve the supplied article and questions exactly. Return one JSON object only, retaining title, source, region, year, difficulty, body, and questions.

Add sentenceTranslations: copy every complete English sentence from body exactly as a key and give a natural, accurate Chinese full-sentence translation as its value.

Add glossary: include every distinct English word in body, keyed by its lowercase surface form. Each value must contain:
{"lemma":"base form","translation":"best Chinese meaning in its first occurrence","pos":"part of speech in its first occurrence","forms":"common exam-relevant forms, or 无常见变形","meanings":"3-5 common Chinese meanings separated by ；","contexts":{"exact sentence copied from body":{"translation":"only the exact Chinese meaning in this sentence","pos":"part of speech in this sentence"}}}

The contexts object must cover every distinct sentence in which the word occurs. Determine different senses separately. For example, figure may mean 数字 or 人物 as a noun and 认为 as a verb depending on its sentence. Do not omit articles, prepositions, pronouns, or other basic words.`,
    JSON.stringify({ ...extracted, sourceUrl: page.url }),
    0
  );
}

async function publishIfConfigured(payload) {
  const url = process.env.PUBLISH_URL?.trim();
  if (!url) {
    console.log("PUBLISH_URL is empty; package was not uploaded");
    return;
  }
  const headers = { "Content-Type": "application/json; charset=utf-8" };
  if (process.env.PUBLISH_BEARER) {
    headers.Authorization = `Bearer ${process.env.PUBLISH_BEARER}`;
  }
  const response = await fetch(url, {
    method: "PUT",
    headers,
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(60_000)
  });
  if (!response.ok) throw new Error(`Publish failed ${response.status}: ${await response.text()}`);
  console.log("Published monthly package");
}
