import assert from "node:assert/strict";
import test from "node:test";
import {
  assessDifficulty,
  balanceCandidatesByDifficulty
} from "../src/difficulty.mjs";

test("assigns reproducible difficulty from passage and question features", () => {
  const basic = assessDifficulty("Short sentences help students read. ".repeat(12), [
    { prompt: "What happened?" }
  ]);
  const hard = assessDifficulty(
    ("Although the evidence appeared convincing, the researchers, who had reviewed "
      + "several competing explanations from earlier investigations, warned that the "
      + "conclusion remained uncertain because the unusually limited sample could not "
      + "represent the wider population with sufficient accuracy. ")
      .repeat(16),
    [{ prompt: "What can be inferred from the passage?" }]
  );
  assert.equal(basic.label, "基础");
  assert.equal(basic.level, 0);
  assert.equal(hard.label, "较难");
  assert.equal(hard.level, 2);
  assert.ok(hard.score > basic.score);
});

test("interleaves candidate levels instead of exhausting one level first", () => {
  const candidates = [];
  for (let level = 0; level < 3; level += 1) {
    for (let index = 0; index < 20; index += 1) {
      candidates.push({ id: `${level}-${index}`, structured: { difficultyLevel: level } });
    }
  }
  const selected = balanceCandidatesByDifficulty(candidates, 30);
  const counts = selected.reduce((result, candidate) => {
    const level = candidate.structured.difficultyLevel;
    result[level] = (result[level] || 0) + 1;
    return result;
  }, {});
  assert.deepEqual(counts, { 0: 8, 1: 16, 2: 6 });
  assert.ok(new Set(selected.slice(0, 6).map(value => value.structured.difficultyLevel)).size > 1);
});
