import fs from "node:fs/promises";
import path from "node:path";
import { AiClient } from "./ai.mjs";
import { WebSearchClient, downloadReadablePage } from "./search.mjs";
import { loadRaceCandidates } from "./race-source.mjs";
import {
  normalizeArticle,
  splitSentences,
  validateArticle,
  validatePackage
} from "./schema.mjs";
import {
  loadExistingArticles,
  mergeArticles
} from "./package-store.mjs";
import {
  loadWordBank,
  mergeArticleIntoWordBank,
  repairArticleGlossary,
  validateWordBank
} from "./word-bank.mjs";

const targetCount = Number(process.env.TARGET_ARTICLE_COUNT || 30);
const maxSearchResults = Number(process.env.MAX_SEARCH_RESULTS || 80);
const maxCandidateAttempts = Number(process.env.MAX_CANDIDATE_ATTEMPTS || Math.max(targetCount * 3, 3));
const outputPath = path.resolve(process.env.OUTPUT_PATH || "./dist/articles.json");
const wordBankPath = path.resolve(process.env.WORD_BANK_PATH || "./dist/word-bank.json");

const ai = new AiClient();
const search = new WebSearchClient();
const wordBank = await loadWordBank(wordBankPath);
const existingArticles = await loadExistingArticles(outputPath);

console.log(`Starting monthly update; target=${targetCount}; existing=${existingArticles.length}`);
const candidates = await findCandidates();
console.log(`Collected ${candidates.length} candidate pages`);
const attemptedCandidates = candidates.slice(0, maxCandidateAttempts);
console.log(`Attempt budget: ${attemptedCandidates.length} candidate(s)`);
const newArticles = [];
const seenIds = new Set(existingArticles.map(article => article.id));

for (const [index, candidate] of attemptedCandidates.entries()) {
  if (newArticles.length >= targetCount) break;
  console.log(`[${index + 1}/${attemptedCandidates.length}] Reading ${candidate.url}`);
  try {
    const page = candidate.structured
      ? { ...candidate, text: candidate.structured.body }
      : await downloadReadablePage(candidate);
    if (!page) continue;
    const extracted = candidate.structured || await extractArticle(page);
    if (!extracted) continue;
    const identity = normalizeArticle(extracted, page);
    if (seenIds.has(identity.id)) {
      console.log(`  skipped existing article: ${identity.title}`);
      continue;
    }
    const enriched = await enrichArticle(extracted, page);
    const article = normalizeArticle(enriched, page);
    const preliminaryErrors = validateArticle(article)
      .filter(error => !error.startsWith("glossary incomplete"));
    if (preliminaryErrors.length) {
      console.log(`  rejected before glossary: ${preliminaryErrors.join("; ")}`);
      continue;
    }
    await repairArticleGlossary(article, ai, wordBank);
    const errors = validateArticle(article);
    if (seenIds.has(article.id) || errors.length) {
      console.log(`  rejected: ${seenIds.has(article.id) ? "duplicate" : errors.join("; ")}`);
      continue;
    }
    seenIds.add(article.id);
    newArticles.push(article);
    mergeArticleIntoWordBank(article, wordBank);
    console.log(`  accepted: ${article.title} (${article.wordCount} words)`);
  } catch (error) {
    console.warn(`  skipped: ${error.message}`);
  }
}

if (newArticles.length === 0) {
  throw new Error("No valid articles were produced; the previous package must remain published");
}
if (newArticles.length < targetCount) {
  console.warn(`Only ${newArticles.length}/${targetCount} passed validation; publishing quality over quota`);
}

const articles = mergeArticles(existingArticles, newArticles);
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
console.log(`Added ${newArticles.length} new article(s); kept ${existingArticles.length} existing article(s)`);
console.log(`Wrote ${Object.keys(wordBank.words).length} words to ${wordBankPath}`);
await publishIfConfigured(payload);

async function findCandidates() {
  const raceDir = process.env.RACE_DIR?.trim();
  if (raceDir) {
    console.log("Loading structured RACE high-school examination questions");
    return loadRaceCandidates(
      raceDir,
      maxSearchResults,
      process.env.CONTENT_SEED?.trim() || undefined
    );
  }
  const queries = await planQueries();
  console.log(`Searching with ${queries.length} query phrases`);
  return collectCandidates(queries);
}

async function planQueries() {
  if (search.provider === "github-code") {
    return [
      '"阅读理解" "答案" extension:md',
      '"英语阅读" "参考答案" extension:txt',
      '"高考英语" "阅读理解" extension:md',
      '"高中英语" "阅读理解" extension:txt',
      '"reading comprehension" "answer key" extension:md',
      '"reading comprehension" "questions" "answers" extension:json'
    ];
  }
  const planned = await ai.json(
    `You plan web searches for existing Chinese high-school English reading-comprehension questions.
Return a JSON array of strings only. Do not add explanations or markdown.`,
    `Create 20 search phrases suitable for a Chinese web search engine.
Find pages containing a complete English passage, multiple-choice questions, answer choices, and confirmed answers.
Include Gaokao papers, provincial or city mock exams, joint exams, midterms, and final exams. The year does not matter.
Use Chinese search phrases and vary source types. Prefer HTML pages over PDF files.`,
    0.3
  );
  const values = Array.isArray(planned) ? planned : planned?.queries;
  if (!Array.isArray(values)) throw new Error("Query planner did not return a query list");
  return values.slice(0, 12).map(String).filter(Boolean);
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
  const sentences = splitSentences(extracted.body)
    .map((sentence, index) => ({ id: `s${index}`, sentence }));
  const sentenceTranslationsById = {};
  for (const batch of chunkValues(sentences, 6)) {
    const translated = await ai.json(
      `Translate English sentences for a Chinese high-school student.
Return exactly one JSON object keyed by every supplied sentence id:
{"s0":"natural, accurate Chinese full-sentence translation","s1":"..."}
Do not omit ids or return English source text, markdown, or commentary.`,
      JSON.stringify(batch),
      0
    );
    Object.assign(sentenceTranslationsById, translated);
  }
  const questionResult = await ai.json(
    `Explain existing high-school English multiple-choice reading questions in Chinese.
Return exactly one JSON object:
{"questionExplanations":["brief Chinese explanation for question 1 grounded in the passage","..."]}
Return one explanation for every question, in order. Do not rewrite questions or return markdown or commentary.`,
    JSON.stringify({
      body: extracted.body,
      questions: extracted.questions.map(question => ({
        prompt: question.prompt,
        options: question.options,
        answer: question.answer
      }))
    }),
    0
  );
  const sentenceTranslations = {};
  for (const { id, sentence } of sentences) {
    const translation = String(sentenceTranslationsById[id] || "").trim();
    if (translation) sentenceTranslations[sentence] = translation;
  }
  return {
    ...extracted,
    sourceUrl: page.url,
    sentenceTranslations,
    glossary: {},
    questions: extracted.questions.map((question, index) => ({
      ...question,
      explanation: String(questionResult?.questionExplanations?.[index] || "").trim()
    }))
  };
}

function chunkValues(values, size) {
  const result = [];
  for (let index = 0; index < values.length; index += size) {
    result.push(values.slice(index, index + size));
  }
  return result;
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
