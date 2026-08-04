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
