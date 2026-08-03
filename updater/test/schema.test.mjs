import test from "node:test";
import assert from "node:assert/strict";
import { normalizeArticle } from "../src/schema.mjs";

test("keeps question evidence and learning diagnostics during normalization", () => {
  const evidence = "The passage gives one exact piece of evidence.";
  const article = normalizeArticle({
    title: "Test",
    body: `${evidence} Another sentence completes the passage.`,
    questions: [{
      prompt: "What does the passage give?",
      options: ["Evidence", "Advice", "A date", "A name"],
      answer: 0,
      explanation: "The first sentence states it.",
      type: "细节理解",
      evidenceSentence: evidence,
      optionExplanations: ["Correct", "Not stated", "Not stated", "Not stated"],
      optionErrorTypes: ["正确", "没有定位证据句", "偷换概念", "偷换概念"]
    }]
  }, { title: "Source", url: "https://example.com" });

  assert.equal(article.questions[0].type, "细节理解");
  assert.equal(article.questions[0].evidenceSentence, evidence);
  assert.equal(article.questions[0].optionExplanations.length, 4);
  assert.deepEqual(article.questions[0].optionErrorTypes,
    ["正确", "没有定位证据句", "偷换概念", "偷换概念"]);
});
