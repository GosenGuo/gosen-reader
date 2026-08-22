import fs from "node:fs/promises";
import path from "node:path";
import { validatePackage } from "./schema.mjs";
import { validateWordBank } from "./word-bank.mjs";

const input = path.resolve(process.argv[2] || "./dist/articles.json");
const payload = JSON.parse(await fs.readFile(input, "utf8"));
const errors = validatePackage(payload, {
  requireLearningMetadata: process.env.REQUIRE_COMPLETE_LEARNING_METADATA === "true",
  requireQuestionGlossary: process.env.REQUIRE_COMPLETE_QUESTION_GLOSSARY === "true"
});
const wordBankInput = process.argv[3] ? path.resolve(process.argv[3]) : null;
if (wordBankInput) {
  const wordBank = JSON.parse(await fs.readFile(wordBankInput, "utf8"));
  errors.push(...validateWordBank(wordBank));
}
if (errors.length) {
  console.error(errors.join("\n"));
  process.exitCode = 1;
} else {
  console.log(`Package is valid: ${payload.articles.length} article(s)`);
}
