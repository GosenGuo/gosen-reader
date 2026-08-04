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
    return {
      ...question,
      type: String(review.type || question.type || "").trim(),
      explanation: String(review.explanation || question.explanation || "").trim(),
      evidenceSentence: String(review.evidenceSentence || question.evidenceSentence || "").trim(),
      optionExplanations: normalizeArray(
        review.optionExplanations || question.optionExplanations,
        question.options.length
      ),
      optionErrorTypes: normalizeArray(
        review.optionErrorTypes || question.optionErrorTypes,
        question.options.length
      )
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
  return candidates.map(value => ({
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
