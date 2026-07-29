import fs from "node:fs/promises";
import path from "node:path";

const RACE_SOURCE_URL = "https://www.cs.cmu.edu/~glai1/data/race/";

export async function loadRaceCandidates(root, limit, seed = currentMonthSeed()) {
  const highRoot = path.join(path.resolve(root), "train", "high");
  const names = (await fs.readdir(highRoot))
    .filter(name => name.endsWith(".txt"))
    .sort();
  if (!names.length) throw new Error(`No RACE high-school files found in ${highRoot}`);

  const start = positiveHash(seed) % names.length;
  const candidates = [];
  for (let offset = 0; offset < names.length && candidates.length < limit; offset += 1) {
    const name = names[(start + offset) % names.length];
    try {
      const record = JSON.parse(await fs.readFile(path.join(highRoot, name), "utf8"));
      const candidate = normalizeRaceRecord(record, name);
      if (candidate) candidates.push(candidate);
    } catch (error) {
      console.warn(`Skipping invalid RACE file ${name}: ${error.message}`);
    }
  }
  return candidates;
}

export function normalizeRaceRecord(record, fallbackId = "race-high") {
  const article = String(record?.article || "").trim();
  const wordCount = article.match(/[A-Za-z]+(?:'[A-Za-z]+)*/g)?.length || 0;
  const questions = Array.isArray(record?.questions) ? record.questions : [];
  const options = Array.isArray(record?.options) ? record.options : [];
  const answers = Array.isArray(record?.answers) ? record.answers : [];
  if (wordCount < 180 || wordCount > 420 || questions.length < 2) return null;
  if (questions.length !== options.length || questions.length !== answers.length) return null;

  const normalizedQuestions = questions.map((prompt, index) => {
    const choices = options[index];
    const answer = "ABCD".indexOf(String(answers[index]).trim().toUpperCase());
    if (!Array.isArray(choices) || choices.length !== 4 || answer < 0) return null;
    return {
      prompt: String(prompt).trim(),
      options: choices.map(choice => String(choice).trim()),
      answer,
      explanation: ""
    };
  });
  if (normalizedQuestions.some(question => !question)) return null;

  const sourceId = String(record.id || fallbackId).replace(/\.[^.]+$/, "");
  return {
    title: `RACE 高中英语阅读 ${sourceId}`,
    url: RACE_SOURCE_URL,
    structured: {
      usable: true,
      title: `RACE 高中英语阅读 ${sourceId}`,
      source: "RACE 中国高中英语考试阅读题库",
      region: "中国",
      year: 2017,
      difficulty: "较难",
      body: article,
      questions: normalizedQuestions
    }
  };
}

function currentMonthSeed() {
  return new Date().toISOString().slice(0, 7);
}

function positiveHash(value) {
  let hash = 2166136261;
  for (const char of String(value)) {
    hash ^= char.codePointAt(0);
    hash = Math.imul(hash, 16777619);
  }
  return hash >>> 0;
}
