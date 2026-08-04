const QUESTION_TYPES = new Set([
  "细节理解", "推理判断", "主旨大意", "词义猜测", "作者态度", "篇章结构"
]);

export async function reviewQuestions(ai, article) {
  return ai.json(
    `Review existing high-school English multiple-choice reading questions for a Chinese high-school student.
Return exactly one JSON object:
{"questionReviews":[{"type":"细节理解","explanation":"brief Chinese explanation grounded in the passage","evidenceSentence":"one exact complete sentence copied verbatim from the passage","optionExplanations":["why option A is correct or wrong in Chinese","why option B is correct or wrong in Chinese","why option C is correct or wrong in Chinese","why option D is correct or wrong in Chinese"],"optionErrorTypes":["正确","偷换概念","推理过度","忽略否定或范围"]}]}
Return one review for every question, in order, and one explanation and error type for every option. Type must be one of: 细节理解, 推理判断, 主旨大意, 词义猜测, 作者态度, 篇章结构. Error type must be one of: 正确, 没有定位证据句, 推理过度, 偷换概念, 忽略否定或范围, 把局部信息当成主旨, 单词或语境理解错误. Mark the correct option's error type as "正确". Evidence sentences must be exact verbatim sentences from the passage. Do not rewrite questions or return markdown or commentary.`,
    JSON.stringify({
      body: article.body,
      questions: article.questions.map(question => ({
        prompt: question.prompt,
        options: question.options,
        answer: question.answer
      }))
    }),
    0
  );
}

export function applyQuestionReviews(article, result) {
  article.questions = article.questions.map((question, index) => {
    const review = result?.questionReviews?.[index] || {};
    const optionExplanations = normalizeArray(
      review.optionExplanations || question.optionExplanations,
      question.options.length
    );
    const optionErrorTypes = normalizeArray(
      review.optionErrorTypes || question.optionErrorTypes,
      question.options.length
    );
    while (optionExplanations.length < question.options.length) {
      const optionIndex = optionExplanations.length;
      optionExplanations.push(optionIndex === question.answer
        ? "该选项与原文证据及题目给定答案一致。"
        : "该选项不能由原文证据支持。");
    }
    while (optionErrorTypes.length < question.options.length) {
      optionErrorTypes.push("没有定位证据句");
    }
    for (let optionIndex = 0; optionIndex < optionErrorTypes.length; optionIndex += 1) {
      if (optionIndex === question.answer) optionErrorTypes[optionIndex] = "正确";
      else if (optionErrorTypes[optionIndex] === "正确") optionErrorTypes[optionIndex] = "偷换概念";
    }
    return {
      ...question,
      type: QUESTION_TYPES.has(String(review.type || question.type || "").trim())
        ? String(review.type || question.type).trim()
        : inferQuestionType(question.prompt),
      explanation: String(review.explanation || question.explanation || "").trim(),
      evidenceSentence: groundEvidenceSentence(
        article.body,
        String(review.evidenceSentence || question.evidenceSentence || "").trim(),
        question
      ),
      optionExplanations,
      optionErrorTypes
    };
  });
  return article;
}

export async function extractLearningPhrases(ai, body) {
  return ai.json(
    `Extract useful multi-word expressions from an English reading passage for a Chinese high-school student.
Return exactly one JSON object: {"phrases":[{"phrase":"figure out","translation":"弄清楚","note":"brief usage note in Chinese","sentence":"one exact complete sentence copied verbatim from the passage"}]}.
Choose 6 to 14 phrasal verbs, fixed expressions, collocations, or meaning-bearing grammatical chunks that genuinely appear as consecutive words in the passage. Prefer expressions whose combined meaning is not obvious from translating each word separately. Use lowercase for phrase unless capitalization is required. Every sentence must be copied exactly from the passage. Do not include isolated single words, invented examples, markdown, or commentary.`,
    body,
    0
  );
}

export function normalizeLearningPhrases(result, body) {
  const candidates = Array.isArray(result?.phrases) ? result.phrases : [];
  const seen = new Set();
  const phrases = candidates.map(value => ({
    phrase: String(value?.phrase || "").trim(),
    translation: String(value?.translation || "").trim(),
    note: String(value?.note || "").trim(),
    sentence: groundEvidenceSentence(
      body,
      String(value?.sentence || "").trim(),
      { prompt: String(value?.phrase || ""), options: [], answer: 0 }
    )
  })).filter(value => {
    const key = value.phrase.toLowerCase();
    const wordCount = value.phrase.match(/[A-Za-z]+(?:['’][A-Za-z]+)*/g)?.length || 0;
    if (!key || seen.has(key) || wordCount < 2 || wordCount > 6) return false;
    if (!value.translation || !value.sentence || !body.includes(value.sentence)) return false;
    if (!value.sentence.toLowerCase().includes(key)) return false;
    seen.add(key);
    return true;
  });
  for (const phrase of deriveKnownPhrases(body)) {
    const key = phrase.phrase.toLowerCase();
    if (!seen.has(key)) {
      phrases.push(phrase);
      seen.add(key);
    }
  }
  return phrases.slice(0, 24);
}

export function hasCompleteQuestionLearningMetadata(article) {
  return Array.isArray(article?.questions) && article.questions.length > 0
    && article.questions.every(question => {
      const explanations = question.optionExplanations;
      const errorTypes = question.optionErrorTypes;
      return QUESTION_TYPES.has(question.type)
        && Boolean(question.evidenceSentence)
        && String(article.body || "").includes(question.evidenceSentence)
        && Array.isArray(explanations)
        && explanations.length === question.options.length
        && explanations.every(Boolean)
        && Array.isArray(errorTypes)
        && errorTypes.length === question.options.length
        && errorTypes.every(Boolean)
        && errorTypes[question.answer] === "正确"
        && errorTypes.every((value, index) => index === question.answer || value !== "正确");
    });
}

const normalizeArray = (values, length) => Array.isArray(values)
  ? values.slice(0, length).map(value => String(value || "").trim())
  : [];

function inferQuestionType(prompt) {
  const value = String(prompt || "").toLowerCase();
  if (/main idea|mainly|best title|title best/.test(value)) return "主旨大意";
  if (/mean|meaning|underlined|refer to/.test(value)) return "词义猜测";
  if (/attitude|tone|feel about/.test(value)) return "作者态度";
  if (/structure|organized|develop the passage/.test(value)) return "篇章结构";
  if (/infer|imply|suggest|probably|why/.test(value)) return "推理判断";
  return "细节理解";
}

function groundEvidenceSentence(body, proposed, question) {
  const text = String(body || "");
  if (proposed && text.includes(proposed)) return proposed;
  const sentences = exactSentences(text);
  if (!sentences.length) return "";
  const normalizedProposed = normalizeText(proposed);
  if (normalizedProposed) {
    const exactNormalized = sentences.find(sentence =>
      normalizeText(sentence) === normalizedProposed
    );
    if (exactNormalized) return exactNormalized;
    const closest = bestSentence(sentences, proposed);
    if (closest.score >= 0.45) return closest.sentence;
  }
  const answer = Array.isArray(question?.options)
    ? question.options[question.answer] || ""
    : "";
  return bestSentence(sentences, `${question?.prompt || ""} ${answer}`).sentence;
}

function exactSentences(body) {
  return (String(body || "").match(/[^.!?\n]+[.!?]+["']?|[^.!?\n]+$/g) || [])
    .map(value => value.trim())
    .filter(value => value && String(body).includes(value));
}

function bestSentence(sentences, query) {
  const queryWords = significantWords(query);
  let best = { sentence: sentences[0] || "", score: 0 };
  for (const sentence of sentences) {
    const sentenceWords = significantWords(sentence);
    if (!sentenceWords.size || !queryWords.size) continue;
    let overlap = 0;
    for (const word of queryWords) if (sentenceWords.has(word)) overlap += 1;
    const coverage = overlap / queryWords.size;
    const precision = overlap / sentenceWords.size;
    const score = coverage * 0.75 + precision * 0.25;
    if (score > best.score) best = { sentence, score };
  }
  return best;
}

function significantWords(value) {
  const stop = new Set([
    "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "for",
    "with", "is", "are", "was", "were", "be", "been", "what", "which", "why",
    "how", "passage", "following", "according", "does", "did", "do", "can", "could"
  ]);
  return new Set((String(value || "").toLowerCase().match(/[a-z]+(?:'[a-z]+)*/g) || [])
    .filter(word => word.length > 2 && !stop.has(word)));
}

const normalizeText = value => String(value || "").toLowerCase()
  .replace(/[“”‘’]/g, "'")
  .replace(/\s+/g, " ")
  .replace(/\s+([,.!?;:])/g, "$1")
  .trim();

function deriveKnownPhrases(body) {
  const known = [
    ["as soon as", "一……就……", "引导时间状语从句"],
    ["out of work", "失业", "表示没有工作"],
    ["dress up as", "装扮成", "表示扮演某种身份"],
    ["fall off", "下降；掉落", "表示数量下降或从某处落下"],
    ["make fun of", "取笑", "表示嘲弄他人"],
    ["pay attention to", "注意", "to 后接名词或动名词"],
    ["at the end of", "在……末尾", "表示时间或位置的末端"],
    ["for some time", "一段时间", "表示持续了一段时间"],
    ["go on", "继续；发生", "根据语境表示继续或发生"],
    ["put on", "穿上；上演", "根据宾语判断具体含义"],
    ["prepare to", "准备做", "后接动词原形"],
    ["be scared", "感到害怕", "描述人的恐惧状态"],
    ["close behind", "紧随其后", "表示距离非常接近"],
    ["run round and round", "不停地绕圈跑", "表示重复绕圈的动作"],
    ["keep going", "继续进行", "强调持续不中断"],
    ["give up", "放弃", "表示停止努力或不再坚持"],
    ["take advantage of", "利用", "表示充分利用机会或资源"],
    ["figure out", "弄清楚", "表示理解或找到解决办法"],
    ["take part in", "参加", "表示参与活动"],
    ["be interested in", "对……感兴趣", "in 后接名词或动名词"]
  ];
  const lower = String(body || "").toLowerCase();
  const result = [];
  for (const [phrase, translation, note] of known) {
    const index = lower.indexOf(phrase);
    if (index < 0) continue;
    const sentence = exactSentences(body).find(value => {
      const start = String(body).indexOf(value);
      return index >= start && index < start + value.length;
    });
    if (sentence) result.push({ phrase, translation, note, sentence });
  }
  return result;
}
