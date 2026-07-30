package com.gosen.reader;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ContentRepository {
    private static final String STORE_FILE = "articles.json";
    private static final String PREF_LAST_SUCCESS = "lastContentUpdateSuccess";
    private static final String PREF_LAST_ATTEMPT = "lastContentUpdateAttempt";
    private static final long SUCCESS_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final long RETRY_CHECK_INTERVAL_MS = 30L * 60 * 1000;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private JSONArray articles = new JSONArray();
    private String packageGeneratedAt = "";

    ContentRepository(Context context) {
        this.context = context.getApplicationContext();
        load();
    }

    int size() {
        return articles.length();
    }

    JSONObject get(int index) {
        return articles.optJSONObject(index);
    }

    JSONObject getById(String id) {
        for (int i = 0; i < articles.length(); i++) {
            JSONObject article = articles.optJSONObject(i);
            if (article != null && id.equals(article.optString("id"))) return article;
        }
        return null;
    }

    void checkForMonthlyUpdate(Runnable onUpdated) {
        String feed = BuildConfig.CONTENT_FEED_URL.trim();
        if (feed.isEmpty()) return;

        android.content.SharedPreferences preferences =
                context.getSharedPreferences("reader", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastSuccess = preferences.getLong(PREF_LAST_SUCCESS, 0);
        long lastAttempt = preferences.getLong(PREF_LAST_ATTEMPT, 0);
        if (now - lastSuccess < SUCCESS_CHECK_INTERVAL_MS
                || now - lastAttempt < RETRY_CHECK_INTERVAL_MS) {
            return;
        }
        preferences.edit().putLong(PREF_LAST_ATTEMPT, now).apply();

        executor.execute(() -> {
            boolean changed = false;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(feed).openConnection();
                connection.setConnectTimeout(12_000);
                connection.setReadTimeout(20_000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "GosenReader/0.1 Android");
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    String raw = readAll(connection.getInputStream());
                    JSONObject payload = new JSONObject(raw);
                    validate(payload, true);
                    String downloadedGeneratedAt = payload.optString("generatedAt");
                    if (downloadedGeneratedAt.isEmpty()
                            || !downloadedGeneratedAt.equals(packageGeneratedAt)) {
                        try (FileOutputStream output =
                                     context.openFileOutput(STORE_FILE, Context.MODE_PRIVATE)) {
                            output.write(raw.getBytes(StandardCharsets.UTF_8));
                        }
                        articles = payload.getJSONArray("articles");
                        packageGeneratedAt = downloadedGeneratedAt;
                        changed = true;
                    }
                    preferences.edit()
                            .putLong(PREF_LAST_SUCCESS, System.currentTimeMillis())
                            .apply();
                }
            } catch (Exception ignored) {
                // Keep the current package and retry after the short attempt interval.
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (changed && onUpdated != null) {
                new Handler(Looper.getMainLooper()).post(onUpdated);
            }
        });
    }

    private void load() {
        try {
            File downloaded = new File(context.getFilesDir(), STORE_FILE);
            InputStream input = downloaded.exists()
                    ? new FileInputStream(downloaded)
                    : context.getAssets().open(STORE_FILE);
            String raw = readAll(input);
            JSONObject payload = new JSONObject(raw);
            validate(payload, downloaded.exists());
            articles = payload.getJSONArray("articles");
            packageGeneratedAt = payload.optString("generatedAt");
        } catch (Exception error) {
            articles = new JSONArray();
            packageGeneratedAt = "";
        }
    }

    private static void validate(JSONObject payload, boolean requireCompleteGlossary) throws Exception {
        if (payload.optInt("schemaVersion") != 1) throw new Exception("Unsupported schema");
        JSONArray list = payload.getJSONArray("articles");
        if (list.length() == 0) throw new Exception("Empty article package");
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            if (item.optString("id").isEmpty()
                    || item.optString("title").isEmpty()
                    || item.optString("body").length() < 80
                    || item.optJSONArray("questions") == null
                    || item.optJSONArray("questions").length() == 0) {
                throw new Exception("Invalid article at index " + i);
            }
            if (requireCompleteGlossary) {
                validateGlossary(item, i);
            }
        }
    }

    private static void validateGlossary(JSONObject article, int articleIndex) throws Exception {
        JSONObject glossary = article.optJSONObject("glossary");
        if (glossary == null) throw new Exception("Missing glossary at index " + articleIndex);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[A-Za-z]+(?:['’][A-Za-z]+)*")
                .matcher(article.optString("body"));
        while (matcher.find()) {
            String key = matcher.group().toLowerCase(java.util.Locale.ROOT).replace('’', '\'');
            JSONObject entry = glossary.optJSONObject(key);
            if (entry == null
                    || entry.optString("lemma").trim().isEmpty()
                    || entry.optString("translation").trim().isEmpty()
                    || entry.optString("pos").trim().isEmpty()
                    || entry.optString("forms").trim().isEmpty()
                    || entry.optString("meanings").trim().isEmpty()) {
                throw new Exception("Incomplete glossary word " + key + " at index " + articleIndex);
            }
            String sentence = findSentence(article.optString("body"), matcher.start());
            JSONObject contexts = entry.optJSONObject("contexts");
            JSONObject context = contexts == null ? null : contexts.optJSONObject(sentence);
            if (context == null
                    || context.optString("translation").trim().isEmpty()
                    || context.optString("pos").trim().isEmpty()) {
                throw new Exception("Incomplete contextual meaning for " + key
                        + " at index " + articleIndex);
            }
        }
    }

    private static String findSentence(String body, int index) {
        int start = index;
        while (start > 0 && ".!?".indexOf(body.charAt(start - 1)) < 0) start--;
        int end = index;
        while (end < body.length() && ".!?".indexOf(body.charAt(end)) < 0) end++;
        if (end < body.length()) end++;
        return body.substring(start, end).trim();
    }

    private static String readAll(InputStream input) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
        }
        return result.toString();
    }
}
