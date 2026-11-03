package com.gosen.reader;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class LearningStoreTest {
    private SharedPreferences prefs;

    @Before public void setup() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("merge-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
    }

    private JSONObject legacy(String meaning, String pos, String sentence, int clicks, int reviews,
                               String due) throws Exception {
        return new JSONObject().put("lemma", "figure").put("translation", meaning).put("pos", pos)
                .put("senseKey", "figure|" + pos + "|" + meaning).put("displayWord", "figures")
                .put("clickCount", clicks).put("reviewCount", reviews).put("correctCount", reviews)
                .put("intervalDays", reviews + 1).put("status", "learning").put("dueDate", due)
                .put("forms", "figures; figured; figuring").put("meanings", meaning)
                .put("sentence", sentence).put("sentenceTranslation", "整句翻译")
                .put("contexts", new JSONArray().put(new JSONObject().put("sentence", sentence)
                        .put("translation", "整句翻译").put("meaning", meaning)));
    }

    @Test public void migratesAllSensesWithoutDoubleCountingLifetimeClicks() throws Exception {
        JSONObject old = new JSONObject()
                .put("figure|n.|数字", legacy("数字", "n.", "Check the figures.", 7, 2, "2026-11-10"))
                .put("figure|v.|认为", legacy("认为", "v.", "We figure it works.", 9, 3, "2026-11-05"));
        prefs.edit().putString("reviewPool", old.toString()).commit();
        LearningStore store = new LearningStore(prefs);
        assertEquals(1, store.totalCount());
        JSONObject word = store.allWords(10).get(0);
        assertEquals("figure", word.getString("senseKey"));
        assertEquals("figure", word.getString("displayWord"));
        assertEquals(2, word.getJSONArray("senses").length());
        assertEquals(2, word.getJSONArray("contexts").length());
        assertTrue(word.getString("translation").contains("数字"));
        assertTrue(word.getString("translation").contains("认为"));
        assertEquals(9, word.getInt("clickCount"));
        assertEquals(5, word.getInt("reviewCount"));
        assertEquals("2026-11-05", word.getString("dueDate"));
        assertEquals(old.toString(), prefs.getString("reviewPoolBeforeLemmaMerge", ""));
        String merged = prefs.getString("reviewPool", "");
        assertEquals(1, new LearningStore(prefs).totalCount());
        assertEquals(merged, prefs.getString("reviewPool", ""));
        store.recordWord("figure", "figure", "身材", "n.", "figures", "身材",
                "She has a slim figure.", "她身材苗条。", "a", "Test", 10);
        assertEquals(1, store.totalCount());
        assertEquals(3, store.allWords(10).get(0).getJSONArray("senses").length());
        store.removeWord("figure");
        assertEquals(0, store.totalCount());
        assertFalse(store.containsLemma("figure"));
    }

    @Test public void newMeaningsAndCorrectionsStayInOneWordWithAllExamples() throws Exception {
        LearningStore store = new LearningStore(prefs);
        for (int i = 0; i < 8; i++) {
            store.recordWord("figure", "figures", i % 2 == 0 ? "数字" : "认为",
                    i % 2 == 0 ? "n." : "v.", "figures", "数字；认为",
                    "Example " + i + ".", "例句" + i, "a", "Test", i + 1);
        }
        assertEquals(1, store.totalCount());
        JSONObject word = store.allWords(10).get(0);
        assertEquals(2, word.getJSONArray("senses").length());
        assertEquals(8, word.getJSONArray("contexts").length());
        store.correctMeaning("figure", "数值", "Example 0.");
        word = store.allWords(10).get(0);
        assertTrue(word.getString("translation").contains("数值"));
        assertTrue(word.getString("translation").contains("认为"));
        store.answer("figure", LearningStore.VAGUE);
        assertEquals(1, store.allWords(10).get(0).getInt("reviewCount"));
        assertEquals("Example 1.", store.reviewContext(word).getString("sentence"));
    }
}
