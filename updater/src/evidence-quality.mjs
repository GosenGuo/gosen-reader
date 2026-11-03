// These checks reject known malformed output; they do not prove semantic correctness.
export function isUsefulExplanation(value) {
  const text = String(value || "").trim();
  return Boolean(text) && !/^(该选项不能由原文证据支持|该选项与原文证据及题目给定答案一致)[。.!！]?$/.test(text);
}

export function isEvidenceQuote(body, evidence) {
  const text = String(evidence || "").trim();
  return Boolean(text) && String(body || "").includes(text)
    && (text.match(/[A-Za-z]+(?:['’][A-Za-z]+)*/g) || []).length >= 4;
}

export function exactSentences(body) {
  const text = String(body || "");
  // Mask punctuation in initials, common titles, decimals and URLs without changing offsets.
  const masked = text.replace(/\b(?:[A-Z]\.)+[A-Z]?\.?|\b(?:Mr|Mrs|Ms|Dr|Prof|Sr|Jr)\.|\d+\.\d+|(?:https?:\/\/|www\.)[^\s]+/g,
    value => value.replaceAll(".", "\u0001"));
  const result = [];
  const pattern = /[^.!?\n]+(?:[.!?]+["'”’]?|(?=\n|$))/g;
  for (const match of masked.matchAll(pattern)) {
    const sentence = text.slice(match.index, match.index + match[0].length).trim();
    if (sentence) result.push(sentence);
  }
  return result;
}

const normalize = value => String(value || "").toLowerCase()
  .replace(/[“”‘’]/g, "'").replace(/\s+/g, " ")
  .replace(/\s*([,.!?;:])\s*/g, "$1").trim();

export function groundEvidenceSentence(body, proposed) {
  if (isEvidenceQuote(body, proposed)) return proposed.trim();
  const target = normalize(proposed);
  if (!target) return "";
  // Only formatting differences may be repaired. Never guess evidence by keyword overlap.
  return exactSentences(body).find(sentence => normalize(sentence) === target
    && isEvidenceQuote(body, sentence)) || "";
}
