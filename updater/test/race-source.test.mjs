import assert from "node:assert/strict";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { loadRaceCandidates, normalizeRaceRecord } from "../src/race-source.mjs";

const sample = {
  id: "high123.txt",
  article: "This is a complete high-school English reading passage. ".repeat(14),
  questions: ["What is the passage mainly about?", "What can we infer?"],
  options: [
    ["Choice A", "Choice B", "Choice C", "Choice D"],
    ["First", "Second", "Third", "Fourth"]
  ],
  answers: ["B", "D"]
};

test("normalizes a structured RACE record without asking AI to reconstruct it", () => {
  const candidate = normalizeRaceRecord(sample);
  assert.equal(candidate.structured.body, sample.article.trim());
  assert.equal(candidate.structured.questions[0].answer, 1);
  assert.equal(candidate.structured.questions[1].answer, 3);
  assert.equal(candidate.url, "https://www.cs.cmu.edu/~glai1/data/race/");
});

test("selects high-school records deterministically for a monthly seed", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "gosen-race-"));
  const high = path.join(root, "train", "high");
  await fs.mkdir(high, { recursive: true });
  await Promise.all(["1.txt", "2.txt", "3.txt"].map((name, index) =>
    fs.writeFile(path.join(high, name), JSON.stringify({
      ...sample,
      id: `high${index + 1}.txt`
    }), "utf8")
  ));
  try {
    const first = await loadRaceCandidates(root, 2, "2026-07");
    const second = await loadRaceCandidates(root, 2, "2026-07");
    assert.deepEqual(first, second);
    assert.equal(first.length, 2);
  } finally {
    await fs.rm(root, { recursive: true, force: true });
  }
});
