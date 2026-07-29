import fs from "node:fs/promises";

const WORD_PATTERN = /[A-Za-z]+(?:['’][A-Za-z]+)*/g;
const PLACEHOLDER_PATTERN = /(待.{0,8}(补充|处理)|未知|暂无|处理中|todo|unknown|pending)/i;

export async function loadWordBank(filePath) {
  try {
    const parsed = JSON.parse(await fs.readFile(filePath, "utf8"));
    if (parsed?.schemaVersion !== 1 || !isPlainObject(parsed.words)) {
      throw new Error("unsupported word bank schema");
    }
    return parsed;
  } catch (error) {
    if (error.code !== "ENOENT") {
      console.warn(`Word bank reset: ${error.message}`);
    }
    return {
      schemaVersion: 1,
      generatedAt: new Date(0).toISOString(),
      words: {}
    };
  }
}

export async function repairArticleGlossary(article, minimax, wordBank) {
  article.glossary = isPlainObject(article.glossary) ? article.glossary : {};

  for (let attempt = 1; attempt <= 4; attempt++) {
    const missing = findIncompleteWords(article);
    if (missing.length === 0) return;
    console.log(`  glossary repair ${attempt}: ${missing.length} word(s)`);

    for (const batch of chunks(missing, 18)) {
      const requested = batch.map(word => {
        const sentences = findWordSentences(article.body, word);
        return {
          word,
          contexts: sentences.map((sentence, index) => ({ id: `s${index}`, sentence })),
          known: reusableWordData(article.glossary[word])
            || reusableWordData(wordBank.words[word])
        };
      });
      const result = await minimax.json(
        `You prepare vocabulary data for Chinese high-school English reading.
Return one JSON object only. Each input word must appear as an unchanged lowercase key.
For every word return:
{"lemma":"base form","translation":"the best Chinese meaning in the first supplied context","pos":"part of speech in the first context","forms":"common exam-relevant word forms, or 无常见变形","meanings":"3-5 common Chinese meanings separated by ；","contexts":{"s0":{"translation":"one exact Chinese contextual meaning","pos":"part of speech"},"s1":{"translation":"...","pos":"..."}}}
Return a contexts item for every supplied context id. Determine each meaning from that sentence; do not dump all dictionary meanings into translation. Reuse accurate known data when supplied.`,
        JSON.stringify({ title: article.title, words: requested }),
        0
      );
      if (!isPlainObject(result)) continue;
      for (const request of requested) {
        const rawEntry = result[request.word];
        const entry = normalizeGlossaryEntry(rawEntry);
        if (!entry) continue;
        entry.contexts = normalizeReturnedContexts(rawEntry?.contexts, request.contexts);
        if (isCompleteGlossaryEntry(entry, request.contexts.map(item => item.sentence))) {
          article.glossary[request.word] = entry;
        }
      }
    }
  }

  const unresolved = findIncompleteWords(article);
  if (unresolved.length) {
    throw new Error(`glossary repair left ${unresolved.length} word(s): ${unresolved.slice(0, 12).join(", ")}`);
  }
}

export function mergeArticleIntoWordBank(article, wordBank) {
  const now = new Date().toISOString();
  for (const word of extractUniqueWords(article.body)) {
    const sentences = findWordSentences(article.body, word);
    const entry = normalizeGlossaryEntry(article.glossary?.[word]);
    if (!isCompleteGlossaryEntry(entry, sentences)) continue;

    const previous = isPlainObject(wordBank.words[word]) ? wordBank.words[word] : {};
    const senses = Array.isArray(previous.senses) ? [...previous.senses] : [];
    for (const sentence of sentences) {
      const context = entry.contexts[sentence];
      if (!senses.some(item =>
        item.translation === context.translation && item.example === sentence)) {
        senses.push({
          translation: context.translation,
          pos: context.pos,
          example: sentence,
          sourceArticleId: article.id
        });
      }
    }
    const articleIds = [...new Set([
      ...(Array.isArray(previous.articleIds) ? previous.articleIds : []),
      article.id
    ])].slice(-100);
    wordBank.words[word] = {
      lemma: entry.lemma,
      pos: entry.pos,
      forms: entry.forms,
      meanings: entry.meanings,
      senses: senses.slice(-12),
      articleIds,
      articleCount: articleIds.length,
      updatedAt: now
    };
  }
  wordBank.generatedAt = now;
}

export function findIncompleteWords(article) {
  const glossary = isPlainObject(article.glossary) ? article.glossary : {};
  return extractUniqueWords(article.body)
    .filter(word => !isCompleteGlossaryEntry(
      glossary[word],
      findWordSentences(article.body, word)
    ));
}

export function isCompleteGlossaryEntry(value, requiredSentences = []) {
  if (!isPlainObject(value)) return false;
  const baseComplete = ["lemma", "translation", "pos", "forms", "meanings"]
    .every(field => isUsefulText(value[field]));
  if (!baseComplete) return false;
  if (requiredSentences.length === 0) return true;
  if (!isPlainObject(value.contexts)) return false;
  return requiredSentences.every(sentence => {
    const context = value.contexts[sentence];
    return isPlainObject(context)
      && isUsefulText(context.translation)
      && isUsefulText(context.pos);
  });
}

export function validateWordBank(wordBank) {
  const errors = [];
  if (wordBank?.schemaVersion !== 1) errors.push("word bank schemaVersion must be 1");
  if (!isPlainObject(wordBank?.words)) {
    errors.push("word bank words must be an object");
    return errors;
  }
  for (const [word, entry] of Object.entries(wordBank.words)) {
    if (!/^[a-z]+(?:'[a-z]+)*$/.test(word)) errors.push(`invalid word bank key ${word}`);
    for (const field of ["lemma", "pos", "forms", "meanings"]) {
      if (!isUsefulText(entry?.[field])) {
        errors.push(`word bank ${word} has invalid ${field}`);
      }
    }
    if (!Array.isArray(entry?.articleIds)
        || Number(entry?.articleCount) !== entry.articleIds.length) {
      errors.push(`word bank ${word} has inconsistent article history`);
    }
  }
  return errors;
}

export function extractUniqueWords(body) {
  return [...new Set(
    (String(body).match(WORD_PATTERN) || []).map(normalizeWord)
  )];
}

export function findWordSentences(body, word) {
  const escaped = word.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const matcher = new RegExp(`(^|[^A-Za-z])(${escaped})(?=[^A-Za-z]|$)`, "gi");
  const source = String(body);
  const sentences = [];
  let match;
  while ((match = matcher.exec(source)) !== null) {
    const wordStart = match.index + match[1].length;
    let start = wordStart;
    while (start > 0 && !".!?".includes(source[start - 1])) start--;
    let end = wordStart;
    while (end < source.length && !".!?".includes(source[end])) end++;
    if (end < source.length) end++;
    const sentence = source.slice(start, end).trim();
    if (sentence && !sentences.includes(sentence)) sentences.push(sentence);
  }
  return sentences;
}

function normalizeGlossaryEntry(value) {
  if (!isPlainObject(value)) return null;
  const contexts = {};
  if (isPlainObject(value.contexts)) {
    for (const [sentence, context] of Object.entries(value.contexts)) {
      if (!isPlainObject(context)) continue;
      contexts[sentence] = {
        translation: String(context.translation || "").trim(),
        pos: String(context.pos || "").trim()
      };
    }
  }
  return {
    lemma: String(value.lemma || "").trim(),
    translation: String(value.translation || "").trim(),
    pos: String(value.pos || "").trim(),
    forms: String(value.forms || "").trim(),
    meanings: String(value.meanings || "").trim(),
    contexts
  };
}

function normalizeReturnedContexts(value, requestedContexts) {
  const result = {};
  const returned = isPlainObject(value) ? value : {};
  for (const request of requestedContexts) {
    const context = returned[request.id];
    result[request.sentence] = {
      translation: String(context?.translation || "").trim(),
      pos: String(context?.pos || "").trim()
    };
  }
  return result;
}

function reusableWordData(value) {
  if (!isPlainObject(value)) return null;
  const data = {};
  for (const field of ["lemma", "pos", "forms", "meanings"]) {
    const text = String(value[field] || "").trim();
    if (isUsefulText(text)) data[field] = text;
  }
  if (Array.isArray(value.senses) && value.senses.length) {
    data.previousSenses = value.senses.slice(-6);
  }
  return Object.keys(data).length ? data : null;
}

function isUsefulText(value) {
  const text = String(value || "").trim();
  return text.length > 0 && !PLACEHOLDER_PATTERN.test(text);
}

function normalizeWord(word) {
  return word.toLowerCase().replaceAll("’", "'");
}

function chunks(values, size) {
  const result = [];
  for (let index = 0; index < values.length; index += size) {
    result.push(values.slice(index, index + size));
  }
  return result;
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
