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
    private static final long UPDATE_INTERVAL_MS = 28L * 24 * 60 * 60 * 1000;

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private JSONArray articles = new JSONArray();

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

        long lastCheck = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
                .getLong("lastUpdateCheck", 0);
        if (System.currentTimeMillis() - lastCheck < UPDATE_INTERVAL_MS) return;

        executor.execute(() -> {
            boolean changed = false;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(feed).openConnection();
                connection.setConnectTimeout(12_000);
                connection.setReadTimeout(20_000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "GosenReader/0.1 Android");
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    String raw = readAll(connection.getInputStream());
                    JSONObject payload = new JSONObject(raw);
                    validate(payload);
                    try (FileOutputStream output = context.openFileOutput(STORE_FILE, Context.MODE_PRIVATE)) {
                        output.write(raw.getBytes(StandardCharsets.UTF_8));
                    }
                    articles = payload.getJSONArray("articles");
                    changed = true;
                }
                connection.disconnect();
            } catch (Exception ignored) {
                // The built-in package remains available if a monthly update fails.
            } finally {
                context.getSharedPreferences("reader", Context.MODE_PRIVATE).edit()
                        .putLong("lastUpdateCheck", System.currentTimeMillis()).apply();
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
            validate(payload);
            articles = payload.getJSONArray("articles");
        } catch (Exception error) {
            articles = new JSONArray();
        }
    }

    private static void validate(JSONObject payload) throws Exception {
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
        }
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
