package com.gosen.reader;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class LearningStore {
    private static final String KEY = "reviewPool";
    private static final int[] INTERVALS = {1, 3, 7, 14, 30};

    private final SharedPreferences prefs;

    LearningStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    void recordWord(String lemma, String displayWord, String translation, String pos,
                    String forms, String meanings, String sentence,
                    String sentenceTranslation, String articleId, String articleTitle,
                    int clickCount) {
        try {
            JSONObject pool = load();
            String normalized = lemma.trim().toLowerCase();
            JSONObject item = pool.optJSONObject(normalized);
            if (item == null) {
                item = new JSONObject();
                item.put("lemma", normalized);
                item.put("stage", 0);
                item.put("dueDate", LocalDate.now().plusDays(1).toString());
                item.put("createdDate", LocalDate.now().toString());
            }
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

            JSONArray contexts = item.optJSONArray("contexts");
            if (contexts == null) contexts = new JSONArray();
            boolean exists = false;
            for (int i = 0; i < contexts.length(); i++) {
                if (sentence.equals(contexts.optJSONObject(i).optString("sentence"))) {
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
                contexts.put(context);
            }
            item.put("contexts", contexts);
            pool.put(normalized, item);
            save(pool);
        } catch (Exception ignored) {
            // A damaged review record must never interrupt reading.
        }
    }

    List<JSONObject> dueWords(int limit) {
        List<JSONObject> result = new ArrayList<>();
        JSONObject pool = load();
        LocalDate today = LocalDate.now();
        JSONArray names = pool.names();
        if (names == null) return result;
        for (int i = 0; i < names.length() && result.size() < limit; i++) {
            JSONObject item = pool.optJSONObject(names.optString(i));
            if (item == null) continue;
            try {
                LocalDate due = LocalDate.parse(item.optString("dueDate", today.toString()));
                if (!due.isAfter(today)) result.add(item);
            } catch (Exception ignored) {
                result.add(item);
            }
        }
        return result;
    }

    int dueCount() {
        return dueWords(Integer.MAX_VALUE).size();
    }

    int totalCount() {
        return load().length();
    }

    void answer(String lemma, boolean remembered) {
        try {
            JSONObject pool = load();
            JSONObject item = pool.optJSONObject(lemma.toLowerCase());
            if (item == null) return;
            int stage = item.optInt("stage", 0);
            if (remembered) stage = Math.min(stage + 1, INTERVALS.length - 1);
            else stage = 0;
            item.put("stage", stage);
            item.put("dueDate", LocalDate.now().plusDays(INTERVALS[stage]).toString());
            item.put("lastReviewedDate", LocalDate.now().toString());
            pool.put(lemma.toLowerCase(), item);
            save(pool);
        } catch (Exception ignored) { }
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
