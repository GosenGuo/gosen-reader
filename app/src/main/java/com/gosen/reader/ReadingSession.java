package com.gosen.reader;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Arrays;

/** One unfinished attempt. Only foreground time on the passage counts as reading. */
final class ReadingSession {
    final String articleId;
    String page = "reader";
    String recall = "";
    int readerScroll;
    int quizScroll;
    int clicks;
    int[] answers;
    private long readingMillis;
    private long runningSince = -1;

    ReadingSession(String articleId, int questionCount) {
        this.articleId = articleId;
        answers = new int[questionCount];
        Arrays.fill(answers, -1);
    }

    void resume(long now) {
        if ("reader".equals(page) && runningSince < 0) runningSince = now;
    }

    void pause(long now) {
        if (runningSince >= 0) readingMillis += Math.max(0, now - runningSince);
        runningSince = -1;
    }

    long seconds(long now) {
        long elapsed = readingMillis + (runningSince < 0 ? 0 : Math.max(0, now - runningSince));
        return Math.max(1, elapsed / 1000);
    }

    JSONObject snapshot(long now) throws JSONException {
        JSONObject value = new JSONObject();
        value.put("articleId", articleId);
        value.put("page", page);
        value.put("recall", recall);
        value.put("readerScroll", readerScroll);
        value.put("quizScroll", quizScroll);
        value.put("clicks", clicks);
        value.put("readingMillis", readingMillis
                + (runningSince < 0 ? 0 : Math.max(0, now - runningSince)));
        JSONArray selected = new JSONArray();
        for (int answer : answers) selected.put(answer);
        value.put("answers", selected);
        return value;
    }

    static ReadingSession restore(String articleId, int questionCount, String saved) {
        ReadingSession session = new ReadingSession(articleId, questionCount);
        try {
            JSONObject value = new JSONObject(saved);
            if (!articleId.equals(value.optString("articleId"))) return session;
            String page = value.optString("page", "reader");
            if ("reader".equals(page) || "recall".equals(page) || "quiz".equals(page)) session.page = page;
            session.recall = value.optString("recall");
            session.readerScroll = Math.max(0, value.optInt("readerScroll"));
            session.quizScroll = Math.max(0, value.optInt("quizScroll"));
            session.clicks = Math.max(0, value.optInt("clicks"));
            session.readingMillis = Math.max(0, value.optLong("readingMillis"));
            JSONArray selected = value.optJSONArray("answers");
            if (selected != null) for (int i = 0; i < session.answers.length; i++) {
                int answer = selected.optInt(i, -1);
                session.answers[i] = answer >= 0 && answer < 4 ? answer : -1;
            }
        } catch (Exception ignored) { /* A damaged draft must not prevent reading. */ }
        return session;
    }
}
