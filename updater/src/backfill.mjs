import fs from "node:fs/promises";
import path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { AiClient } from "./ai.mjs";
import { assessDifficulty } from "./difficulty.mjs";
import {
  applyQuestionReviews,
  extractLearningPhrases,
  hasCompleteQuestionLearningMetadata,
  normalizeLearningPhrases,
  reviewQuestions
} from "./learning-enrichment.mjs";
import { validatePackage } from "./schema.mjs";

const outputPath = path.resolve(process.env.OUTPUT_PATH || "./dist/articles.json");
const ledgerPath = path.resolve(
  process.env.GENERATION_LEDGER_PATH || "./dist/generation-ledger.json"
);
const runId = `${process.env.GENERATION_RUN_ID || process.env.GITHUB_RUN_ID || `local-${Date.now()}`}-quality`;
const checkpointEachArticle = process.env.CHECKPOINT_EACH_ARTICLE === "true"
  || (process.env.CHECKPOINT_EACH_ARTICLE !== "false" && process.env.GITHUB_ACTIONS === "true");
const execFileAsync = promisify(execFile);
const ai = new AiClient();
const bulkAi = new AiClient({
  ...process.env,
  AI_MODEL: process.env.BULK_AI_MODEL?.trim() || "claude-haiku-4-5-20251001",
  AI_FALLBACK_MODELS: process.env.BULK_AI_FALLBACK_MODELS?.trim()
    || "gemini-2.5-flash"
}, { usageEntries: ai.usageEntries });

const payload = JSON.parse(await fs.readFile(outputPath, "utf8"));
const startedAt = new Date().toISOString();
const repaired = [];
const failures = [];
let difficultyChanged = false;

for (const article of payload.articles) {
  const profile = assessDifficulty(article.body, article.questions);
  const oldProfile = JSON.stringify([
    article.difficulty,
    article.difficultyLevel,
    article.difficultyScore,
    article.difficultyMetrics
  ]);
  article.difficulty = profile.label;
  article.difficultyLevel = profile.level;
  article.difficultyScore = profile.score;
  article.difficultyMetrics = profile.metrics;
  difficultyChanged ||= oldProfile !== JSON.stringify([
    article.difficulty,
    article.difficultyLevel,
    article.difficultyScore,
    article.difficultyMetrics
  ]);
}

const targets = payload.articles.filter(article =>
  !hasCompleteQuestionLearningMetadata(article)
  || !Array.isArray(article.phrases)
  || article.phrases.length < 3
);
console.log(`Quality backfill: ${targets.length}/${payload.articles.length} article(s) need enrichment`);

for (const [index, article] of targets.entries()) {
  const usageStart = ai.usageSnapshot();
  console.log(`[${index + 1}/${targets.length}] Enriching ${article.title}`);
  try {
    const updated = structuredClone(article);
    if (!hasCompleteQuestionLearningMetadata(updated)) {
      await retrySemantic("question diagnostics", async () => {
        const candidate = structuredClone(updated);
        applyQuestionReviews(candidate, await reviewQuestions(ai, candidate));
        if (!hasCompleteQuestionLearningMetadata(candidate)) {
          throw new Error("question diagnostics were incomplete or evidence was not verbatim");
        }
        updated.questions = candidate.questions;
      });
    }
    if (!Array.isArray(updated.phrases) || updated.phrases.length < 3) {
      await retrySemantic("learning phrases", async () => {
        const phrases = normalizeLearningPhrases(
          await extractLearningPhrases(bulkAi, updated.body),
          updated.body
        );
        if (phrases.length < 3) {
          throw new Error(`only ${phrases.length} valid phrase(s) were returned`);
        }
        updated.phrases = phrases;
      });
    }
    Object.assign(article, updated);
    const usage = ai.usageSince(usageStart);
    repaired.push({
      articleId: article.id,
      title: article.title,
      requests: usage.requests,
      inputTokens: usage.inputTokens,
      outputTokens: usage.outputTokens,
      totalTokens: usage.totalTokens,
      estimatedCostCny: usage.estimatedCostCny,
      models: usage.models
    });
    await saveProgress(false);
    if (checkpointEachArticle) await commitCheckpoint(article);
    console.log(`  enriched with ${article.phrases.length} phrase(s)`);
  } catch (error) {
    failures.push({ articleId: article.id, error: error.message });
    console.warn(`  failed: ${error.message}`);
  }
}

if (targets.length === 0 && !difficultyChanged) {
  console.log("Quality backfill is already complete");
} else {
  await saveProgress(failures.length === 0);
}
if (failures.length) {
  throw new Error(`Quality backfill failed for ${failures.length} article(s): ${JSON.stringify(failures)}`);
}
console.log(`Quality backfill complete: ${repaired.length} article(s) enriched`);

async function retrySemantic(label, operation) {
  let lastError;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    try {
      return await operation();
    } catch (error) {
      lastError = error;
      console.warn(`  ${label} attempt ${attempt}/3 failed: ${error.message}`);
    }
  }
  throw lastError;
}

async function saveProgress(completed) {
  payload.generatedAt = new Date().toISOString();
  const errors = validatePackage(payload, { requireLearningMetadata: completed });
  if (errors.length) throw new Error(`Backfilled package is invalid:\n${errors.join("\n")}`);
  await fs.writeFile(outputPath, JSON.stringify(payload, null, 2), "utf8");

  let ledger = { schemaVersion: 1, runs: [] };
  try {
    ledger = JSON.parse(await fs.readFile(ledgerPath, "utf8"));
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const record = {
    runId,
    kind: "quality-backfill",
    startedAt,
    generatedAt: payload.generatedAt,
    requestedArticles: targets.length,
    acceptedArticles: repaired.length,
    completed,
    usage: ai.usageSince(0),
    articles: repaired,
    failures
  };
  const recordIndex = ledger.runs.findIndex(value => value.runId === runId);
  if (recordIndex >= 0) ledger.runs[recordIndex] = record;
  else ledger.runs.push(record);
  await fs.writeFile(ledgerPath, JSON.stringify(ledger, null, 2), "utf8");
}

async function commitCheckpoint(article) {
  const repositoryPath = process.env.GITHUB_WORKSPACE?.trim();
  if (!repositoryPath) return;
  const files = [outputPath, ledgerPath].map(file => path.relative(repositoryPath, file));
  const git = args => execFileAsync("git", args, {
    cwd: repositoryPath,
    maxBuffer: 10 * 1024 * 1024
  });
  await git(["add", "--", ...files]);
  await git([
    "-c", "user.name=github-actions[bot]",
    "-c", "user.email=41898282+github-actions[bot]@users.noreply.github.com",
    "commit", "-m", `content: enrich reading ${article.id}`
  ]);
  await git(["pull", "--rebase", "origin", "main"]);
  await git(["push", "origin", "HEAD:main"]);
  try {
    await execFileAsync("gh", [
      "workflow", "run", "pages.yml",
      "--repo", process.env.GITHUB_REPOSITORY,
      "--ref", "main"
    ], { cwd: repositoryPath, env: process.env });
  } catch (error) {
    console.warn(`  saved checkpoint, but Pages dispatch failed: ${error.message}`);
  }
}
