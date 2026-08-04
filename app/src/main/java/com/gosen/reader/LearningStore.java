package com.gosen.reader;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class LearningStore {
    static final int FORGOT = 0;
    static final int VAGUE = 1;
    static final int REMEMBERED = 2;

    private static final String KEY = "reviewPool";
    private final SharedPreferences prefs;

    LearningStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    String recordWord(String lemma, String displayWord, String translation, String pos,
                      String forms, String meanings, String sentence,
                      String sentenceTranslation, String articleId, String articleTitle,
                      int clickCount) {
        String normalized = normalize(lemma);
        String senseKey = senseKey(normalized, pos, translation);
        try {
            JSONObject pool = load();
            JSONObject item = pool.optJSONObject(senseKey);

            // Version 1 stored one record per lemma. Move that record into the
            // first concrete sense encountered so existing learning data survives.
            if (item == null) {
                JSONObject legacy = pool.optJSONObject(normalized);
                if (legacy != null && legacy.optString("senseKey").isEmpty()) {
                    item = legacy;
                    pool.remove(normalized);
                }
            }
            if (item == null) {
                JSONArray names = pool.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String candidateKey = names.optString(i);
                        JSONObject candidate = pool.optJSONObject(candidateKey);
                        if (candidate == null
                                || !normalized.equals(normalize(candidate.optString("lemma")))
                                || !normalize(pos).equals(normalize(candidate.optString("pos")))) {
                            continue;
                        }
                        JSONArray candidateContexts = candidate.optJSONArray("contexts");
                        if (candidateContexts == null) continue;
                        for (int contextIndex = 0;
                             contextIndex < candidateContexts.length(); contextIndex++) {
                            JSONObject context = candidateContexts.optJSONObject(contextIndex);
                            if (context != null && sentence.equals(context.optString("sentence"))) {
                                senseKey = candidateKey;
                                item = candidate;
                                break;
                            }
                        }
                        if (item != null) break;
                    }
                }
            }
            if (item == null) {
                item = new JSONObject();
                item.put("createdDate", LocalDate.now().toString());
                item.put("dueDate", LocalDate.now().plusDays(1).toString());
                item.put("intervalDays", 1);
                item.put("reviewCount", 0);
                item.put("correctCount", 0);
                item.put("consecutiveCorrect", 0);
                item.put("masteryScore", 10);
                item.put("status", "new");
                item.put("contexts", new JSONArray());
            } else if ("mastered".equals(item.optString("status"))) {
                // Looking up a mastered sense is evidence that it needs consolidation.
                item.put("status", "consolidating");
                item.put("dueDate", LocalDate.now().plusDays(1).toString());
            }

            item.put("senseKey", senseKey);
            item.put("lemma", normalized);
            item.put("displayWord", displayWord);
            item.put("translation", translation);
            item.put("pos", pos);
            item.put("forms", forms);
            item.put("meanings", meanings);
            item.put("sentence", sentence);
            item.put("sentenceTranslation", sentenceTranslation);
            item.put("articleId", articleId);
            item.put("articleTitle", articleTitle);
            item.put("clickCount", clickCount);
            item.put("lastSeenDate", LocalDate.now().toString());

            JSONArray contexts = item.optJSONArray("contexts");
            if (contexts == null) contexts = new JSONArray();
            boolean exists = false;
            for (int i = 0; i < contexts.length(); i++) {
                JSONObject context = contexts.optJSONObject(i);
                if (context != null && sentence.equals(context.optString("sentence"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                JSONObject context = new JSONObject();
                context.put("sentence", sentence);
                context.put("translation", sentenceTranslation);
                context.put("meaning", translation);
                context.put("articleId", articleId);
                context.put("date", LocalDate.now().toString());
                contexts.put(context);
                if (contexts.length() > 5) {
                    JSONArray trimmed = new JSONArray();
                    for (int i = contexts.length() - 5; i < contexts.length(); i++) {
                        trimmed.put(contexts.optJSONObject(i));
                    }
                    contexts = trimmed;
                }
            }
            item.put("contexts", contexts);
            item.put("contextCount", contexts.length());
            pool.put(senseKey, item);
            save(pool);
        } catch (Exception ignored) {
            // A damaged learning record must never interrupt reading.
        }
        return senseKey;
    }

    List<JSONObject> dueWords(int limit) {
        List<JSONObject> result = new ArrayList<>();
        JSONObject pool = load();
        LocalDate today = LocalDate.now();
        JSONArray names = pool.names();
        if (names == null) return result;
        for (int i = 0; i < names.length(); i++) {
            JSONObject item = pool.optJSONObject(names.optString(i));
            if (item == null || "mastered".equals(item.optString("status"))) continue;
            try {
                LocalDate due = LocalDate.parse(item.optString("dueDate", today.toString()));
                if (!due.isAfter(today)) result.add(item);
            } catch (Exception ignored) {
                result.add(item);
            }
        }
        Collections.sort(result, (left, right) -> left.optString("dueDate")
                .compareTo(right.optString("dueDate")));
        return result.subList(0, Math.min(limit, result.size()));
    }

    List<JSONObject> allWords(int limit) {
        List<JSONObject> result = new ArrayList<>();
        JSONObject pool = load();
        JSONArray names = pool.names();
        if (names == null) return result;
        for (int i = 0; i < names.length(); i++) {
            JSONObject item = pool.optJSONObject(names.optString(i));
            if (item != null) result.add(item);
        }
        Collections.sort(result, (left, right) -> right.optString("lastSeenDate")
                .compareTo(left.optString("lastSeenDate")));
        return result.subList(0, Math.min(Math.max(0, limit), result.size()));
    }

    void answer(String senseKey, int result) {
        try {
            JSONObject pool = load();
            JSONObject item = pool.optJSONObject(senseKey);
            if (item == null) return;
            int interval = Math.max(1, item.optInt("intervalDays", 1));
            int consecutive = item.optInt("consecutiveCorrect", 0);
            int mastery = item.optInt("masteryScore", 10);
            int correct = item.optInt("correctCount", 0);

            if (result == FORGOT) {
                interval = 1;
                consecutive = 0;
                mastery = Math.max(0, mastery - 20);
            } else if (result == VAGUE) {
                interval = Math.max(2, Math.min(3, interval));
                consecutive = 0;
                mastery = Math.min(100, mastery + 5);
            } else {
                correct++;
                consecutive++;
                mastery = Math.min(100, mastery + 15);
                if (interval <= 1) interval = 3;
                else if (interval <= 3) interval = 7;
                else if (interval <= 7) interval = 14;
                else if (interval <= 14) interval = 30;
                else interval = 45;
            }

            int contexts = item.optInt("contextCount",
                    item.optJSONArray("contexts") == null ? 0 : item.optJSONArray("contexts").length());
            String status;
            if (contexts >= 3 && consecutive >= 3 && interval >= 14) status = "mastered";
            else if (consecutive >= 2 || mastery >= 50) status = "consolidating";
            else status = "learning";

            item.put("intervalDays", interval);
            item.put("consecutiveCorrect", consecutive);
            item.put("masteryScore", mastery);
            item.put("correctCount", correct);
            item.put("reviewCount", item.optInt("reviewCount", 0) + 1);
            item.put("status", status);
            item.put("lastReviewedDate", LocalDate.now().toString());
            item.put("dueDate", LocalDate.now().plusDays(interval).toString());
            pool.put(senseKey, item);
            save(pool);
        } catch (Exception ignored) { }
    }

    JSONObject reviewContext(JSONObject item) {
        JSONArray contexts = item == null ? null : item.optJSONArray("contexts");
        if (contexts != null && contexts.length() > 0) {
            int index = Math.floorMod(item.optInt("reviewCount", 0), contexts.length());
            JSONObject context = contexts.optJSONObject(index);
            if (context != null) return context;
        }
        JSONObject fallback = new JSONObject();
        try {
            fallback.put("sentence", item == null ? "" : item.optString("sentence"));
            fallback.put("translation", item == null ? "" : item.optString("sentenceTranslation"));
            fallback.put("meaning", item == null ? "" : item.optString("translation"));
        } catch (Exception ignored) { }
        return fallback;
    }

    void correctMeaning(String senseKey, String correctedTranslation, String sentence) {
        String corrected = correctedTranslation == null ? "" : correctedTranslation.trim();
        if (corrected.isEmpty()) return;
        try {
            JSONObject pool = load();
            JSONObject item = pool.optJSONObject(senseKey);
            if (item == null) return;
            item.put("translation", corrected);
            JSONArray contexts = item.optJSONArray("contexts");
            if (contexts != null) {
                for (int i = 0; i < contexts.length(); i++) {
                    JSONObject context = contexts.optJSONObject(i);
                    if (context != null && sentence.equals(context.optString("sentence"))) {
                        context.put("meaning", corrected);
                    }
                }
            }
            pool.put(senseKey, item);
            save(pool);
        } catch (Exception ignored) { }
    }

    void removeWord(String senseKey) {
        try {
            JSONObject pool = load();
            pool.remove(senseKey);
            save(pool);
        } catch (Exception ignored) { }
    }

    int dueCount() {
        return dueWords(Integer.MAX_VALUE).size();
    }

    int totalCount() {
        return load().length();
    }

    int statusCount(String status) {
        int count = 0;
        JSONObject pool = load();
        JSONArray names = pool.names();
        if (names == null) return 0;
        for (int i = 0; i < names.length(); i++) {
            JSONObject item = pool.optJSONObject(names.optString(i));
            if (item != null && status.equals(normalizedStatus(item))) count++;
        }
        return count;
    }

    int learningWordCount(JSONObject article) {
        Set<String> learningLemmas = new HashSet<>();
        JSONObject pool = load();
        JSONArray names = pool.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                JSONObject item = pool.optJSONObject(names.optString(i));
                if (item != null && !"mastered".equals(normalizedStatus(item))) {
                    learningLemmas.add(normalize(item.optString("lemma")));
                }
            }
        }
        if (learningLemmas.isEmpty()) return 0;
        Set<String> matched = new HashSet<>();
        JSONObject glossary = article.optJSONObject("glossary");
        JSONArray words = glossary == null ? null : glossary.names();
        if (words == null) return 0;
        for (int i = 0; i < words.length(); i++) {
            JSONObject entry = glossary.optJSONObject(words.optString(i));
            if (entry == null) continue;
            String lemma = normalize(entry.optString("lemma", words.optString(i)));
            if (learningLemmas.contains(lemma)) matched.add(lemma);
        }
        return matched.size();
    }

    String statusLabel(JSONObject item) {
        switch (normalizedStatus(item)) {
            case "mastered": return "已掌握";
            case "consolidating": return "巩固中";
            case "learning": return "学习中";
            default: return "新词";
        }
    }

    private String normalizedStatus(JSONObject item) {
        String status = item.optString("status");
        if (!status.isEmpty()) return status;
        return item.optInt("stage", 0) >= 3 ? "consolidating" : "learning";
    }

    private String senseKey(String lemma, String pos, String translation) {
        return lemma + "|" + normalize(pos) + "|" + normalize(translation);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private JSONObject load() {
        try {
            return new JSONObject(prefs.getString(KEY, "{}"));
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private void save(JSONObject pool) {
        prefs.edit().putString(KEY, pool.toString()).apply();
    }
}
