import assert from "node:assert/strict";
import test from "node:test";
import {
  applyQuestionReviews,
  hasCompleteQuestionLearningMetadata,
  normalizeLearningPhrases
} from "../src/learning-enrichment.mjs";

test("applies complete question diagnostics and validates exact evidence", () => {
  const article = {
    body: "Students figured out the answer together. Another sentence follows.",
    questions: [{
      prompt: "What did the students do?",
      options: ["They left", "They answered", "They slept", "They argued"],
      answer: 1,
      explanation: ""
    }]
  };
  applyQuestionReviews(article, { questionReviews: [{
    type: "细节理解",
    explanation: "原文直接说明。",
    evidenceSentence: "Students figured out the answer together.",
    optionExplanations: ["未提及", "正确", "未提及", "未提及"],
    optionErrorTypes: ["没有定位证据句", "正确", "偷换概念", "推理过度"]
  }] });
  assert.equal(hasCompleteQuestionLearningMetadata(article), true);
});

test("keeps only exact useful multi-word expressions", () => {
  const body = "Students figured out the answer together. They took part in the lesson.";
  const phrases = normalizeLearningPhrases({ phrases: [
    { phrase: "figured out", translation: "弄清楚", sentence: "Students figured out the answer together." },
    { phrase: "took part in", translation: "参加", sentence: "They took part in the lesson." },
    { phrase: "invented phrase", translation: "虚构", sentence: "Students figured out the answer together." },
    { phrase: "lesson", translation: "课程", sentence: "They took part in the lesson." }
  ] }, body);
  assert.deepEqual(phrases.map(value => value.phrase), ["figured out", "took part in"]);
});

test("grounds slightly rewritten evidence back to an exact passage sentence", () => {
  const article = {
    body: "In the early 1990s,the Internet was strange to most people. Today it is useful.",
    questions: [{
      prompt: "What was strange to most people?",
      options: ["The Internet", "Television", "Radio", "Books"],
      answer: 0,
      explanation: ""
    }]
  };
  applyQuestionReviews(article, { questionReviews: [{
    type: "细节理解",
    explanation: "原文直接说明。",
    evidenceSentence: "In the early 1990s, the Internet was strange to most people.",
    optionExplanations: ["正确", "错误", "错误", "错误"],
    optionErrorTypes: ["正确", "没有定位证据句", "没有定位证据句", "没有定位证据句"]
  }] });
  assert.equal(
    article.questions[0].evidenceSentence,
    "In the early 1990s,the Internet was strange to most people."
  );
  assert.equal(hasCompleteQuestionLearningMetadata(article), true);
});

test("supplements an empty model response with known high-value expressions", () => {
  const body = "As soon as he was out of work, he decided to dress up as a gorilla. "
    + "He wanted people to pay attention to him and not make fun of him.";
  const phrases = normalizeLearningPhrases({ phrases: [] }, body);
  assert.ok(phrases.length >= 4);
  assert.ok(phrases.some(value => value.phrase === "as soon as"));
  assert.ok(phrases.some(value => value.phrase === "pay attention to"));
});
