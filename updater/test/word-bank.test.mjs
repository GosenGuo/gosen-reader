import assert from "node:assert/strict";
import test from "node:test";
import {
  findIncompleteWords,
  findArticleWordContexts,
  findWordSentences,
  isCompleteGlossaryEntry,
  mergeArticleIntoWordBank,
  repairArticleGlossary,
  validateWordBank
} from "../src/word-bank.mjs";
import { validateArticle } from "../src/schema.mjs";

test("repairs every sentence context and updates the reusable word bank once", async () => {
  const sentences = [
    "A figure on the screen helped the students understand the result.",
    "We figure the answer will become clear after another careful check.",
    "Everyone discussed the result and wrote a short answer with evidence.",
    "Later, the class checked the answer and corrected one small mistake.",
    "This careful process helped every student learn from the reading task.",
    "They finally shared the result with another class and received useful feedback.",
    "The experience showed that clear evidence can improve an explanation.",
    "It also showed why students should check an answer before sharing it.",
    "Their teacher then asked the group to describe what they had learned.",
    "Each student used a complete sentence and supported it with details.",
    "They were spending enough time on careful practice."
  ];
  const article = {
    id: "web-test",
    title: "Context Test",
    source: "网络试题",
    body: sentences.join(" "),
    wordCount: 120,
    sentenceTranslations: Object.fromEntries(sentences.map(sentence => [sentence, `整句翻译：${sentence}`])),
    glossary: {
      figure: {
        lemma: "",
        translation: "该词释义待题库处理器补充",
        pos: "",
        forms: "",
        meanings: ""
      },
      spending: {
        lemma: "spend",
        translation: "花费",
        pos: "v.",
        forms: "spends；spent；spending",
        meanings: "花费；度过；用尽",
        contexts: {
          "They were spending enough time on careful practice.": {
            translation: "花费",
            pos: "v."
          }
        }
      }
    },
    questions: [
      {
        prompt: "What helped the students?",
        options: ["A figure", "They spend time", "A game", "A trip"],
        answer: 0,
        explanation: "首句提供了答案。"
      },
      {
        prompt: "What did the class do later?",
        options: ["Left", "Slept", "Checked the answer", "Changed schools"],
        answer: 2,
        explanation: "文章说明全班随后检查了答案。"
      }
    ]
  };
  const wordBank = {
    schemaVersion: 1,
    generatedAt: new Date(0).toISOString(),
    words: {}
  };
  const minimax = {
    async json(_system, user) {
      const request = JSON.parse(user);
      return Object.fromEntries(request.words.flatMap(({ word, contexts }) => {
        if (word === "spend") return [];
        const returnedContexts = Object.fromEntries(contexts.map(({ id, sentence }) => [
          id,
          {
            translation: word === "figure"
              ? (sentence.startsWith("We figure") ? "认为" : "数字")
              : `测试义-${word}`,
            pos: word === "figure"
              ? (sentence.startsWith("We figure") ? "v." : "n.")
              : "词性"
          }
        ]));
        return [[word, {
          lemma: word === "spending" ? "spend" : word,
          translation: returnedContexts.s0.translation,
          pos: returnedContexts.s0.pos,
          forms: "无常见变形",
          meanings: "含义一；含义二；含义三",
          contexts: returnedContexts
        }]];
      }));
    }
  };

  assert.ok(findIncompleteWords(article).includes("figure"));
  assert.deepEqual(findArticleWordContexts(article, "spending"), [sentences[10]]);
  assert.equal(isCompleteGlossaryEntry(
    article.glossary.spending,
    findArticleWordContexts(article, "spending")
  ), true);
  await repairArticleGlossary(article, minimax, wordBank);
  assert.deepEqual(findIncompleteWords(article), []);
  assert.deepEqual(findWordSentences(article.body, "figure"), sentences.slice(0, 2));
  assert.equal(article.glossary.figure.contexts[sentences[0]].translation, "数字");
  assert.equal(article.glossary.figure.contexts[sentences[1]].translation, "认为");
  assert.equal(article.glossary.figure.contexts["A figure"].translation, "数字");
  assert.ok(findArticleWordContexts(article, "figure").includes("A figure"));
  assert.equal(article.glossary.spend.lemma, "spend");
  assert.equal(article.glossary.spend.contexts["They spend time"].pos, "v.");
  assert.deepEqual(validateArticle(article), []);

  mergeArticleIntoWordBank(article, wordBank);
  mergeArticleIntoWordBank(article, wordBank);
  assert.equal(wordBank.words.figure.senses[0].translation, "数字");
  assert.equal(wordBank.words.figure.senses[1].translation, "认为");
  assert.equal(wordBank.words.figure.articleCount, 1);
  assert.deepEqual(validateWordBank(wordBank), []);
});
