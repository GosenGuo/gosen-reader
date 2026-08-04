import crypto from "node:crypto";
import {
  extractUniqueWords,
  findIncompleteWords
} from "./word-bank.mjs";
import { assessDifficulty } from "./difficulty.mjs";

export function normalizeArticle(raw, source) {
  const body = String(raw.body || "").replace(/\r/g, "").trim();
  const title = String(raw.title || source.title || "高中英语阅读").trim();
  const hash = crypto.createHash("sha256")
    .update(body.toLowerCase().replace(/\s+/g, " "))
    .digest("hex").slice(0, 16);
  const questions = Array.isArray(raw.questions) ? raw.questions.map(question => ({
    prompt: String(question.prompt || "").trim(),
    options: Array.isArray(question.options)
      ? question.options.map(value => String(value).trim())
      : [],
    answer: Number(question.answer),
    explanation: String(question.explanation || "").trim(),
    type: String(question.type || "").trim(),
    evidenceSentence: String(question.evidenceSentence || "").trim(),
    optionExplanations: Array.isArray(question.optionExplanations)
      ? question.optionExplanations.map(value => String(value || "").trim())
      : [],
    optionErrorTypes: Array.isArray(question.optionErrorTypes)
      ? question.optionErrorTypes.map(value => String(value || "").trim())
      : []
  })) : [];
  const difficulty = assessDifficulty(body, questions);
  return {
    id: `web-${hash}`,
    title,
    source: String(raw.source || source.title || "网络试题").slice(0, 120),
    sourceUrl: source.url,
    region: String(raw.region || "高中英语"),
    year: Number(raw.year) || new Date().getFullYear(),
    difficulty: difficulty.label,
    difficultyLevel: difficulty.level,
    difficultyScore: difficulty.score,
    difficultyMetrics: difficulty.metrics,
    body,
    wordCount: body.match(/[A-Za-z]+(?:['’][A-Za-z]+)*/g)?.length || 0,
    sentenceTranslations: raw.sentenceTranslations || {},
    glossary: raw.glossary || {},
    phrases: normalizePhrases(raw.phrases, body),
    questions
  };
}

function normalizePhrases(rawPhrases, body) {
  if (!Array.isArray(rawPhrases)) return [];
  const seen = new Set();
  return rawPhrases.map(value => ({
    phrase: String(value?.phrase || "").trim(),
    translation: String(value?.translation || "").trim(),
    note: String(value?.note || "").trim(),
    sentence: String(value?.sentence || "").trim()
  })).filter(value => {
    const key = value.phrase.toLowerCase();
    const wordCount = value.phrase.match(/[A-Za-z]+(?:['’][A-Za-z]+)*/g)?.length || 0;
    if (!key || seen.has(key) || wordCount < 2 || wordCount > 6) return false;
    if (!value.translation || !value.sentence || !body.includes(value.sentence)) return false;
    if (!value.sentence.toLowerCase().includes(key)) return false;
    seen.add(key);
    return true;
  }).slice(0, 24);
}

export function validateArticle(article, options = {}) {
  const errors = [];
  if (!article.id) errors.push("missing id");
  if (article.wordCount < 90 || article.wordCount > 900) errors.push("word count outside 90-900");
  if (!Array.isArray(article.questions) || article.questions.length < 2) {
    errors.push("fewer than two questions");
  }
  for (const [index, question] of article.questions.entries()) {
    const optionExplanations = Array.isArray(question.optionExplanations)
      ? question.optionExplanations : [];
    const optionErrorTypes = Array.isArray(question.optionErrorTypes)
      ? question.optionErrorTypes : [];
    if (!question.prompt) errors.push(`question ${index + 1} has no prompt`);
    if (question.options.length !== 4) errors.push(`question ${index + 1} does not have four options`);
    if (!Number.isInteger(question.answer) || question.answer < 0 || question.answer > 3) {
      errors.push(`question ${index + 1} answer is invalid`);
    }
    if (!question.explanation) errors.push(`question ${index + 1} has no explanation`);
    const hasLearningMetadata = Boolean(
      question.type
      || question.evidenceSentence
      || optionExplanations.length
      || optionErrorTypes.length
    );
    if (options.requireLearningMetadata || hasLearningMetadata) {
      if (!question.type) errors.push(`question ${index + 1} has no type`);
      if (!question.evidenceSentence || !article.body.includes(question.evidenceSentence)) {
        errors.push(`question ${index + 1} evidence is not an exact passage sentence`);
      }
      if (optionExplanations.length !== 4
          || optionExplanations.some(value => !value)) {
        errors.push(`question ${index + 1} option explanations are incomplete`);
      }
      if (optionErrorTypes.length !== 4
          || optionErrorTypes.some(value => !value)) {
        errors.push(`question ${index + 1} option error types are incomplete`);
      } else if (optionErrorTypes[question.answer] !== "正确"
          || optionErrorTypes.some((value, optionIndex) =>
            optionIndex !== question.answer && value === "正确")) {
        errors.push(`question ${index + 1} option error types do not match the answer`);
      }
    }
  }
  if (options.requireLearningMetadata) {
    if (!Number.isInteger(article.difficultyLevel)
        || article.difficultyLevel < 0 || article.difficultyLevel > 2) {
      errors.push("difficulty level is missing or invalid");
    }
    if (!Number.isInteger(article.difficultyScore)
        || article.difficultyScore < 0 || article.difficultyScore > 100) {
      errors.push("difficulty score is missing or invalid");
    }
    if (!Array.isArray(article.phrases) || article.phrases.length < 3) {
      errors.push("fewer than three learning phrases");
    }
  }
  for (const [index, phrase] of (Array.isArray(article.phrases) ? article.phrases : []).entries()) {
    const phraseWords = String(phrase?.phrase || "")
      .match(/[A-Za-z]+(?:['’][A-Za-z]+)*/g)?.length || 0;
    if (phraseWords < 2 || phraseWords > 6
        || !phrase.translation
        || !phrase.sentence
        || !article.body.includes(phrase.sentence)
        || !phrase.sentence.toLowerCase().includes(phrase.phrase.toLowerCase())) {
      errors.push(`learning phrase ${index + 1} is invalid`);
    }
  }
  const sentences = splitSentences(article.body);
  const translated = sentences.filter(sentence => article.sentenceTranslations[sentence]).length;
  if (translated < Math.max(1, Math.floor(sentences.length * 0.85))) {
    errors.push("sentence translations cover less than 85%");
  }
  const uniqueWords = extractUniqueWords(article.body);
  const incompleteWords = findIncompleteWords(article);
  if (!article.source.startsWith("内置示例") && incompleteWords.length > 0) {
    errors.push(
      `glossary incomplete for ${incompleteWords.length}/${uniqueWords.length} word(s): `
      + incompleteWords.slice(0, 12).join(", ")
    );
  }
  return errors;
}

export function validatePackage(payload, options = {}) {
  const errors = [];
  if (payload.schemaVersion !== 1) errors.push("schemaVersion must be 1");
  if (!Array.isArray(payload.articles) || payload.articles.length === 0) {
    errors.push("package contains no articles");
    return errors;
  }
  const ids = new Set();
  for (const [index, article] of payload.articles.entries()) {
    if (ids.has(article.id)) errors.push(`duplicate article id ${article.id}`);
    ids.add(article.id);
    for (const error of validateArticle(article, options)) {
      errors.push(`article ${index + 1}: ${error}`);
    }
  }
  return errors;
}

export function splitSentences(body) {
  return body
    .replace(/\n+/g, " ")
    .match(/[^.!?]+[.!?]/g)
    ?.map(value => value.trim()) || [];
}
