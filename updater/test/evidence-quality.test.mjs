import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import { exactSentences, groundEvidenceSentence } from "../src/evidence-quality.mjs";
import { applyQuestionReviews, hasCompleteQuestionLearningMetadata } from "../src/learning-enrichment.mjs";
import { validateArticle } from "../src/schema.mjs";

test("initials and titles stay inside complete evidence sentences", () => {
  assert.deepEqual(exactSentences("During dinner, T.J. tells jokes. Dr. Smith laughs. It costs 1.50 dollars."),
    ["During dinner, T.J. tells jokes.", "Dr. Smith laughs.", "It costs 1.50 dollars."]);
});

test("missing or unrelated evidence is not replaced with a keyword match", () => {
  const body = "Keith was tired of the constant stream of riddles. His brother looked sad.";
  assert.equal(groundEvidenceSentence(body, "Keith shouted because he was hungry."), "");
  assert.equal(groundEvidenceSentence(body, ""), "");
  assert.equal(groundEvidenceSentence('"Hey, Keith!" ' + body, '"Hey, Keith!"'), "");
});

test("an empty model response cannot become a complete review through defaults", () => {
  const article = { body: "Keith was tired of the constant stream of riddles.", questions: [{
    prompt: "Why did Keith shout?", options: ["Riddles", "Hunger", "Anger", "Fear"], answer: 0
  }] };
  applyQuestionReviews(article, {});
  assert.equal(article.questions[0].evidenceSentence, "");
  assert.deepEqual(article.questions[0].optionExplanations, []);
  assert.equal(hasCompleteQuestionLearningMetadata(article), false);
});

test("the repaired real passage has substantive reviews and rejects old placeholders", () => {
  const payload = JSON.parse(fs.readFileSync(new URL("../../docs/articles.json", import.meta.url), "utf8"));
  const article = structuredClone(payload.articles.find(a => a.title.includes("high17052")));
  assert.equal(hasCompleteQuestionLearningMetadata(article), true);
  assert.deepEqual(validateArticle(article), []);
  article.questions[0].optionExplanations[0] = "该选项不能由原文证据支持。";
  assert.equal(hasCompleteQuestionLearningMetadata(article), false);
  assert.ok(validateArticle(article).some(e => e.includes("option explanations")));
  article.questions[1].evidenceSentence = "During dinner ,T.";
  assert.ok(validateArticle(article).some(e => e.includes("evidence")));
});
