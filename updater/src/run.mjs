import fs from "node:fs/promises";
import path from "node:path";
import { MiniMaxClient } from "./minimax.mjs";
import { WebSearchClient, downloadReadablePage } from "./search.mjs";
import { normalizeArticle, validateArticle, validatePackage } from "./schema.mjs";

const targetCount = Number(process.env.TARGET_ARTICLE_COUNT || 30);
const maxSearchResults = Number(process.env.MAX_SEARCH_RESULTS || 80);
const outputPath = path.resolve(process.env.OUTPUT_PATH || "./dist/articles.json");

const minimax = new MiniMaxClient();
const search = new WebSearchClient();

console.log(`Starting monthly update; target=${targetCount}`);
const queries = await planQueries();
const candidates = await collectCandidates(queries);
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
    const errors = validateArticle(article);
    if (seenIds.has(article.id) || errors.length) {
      console.log(`  rejected: ${seenIds.has(article.id) ? "duplicate" : errors.join("; ")}`);
      continue;
    }
    seenIds.add(article.id);
    articles.push(article);
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

await fs.mkdir(path.dirname(outputPath), { recursive: true });
await fs.writeFile(outputPath, JSON.stringify(payload, null, 2), "utf8");
console.log(`Wrote ${articles.length} articles to ${outputPath}`);
await publishIfConfigured(payload);

async function planQueries() {
  const planned = await minimax.json(
    "你是高中英语阅读题搜索规划器。只输出JSON数组，不回答其他内容。",
    `为中国高中生寻找互联网上已有的完整英语阅读理解题。需要覆盖高考真题、各省市模拟考、联考、期中和期末试卷。生成10条适合中文网页搜索引擎的检索词，优先寻找含完整英文文章、四选一题目和答案的网页。年份不重要。输出字符串数组。`,
    0.3
  );
  if (!Array.isArray(planned)) throw new Error("Query planner did not return an array");
  return planned.slice(0, 12).map(String);
}

async function collectCandidates(queries) {
  const found = new Map();
  for (const query of queries) {
    try {
      const results = await search.search(query, 10);
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
  const result = await minimax.json(
    `你负责从网页中提取“已经存在”的高中英语四选一阅读理解题，绝不补写、续写或虚构缺失内容。
只输出JSON。若网页没有一篇完整可用的阅读题，输出 {"usable":false}。
若可用，输出：
{"usable":true,"title":"","source":"","region":"","year":2020,"difficulty":"基础|高考|较难","body":"完整英文文章","questions":[{"prompt":"英文题干","options":["A选项正文","B选项正文","C选项正文","D选项正文"],"answer":0,"explanation":"基于原文的中文简析"}]}
answer使用0到3。网页没有明确答案时必须输出usable=false。不要把网页导航、广告或中文解析混进body。`,
    `网页标题：${page.title}\n来源网址：${page.url}\n\n网页正文：\n${page.text}`,
    0
  );
  return result?.usable ? result : null;
}

async function enrichArticle(extracted, page) {
  const result = await minimax.json(
    `你是高中英语阅读数据处理器。保留输入文章与题目原文，不得改写。
只输出一个JSON对象，并保留输入中的title、source、region、year、difficulty、body、questions字段。
增加sentenceTranslations和glossary字段。
sentenceTranslations：以文章中每个完整英文句子原文为键，以自然、准确的中文整句翻译为值，键必须逐字符来自原文。
glossary：为文章每个不同的英文单词建立条目，以正文中小写词形为键。值格式：
{"lemma":"原形","translation":"该词在本句中的中文含义","pos":"词性缩写","forms":"高考常见词形变化","meanings":"3至6个高考常见含义，用；分隔"}
同一个词在不同句子含义不同时，以文章首次出现的语境义作为translation；meanings包含其他常见义。不要遗漏冠词、介词等基础词。`,
    JSON.stringify({ ...extracted, sourceUrl: page.url }),
    0
  );
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
