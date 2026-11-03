package com.gosen.reader;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioGroup;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MainActivityTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private SharedPreferences prefs;

    @Before public void setup() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("reader", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        start();
    }

    private void start() {
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();
        activity = controller.get();
    }

    @After public void close() { if (controller != null) controller.pause().stop().destroy(); }

    private Object field(String name) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity);
    }

    private void page(String name, JSONObject article) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, JSONObject.class);
        method.setAccessible(true); method.invoke(activity, article);
    }

    private JSONObject article(int index) throws Exception { return ((ContentRepository) field("repository")).get(index); }

    private void submit(JSONObject article) throws Exception {
        int[] answers = new int[article.getJSONArray("questions").length()];
        for (int i = 0; i < answers.length; i++) answers[i] = article.getJSONArray("questions").getJSONObject(i).getInt("answer");
        Method m = MainActivity.class.getDeclaredMethod("markTodayComplete", JSONObject.class, int[].class);
        m.setAccessible(true); m.invoke(activity, article, answers);
    }

    private List<RadioGroup> groups() {
        List<RadioGroup> groups = new ArrayList<>(); collect(activity.findViewById(android.R.id.content), groups); return groups;
    }

    private void collect(View view, List<RadioGroup> found) {
        if (view instanceof RadioGroup) found.add((RadioGroup) view);
        else if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), found);
    }

    @Test public void returningToPassagePreservesSelectionAndLookupCount() throws Exception {
        JSONObject a = article(0);
        page("showReader", a);
        ReadingSession session = (ReadingSession) field("session"); session.clicks = 5;
        page("showQuiz", a);
        RadioGroup group = groups().get(0);
        group.check(group.getChildAt(2).getId());
        page("showReader", a);
        page("showQuiz", a);
        assertEquals(groups().get(0).getChildAt(2).getId(), groups().get(0).getCheckedRadioButtonId());
        assertEquals(5, ((ReadingSession) field("session")).clicks);
    }

    @Test public void reviewContinuesBeyondDailyLimitAndIncludesFutureWords() throws Exception {
        LearningStore store = (LearningStore) field("learningStore");
        String key = store.recordWord("school", "school", "学校", "n.", "schools", "学校",
                "The school is open.", "学校开门了。", "test", "Test", 1);
        store.answer(key, LearningStore.VAGUE);
        prefs.edit().putString("reviewDate", java.time.LocalDate.now().toString())
                .putInt("reviewCountToday", 99).commit();
        Method review = MainActivity.class.getDeclaredMethod("showReview");
        review.setAccessible(true);
        review.invoke(activity);
        assertTrue(containsText(activity.findViewById(android.R.id.content), "school"));
    }

    private boolean containsText(View view, String value) {
        if (view instanceof android.widget.TextView
                && value.contentEquals(((android.widget.TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
                if (containsText(((ViewGroup) view).getChildAt(i), value)) return true;
            }
        }
        return false;
    }

    @Test public void wordAlwaysShowsBubbleAndOnlyBubbleExpands() throws Exception {
        JSONObject a = article(0);
        Method clickable = MainActivity.class.getDeclaredMethod("makeClickableText",
                JSONObject.class, String.class, String.class);
        clickable.setAccessible(true);
        android.text.SpannableString text = (android.text.SpannableString)
                clickable.invoke(activity, a, "school", "school");
        android.widget.TextView anchor = new android.widget.TextView(activity);
        anchor.setText(text);
        ((android.widget.FrameLayout) field("content")).addView(anchor);
        anchor.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        anchor.layout(0, 0, 500, 100);
        android.text.style.ClickableSpan span = text.getSpans(0, text.length(),
                android.text.style.ClickableSpan.class)[0];
        span.onClick(anchor);
        assertTrue(((android.widget.PopupWindow) field("wordBubble")).isShowing());
        assertTrue(prefs.getBoolean("lookedUp:" + a.getString("id") + ":school", false));
        assertEquals(1, prefs.getInt("click:school", 0));
        span.onClick(anchor);
        assertTrue(((android.widget.PopupWindow) field("wordBubble")).isShowing());
        assertEquals(2, prefs.getInt("click:school", 0));
        ((android.widget.PopupWindow) field("wordBubble")).dismiss();
        span.onClick(anchor);
        android.widget.PopupWindow bubble = (android.widget.PopupWindow) field("wordBubble");
        assertTrue(bubble.isShowing());
        bubble.getContentView().performClick();
        assertFalse(bubble.isShowing());
        assertEquals(1, text.getSpans(0, text.length(), MainActivity.LookupMark.class).length);
    }

    @Test public void activityRecreationRestoresQuizAndAnswers() throws Exception {
        JSONObject a = article(0); page("showQuiz", a);
        RadioGroup group = groups().get(0); group.check(group.getChildAt(1).getId());
        controller.pause().stop().destroy(); controller = null;
        start();
        assertEquals("quiz", ((ReadingSession) field("session")).page);
        assertEquals(groups().get(0).getChildAt(1).getId(), groups().get(0).getCheckedRadioButtonId());
    }

    @Test public void legacyRecallDraftResumesDirectlyInQuiz() throws Exception {
        page("showReader", article(0));
        ((ReadingSession) field("session")).page = "recall";
        controller.pause().stop().destroy();
        controller = null;
        start();
        assertEquals("quiz", ((ReadingSession) field("session")).page);
        assertFalse(containsText(activity.findViewById(android.R.id.content), "60 秒主动回忆"));
    }

    @Test public void backgroundTimeIsExcludedFromReading() throws Exception {
        page("showReader", article(0));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20));
        controller.pause();
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10));
        controller.resume();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));
        page("showQuiz", article(0));
        ReadingSession s = (ReadingSession) field("session");
        assertEquals(30, s.seconds(android.os.SystemClock.elapsedRealtime()));
    }

    @Test public void sameDayArticlesCountSeparatelyAndRepeatsDoNotChangeAbility() throws Exception {
        JSONObject first = article(0), second = article(1);
        page("showReader", first); submit(first);
        page("showReader", second); submit(second);
        assertEquals(2, prefs.getInt("totalArticles", 0));
        assertEquals(first.getInt("wordCount") + second.getInt("wordCount"), prefs.getInt("totalWords", 0));
        assertEquals(1, prefs.getStringSet("completedDates", new HashSet<>()).size());
        float before = prefs.getFloat("abilityScore", -1);
        page("showReader", first); submit(first);
        assertEquals(before, prefs.getFloat("abilityScore", -1), 0f);
        assertEquals(2, prefs.getInt("totalArticles", 0));
        assertEquals(1, prefs.getInt("repeatAttempts", 0));
    }

    @Test public void migratesUndercountedLegacyTotalsWithoutRemovingHistory() throws Exception {
        JSONObject first = article(0), second = article(1);
        HashSet<String> ids = new HashSet<>(); ids.add(first.getString("id")); ids.add(second.getString("id"));
        prefs.edit().remove("articleTotalsV2").putStringSet("completedIds", ids)
                .putInt("totalArticles", 1).putInt("totalWords", first.getInt("wordCount"))
                .putInt("click:example", 9).commit();
        Method m = MainActivity.class.getDeclaredMethod("migrateArticleTotals"); m.setAccessible(true); m.invoke(activity);
        assertEquals(2, prefs.getInt("totalArticles", 0));
        assertEquals(first.getInt("wordCount") + second.getInt("wordCount"), prefs.getInt("totalWords", 0));
        assertEquals(9, prefs.getInt("click:example", 0));
    }
}
