package com.gosen.reader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppUpdateManager {
    private static final String PREFS = "app_updates";
    private static final String KEY_DOWNLOAD_ID = "download_id";
    private static final String KEY_FILE_NAME = "file_name";
    private static final String KEY_SHA256 = "sha256";
    private static final String KEY_VERIFIED = "verified";

    private final Activity activity;
    private final SharedPreferences preferences;
    private final DownloadManager downloadManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean receiverRegistered;
    private boolean dialogVisible;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }
            long expected = preferences.getLong(KEY_DOWNLOAD_ID, -1L);
            long completed = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -2L);
            if (expected == completed) {
                handleCompletedDownload(completed);
            }
        }
    };

    public AppUpdateManager(Activity activity) {
        this.activity = activity;
        preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        downloadManager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(downloadReceiver, filter);
        }
        receiverRegistered = true;
    }

    public void checkForUpdates() {
        if (BuildConfig.UPDATE_MANIFEST_URL == null
                || !BuildConfig.UPDATE_MANIFEST_URL.startsWith("https://")) {
            return;
        }
        executor.execute(() -> {
            try {
                UpdateInfo info = fetchUpdateInfo(BuildConfig.UPDATE_MANIFEST_URL);
                if (info.versionCode > BuildConfig.VERSION_CODE && !activity.isFinishing()) {
                    activity.runOnUiThread(() -> showUpdateDialog(info));
                }
            } catch (Exception ignored) {
                // Updating is optional. A network failure must never block reading.
            }
        });
    }

    public void resumePendingInstall() {
        String fileName = preferences.getString(KEY_FILE_NAME, "");
        if (fileName.isEmpty()) {
            return;
        }
        File file = updateFile(fileName);
        if (file.isFile() && preferences.getBoolean(KEY_VERIFIED, false)
                && canInstallPackages()) {
            launchInstaller(file);
            return;
        }
        long downloadId = preferences.getLong(KEY_DOWNLOAD_ID, -1L);
        if (downloadId >= 0L && !preferences.getBoolean(KEY_VERIFIED, false)) {
            handleCompletedDownload(downloadId);
        }
    }

    public void close() {
        executor.shutdownNow();
        if (receiverRegistered) {
            try {
                activity.unregisterReceiver(downloadReceiver);
            } catch (IllegalArgumentException ignored) {
                // The Activity may already have unregistered during teardown.
            }
            receiverRegistered = false;
        }
    }

    private UpdateInfo fetchUpdateInfo(String manifestUrl) throws Exception {
        HttpURLConnection connection = openConnection(manifestUrl);
        try (InputStream stream = new BufferedInputStream(connection.getInputStream());
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                json.append(line);
            }
            JSONObject object = new JSONObject(json.toString());
            String apkUrl = object.getString("apkUrl").trim();
            if (!apkUrl.startsWith("https://")) {
                throw new IllegalArgumentException("APK URL must use HTTPS");
            }
            return new UpdateInfo(
                    object.getInt("versionCode"),
                    object.optString("versionName", ""),
                    apkUrl,
                    object.optString("sha256", "").trim().toLowerCase(Locale.ROOT),
                    object.optString("releaseNotes", "修复问题并改进使用体验。")
            );
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "GosenReader/" + BuildConfig.VERSION_NAME);
        connection.setInstanceFollowRedirects(true);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IllegalStateException("Update server returned " + status);
        }
        return connection;
    }

    private void showUpdateDialog(UpdateInfo info) {
        if (dialogVisible || activity.isFinishing()) {
            return;
        }
        dialogVisible = true;
        String title = info.versionName.isEmpty()
                ? "发现新版本"
                : "发现新版本 " + info.versionName;
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(info.releaseNotes)
                .setNegativeButton("以后再说", (dialog, which) -> dialogVisible = false)
                .setPositiveButton("下载更新", (dialog, which) -> {
                    dialogVisible = false;
                    startDownload(info);
                })
                .setOnCancelListener(dialog -> dialogVisible = false)
                .show();
    }

    private void startDownload(UpdateInfo info) {
        if (downloadManager == null) {
            Toast.makeText(activity, "系统下载服务不可用", Toast.LENGTH_LONG).show();
            return;
        }
        String fileName = "GosenReader-" + info.versionCode + ".apk";
        File destination = updateFile(fileName);
        if (destination.exists() && !destination.delete()) {
            Toast.makeText(activity, "无法清理旧安装包", Toast.LENGTH_LONG).show();
            return;
        }
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("Gosen Reader 更新")
                .setDescription("正在下载 " + (info.versionName.isEmpty() ? "新版本" : info.versionName))
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                        activity, Environment.DIRECTORY_DOWNLOADS, fileName);
        long id = downloadManager.enqueue(request);
        preferences.edit()
                .putLong(KEY_DOWNLOAD_ID, id)
                .putString(KEY_FILE_NAME, fileName)
                .putString(KEY_SHA256, info.sha256)
                .putBoolean(KEY_VERIFIED, false)
                .apply();
        Toast.makeText(activity, "已开始下载，完成后会打开安装界面", Toast.LENGTH_LONG).show();
    }

    private void handleCompletedDownload(long id) {
        try (Cursor cursor = downloadManager.query(
                new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                clearPendingDownload();
                return;
            }
            int status = cursor.getInt(
                    cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_PENDING
                    || status == DownloadManager.STATUS_RUNNING
                    || status == DownloadManager.STATUS_PAUSED) {
                return;
            }
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                clearPendingDownload();
                Toast.makeText(activity, "更新包下载失败，请稍后重试", Toast.LENGTH_LONG).show();
                return;
            }
        }
        String fileName = preferences.getString(KEY_FILE_NAME, "");
        String expectedSha = preferences.getString(KEY_SHA256, "");
        File file = updateFile(fileName);
        executor.execute(() -> {
            boolean valid = expectedSha.isEmpty()
                    || expectedSha.equalsIgnoreCase(sha256(file));
            activity.runOnUiThread(() -> {
                if (!valid) {
                    file.delete();
                    clearPendingDownload();
                    Toast.makeText(activity, "安装包校验失败，已取消安装", Toast.LENGTH_LONG).show();
                    return;
                }
                preferences.edit().putBoolean(KEY_VERIFIED, true).apply();
                requestInstall(file);
            });
        });
    }

    private void requestInstall(File file) {
        if (!canInstallPackages()) {
            Toast.makeText(activity, "请允许本应用安装更新包", Toast.LENGTH_LONG).show();
            Intent settings = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            return;
        }
        launchInstaller(file);
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || activity.getPackageManager().canRequestPackageInstalls();
    }

    private void launchInstaller(File file) {
        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(activity.getPackageName() + ".apk-files")
                .appendPath(file.getName())
                .build();
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(install);
    }

    private File updateFile(String fileName) {
        File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            directory = activity.getFilesDir();
        }
        return new File(directory, fileName);
    }

    private String sha256(File file) {
        try (InputStream stream = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception error) {
            return "";
        }
    }

    private void clearPendingDownload() {
        preferences.edit()
                .remove(KEY_DOWNLOAD_ID)
                .remove(KEY_FILE_NAME)
                .remove(KEY_SHA256)
                .remove(KEY_VERIFIED)
                .apply();
    }

    private static final class UpdateInfo {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final String releaseNotes;

        UpdateInfo(int versionCode, String versionName, String apkUrl,
                   String sha256, String releaseNotes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.releaseNotes = releaseNotes;
        }
    }
}
