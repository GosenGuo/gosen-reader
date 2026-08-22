import fs from "node:fs/promises";
import path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import "./backfill.mjs";
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
import {
  applyQuestionReviews,
  extractLearningPhrases,
  normalizeLearningPhrases,
  reviewQuestions
} from "./learning-enrichment.mjs";

const targetCount = Number(process.env.TARGET_ARTICLE_COUNT || 30);
const maxSearchResults = Number(process.env.MAX_SEARCH_RESULTS || 80);
const maxCandidateAttempts = Number(process.env.MAX_CANDIDATE_ATTEMPTS || Math.max(targetCount * 3, 3));
const outputPath = path.resolve(process.env.OUTPUT_PATH || "./dist/articles.json");
const wordBankPath = path.resolve(process.env.WORD_BANK_PATH || "./dist/word-bank.json");
const ledgerPath = path.resolve(
  process.env.GENERATION_LEDGER_PATH || "./dist/generation-ledger.json"
);
const checkpointEachArticle = process.env.CHECKPOINT_EACH_ARTICLE === "true"
  || (process.env.CHECKPOINT_EACH_ARTICLE !== "false" && process.env.GITHUB_ACTIONS === "true");
const generationRunId = process.env.GENERATION_RUN_ID
  || process.env.GITHUB_RUN_ID
  || `local-${Date.now()}`;
const generationStartedAt = new Date().toISOString();
const execFileAsync = promisify(execFile);
const translationConcurrency = Math.max(
  1,
  Number(process.env.TRANSLATION_CONCURRENCY || 3)
);

const ai = new AiClient();
const bulkAi = new AiClient({
  ...process.env,
  AI_MODEL: process.env.BULK_AI_MODEL?.trim() || "claude-haiku-4-5-20251001",
  AI_FALLBACK_MODELS: process.env.BULK_AI_FALLBACK_MODELS?.trim()
    || "gemini-2.5-flash"
}, { usageEntries: ai.usageEntries });
const search = new WebSearchClient();
const wordBank = await loadWordBank(wordBankPath);
const existingArticles = await loadExistingArticles(outputPath);

console.log(`Starting on-demand refill; target=${targetCount}; existing=${existingArticles.length}`);
console.log(`AI routes: strict=${ai.models.join(" -> ")}; bulk=${bulkAi.models.join(" -> ")}`);
const candidates = await findCandidates();
console.log(`Collected ${candidates.length} candidate pages`);
const attemptedCandidates = candidates.slice(0, maxCandidateAttempts);
console.log(`Attempt budget: ${attemptedCandidates.length} candidate(s)`);
const newArticles = [];
const articleUsage = [];
const seenIds = new Set(existingArticles.map(article => article.id));

for (const [index, candidate] of attemptedCandidates.entries()) {
  if (newArticles.length >= targetCount) break;
  console.log(`[${index + 1}/${attemptedCandidates.length}] Reading ${candidate.url}`);
  const usageStart = ai.usageSnapshot();
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
    const preliminaryErrors = validateArticle(article, { requireQuestionGlossary: true })
      .filter(error => !error.startsWith("glossary incomplete"));
    if (preliminaryErrors.length) {
      console.log(`  rejected before glossary: ${preliminaryErrors.join("; ")}`);
      continue;
    }
    await repairArticleGlossary(article, bulkAi, wordBank);
    const errors = validateArticle(article, { requireQuestionGlossary: true });
    if (seenIds.has(article.id) || errors.length) {
      console.log(`  rejected: ${seenIds.has(article.id) ? "duplicate" : errors.join("; ")}`);
      continue;
    }
    seenIds.add(article.id);
    const generationUsage = ai.usageSince(usageStart);
    article.generationUsage = generationUsage;
    newArticles.push(article);
    articleUsage.push({
      articleId: article.id,
      title: article.title,
      sourceUrl: article.sourceUrl,
      ...generationUsage
    });
    mergeArticleIntoWordBank(article, wordBank);
    console.log(
      `  accepted: ${article.title} (${article.wordCount} words, `
      + `${generationUsage.totalTokens} tokens, `
      + formatCost(generationUsage) + ")"
    );
    if (checkpointEachArticle) {
      try {
        await persistCurrentPackage({ completed: false, checkpointArticle: article });
      } catch (error) {
        error.checkpointFatal = true;
        throw error;
      }
    }
  } catch (error) {
    if (error.checkpointFatal) throw error;
    console.warn(`  skipped: ${error.message}`);
  }
}

if (newArticles.length === 0) {
  throw new Error("No valid articles were produced; the previous package must remain published");
}
const reachedTarget = newArticles.length >= targetCount;
if (!reachedTarget) {
  console.warn(`Only ${newArticles.length}/${targetCount} passed validation; saved valid checkpoints but quota was not reached`);
}

const payload = await persistCurrentPackage({ completed: reachedTarget });
const articles = payload.articles;
console.log(`Wrote ${articles.length} articles to ${outputPath}`);
console.log(`Added ${newArticles.length} new article(s); kept ${existingArticles.length} existing article(s)`);
console.log(`Wrote ${Object.keys(wordBank.words).length} words to ${wordBankPath}`);
await publishIfConfigured(payload);
if (!reachedTarget) {
  throw new Error(`Generation quota not reached: ${newArticles.length}/${targetCount} valid articles`);
}

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
  // Attach a rejection handler immediately. Without this wrapper, the parallel
  // question request can time out while sentence translation is still running;
  // Node then treats it as an unhandled rejection and kills the whole refill.
  const questionPromise = reviewQuestions(ai, extracted).then(
    value => ({ value, error: null }),
    error => ({ value: null, error })
  );
  const phrasePromise = extractLearningPhrases(bulkAi, extracted.body).then(
    value => ({ value, error: null }),
    error => ({ value: null, error })
  );
  const sentenceTranslationsById = {};
  const translationBatches = chunkValues(sentences, 6);
  for (const group of chunkValues(translationBatches, translationConcurrency)) {
    const translatedGroup = await Promise.all(group.map(batch => bulkAi.json(
      `Translate English sentences for a Chinese high-school student.
Return exactly one JSON object keyed by every supplied sentence id:
{"s0":"natural, accurate Chinese full-sentence translation","s1":"..."}
Do not omit ids or return English source text, markdown, or commentary.`,
      JSON.stringify(batch),
      0
    )));
    for (const translated of translatedGroup) {
      Object.assign(sentenceTranslationsById, translated);
    }
  }
  const questionOutcome = await questionPromise;
  if (questionOutcome.error) throw questionOutcome.error;
  const phraseOutcome = await phrasePromise;
  if (phraseOutcome.error) throw phraseOutcome.error;
  const sentenceTranslations = {};
  for (const { id, sentence } of sentences) {
    const translation = String(sentenceTranslationsById[id] || "").trim();
    if (translation) sentenceTranslations[sentence] = translation;
  }
  const enriched = {
    ...extracted,
    sourceUrl: page.url,
    sentenceTranslations,
    glossary: {},
    phrases: normalizeLearningPhrases(phraseOutcome.value, extracted.body)
  };
  applyQuestionReviews(enriched, questionOutcome.value);
  if (enriched.phrases.length < 3) {
    throw new Error(`phrase extraction returned only ${enriched.phrases.length} valid expression(s)`);
  }
  return enriched;
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
  console.log("Published on-demand package");
}

async function persistCurrentPackage({ completed, checkpointArticle = null }) {
  const articles = mergeArticles(existingArticles, newArticles);
  const payload = {
    schemaVersion: 1,
    generatedAt: new Date().toISOString(),
    articles
  };
  const packageErrors = validatePackage(payload, { requireLearningMetadata: true });
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
  await writeGenerationLedgerProgress({
    runId: generationRunId,
    startedAt: generationStartedAt,
    generatedAt: payload.generatedAt,
    requestedArticles: targetCount,
    acceptedArticles: newArticles.length,
    completed,
    usage: ai.usageSince(0),
    articles: articleUsage
  });

  if (checkpointArticle) {
    await commitArticleCheckpoint(checkpointArticle);
  }
  return payload;
}

async function writeGenerationLedgerProgress(runRecord) {
  let ledger = { schemaVersion: 1, runs: [] };
  try {
    ledger = JSON.parse(await fs.readFile(ledgerPath, "utf8"));
    if (ledger.schemaVersion !== 1 || !Array.isArray(ledger.runs)) {
      throw new Error("Generation ledger has an unsupported schema");
    }
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const existingIndex = ledger.runs.findIndex(record => record.runId === runRecord.runId);
  if (existingIndex >= 0) ledger.runs[existingIndex] = runRecord;
  else ledger.runs.push(runRecord);
  await fs.mkdir(path.dirname(ledgerPath), { recursive: true });
  await fs.writeFile(ledgerPath, JSON.stringify(ledger, null, 2), "utf8");
  console.log(
    `Recorded ${runRecord.usage.totalTokens} total tokens; `
    + formatCost(runRecord.usage)
  );
}

async function commitArticleCheckpoint(article) {
  const repositoryPath = process.env.GITHUB_WORKSPACE?.trim();
  if (!repositoryPath) {
    console.warn("  checkpoint skipped: GITHUB_WORKSPACE is unavailable");
    return;
  }
  const files = [outputPath, wordBankPath, ledgerPath]
    .map(file => path.relative(repositoryPath, file));
  const git = args => execFileAsync("git", args, {
    cwd: repositoryPath,
    maxBuffer: 10 * 1024 * 1024
  });
  await git(["add", "--", ...files]);
  await git([
    "-c", "user.name=github-actions[bot]",
    "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
    "commit", "-m", `content: add reading ${article.id}`
  ]);
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      await git(["pull", "--rebase", "origin", "main"]);
      await git(["push", "origin", "HEAD:main"]);
      console.log(`  published checkpoint ${newArticles.length}/${targetCount}: ${article.id}`);
      try {
        await execFileAsync("gh", [
          "workflow", "run", "pages.yml",
          "--repo", process.env.GITHUB_REPOSITORY,
          "--ref", "main"
        ], {
          cwd: repositoryPath,
          env: process.env,
          maxBuffer: 10 * 1024 * 1024
        });
        console.log("  requested GitHub Pages deployment for this checkpoint");
      } catch (error) {
        console.warn(`  checkpoint was saved, but Pages dispatch failed: ${error.message}`);
      }
      return;
    } catch (error) {
      lastError = error;
      console.warn(`  checkpoint push attempt ${attempt}/3 failed; retrying`);
    }
  }
  throw new Error(`Checkpoint push failed: ${lastError?.message || "unknown git error"}`);
}

function formatCost(usage) {
  const reported = Object.entries(usage.reportedCosts || {});
  if (reported.length > 0) {
    return reported
      .map(([currency, value]) => `${currency} ${Number(value).toFixed(6)} reported`)
      .join(" + ");
  }
  if (!usage.pricingComplete) return "cost unavailable (pricing not configured)";
  if (usage.estimatedCostUsd == null && usage.estimatedCostCny != null) {
    return `CNY ${usage.estimatedCostCny.toFixed(6)} estimated`;
  }
  const cny = usage.estimatedCostCny == null
    ? ""
    : ` / CNY ${usage.estimatedCostCny.toFixed(6)}`;
  return `$${usage.estimatedCostUsd.toFixed(6)}${cny} estimated`;
}
