const WORD_PATTERN = /[A-Za-z]+(?:['’][A-Za-z]+)*/g;

export const DIFFICULTY_LABELS = ["基础", "高考标准", "较难"];

export function assessDifficulty(body, questions = []) {
  const text = String(body || "");
  const wordCount = text.match(WORD_PATTERN)?.length || 0;
  const sentenceLengths = (text.replace(/\n+/g, " ").match(/[^.!?]+[.!?]/g) || [])
    .map(sentence => sentence.match(WORD_PATTERN)?.length || 0)
    .filter(Boolean);
  const averageSentenceWords = sentenceLengths.length
    ? sentenceLengths.reduce((sum, length) => sum + length, 0) / sentenceLengths.length
    : wordCount;
  const longSentenceRatio = sentenceLengths.length
    ? sentenceLengths.filter(length => length >= 28).length / sentenceLengths.length
    : 0;
  const challengingQuestions = questions.filter(question =>
    /infer|imply|suggest|attitude|purpose|main idea|best title|structure|except|not true/i
      .test(String(question?.prompt || ""))
  ).length;
  const challengingQuestionRatio = questions.length
    ? challengingQuestions / questions.length
    : 0;

  const score = Math.round(
    clamp((wordCount - 180) / 220) * 45
    + clamp((averageSentenceWords - 12) / 16) * 30
    + clamp(longSentenceRatio / 0.4) * 20
    + challengingQuestionRatio * 5
  );
  const level = score < 28 ? 0 : score < 55 ? 1 : 2;
  return {
    level,
    label: DIFFICULTY_LABELS[level],
    score,
    metrics: {
      wordCount,
      sentenceCount: sentenceLengths.length,
      averageSentenceWords: roundOne(averageSentenceWords),
      longSentenceRatio: roundThree(longSentenceRatio),
      challengingQuestionRatio: roundThree(challengingQuestionRatio)
    }
  };
}

export function balanceCandidatesByDifficulty(candidates, limit) {
  const buckets = [[], [], []];
  for (const candidate of candidates) {
    const level = Number(candidate?.structured?.difficultyLevel);
    buckets[Number.isInteger(level) && level >= 0 && level <= 2 ? level : 1]
      .push(candidate);
  }
  const weights = [0.27, 0.53, 0.20];
  const used = [0, 0, 0];
  const result = [];
  const target = Math.min(Math.max(0, Number(limit) || 0), candidates.length);
  while (result.length < target) {
    let selected = -1;
    let strongestDeficit = -Infinity;
    for (let level = 0; level < buckets.length; level += 1) {
      if (used[level] >= buckets[level].length) continue;
      const deficit = weights[level] * (result.length + 1) - used[level];
      if (deficit > strongestDeficit) {
        strongestDeficit = deficit;
        selected = level;
      }
    }
    if (selected < 0) break;
    result.push(buckets[selected][used[selected]]);
    used[selected] += 1;
  }
  return result;
}

const clamp = value => Math.max(0, Math.min(1, Number(value) || 0));
const roundOne = value => Math.round(value * 10) / 10;
const roundThree = value => Math.round(value * 1000) / 1000;
