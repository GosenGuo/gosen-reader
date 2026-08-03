package com.gosen.reader;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final Pattern WORD_PATTERN =
            Pattern.compile("[A-Za-z]+(?:['’][A-Za-z]+)*");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private ContentRepository repository;
    private SharedPreferences prefs;
    private FrameLayout content;
    private LinearLayout nav;
    private JSONObject activeArticle;
    private boolean onHomeScreen;
    private AppUpdateManager updateManager;
    private ContentRefillManager refillManager;
    private LearningStore learningStore;
    private long readingStartedAt;
    private long activeReadingSeconds;
    private int activeArticleClicks;
    private boolean darkMode;
    private int GREEN;
    private int PRIMARY;
    private int GREEN_LIGHT;
    private int CREAM;
    private int SURFACE;
    private int INK;
    private int MUTED;
    private int GOLD;
    private int DANGER;
    private final ArrayList<Button> navButtons = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("reader", Context.MODE_PRIVATE);
        boolean systemDark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        darkMode = prefs.contains("darkMode")
                ? prefs.getBoolean("darkMode", false) : systemDark;
        applyPalette();
        setTheme(darkMode ? R.style.AppTheme_Dark : R.style.AppTheme);
        getWindow().setStatusBarColor(CREAM);
        getWindow().setNavigationBarColor(SURFACE);
        int lightBars = darkMode ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getDecorView().setSystemUiVisibility(lightBars);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    lightBars | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        repository = new ContentRepository(this);
        refillManager = new ContentRefillManager(this);
        learningStore = new LearningStore(prefs);
        buildShell();
        showHome();
        repository.checkForUpdates((success, changed) -> {
            if (changed && onHomeScreen) showHome();
            maybeRequestContentRefill();
        });
        updateManager = new AppUpdateManager(this);
        updateManager.checkForUpdates();
    }

    private void applyPalette() {
        if (darkMode) {
            GREEN = Color.rgb(111, 210, 158);
            PRIMARY = Color.rgb(38, 112, 77);
            GREEN_LIGHT = Color.rgb(29, 53, 42);
            CREAM = Color.rgb(16, 20, 18);
            SURFACE = Color.rgb(27, 33, 30);
            INK = Color.rgb(235, 240, 236);
            MUTED = Color.rgb(164, 176, 168);
            GOLD = Color.rgb(238, 188, 87);
            DANGER = Color.rgb(242, 139, 126);
        } else {
            GREEN = Color.rgb(47, 107, 79);
            PRIMARY = GREEN;
            GREEN_LIGHT = Color.rgb(229, 239, 232);
            CREAM = Color.rgb(250, 248, 242);
            SURFACE = Color.WHITE;
            INK = Color.rgb(39, 43, 40);
            MUTED = Color.rgb(102, 107, 103);
            GOLD = Color.rgb(233, 180, 76);
            DANGER = Color.rgb(176, 76, 65);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (updateManager != null) {
            updateManager.resumePendingInstall();
        }
    }

    @Override
    protected void onDestroy() {
        if (updateManager != null) {
            updateManager.close();
        }
        if (repository != null) {
            repository.close();
        }
        if (refillManager != null) {
            refillManager.close();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (onHomeScreen) {
            super.onBackPressed();
        } else {
            showHome();
        }
    }

    private void buildShell() {
        navButtons.clear();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CREAM);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(12), dp(6), dp(12), dp(8));
        nav.setBackgroundColor(SURFACE);
        addNavButton("首页", this::showHome);
        addNavButton("题库", this::showLibrary);
        addNavButton("复习", this::showReview);
        addNavButton("统计", this::showStats);
        root.addView(nav, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset;
            int bottomInset;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                int types = WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout();
                topInset = insets.getInsets(types).top;
                bottomInset = insets.getInsets(types).bottom;
            } else {
                topInset = insets.getSystemWindowInsetTop();
                bottomInset = insets.getSystemWindowInsetBottom();
            }
            content.setPadding(0, topInset, 0, 0);
            nav.setPadding(dp(12), dp(6), dp(12), dp(8) + bottomInset);
            ViewGroup.LayoutParams navParams = nav.getLayoutParams();
            navParams.height = dp(64) + bottomInset;
            nav.setLayoutParams(navParams);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
    }

    private void addNavButton(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(GREEN);
        button.setAllCaps(false);
        button.setTag(label);
        button.setBackground(rounded(Color.TRANSPARENT, 12));
        button.setOnClickListener(view -> {
            selectNav(label);
            action.run();
        });
        navButtons.add(button);
        nav.addView(button, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private void selectNav(String label) {
        for (Button button : navButtons) {
            boolean selected = label.equals(button.getTag());
            button.setTextColor(selected ? GREEN : MUTED);
            button.setTypeface(Typeface.DEFAULT,
                    selected ? Typeface.BOLD : Typeface.NORMAL);
            button.setBackground(rounded(selected ? GREEN_LIGHT : Color.TRANSPARENT, 12));
        }
    }

    private void setScreen(View view, boolean showNav) {
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nav.setVisibility(showNav ? View.VISIBLE : View.GONE);
    }

    private void showHome() {
        onHomeScreen = true;
        selectNav("首页");
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView greeting = label("你好，Gosen", 28, INK, true);
        header.addView(greeting, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button theme = secondaryButton(darkMode ? "浅色" : "深色");
        theme.setTextSize(13);
        theme.setMinHeight(dp(40));
        theme.setPadding(dp(12), 0, dp(12), 0);
        theme.setOnClickListener(view -> {
            prefs.edit().putBoolean("darkMode", !darkMode).apply();
            recreate();
        });
        header.addView(theme, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));
        page.addView(header);
        page.addView(label("每天读一篇，让阅读变成习惯。", 15, MUTED, false));
        page.addView(space(24));

        LinearLayout streakCard = card(PRIMARY);
        streakCard.addView(label("连续阅读", 14, Color.WHITE, false));
        TextView streak = label(prefs.getInt("streak", 0) + " 天", 38,
                Color.WHITE, true);
        streak.setPadding(0, dp(4), 0, dp(4));
        streakCard.addView(streak);
        String doneText = isTodayDone() ? "今日学习闭环已完成 ✓"
                : "复习 → 阅读 → 答题 → 复盘";
        streakCard.addView(label(doneText, 14, Color.WHITE, false));
        page.addView(streakCard);
        page.addView(space(18));

        LinearLayout reviewCard = card(GREEN_LIGHT);
        int due = Math.min(remainingDailyReviews(), learningStore.dueCount());
        reviewCard.addView(label("生词复习", 18, GREEN, true));
        reviewCard.addView(label(due > 0 ? "今天有 " + due + " 个词待复习"
                : "今天的生词已复习完", 14, MUTED, false));
        reviewCard.addView(space(10));
        Button review = secondaryButton(due > 0 ? "开始复习" : "查看生词本");
        review.setOnClickListener(view -> showReview());
        reviewCard.addView(review);
        page.addView(reviewCard);
        page.addView(space(18));

        page.addView(label("今日阅读", 20, INK, true));
        page.addView(space(10));
        if (repository.size() == 0) {
            LinearLayout empty = card(SURFACE);
            empty.addView(label("题库暂时无法读取", 17, INK, true));
            empty.addView(label("请检查内置 articles.json 数据。", 14, MUTED, false));
            page.addView(empty);
        } else {
            JSONObject article = todayArticle();
            LinearLayout articleCard = articleCard(article, true);
            page.addView(articleCard);
        }

        page.addView(space(20));
        page.addView(label("本月进度", 20, INK, true));
        page.addView(space(10));
        LinearLayout progress = card(SURFACE);
        progress.addView(label("已完成 " + completedDaysThisMonth() + " 天", 18, INK, true));
        progress.addView(space(8));
        TextView hint = label("目标：每天一篇 · 正确率不影响打卡", 14, MUTED, false);
        progress.addView(hint);
        progress.addView(space(8));
        progress.addView(label("已缓存 " + repository.size() + " 篇，其中 "
                + unreadCount() + " 篇未读；断网也可阅读。", 13, MUTED, false));
        page.addView(progress);

        setScreen(scroll, true);
    }

    private JSONObject todayArticle() {
        float target = prefs.getFloat("abilityScore", 1f);
        JSONObject best = null;
        float bestScore = -1f;
        String lastSource = prefs.getString("lastArticleSource", "");
        for (int i = 0; i < repository.size(); i++) {
            JSONObject candidate = repository.get(i);
            if (candidate == null || isCompleted(candidate.optString("id"))) continue;
            float difficultyFit = 1f - Math.min(1f,
                    Math.abs(articleLevel(candidate) - target) / 2f);
            int learningWords = learningStore.learningWordCount(candidate);
            float vocabularyFit = Math.min(1f, learningWords / 10f);
            float variety = lastSource.equals(candidate.optString("source")) ? 0f : 1f;
            float score = difficultyFit * 40f + vocabularyFit * 35f + variety * 15f + 10f;
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best != null) return best;
        int index = (LocalDate.now().getDayOfYear() - 1) % repository.size();
        return repository.get(index);
    }

    private float articleLevel(JSONObject article) {
        String difficulty = article.optString("difficulty", "高考");
        if (difficulty.contains("基础") || difficulty.contains("容易")) return 0f;
        if (difficulty.contains("较难") || difficulty.contains("困难")) return 2f;
        return 1f;
    }

    private int unreadCount() {
        int unread = 0;
        for (int i = 0; i < repository.size(); i++) {
            JSONObject article = repository.get(i);
            if (article != null && !isCompleted(article.optString("id"))) unread++;
        }
        return unread;
    }

    private LinearLayout articleCard(JSONObject article, boolean prominent) {
        LinearLayout box = card(SURFACE);
        TextView tag = label(article.optString("difficulty", "高中") + "  ·  "
                + article.optInt("wordCount", 0) + " 词", 13, GREEN, true);
        box.addView(tag);
        box.addView(space(8));
        box.addView(label(article.optString("title"), prominent ? 23 : 18, INK, true));
        box.addView(space(6));
        box.addView(label(article.optString("source", "英语阅读"), 13, MUTED, false));
        int learningWords = learningStore.learningWordCount(article);
        if (learningWords > 0) {
            box.addView(space(6));
            box.addView(label("包含 " + learningWords + " 个你正在巩固的词",
                    13, GREEN, true));
        }
        box.addView(space(16));
        Button read = primaryButton(isCompleted(article.optString("id"))
                ? "再次阅读" : "开始阅读");
        read.setOnClickListener(view -> showReader(article));
        box.addView(read);
        return box;
    }

    private void showLibrary() {
        onHomeScreen = false;
        selectNav("题库");
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(label("阅读题库", 28, INK, true));
        page.addView(label("当前共有 " + repository.size() + " 篇，月度更新包会自动替换题库。",
                14, MUTED, false));
        page.addView(space(12));
        Button refresh = secondaryButton("刷新题库");
        refresh.setOnClickListener(view -> {
            refresh.setEnabled(false);
            refresh.setText("正在刷新…");
            repository.checkForUpdates((success, changed) -> {
                if (success) {
                    Toast.makeText(this, changed ? "题库已更新" : "当前已是最新题库",
                            Toast.LENGTH_SHORT).show();
                    showLibrary();
                } else {
                    refresh.setEnabled(true);
                    refresh.setText("刷新题库");
                    Toast.makeText(this, "题库刷新失败，请检查网络后重试",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
        page.addView(refresh);
        page.addView(space(18));
        for (int i = 0; i < repository.size(); i++) {
            page.addView(articleCard(repository.get(i), false));
            page.addView(space(12));
        }
        setScreen(scroll, true);
    }

    private void showReview() {
        onHomeScreen = false;
        selectNav("复习");
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(label("生词复习", 28, INK, true));
        page.addView(label("每天最多 12 个；系统会根据“忘记、模糊、记得”动态安排。",
                14, MUTED, false));
        page.addView(space(18));

        int remaining = remainingDailyReviews();
        ArrayList<JSONObject> dueWords = remaining == 0
                ? new ArrayList<>() : new ArrayList<>(learningStore.dueWords(1));
        if (dueWords.isEmpty()) {
            LinearLayout done = card(GREEN_LIGHT);
            done.addView(label(remaining == 0 ? "今日 12 个复习已完成 ✓"
                    : "今天的到期生词已复习 ✓", 21, GREEN, true));
            done.addView(space(6));
            done.addView(label("生词本 " + learningStore.totalCount() + " 个词义 · 已掌握 "
                    + learningStore.statusCount("mastered") + " 个", 14, MUTED, false));
            page.addView(done);
            setScreen(scroll, true);
            return;
        }

        JSONObject item = dueWords.get(0);
        String senseKey = item.optString("senseKey", item.optString("lemma"));
        String lemma = item.optString("lemma");
        String displayWord = item.optString("displayWord", lemma);
        String sentence = item.optString("sentence");
        String masked = sentence.replaceAll("(?i)\\b" + Pattern.quote(displayWord) + "\\b", "______");
        if (masked.equals(sentence)) {
            masked = sentence.replaceAll("(?i)\\b" + Pattern.quote(lemma) + "\\b", "______");
        }

        LinearLayout box = card(SURFACE);
        box.addView(label("先回忆这个词在句中的含义", 14, MUTED, true));
        box.addView(space(10));
        box.addView(label(masked, 19, INK, false));
        box.addView(space(14));
        Button reveal = primaryButton("显示答案");
        box.addView(reveal);

        LinearLayout answer = new LinearLayout(this);
        answer.setOrientation(LinearLayout.VERTICAL);
        answer.setVisibility(View.GONE);
        answer.setPadding(0, dp(16), 0, 0);
        answer.addView(label(displayWord + "  ·  " + item.optString("pos", "—"),
                22, INK, true));
        answer.addView(label(item.optString("translation"), 23, GREEN, true));
        answer.addView(label(learningStore.statusLabel(item) + "  ·  已在 "
                + item.optInt("contextCount", 1) + " 个语境中遇见",
                13, MUTED, true));
        answer.addView(space(8));
        answer.addView(label(sentence, 15, INK, false));
        answer.addView(label(item.optString("sentenceTranslation"), 15, MUTED, false));
        answer.addView(space(10));
        answer.addView(label("词形：" + item.optString("forms", "—"), 14, MUTED, false));
        answer.addView(label("已点击 " + item.optInt("clickCount") + " 次", 13, MUTED, false));
        answer.addView(space(16));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button forgot = secondaryButton("没想起来");
        Button vague = secondaryButton("有点模糊");
        Button remembered = primaryButton("完全记得");
        forgot.setTextSize(12);
        vague.setTextSize(12);
        remembered.setTextSize(12);
        actions.addView(forgot, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams vagueParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        vagueParams.leftMargin = dp(6);
        actions.addView(vague, vagueParams);
        LinearLayout.LayoutParams rememberedParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        rememberedParams.leftMargin = dp(6);
        actions.addView(remembered, rememberedParams);
        answer.addView(actions);
        box.addView(answer);

        reveal.setOnClickListener(view -> {
            reveal.setVisibility(View.GONE);
            answer.setVisibility(View.VISIBLE);
        });
        forgot.setOnClickListener(view -> {
            learningStore.answer(senseKey, LearningStore.FORGOT);
            recordReviewToday();
            showReview();
        });
        vague.setOnClickListener(view -> {
            learningStore.answer(senseKey, LearningStore.VAGUE);
            recordReviewToday();
            showReview();
        });
        remembered.setOnClickListener(view -> {
            learningStore.answer(senseKey, LearningStore.REMEMBERED);
            recordReviewToday();
            showReview();
        });
        page.addView(box);
        setScreen(scroll, true);
    }

    private void showStats() {
        onHomeScreen = false;
        selectNav("统计");
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(label("阅读统计", 28, INK, true));
        page.addView(label("数据只保存在这台手机上。", 14, MUTED, false));
        page.addView(space(20));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        grid.addView(statCard("当前连续", prefs.getInt("streak", 0) + " 天"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        grid.addView(space(10));
        grid.addView(statCard("最长连续", prefs.getInt("longestStreak", 0) + " 天"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(grid);
        page.addView(space(12));

        LinearLayout grid2 = new LinearLayout(this);
        grid2.setOrientation(LinearLayout.HORIZONTAL);
        grid2.addView(statCard("累计文章", prefs.getInt("totalArticles", 0) + " 篇"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        grid2.addView(space(10));
        grid2.addView(statCard("累计词数", String.valueOf(prefs.getInt("totalWords", 0))),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(grid2);
        page.addView(space(20));

        LinearLayout clicks = card(SURFACE);
        clicks.addView(label("单词点击", 18, INK, true));
        clicks.addView(space(6));
        clicks.addView(label("累计点击 " + prefs.getInt("totalClicks", 0)
                + " 次；相同词形会尽量归入原形统计。", 14, MUTED, false));
        page.addView(clicks);
        page.addView(space(12));

        LinearLayout learning = card(SURFACE);
        learning.addView(label("学习状态", 18, INK, true));
        learning.addView(space(6));
        learning.addView(label("生词本 " + learningStore.totalCount() + " 个 · 今日待复习 "
                + learningStore.dueCount() + " 个", 14, MUTED, false));
        learning.addView(label("新词 " + learningStore.statusCount("new")
                + " · 学习中 " + learningStore.statusCount("learning")
                + " · 巩固中 " + learningStore.statusCount("consolidating")
                + " · 已掌握 " + learningStore.statusCount("mastered"),
                13, MUTED, false));
        learning.addView(label("当前阅读等级：" + abilityLabel(prefs.getFloat("abilityScore", 1f))
                + " · 离线缓存 " + repository.size() + " 篇", 14, MUTED, false));
        page.addView(learning);
        page.addView(space(12));
        page.addView(weeklyReportCard());
        setScreen(scroll, true);
    }

    private LinearLayout weeklyReportCard() {
        String week = weekKey();
        int articles = prefs.getInt(week + ":articles", 0);
        int words = prefs.getInt(week + ":words", 0);
        int questions = prefs.getInt(week + ":questions", 0);
        int correct = prefs.getInt(week + ":correct", 0);
        int clicks = prefs.getInt(week + ":clicks", 0);
        long seconds = prefs.getLong(week + ":seconds", 0L);
        String weakness = topWeeklyWeakness(week);
        LinearLayout box = card(SURFACE);
        box.addView(label("本周学习报告", 18, INK, true));
        box.addView(space(8));
        box.addView(label("阅读 " + articles + " 篇 · " + words + " 词 · 查词 "
                + clicks + " 次", 14, MUTED, false));
        if (words > 0) {
            int clickRate = Math.round(clicks * 100f / words);
            int speed = seconds <= 0 ? 0 : Math.round(words * 60f / seconds);
            box.addView(label("每百词查词 " + clickRate + " 次"
                    + (speed > 0 ? " · 约 " + speed + " 词/分钟" : ""),
                    14, MUTED, false));
        }
        String accuracy = questions == 0 ? "暂无答题数据"
                : "答题正确率 " + Math.round(correct * 100f / questions) + "%";
        box.addView(label(accuracy, 14, MUTED, false));
        box.addView(space(10));
        box.addView(label("下周唯一重点", 13, GREEN, true));
        box.addView(label(weakness.isEmpty()
                ? "继续完成阅读闭环，累积至少 10 道题后生成诊断。"
                : trainingAdvice(weakness), 15, INK, true));
        return box;
    }

    private String abilityLabel(float score) {
        if (score < 0.67f) return "基础";
        if (score > 1.33f) return "较难";
        return "高考";
    }

    private LinearLayout statCard(String title, String value) {
        LinearLayout box = card(SURFACE);
        box.addView(label(title, 13, MUTED, false));
        box.addView(space(8));
        box.addView(label(value, 24, GREEN, true));
        return box;
    }

    private void showReader(JSONObject article) {
        onHomeScreen = false;
        activeArticle = article;
        readingStartedAt = System.currentTimeMillis();
        activeReadingSeconds = 0L;
        activeArticleClicks = 0;
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);

        page.addView(backRow("阅读文章", this::showHome));
        page.addView(space(12));
        page.addView(label(article.optString("title"), 27, INK, true));
        page.addView(space(5));
        page.addView(label(article.optString("source") + "  ·  "
                + article.optInt("wordCount") + " 词", 13, MUTED, false));
        page.addView(space(16));

        LinearLayout instruction = card(GREEN_LIGHT);
        instruction.setPadding(dp(15), dp(12), dp(15), dp(12));
        instruction.addView(label("直接点击任何不认识的英文单词查看释义", 14, GREEN, true));
        page.addView(instruction);
        page.addView(space(18));

        TextView body = label("", 19, INK, false);
        body.setLineSpacing(dp(7), 1.05f);
        body.setTextIsSelectable(false);
        body.setHighlightColor(Color.TRANSPARENT);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setText(makeClickableArticle(article));
        page.addView(body);
        page.addView(space(28));

        Button finish = primaryButton("完成阅读，先回忆主旨");
        finish.setOnClickListener(view -> showRecall(article));
        page.addView(finish);
        page.addView(space(24));
        setScreen(scroll, false);
    }

    private void showRecall(JSONObject article) {
        onHomeScreen = false;
        if (activeReadingSeconds == 0L) {
            activeReadingSeconds = Math.max(1,
                    (System.currentTimeMillis() - readingStartedAt) / 1000L);
        }
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(backRow("返回文章", () -> showReader(article)));
        page.addView(space(14));
        page.addView(label("60 秒主动回忆", 28, INK, true));
        page.addView(label("先不看原文，用一句话说出这篇文章主要讲了什么。",
                16, MUTED, false));
        page.addView(space(18));

        LinearLayout recallCard = card(SURFACE);
        EditText recall = new EditText(this);
        recall.setHint("可以输入一句概括，也可以只在脑中回忆");
        recall.setHintTextColor(MUTED);
        recall.setTextColor(INK);
        recall.setTextSize(16);
        recall.setMinLines(3);
        recall.setGravity(Gravity.TOP);
        recall.setBackground(rounded(GREEN_LIGHT, 12));
        recall.setPadding(dp(14), dp(12), dp(14), dp(12));
        recallCard.addView(recall, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        recallCard.addView(space(14));
        Button complete = primaryButton("我已回忆，开始答题");
        complete.setOnClickListener(view -> {
            String key = "recall:" + article.optString("id") + ":"
                    + LocalDate.now().format(DATE_FORMAT);
            prefs.edit().putBoolean(key, true)
                    .putString(key + ":text", recall.getText().toString().trim()).apply();
            showQuiz(article);
        });
        recallCard.addView(complete);
        recallCard.addView(space(10));
        Button forgot = secondaryButton("完全想不起来，返回文章");
        forgot.setOnClickListener(view -> showReader(article));
        recallCard.addView(forgot);
        page.addView(recallCard);
        setScreen(scroll, false);
    }

    private SpannableString makeClickableArticle(JSONObject article) {
        String body = article.optString("body");
        SpannableString text = new SpannableString(body);
        Matcher matcher = WORD_PATTERN.matcher(body);
        while (matcher.find()) {
            final String word = matcher.group();
            final int wordStart = matcher.start();
            final String sentence = findSentence(body, wordStart);
            text.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    showWordCard(article, word, sentence);
                }

                @Override
                public void updateDrawState(TextPaint paint) {
                    paint.setColor(INK);
                    paint.setUnderlineText(false);
                    paint.setTypeface(Typeface.create(paint.getTypeface(), Typeface.NORMAL));
                }
            }, matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return text;
    }

    private String findSentence(String body, int index) {
        int start = index;
        while (start > 0 && ".!?".indexOf(body.charAt(start - 1)) < 0) start--;
        int end = index;
        while (end < body.length() && ".!?".indexOf(body.charAt(end)) < 0) end++;
        if (end < body.length()) end++;
        return body.substring(start, end).trim();
    }

    private void showWordCard(JSONObject article, String displayedWord, String sentence) {
        JSONObject glossary = article.optJSONObject("glossary");
        String key = displayedWord.toLowerCase(Locale.ROOT).replace('’', '\'');
        JSONObject entry = glossary == null ? null : glossary.optJSONObject(key);
        if (entry == null) entry = commonEntry(key);
        JSONObject contexts = entry == null ? null : entry.optJSONObject("contexts");
        JSONObject contextEntry = contexts == null ? null : contexts.optJSONObject(sentence);

        String lemma = entry != null ? entry.optString("lemma", key) : roughLemma(key);
        int history = prefs.getInt("click:" + lemma, 0) + 1;
        prefs.edit()
                .putInt("click:" + lemma, history)
                .putInt("totalClicks", prefs.getInt("totalClicks", 0) + 1)
                .apply();

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(22), dp(20), dp(22), dp(24));
        sheet.setBackground(rounded(SURFACE, 22));

        TextView word = label(displayedWord, 30, INK, true);
        sheet.addView(word);
        sheet.addView(label("原形  " + lemma + "   ·   历史点击 " + history + " 次",
                13, MUTED, false));
        sheet.addView(space(16));

        String translation = contextEntry != null
                ? contextEntry.optString("translation", entry.optString("translation"))
                : entry == null ? "该词释义待题库处理器补充"
                : entry.optString("translation");
        sheet.addView(label(translation, 24, GREEN, true));
        String pos = contextEntry != null
                ? contextEntry.optString("pos", entry.optString("pos", "—"))
                : entry == null ? "—" : entry.optString("pos", "—");
        String forms = entry == null ? "—" : entry.optString("forms", "—");
        String meanings = entry == null ? translation : entry.optString("meanings", translation);
        JSONObject translations = article.optJSONObject("sentenceTranslations");
        String chinese = translations == null ? "" : translations.optString(sentence);
        activeArticleClicks++;
        learningStore.recordWord(lemma, displayedWord, translation, pos, forms, meanings,
                sentence, chinese, article.optString("id"), article.optString("title"), history);
        sheet.addView(label("词性  " + pos, 14, MUTED, false));
        sheet.addView(space(14));

        if (entry != null) {
            sheet.addView(label("常见含义", 14, MUTED, true));
            sheet.addView(label(entry.optString("meanings", translation), 16, INK, false));
            sheet.addView(space(12));
            sheet.addView(label("常见词形", 14, MUTED, true));
            sheet.addView(label(forms, 16, INK, false));
            sheet.addView(space(14));
        }

        sheet.addView(label("所在句", 14, MUTED, true));
        sheet.addView(label(sentence, 15, INK, false));
        sheet.addView(space(8));
        sheet.addView(label(chinese.isEmpty() ? "本句翻译待题库处理器补充。" : chinese,
                15, GREEN, false));
        sheet.addView(space(18));
        Button close = secondaryButton("继续阅读");
        close.setOnClickListener(view -> dialog.dismiss());
        sheet.addView(close);

        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.getAttributes().windowAnimations = android.R.style.Animation_Dialog;
        }
        dialog.setOnShowListener(ignored -> {
            Window shown = dialog.getWindow();
            if (shown != null) shown.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        });
        dialog.show();
    }

    private JSONObject commonEntry(String word) {
        Map<String, String[]> common = new HashMap<>();
        common.put("the", new String[]{"the", "这／该（特指）", "art.", "—", "这；该；特指的人或事物"});
        common.put("a", new String[]{"a", "一个", "art.", "an", "一个；某一个"});
        common.put("an", new String[]{"an", "一个", "art.", "a", "一个；某一个"});
        common.put("and", new String[]{"and", "和；并且", "conj.", "—", "和；并且；然后"});
        common.put("but", new String[]{"but", "但是", "conj.", "—", "但是；除了"});
        common.put("because", new String[]{"because", "因为", "conj.", "—", "因为"});
        common.put("after", new String[]{"after", "在……之后", "prep./conj.", "—", "在……之后；后来"});
        common.put("before", new String[]{"before", "在……之前", "prep./conj.", "—", "在……之前；以前"});
        common.put("school", new String[]{"school", "学校", "n.", "schools", "学校；学院；学派"});
        common.put("students", new String[]{"student", "学生", "n.", "students", "学生；研究者"});
        common.put("student", new String[]{"student", "学生", "n.", "students", "学生；研究者"});
        common.put("library", new String[]{"library", "图书馆", "n.", "libraries", "图书馆；藏书室"});
        common.put("books", new String[]{"book", "书籍", "n.", "books · booked · booking", "书；预订"});
        common.put("book", new String[]{"book", "书", "n.", "books · booked · booking", "书；预订"});
        common.put("people", new String[]{"person", "人们", "n.", "person · people", "人们；人员"});
        common.put("new", new String[]{"new", "新的", "adj.", "newer · newest", "新的；新近的"});
        common.put("more", new String[]{"much/many", "更多的", "det./adv.", "more · most", "更多；更加"});
        common.put("always", new String[]{"always", "总是", "adv.", "—", "总是；一直"});
        common.put("sometimes", new String[]{"sometimes", "有时", "adv.", "—", "有时；间或"});
        common.put("different", new String[]{"different", "不同的", "adj.", "—", "不同的；各式各样的"});
        common.put("found", new String[]{"find", "发现", "v.", "finds · found · finding", "发现；找到；认为"});
        common.put("made", new String[]{"make", "使；制作", "v.", "makes · made · making", "制作；使得；做出"});
        common.put("showing", new String[]{"show", "展示；表明", "v.", "shows · showed · shown · showing", "展示；表明；带领"});
        common.put("showed", new String[]{"show", "表明", "v.", "shows · showed · shown · showing", "展示；表明；带领"});
        common.put("easier", new String[]{"easy", "更容易的", "adj.", "easy · easier · easiest", "容易的；舒适的"});
        common.put("fastest", new String[]{"fast", "最快的", "adj.", "fast · faster · fastest", "快的；牢固的"});
        common.put("choice", new String[]{"choice", "选择", "n.", "choices", "选择；入选者"});
        common.put("morning", new String[]{"morning", "早晨", "n.", "mornings", "早晨；上午"});
        common.put("road", new String[]{"road", "道路", "n.", "roads", "道路；途径"});
        common.put("longer", new String[]{"long", "更长的", "adj.", "long · longer · longest", "长的；长时间的"});
        common.put("river", new String[]{"river", "河流", "n.", "rivers", "河；水流"});
        common.put("quiet", new String[]{"quiet", "安静的", "adj.", "quieter · quietest", "安静的；平静的"});
        common.put("changed", new String[]{"change", "改变", "v.", "changes · changed · changing", "改变；变化；零钱"});
        String[] values = common.get(word);
        if (values == null) return null;
        JSONObject result = new JSONObject();
        try {
            result.put("lemma", values[0]);
            result.put("translation", values[1]);
            result.put("pos", values[2]);
            result.put("forms", values[3]);
            result.put("meanings", values[4]);
        } catch (Exception ignored) { }
        return result;
    }

    private String roughLemma(String word) {
        if (word.endsWith("ies") && word.length() > 4) return word.substring(0, word.length() - 3) + "y";
        if (word.endsWith("ing") && word.length() > 5) return word.substring(0, word.length() - 3);
        if (word.endsWith("ed") && word.length() > 4) return word.substring(0, word.length() - 2);
        if (word.endsWith("s") && word.length() > 3) return word.substring(0, word.length() - 1);
        return word;
    }

    private void showQuiz(JSONObject article) {
        onHomeScreen = false;
        JSONArray questions = article.optJSONArray("questions");
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(backRow("返回文章", () -> showReader(article)));
        page.addView(space(12));
        page.addView(label("完成题目", 28, INK, true));
        page.addView(label("提交后即可完成今日打卡，正确率不影响打卡。", 14, MUTED, false));
        page.addView(space(18));

        ArrayList<RadioGroup> groups = new ArrayList<>();
        for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.optJSONObject(i);
            LinearLayout box = card(SURFACE);
            box.addView(label((i + 1) + ". " + question.optString("prompt"),
                    17, INK, true));
            box.addView(space(10));
            RadioGroup group = new RadioGroup(this);
            JSONArray options = question.optJSONArray("options");
            for (int j = 0; j < options.length(); j++) {
                RadioButton option = new RadioButton(this);
                option.setId(View.generateViewId());
                option.setTag(j);
                option.setText(String.format(Locale.US, "%c. %s",
                        (char) ('A' + j), options.optString(j)));
                option.setTextSize(15);
                option.setTextColor(INK);
                option.setPadding(0, dp(6), 0, dp(6));
                group.addView(option);
            }
            box.addView(group);
            groups.add(group);
            page.addView(box);
            page.addView(space(12));
        }

        Button submit = primaryButton("提交答案");
        submit.setOnClickListener(view -> {
            int[] selected = new int[groups.size()];
            for (int i = 0; i < groups.size(); i++) {
                RadioButton checked = groups.get(i).findViewById(groups.get(i).getCheckedRadioButtonId());
                if (checked == null) {
                    Toast.makeText(this, "请先完成第 " + (i + 1) + " 题", Toast.LENGTH_SHORT).show();
                    return;
                }
                selected[i] = (int) checked.getTag();
            }
            markTodayComplete(article, selected);
            showResults(article, selected);
        });
        page.addView(submit);
        page.addView(space(24));
        setScreen(scroll, false);
    }

    private void showResults(JSONObject article, int[] selected) {
        onHomeScreen = false;
        JSONArray questions = article.optJSONArray("questions");
        int correct = 0;
        for (int i = 0; i < selected.length; i++) {
            if (selected[i] == questions.optJSONObject(i).optInt("answer")) correct++;
        }

        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(label("今日打卡完成 ✓", 28, GREEN, true));
        page.addView(label("答对 " + correct + " / " + selected.length
                + " 题。正确率用于复盘，不影响打卡。", 16, INK, false));
        page.addView(space(20));

        for (int i = 0; i < questions.length(); i++) {
            JSONObject q = questions.optJSONObject(i);
            int answer = q.optInt("answer");
            JSONArray options = q.optJSONArray("options");
            LinearLayout box = card(SURFACE);
            boolean right = selected[i] == answer;
            box.addView(label((i + 1) + ". " + (right ? "回答正确" : "需要复盘"),
                    17, right ? GREEN : DANGER, true));
            if (!right) {
                String diagnosis = diagnoseError(q, selected[i]);
                box.addView(label("本题错因：" + diagnosis, 14, DANGER, true));
            }
            box.addView(space(6));
            box.addView(label("你的答案：" + (char) ('A' + selected[i]) + "  ·  正确答案："
                    + (char) ('A' + answer), 14, INK, false));
            box.addView(label(options.optString(answer), 15, GREEN, true));
            box.addView(space(8));
            box.addView(label(q.optString("explanation"), 14, MUTED, false));
            String evidence = q.optString("evidenceSentence");
            if (evidence.isEmpty() || !article.optString("body").contains(evidence)) {
                evidence = findEvidenceSentence(article, q);
            }
            if (!evidence.isEmpty()) {
                box.addView(space(12));
                box.addView(label("文章证据", 14, GREEN, true));
                box.addView(label(evidence, 15, INK, false));
                JSONObject sentenceTranslations = article.optJSONObject("sentenceTranslations");
                String evidenceChinese = sentenceTranslations == null
                        ? "" : sentenceTranslations.optString(evidence);
                if (!evidenceChinese.isEmpty()) {
                    box.addView(label(evidenceChinese, 14, MUTED, false));
                }
            }
            JSONArray optionExplanations = q.optJSONArray("optionExplanations");
            box.addView(space(12));
            box.addView(label("选项分析", 14, GREEN, true));
            for (int j = 0; j < options.length(); j++) {
                if (right && j != answer) continue;
                if (!right && j != answer && j != selected[i]) continue;
                String reason = optionExplanations == null ? "" : optionExplanations.optString(j);
                if (reason.isEmpty()) {
                    reason = j == answer ? q.optString("explanation")
                            : "该选项与上方证据句或文章主旨不符。";
                }
                box.addView(label((char) ('A' + j) + ". " + reason,
                        14, j == answer ? GREEN : MUTED, false));
            }
            page.addView(box);
            page.addView(space(12));
        }
        Button home = primaryButton("返回首页");
        home.setOnClickListener(view -> showHome());
        page.addView(home);
        page.addView(space(24));
        setScreen(scroll, false);
    }

    private String findEvidenceSentence(JSONObject article, JSONObject question) {
        String body = article.optString("body");
        JSONArray options = question.optJSONArray("options");
        int answer = question.optInt("answer");
        String query = question.optString("prompt") + " "
                + (options == null ? "" : options.optString(answer));
        Set<String> keywords = new HashSet<>();
        String stopwords = "|the|a|an|and|or|but|is|are|was|were|be|been|to|of|in|on|at|for|"
                + "with|from|by|that|this|it|its|what|which|who|why|how|according|passage|"
                + "following|true|not|best|main|most|likely|can|could|would|should|";
        Matcher queryWords = WORD_PATTERN.matcher(query.toLowerCase(Locale.ROOT));
        while (queryWords.find()) {
            String word = queryWords.group();
            if (word.length() > 2 && !stopwords.contains("|" + word + "|")) keywords.add(word);
        }
        String best = "";
        int bestScore = -1;
        for (String sentence : body.split("(?<=[.!?])\\s+")) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            int score = 0;
            for (String keyword : keywords) if (lower.contains(keyword)) score++;
            if (score > bestScore) {
                best = sentence.trim();
                bestScore = score;
            }
        }
        return best;
    }

    private String diagnoseError(JSONObject question, int selected) {
        JSONArray errorTypes = question.optJSONArray("optionErrorTypes");
        String tagged = errorTypes == null ? "" : errorTypes.optString(selected).trim();
        if (!tagged.isEmpty() && !"正确".equals(tagged)) return tagged;
        String type = question.optString("type").toLowerCase(Locale.ROOT);
        if (type.contains("细节") || type.contains("detail")) return "没有定位证据句";
        if (type.contains("推理") || type.contains("infer")) return "推理过度";
        if (type.contains("主旨") || type.contains("main")) return "把局部信息当成主旨";
        if (type.contains("词义") || type.contains("word")) return "单词或语境理解错误";
        if (type.contains("态度") || type.contains("attitude")) return "作者态度判断错误";
        if (type.contains("结构") || type.contains("structure")) return "篇章结构理解错误";
        return "没有用原文证据排除选项";
    }

    private void recordStudyAnalytics(JSONObject article, int[] selected) {
        String today = LocalDate.now().format(DATE_FORMAT);
        String guard = "analyticsRecorded:" + article.optString("id") + ":" + today;
        if (prefs.getBoolean(guard, false)) return;
        JSONArray questions = article.optJSONArray("questions");
        if (questions == null) return;
        int correct = 0;
        ArrayList<String> errors = new ArrayList<>();
        for (int i = 0; i < selected.length; i++) {
            JSONObject question = questions.optJSONObject(i);
            if (question == null) continue;
            if (selected[i] == question.optInt("answer")) correct++;
            else errors.add(diagnoseError(question, selected[i]));
        }
        String week = weekKey();
        long readingSeconds = activeReadingSeconds > 0 ? activeReadingSeconds : Math.max(1,
                (System.currentTimeMillis() - readingStartedAt) / 1000L);
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(guard, true)
                .putInt(week + ":articles", prefs.getInt(week + ":articles", 0) + 1)
                .putInt(week + ":words", prefs.getInt(week + ":words", 0)
                        + article.optInt("wordCount"))
                .putInt(week + ":questions", prefs.getInt(week + ":questions", 0)
                        + selected.length)
                .putInt(week + ":correct", prefs.getInt(week + ":correct", 0) + correct)
                .putInt(week + ":clicks", prefs.getInt(week + ":clicks", 0)
                        + activeArticleClicks)
                .putLong(week + ":seconds", prefs.getLong(week + ":seconds", 0L)
                        + readingSeconds);
        for (String error : errors) {
            String key = week + ":error:" + error;
            editor.putInt(key, prefs.getInt(key, 0) + 1);
        }
        editor.apply();
    }

    private String weekKey() {
        WeekFields fields = WeekFields.ISO;
        LocalDate today = LocalDate.now();
        return "week:" + today.get(fields.weekBasedYear()) + "-"
                + String.format(Locale.US, "%02d", today.get(fields.weekOfWeekBasedYear()));
    }

    private String topWeeklyWeakness(String week) {
        if (prefs.getInt(week + ":questions", 0) < 10) return "";
        String prefix = week + ":error:";
        String best = "";
        int highest = 0;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(prefix) || !(entry.getValue() instanceof Integer)) continue;
            int count = (Integer) entry.getValue();
            if (count > highest) {
                highest = count;
                best = entry.getKey().substring(prefix.length());
            }
        }
        return best;
    }

    private String trainingAdvice(String weakness) {
        if (weakness.contains("证据") || weakness.contains("定位")) {
            return "细节题先在原文找到证据句，再看选项。";
        }
        if (weakness.contains("推理")) return "推理题只选原文能支持的结论，不凭常识扩展。";
        if (weakness.contains("主旨")) return "概括每段共同指向，不用一个细节代替全文。";
        if (weakness.contains("单词") || weakness.contains("语境")) {
            return "猜词时先看上下句逻辑，再根据词性缩小含义。";
        }
        if (weakness.contains("态度")) return "圈出表示评价的形容词和转折词，判断作者立场。";
        return "每道错题必须指出一句原文证据，再说明错项错在哪里。";
    }

    private int remainingDailyReviews() {
        String today = LocalDate.now().format(DATE_FORMAT);
        int completed = today.equals(prefs.getString("reviewDate", ""))
                ? prefs.getInt("reviewCountToday", 0) : 0;
        return Math.max(0, 12 - completed);
    }

    private void recordReviewToday() {
        String today = LocalDate.now().format(DATE_FORMAT);
        int completed = today.equals(prefs.getString("reviewDate", ""))
                ? prefs.getInt("reviewCountToday", 0) : 0;
        prefs.edit().putString("reviewDate", today)
                .putInt("reviewCountToday", completed + 1).apply();
    }

    private void markTodayComplete(JSONObject article, int[] selected) {
        updateAdaptiveLevel(article, selected);
        recordStudyAnalytics(article, selected);
        String today = LocalDate.now().format(DATE_FORMAT);
        Set<String> dates = new HashSet<>(prefs.getStringSet("completedDates", new HashSet<>()));
        Set<String> ids = new HashSet<>(prefs.getStringSet("completedIds", new HashSet<>()));
        ids.add(article.optString("id"));
        if (!dates.contains(today)) {
            String last = prefs.getString("lastCompletedDate", "");
            int streak = prefs.getInt("streak", 0);
            String yesterday = LocalDate.now().minusDays(1).format(DATE_FORMAT);
            streak = yesterday.equals(last) ? streak + 1 : 1;
            dates.add(today);
            prefs.edit()
                    .putStringSet("completedDates", dates)
                    .putStringSet("completedIds", ids)
                    .putString("lastCompletedDate", today)
                    .putInt("streak", streak)
                    .putInt("longestStreak", Math.max(streak, prefs.getInt("longestStreak", 0)))
                    .putInt("totalArticles", prefs.getInt("totalArticles", 0) + 1)
                    .putInt("totalWords", prefs.getInt("totalWords", 0)
                            + article.optInt("wordCount", 0))
                    .putString("lastArticleSource", article.optString("source"))
                    .apply();
        } else {
            prefs.edit().putStringSet("completedIds", ids)
                    .putString("lastArticleSource", article.optString("source")).apply();
        }
        maybeRequestContentRefill();
    }

    private void updateAdaptiveLevel(JSONObject article, int[] selected) {
        JSONArray questions = article.optJSONArray("questions");
        if (questions == null || selected.length == 0) return;
        int correct = 0;
        for (int i = 0; i < selected.length; i++) {
            JSONObject question = questions.optJSONObject(i);
            if (question != null && selected[i] == question.optInt("answer")) correct++;
        }
        float accuracy = correct / (float) selected.length;
        float clicksPerHundred = activeArticleClicks * 100f
                / Math.max(1, article.optInt("wordCount", 1));
        long seconds = activeReadingSeconds > 0 ? activeReadingSeconds : Math.max(1,
                (System.currentTimeMillis() - readingStartedAt) / 1000L);
        float score = prefs.getFloat("abilityScore", 1f);
        if (accuracy >= 0.8f && clicksPerHundred <= 10f) score += 0.25f;
        else if (accuracy < 0.6f || clicksPerHundred > 16f) score -= 0.25f;
        score = Math.max(0f, Math.min(2f, score));
        prefs.edit()
                .putFloat("abilityScore", score)
                .putFloat("lastAccuracy", accuracy)
                .putFloat("lastClicksPerHundred", clicksPerHundred)
                .putLong("lastReadingSeconds", seconds)
                .apply();
    }

    private boolean isTodayDone() {
        return prefs.getStringSet("completedDates", new HashSet<>())
                .contains(LocalDate.now().format(DATE_FORMAT));
    }

    private boolean isCompleted(String articleId) {
        return prefs.getStringSet("completedIds", new HashSet<>()).contains(articleId);
    }

    private void maybeRequestContentRefill() {
        if (refillManager == null || repository == null || prefs == null) return;
        Set<String> completedIds =
                prefs.getStringSet("completedIds", new HashSet<>());
        int unread = 0;
        for (int index = 0; index < repository.size(); index++) {
            JSONObject article = repository.get(index);
            if (article != null && !completedIds.contains(article.optString("id"))) {
                unread++;
            }
        }
        refillManager.requestIfBelowThreshold(unread);
    }

    private int completedDaysThisMonth() {
        String prefix = LocalDate.now().toString().substring(0, 7);
        int count = 0;
        for (String date : prefs.getStringSet("completedDates", new HashSet<>())) {
            if (date.startsWith(prefix)) count++;
        }
        return count;
    }

    private LinearLayout backRow(String text, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = label("‹  " + text, 16, GREEN, true);
        back.setPadding(0, dp(10), dp(10), dp(10));
        back.setOnClickListener(view -> action.run());
        row.addView(back);
        return row;
    }

    private ScrollView screenScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(CREAM);
        return scroll;
    }

    private LinearLayout page() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(24), dp(20), dp(28));
        return page;
    }

    private LinearLayout card(int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.setBackground(rounded(color, 18));
        box.setElevation(dp(1));
        return box;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1.08f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(rounded(PRIMARY, 14));
        button.setMinHeight(dp(52));
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(GREEN);
        button.setAllCaps(false);
        button.setBackground(rounded(GREEN_LIGHT, 14));
        button.setMinHeight(dp(50));
        return button;
    }

    private Space space(int heightDp) {
        Space space = new Space(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return space;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
