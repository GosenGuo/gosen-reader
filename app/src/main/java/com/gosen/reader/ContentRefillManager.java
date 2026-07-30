package com.gosen.reader;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ContentRefillManager {
    private static final int UNREAD_THRESHOLD = 5;
    private static final int REFILL_COUNT = 30;
    private static final long RETRY_COOLDOWN_MS = 6L * 60L * 60L * 1000L;
    private static final String LAST_REQUEST_AT = "contentRefillLastRequestAt";

    private final SharedPreferences prefs;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    ContentRefillManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences("reader", Context.MODE_PRIVATE);
    }

    void requestIfBelowThreshold(int unreadCount) {
        String endpoint = BuildConfig.CONTENT_REFILL_URL.trim();
        if (unreadCount >= UNREAD_THRESHOLD || !endpoint.startsWith("https://")) return;
        long lastRequestAt = prefs.getLong(LAST_REQUEST_AT, 0L);
        if (System.currentTimeMillis() - lastRequestAt < RETRY_COOLDOWN_MS) return;

        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(12_000);
                connection.setReadTimeout(20_000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty(
                        "User-Agent", "GosenReader/" + BuildConfig.VERSION_NAME);
                String token = BuildConfig.CONTENT_REFILL_TOKEN.trim();
                if (!token.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + token);
                }
                JSONObject request = new JSONObject()
                        .put("unreadCount", unreadCount)
                        .put("requestedCount", REFILL_COUNT)
                        .put("appVersion", BuildConfig.VERSION_NAME);
                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
                int status = connection.getResponseCode();
                if ((status >= 200 && status < 300) || status == 409) {
                    prefs.edit()
                            .putLong(LAST_REQUEST_AT, System.currentTimeMillis())
                            .apply();
                }
            } catch (Exception ignored) {
                // A later app launch retries without interrupting reading.
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    void close() {
        executor.shutdownNow();
    }
}
