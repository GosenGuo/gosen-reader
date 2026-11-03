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
import java.util.Map;
import java.util.WeakHashMap;
import java.util.LinkedHashSet;

final class LearningStore {
    static final int FORGOT = 0;
    static final int VAGUE = 1;
    static final int REMEMBERED = 2;

    private static final String KEY = "reviewPool";
    private final SharedPreferences prefs;
    private String cachedRaw;
    private JSONObject cachedPool;
    private String indexedRaw;
    private Set<String> learningLemmas = Collections.emptySet();
    private Set<String> vocabularyLemmas = Collections.emptySet();
    private final Map<JSONObject, Set<String>> articleLemmas = new WeakHashMap<>();

    LearningStore(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    String recordWord(String lemma, String displayWord, String translation, String pos,
                      String forms, String meanings, String sentence,
                      String sentenceTranslation, String articleId, String articleTitle,
                      int clickCount) {
        String normalized = normalize(lemma);
        String senseKey = normalized;
        try {
            JSONObject pool = load();
            JSONObject item = pool.optJSONObject(senseKey);

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
            item.put("wordSchema", 2);
            item.put("lemma", normalized);
            item.put("displayWord", normalized);
            addSense(item, translation, pos);
            item.put("forms", joinText(item.optString("forms"), forms));
            item.put("meanings", joinText(item.optString("meanings"), meanings));
            item.put("sentence", sentence);
            item.put("sentenceTranslation", sentenceTranslation);
            item.put("articleId", articleId);
            item.put("articleTitle", articleTitle);
            item.put("clickCount", Math.max(item.optInt("clickCount"), clickCount));
            item.put("lastSeenDate", LocalDate.now().toString());

            JSONArray contexts = item.optJSONArray("contexts");
            if (contexts == null) contexts = new JSONArray();
            boolean exists = false;
            for (int i = 0; i < contexts.length(); i++) {
                JSONObject context = contexts.optJSONObject(i);
                if (context != null && sentence.equals(context.optString("sentence"))
                        && translation.equals(context.optString("meaning"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                JSONObject context = new JSONObject();
                context.put("sentence", sentence);
                context.put("translation", sentenceTranslation);
                context.put("meaning", translation);
                context.put("pos", pos);
                context.put("articleId", articleId);
                context.put("date", LocalDate.now().toString());
                contexts.put(context);
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
            Set<String> previous = new HashSet<>();
            JSONArray contexts = item.optJSONArray("contexts");
            if (contexts != null) {
                for (int i = 0; i < contexts.length(); i++) {
                    JSONObject context = contexts.optJSONObject(i);
                    if (context != null && sentence.equals(context.optString("sentence"))) {
                        previous.add(context.optString("meaning"));
                        context.put("meaning", corrected);
                    }
                }
            }
            JSONArray senses = item.optJSONArray("senses");
            boolean replaced = false;
            if (senses != null) for (int i = 0; i < senses.length(); i++) {
                JSONObject sense = senses.getJSONObject(i);
                if (previous.contains(sense.optString("translation"))) {
                    sense.put("translation", corrected);
                    replaced = true;
                }
            }
            addSense(item, replaced ? "" : corrected, replaced ? "" : item.optString("pos"));
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
        return countLearningWords(article, learningLemmas());
    }

    Set<String> learningLemmas() {
        JSONObject pool = load();
        if (cachedRaw.equals(indexedRaw)) return learningLemmas;
        Set<String> lemmas = new HashSet<>();
        Set<String> vocabulary = new HashSet<>();
        JSONArray names = pool.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                JSONObject item = pool.optJSONObject(names.optString(i));
                if (item != null) vocabulary.add(normalize(item.optString("lemma")));
                if (item != null && !"mastered".equals(normalizedStatus(item))) {
                    lemmas.add(normalize(item.optString("lemma")));
                }
            }
        }
        learningLemmas = Collections.unmodifiableSet(lemmas);
        vocabularyLemmas = Collections.unmodifiableSet(vocabulary);
        indexedRaw = cachedRaw;
        return learningLemmas;
    }

    Set<String> vocabularyLemmas() {
        learningLemmas();
        return vocabularyLemmas;
    }

    boolean containsLemma(String lemma) {
        return vocabularyLemmas().contains(normalize(lemma));
    }

    void removeLemma(String lemma) {
        JSONObject pool = load();
        JSONArray names = pool.names();
        if (names == null) return;
        String normalized = normalize(lemma);
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            JSONObject item = pool.optJSONObject(key);
            if (item != null && normalized.equals(normalize(item.optString("lemma")))) pool.remove(key);
        }
        save(pool);
    }

    synchronized int countLearningWords(JSONObject article, Set<String> learning) {
        if (learning.isEmpty()) return 0;
        Set<String> lemmas = articleLemmas.get(article);
        if (lemmas == null) {
            lemmas = new HashSet<>();
            JSONObject glossary = article.optJSONObject("glossary");
            JSONArray words = glossary == null ? null : glossary.names();
            if (words != null) for (int i = 0; i < words.length(); i++) {
                JSONObject entry = glossary.optJSONObject(words.optString(i));
                if (entry != null) lemmas.add(normalize(entry.optString("lemma", words.optString(i))));
            }
            articleLemmas.put(article, lemmas);
        }
        int count = 0;
        for (String lemma : learning) if (lemmas.contains(lemma)) count++;
        return count;
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private JSONObject load() {
        String raw = prefs.getString(KEY, "{}");
        if (cachedPool != null && raw.equals(cachedRaw)) return cachedPool;
        try {
            cachedPool = new JSONObject(raw);
        } catch (Exception ignored) {
            cachedPool = new JSONObject();
        }
        cachedRaw = raw;
        migrateDuplicateWords(raw);
        return cachedPool;
    }

    private String joinText(String first, String second) {
        Set<String> parts = new LinkedHashSet<>();
        for (String value : new String[]{first, second}) {
            for (String part : value.split("[；;]")) {
                if (!part.trim().isEmpty()) parts.add(part.trim());
            }
        }
        return String.join("；", parts);
    }

    private void addSense(JSONObject item, String translation, String pos) throws Exception {
        JSONArray senses = item.optJSONArray("senses");
        if (senses == null) senses = new JSONArray();
        boolean exists = false;
        for (int i = 0; i < senses.length(); i++) {
            JSONObject sense = senses.getJSONObject(i);
            if (translation.equals(sense.optString("translation"))
                    && pos.equals(sense.optString("pos"))) exists = true;
        }
        if (!exists && !translation.trim().isEmpty()) {
            senses.put(new JSONObject().put("translation", translation).put("pos", pos));
        }
        item.put("senses", senses);
        String translations = "", partsOfSpeech = "";
        for (int i = 0; i < senses.length(); i++) {
            JSONObject sense = senses.getJSONObject(i);
            translations = joinText(translations, sense.optString("translation"));
            partsOfSpeech = joinText(partsOfSpeech, sense.optString("pos"));
        }
        item.put("translation", translations);
        item.put("pos", partsOfSpeech);
    }

    private void migrateDuplicateWords(String original) {
        JSONArray keys = cachedPool.names();
        if (keys == null) return;
        boolean needsMigration = false;
        for (int i = 0; i < keys.length(); i++) {
            JSONObject item = cachedPool.optJSONObject(keys.optString(i));
            if (item != null && (item.optInt("wordSchema") != 2
                    || !keys.optString(i).equals(normalize(item.optString("lemma"))))) {
                needsMigration = true;
                break;
            }
        }
        if (!needsMigration) return;
        try {
            JSONObject merged = new JSONObject();
            for (int i = 0; i < keys.length(); i++) {
                JSONObject source = cachedPool.optJSONObject(keys.optString(i));
                if (source == null) continue;
                String lemma = normalize(source.optString("lemma", keys.optString(i).split("\\|")[0]));
                if (lemma.isEmpty()) lemma = keys.optString(i);
                JSONObject item = merged.optJSONObject(lemma);
                if (item == null) {
                    item = new JSONObject(source.toString());
                    item.put("senses", new JSONArray());
                    item.put("contexts", new JSONArray());
                } else {
                    // Review counts are per sense; clickCount is a shared lifetime count.
                    for (String field : new String[]{"reviewCount", "correctCount"}) {
                        item.put(field, item.optInt(field) + source.optInt(field));
                    }
                    item.put("clickCount", Math.max(item.optInt("clickCount"), source.optInt("clickCount")));
                    for (String field : new String[]{"intervalDays", "masteryScore", "consecutiveCorrect"}) {
                        item.put(field, Math.min(item.optInt(field), source.optInt(field)));
                    }
                    for (String field : new String[]{"createdDate", "dueDate"}) {
                        String value = source.optString(field);
                        if (!value.isEmpty() && (item.optString(field).isEmpty()
                                || value.compareTo(item.optString(field)) < 0)) item.put(field, value);
                    }
                    for (String field : new String[]{"lastSeenDate", "lastReviewedDate"}) {
                        if (source.optString(field).compareTo(item.optString(field)) > 0) {
                            item.put(field, source.optString(field));
                        }
                    }
                    if (!"mastered".equals(normalizedStatus(source))) item.put("status", "learning");
                }
                JSONArray senses = source.optJSONArray("senses");
                if (senses == null || senses.length() == 0) {
                    addSense(item, source.optString("translation"), source.optString("pos"));
                } else for (int j = 0; j < senses.length(); j++) {
                    JSONObject sense = senses.getJSONObject(j);
                    addSense(item, sense.optString("translation"), sense.optString("pos"));
                }
                item.put("forms", joinText(item.optString("forms"), source.optString("forms")));
                item.put("meanings", joinText(item.optString("meanings"), source.optString("meanings")));
                JSONArray contexts = item.getJSONArray("contexts");
                JSONArray incoming = source.optJSONArray("contexts");
                if (incoming == null || incoming.length() == 0) {
                    incoming = new JSONArray().put(new JSONObject()
                            .put("sentence", source.optString("sentence"))
                            .put("translation", source.optString("sentenceTranslation"))
                            .put("meaning", source.optString("translation"))
                            .put("pos", source.optString("pos")));
                }
                for (int j = 0; j < incoming.length(); j++) {
                    JSONObject context = incoming.getJSONObject(j);
                    boolean duplicate = false;
                    for (int k = 0; k < contexts.length(); k++) {
                        JSONObject existing = contexts.getJSONObject(k);
                        if (existing.optString("sentence").equals(context.optString("sentence"))
                                && existing.optString("meaning").equals(context.optString("meaning"))) duplicate = true;
                    }
                    if (!duplicate) contexts.put(context);
                }
                item.put("lemma", lemma).put("displayWord", lemma).put("senseKey", lemma)
                        .put("wordSchema", 2).put("contextCount", contexts.length());
                item.put("clickCount", Math.max(item.optInt("clickCount"), prefs.getInt("click:" + lemma, 0)));
                merged.put(lemma, item);
            }
            if (!prefs.contains("reviewPoolBeforeLemmaMerge")) {
                prefs.edit().putString("reviewPoolBeforeLemmaMerge", original).apply();
            }
            save(merged);
        } catch (Exception ignored) {
            // Leave the original pool intact if migration cannot be completed.
        }
    }

    private void save(JSONObject pool) {
        cachedRaw = pool.toString();
        cachedPool = pool;
        indexedRaw = null;
        prefs.edit().putString(KEY, cachedRaw).apply();
    }
}
