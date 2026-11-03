package com.gosen.reader;

import android.app.Activity;
import android.app.Dialog;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.StateListAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.LeadingMarginSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.ScaleAnimation;
import android.view.animation.TranslateAnimation;
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
import android.widget.PopupWindow;
import android.widget.ListView;
import android.widget.BaseAdapter;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private PopupWindow wordBubble;
    private final Set<String> reviewRound = new HashSet<>();
    private final Map<String, JSONObject> fallbackEntries = new HashMap<>();
    private final ExecutorService recommendations = Executors.newSingleThreadExecutor();
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
    private ReadingSession session;
    private boolean foreground;
    private boolean onReadingFlow;
    private boolean showingPassage;
    private boolean activeAssessment;
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
        migrateArticleTotals();
        buildShell();
        String resumeId = prefs.getString("resumeArticleId", "");
        showHome();
        JSONObject unfinished = repository.getById(resumeId);
        if (unfinished != null) {
            openSession(unfinished);
            String page = session.page;
            if ("quiz".equals(page)) showQuiz(unfinished);
            else if ("recall".equals(page)) showQuiz(unfinished);
            else showReader(unfinished);
        }
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
            CREAM = Color.rgb(12, 13, 17);
            SURFACE = Color.rgb(26, 28, 34);
            INK = Color.rgb(235, 240, 236);
            MUTED = Color.rgb(164, 176, 168);
            GOLD = Color.rgb(238, 188, 87);
            DANGER = Color.rgb(242, 139, 126);
        } else {
            GREEN = Color.rgb(47, 107, 79);
            PRIMARY = GREEN;
            GREEN_LIGHT = Color.rgb(229, 239, 232);
            CREAM = Color.rgb(245, 246, 250);
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
        foreground = true;
        if (showingPassage && session != null) session.resume(SystemClock.elapsedRealtime());
        if (updateManager != null) {
            updateManager.resumePendingInstall();
        }
    }

    @Override
    protected void onPause() {
        if (wordBubble != null) wordBubble.dismiss();
        foreground = false;
        if (session != null) session.pause(SystemClock.elapsedRealtime());
        saveSession();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        saveSession();
        super.onSaveInstanceState(state);
    }

    private void openSession(JSONObject article) {
        if (session == null || !session.articleId.equals(article.optString("id"))) {
            if (session != null) session.pause(SystemClock.elapsedRealtime());
            saveSession();
            session = ReadingSession.restore(article.optString("id"),
                    article.optJSONArray("questions").length(),
                    prefs.getString("session:" + article.optString("id"), "{}"));
        }
        activeArticle = article;
        activeAssessment = isCurrentAssessmentArticle(article);
    }

    private void enterReadingPage(JSONObject article, String page) {
        openSession(article);
        session.pause(SystemClock.elapsedRealtime());
        session.page = page;
        onReadingFlow = true;
        showingPassage = "reader".equals(page);
        if (foreground && showingPassage) session.resume(SystemClock.elapsedRealtime());
        saveSession();
    }

    private void saveSession() {
        if (session == null) return;
        try {
            SharedPreferences.Editor editor = prefs.edit().putString("session:" + session.articleId,
                    session.snapshot(SystemClock.elapsedRealtime()).toString());
            if (onReadingFlow) editor.putString("resumeArticleId", session.articleId);
            editor.apply();
        } catch (Exception ignored) { }
    }

    private long readingSeconds() {
        return session == null ? 1 : session.seconds(SystemClock.elapsedRealtime());
    }

    private int readingClicks() { return session == null ? 0 : session.clicks; }

    private void migrateArticleTotals() {
        if (prefs.getBoolean("articleTotalsV2", false)) return;
        Set<String> ids = prefs.getStringSet("completedIds", new HashSet<>());
        int words = 0;
        boolean allPresent = true;
        for (String id : ids) {
            JSONObject article = repository.getById(id);
            if (article == null) allPresent = false;
            else words += article.optInt("wordCount");
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putInt("totalArticles", ids.size()).putBoolean("articleTotalsV2", true);
        editor.putInt("totalWords", allPresent ? words : Math.max(words, prefs.getInt("totalWords", 0)));
        editor.apply();
    }

    @Override
    protected void onDestroy() {
        recommendations.shutdownNow();
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
        installPressAnimation(button);
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
            button.setBackground(rounded(selected ? GREEN_LIGHT : Color.TRANSPARENT, 24));
        }
    }

    private void setScreen(View view, boolean showNav) {
        if (wordBubble != null) wordBubble.dismiss();
        if (showNav) {
            if (session != null) session.pause(SystemClock.elapsedRealtime());
            saveSession();
            showingPassage = false;
            onReadingFlow = false;
            prefs.edit().remove("resumeArticleId").apply();
        }
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nav.setVisibility(showNav ? View.VISIBLE : View.GONE);
        animateEnter(view, 1f, dp(6));
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
        TextView greeting = label("阅读", 34, INK, true);
        header.addView(greeting, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button theme = secondaryButton(darkMode ? "☀" : "☾");
        theme.setContentDescription(darkMode ? "切换浅色模式" : "切换深色模式");
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
        page.addView(space(6));
        page.addView(label(LocalDate.now().format(DateTimeFormatter.ofPattern("MM.dd")), 14, MUTED, false));
        page.addView(space(24));

        LinearLayout widgets = new LinearLayout(this);
        widgets.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout streakCard = card(SURFACE);
        streakCard.addView(label("连续", 14, MUTED, false));
        TextView streak = label(prefs.getInt("streak", 0) + " 天", 38,
                GREEN, true);
        streak.setPadding(0, dp(4), 0, dp(4));
        streakCard.addView(streak);
        if (isTodayDone()) streakCard.addView(label("✓", 13, GREEN, true));
        widgets.addView(streakCard, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout reviewCard = card(GREEN_LIGHT);
        int due = learningStore.dueCount();
        reviewCard.addView(label("生词", 14, MUTED, false));
        reviewCard.addView(label(String.valueOf(due), 38, GREEN, true));
        reviewCard.setOnClickListener(view -> showReview());
        installPressAnimation(reviewCard);
        reviewCard.setContentDescription("生词复习，" + due + " 个到期词，点击开始");
        LinearLayout.LayoutParams reviewParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        reviewParams.leftMargin = dp(12);
        widgets.addView(reviewCard, reviewParams);
        page.addView(widgets);
        page.addView(space(28));

        if (repository.size() == 0) {
            LinearLayout empty = card(SURFACE);
            empty.addView(label("题库暂时无法读取", 17, INK, true));
            page.addView(empty);
        } else {
            LinearLayout recommendation = new LinearLayout(this);
            recommendation.setOrientation(LinearLayout.VERTICAL);
            android.widget.ProgressBar loading = new android.widget.ProgressBar(this);
            recommendation.addView(loading, new LinearLayout.LayoutParams(dp(28), dp(28)));
            page.addView(recommendation);
            JSONObject assessment = assessmentArticleIfDue();
            ArrayList<JSONObject> candidates = new ArrayList<>();
            Set<String> completed = new HashSet<>(prefs.getStringSet("completedIds", new HashSet<>()));
            for (int i = 0; i < repository.size(); i++) candidates.add(repository.get(i));
            Set<String> learning = learningStore.learningLemmas();
            float target = prefs.getFloat("abilityScore", 1f);
            String lastSource = prefs.getString("lastArticleSource", "");
            recommendations.execute(() -> {
                JSONObject article = assessment != null ? assessment
                        : todayArticle(candidates, completed, learning, target, lastSource);
                runOnUiThread(() -> {
                    if (isDestroyed() || content.getChildAt(0) != scroll) return;
                    recommendation.removeAllViews();
                    recommendation.addView(articleCard(article, true));
                });
            });
        }

        page.addView(space(20));
        LinearLayout progress = card(SURFACE);
        progress.addView(label("本月  " + completedDaysThisMonth() + " / "
                + LocalDate.now().lengthOfMonth(), 18, INK, true));
        progress.addView(space(8));
        android.widget.ProgressBar monthProgress = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        monthProgress.setMax(LocalDate.now().lengthOfMonth());
        monthProgress.setProgress(completedDaysThisMonth());
        monthProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        monthProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(GREEN_LIGHT));
        progress.addView(monthProgress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        page.addView(progress);

        setScreen(scroll, true);
    }

    private JSONObject todayArticle(ArrayList<JSONObject> candidates, Set<String> completed,
                                    Set<String> learning, float target, String lastSource) {
        JSONObject best = null;
        float bestScore = -1f;
        for (JSONObject candidate : candidates) {
            if (candidate == null || completed.contains(candidate.optString("id"))) continue;
            float difficultyFit = 1f - Math.min(1f,
                    Math.abs(articleLevel(candidate) - target) / 2f);
            int learningWords = learningStore.countLearningWords(candidate, learning);
            float vocabularyFit = Math.min(1f, learningWords / 10f);
            float variety = lastSource.equals(candidate.optString("source")) ? 0f : 1f;
            float score = difficultyFit * 40f + vocabularyFit * 35f + variety * 15f + 10f;
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        if (best != null) return best;
        int index = (LocalDate.now().getDayOfYear() - 1) % candidates.size();
        return candidates.get(index);
    }

    private JSONObject assessmentArticleIfDue() {
        int total = prefs.getInt("totalArticles", 0);
        int completedAssessments = prefs.getInt("assessmentCount", 0);
        if (total < 10 || completedAssessments >= total / 10) return null;

        String reservedId = prefs.getString("currentAssessmentArticleId", "");
        JSONObject reserved = repository.getById(reservedId);
        if (reserved != null && !isCompleted(reservedId)) return reserved;

        JSONObject best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (int i = 0; i < repository.size(); i++) {
                JSONObject candidate = repository.get(i);
                if (candidate == null || isCompleted(candidate.optString("id"))) continue;
                int score = candidate.optInt("difficultyScore", 42);
                int words = candidate.optInt("wordCount", 0);
                JSONArray questions = candidate.optJSONArray("questions");
                boolean fixedBand = articleLevel(candidate) == 1f
                        && score >= 34 && score <= 50
                        && words >= 240 && words <= 360
                        && questions != null && questions.length() >= 3;
                if (pass == 0 && !fixedBand) continue;
                int distance = Math.abs(score - 42) + Math.abs(words - 300) / 20;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        if (best != null) {
            prefs.edit().putString("currentAssessmentArticleId", best.optString("id")).apply();
        }
        return best;
    }

    private boolean isCurrentAssessmentArticle(JSONObject article) {
        return article != null && article.optString("id").equals(
                prefs.getString("currentAssessmentArticleId", ""));
    }

    private float articleLevel(JSONObject article) {
        if (article.has("difficultyLevel")) {
            return Math.max(0f, Math.min(2f, (float) article.optDouble("difficultyLevel", 1d)));
        }
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
        return articleCard(article, prominent, card(SURFACE));
    }

    private LinearLayout articleCard(JSONObject article, boolean prominent, LinearLayout box) {
        box.removeAllViews();
        boolean assessment = isCurrentAssessmentArticle(article);
        if (assessment && !prominent) {
            box.addView(label("阶段检测 · 固定难度对比", 13, GOLD, true));
            box.addView(space(6));
        }
        TextView tag = label((prominent ? (assessment ? "检测 · " : "今日 · ") : "")
                + article.optString("difficulty", "高中") + "  ·  "
                + article.optInt("wordCount", 0) + " 词"
                + (!prominent && article.has("difficultyScore")
                ? "  ·  难度 " + article.optInt("difficultyScore") : ""),
                13, GREEN, true);
        box.addView(tag);
        box.addView(space(8));
        String title = article.optString("title");
        if (prominent) title = title.replaceFirst("^RACE\\s+高中英语阅读\\s+", "");
        box.addView(label(title, prominent ? 23 : 18, INK, true));
        if (!prominent) {
            box.addView(space(6));
            box.addView(label(article.optString("source", "英语阅读"), 13, MUTED, false));
        }
        int learningWords = prominent ? 0 : learningStore.learningWordCount(article);
        if (learningWords > 0) {
            box.addView(space(6));
            box.addView(label("包含 " + learningWords + " 个你正在巩固的词",
                    13, GREEN, true));
        }
        box.addView(space(16));
        Button read = primaryButton(isCompleted(article.optString("id"))
                ? "再次阅读" : assessment ? "开始阶段检测" : "开始阅读");
        read.setOnClickListener(view -> showReader(article));
        box.addView(read);
        return box;
    }

    private void showLibrary() {
        onHomeScreen = false;
        selectNav("题库");
        ListView list = new ListView(this);
        list.setBackgroundColor(CREAM);
        list.setPadding(dp(20), 0, dp(20), dp(28));
        list.setClipToPadding(false);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(12));
        LinearLayout page = page();
        page.setPadding(0, dp(24), 0, dp(6));
        list.addHeaderView(page, null, false);
        ArrayList<JSONObject> articles = new ArrayList<>();
        for (int i = 0; i < repository.size(); i++) articles.add(repository.get(i));
        page.addView(label("阅读题库", 28, INK, true));
        page.addView(label(repository.size() + " 篇文章 · " + unreadCount() + " 篇未读",
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
                    if (content.getChildAt(0) == list) showLibrary();
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
        list.setAdapter(new BaseAdapter() {
            public int getCount() { return articles.size(); }
            public JSONObject getItem(int position) { return articles.get(position); }
            public long getItemId(int position) { return position; }
            public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout box = recycled instanceof LinearLayout
                        ? (LinearLayout) recycled : card(SURFACE);
                return articleCard(getItem(position), false, box);
            }
        });
        setScreen(list, true);
    }

    private void showReview() {
        onHomeScreen = false;
        selectNav("复习");
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(label("生词复习", 28, INK, true));
        page.addView(space(10));
        Button manageVocabulary = secondaryButton("管理全部生词与释义");
        manageVocabulary.setOnClickListener(view -> showVocabularyManager());
        page.addView(manageVocabulary);
        page.addView(space(18));

        ArrayList<JSONObject> dueWords = new ArrayList<>(learningStore.dueWords(Integer.MAX_VALUE));
        Set<String> available = new HashSet<>();
        for (JSONObject item : dueWords) available.add(item.optString("senseKey", item.optString("lemma")));
        for (JSONObject item : learningStore.allWords(Integer.MAX_VALUE)) {
            if (available.add(item.optString("senseKey", item.optString("lemma")))) dueWords.add(item);
        }
        dueWords.removeIf(item -> reviewRound.contains(item.optString("senseKey", item.optString("lemma"))));
        if (dueWords.isEmpty() && learningStore.totalCount() > 0) {
            reviewRound.clear();
            dueWords.addAll(learningStore.allWords(Integer.MAX_VALUE));
        }
        if (dueWords.isEmpty()) {
            LinearLayout done = card(GREEN_LIGHT);
            done.addView(label("暂无生词", 21, GREEN, true));
            done.addView(space(6));
            done.addView(label("生词本 " + learningStore.totalCount() + " 个词 · 已掌握 "
                    + learningStore.statusCount("mastered") + " 个", 14, MUTED, false));
            page.addView(done);
            setScreen(scroll, true);
            return;
        }

        JSONObject item = dueWords.get(0);
        String senseKey = item.optString("senseKey", item.optString("lemma"));
        String lemma = item.optString("lemma");
        String displayWord = item.optString("displayWord", lemma);
        JSONObject reviewContext = learningStore.reviewContext(item);
        String sentence = reviewContext.optString("sentence", item.optString("sentence"));
        String contextMeaning = reviewContext.optString("meaning", item.optString("translation"));
        String contextTranslation = reviewContext.optString(
                "translation", item.optString("sentenceTranslation"));

        FrameLayout flashcard = new FrameLayout(this);
        flashcard.setMinimumHeight(dp(360));
        flashcard.setPadding(dp(24), dp(28), dp(24), dp(28));
        flashcard.setBackground(rounded(SURFACE, 22));
        flashcard.setElevation(dp(3));

        LinearLayout front = new LinearLayout(this);
        front.setGravity(Gravity.CENTER);
        TextView frontWord = label(displayWord, 38, INK, true);
        frontWord.setGravity(Gravity.CENTER);
        front.addView(frontWord);

        LinearLayout back = new LinearLayout(this);
        back.setOrientation(LinearLayout.VERTICAL);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setVisibility(View.GONE);
        TextView backWord = label(displayWord + "  ·  " + item.optString("pos", "—"),
                25, INK, true);
        backWord.setGravity(Gravity.CENTER);
        back.addView(backWord);
        TextView backMeaning = label(contextMeaning, 26, GREEN, true);
        backMeaning.setGravity(Gravity.CENTER);
        back.addView(backMeaning);
        JSONArray senses = item.optJSONArray("senses");
        if (senses != null && senses.length() > 1) {
            back.addView(label(item.optString("translation"), 15, MUTED, false));
        }
        back.addView(space(14));
        back.addView(label("词形：" + item.optString("forms", "—"), 14, MUTED, false));
        back.addView(space(10));
        back.addView(label(sentence, 16, INK, false));
        if (!contextTranslation.isEmpty()) {
            back.addView(space(5));
            back.addView(label(contextTranslation, 15, MUTED, false));
        }
        back.addView(space(12));
        back.addView(label(learningStore.statusLabel(item) + " · 已点击 "
                + item.optInt("clickCount") + " 次 · "
                + item.optInt("contextCount", 1) + " 个语境", 13, MUTED, false));

        flashcard.addView(front, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        flashcard.addView(back, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView swipeFeedback = label("", 28, GREEN, true);
        swipeFeedback.setGravity(Gravity.CENTER);
        swipeFeedback.setAlpha(0f);
        flashcard.addView(swipeFeedback, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40), Gravity.TOP));
        installFlashcardGestures(flashcard, front, back, swipeFeedback, () -> {
            reviewRound.add(senseKey);
            learningStore.removeWord(senseKey);
            recordReviewToday();
            if (content.getChildAt(0) == scroll) showReview();
        }, () -> {
            reviewRound.add(senseKey);
            learningStore.answer(senseKey, LearningStore.VAGUE);
            recordReviewToday();
            if (content.getChildAt(0) == scroll) showReview();
        });
        page.addView(flashcard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        page.addView(space(12));
        LinearLayout swipeGuide = new LinearLayout(this);
        swipeGuide.setGravity(Gravity.CENTER);
        TextView leftGuide = label("← 认识，移除", 14, GREEN, true);
        leftGuide.setGravity(Gravity.START);
        TextView rightGuide = label("保留，继续复习 →", 14, GOLD, true);
        rightGuide.setGravity(Gravity.END);
        swipeGuide.addView(leftGuide, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        swipeGuide.addView(rightGuide, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(swipeGuide);
        setScreen(scroll, true);
    }

    private void installFlashcardGestures(View card, View front, View back, TextView feedback,
                                          Runnable onSwipeLeft, Runnable onSwipeRight) {
        final float[] downX = {0f};
        final float[] downY = {0f};
        final boolean[] horizontal = {false};
        final boolean[] showingBack = {false};
        final boolean[] busy = {false};
        card.setCameraDistance(dp(8000));
        card.setOnTouchListener((view, event) -> {
            if (busy[0]) return true;
            float deltaX = event.getRawX() - downX[0];
            float deltaY = event.getRawY() - downY[0];
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX[0] = event.getRawX();
                downY[0] = event.getRawY();
                horizontal[0] = false;
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                if (!horizontal[0] && Math.abs(deltaX) > dp(10)
                        && Math.abs(deltaX) > Math.abs(deltaY)) {
                    horizontal[0] = true;
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (horizontal[0]) {
                    boolean left = deltaX < 0;
                    feedback.setText(left ? "✓" : "↻");
                    feedback.setTextColor(left ? GREEN : GOLD);
                    feedback.setAlpha(Math.min(1f, Math.abs(deltaX) / dp(90)));
                    ((GradientDrawable) view.getBackground()).setStroke(dp(2), left ? GREEN : GOLD);
                    view.setTranslationX(deltaX);
                    view.setRotation(deltaX / Math.max(1f, view.getWidth()) * 8f);
                    view.setAlpha(Math.max(0.55f,
                            1f - Math.abs(deltaX) / Math.max(1f, view.getWidth())));
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (horizontal[0] && Math.abs(deltaX) >= dp(90)) {
                    busy[0] = true;
                    boolean left = deltaX < 0;
                    feedback.setAlpha(1f);
                    view.announceForAccessibility(left ? "已移除" : "已保留");
                    float target = left ? -Math.max(view.getWidth(), dp(420))
                            : Math.max(view.getWidth(), dp(420));
                    view.animate().translationX(target).rotation(left ? -10f : 10f)
                            .alpha(0f).setDuration(motionEnabled() ? 180 : 0)
                            .withEndAction(left ? onSwipeLeft : onSwipeRight).start();
                } else {
                    feedback.setAlpha(0f);
                    ((GradientDrawable) view.getBackground()).setStroke(0, Color.TRANSPARENT);
                    view.animate().translationX(0f).rotation(0f).alpha(1f)
                            .setDuration(motionEnabled() ? 140 : 0).start();
                    if (!horizontal[0] && Math.abs(deltaX) < dp(10)
                            && Math.abs(deltaY) < dp(10)) {
                        busy[0] = true;
                        view.animate().rotationY(motionEnabled() ? 90f : 0f)
                                .setDuration(motionEnabled() ? 90 : 0).withEndAction(() -> {
                                    showingBack[0] = !showingBack[0];
                                    front.setVisibility(showingBack[0] ? View.GONE : View.VISIBLE);
                                    back.setVisibility(showingBack[0] ? View.VISIBLE : View.GONE);
                                    view.setRotationY(motionEnabled() ? -90f : 0f);
                                    view.animate().rotationY(0f).setDuration(motionEnabled() ? 130 : 0)
                                            .withEndAction(() -> busy[0] = false).start();
                                }).start();
                    }
                }
                view.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                feedback.setAlpha(0f);
                ((GradientDrawable) view.getBackground()).setStroke(0, Color.TRANSPARENT);
                view.animate().translationX(0f).rotation(0f).alpha(1f)
                        .setDuration(motionEnabled() ? 140 : 0).start();
                view.getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            }
            return false;
        });
    }

    private void showVocabularyManager() {
        showVocabularyManager(0);
    }

    private void showVocabularyManager(int scrollY) {
        onHomeScreen = false;
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(backRow("返回复习", this::showReview));
        page.addView(space(12));
        page.addView(label("生词本管理", 28, INK, true));
        page.addView(space(16));
        ArrayList<JSONObject> words = new ArrayList<>(learningStore.allWords(200));
        if (words.isEmpty()) {
            page.addView(label("暂无生词",
                    16, MUTED, false));
            setScreen(scroll, false);
            return;
        }
        for (JSONObject item : words) {
            String senseKey = item.optString("senseKey", item.optString("lemma"));
            LinearLayout box = card(SURFACE);
            box.addView(label(item.optString("displayWord", item.optString("lemma"))
                    + "  ·  " + item.optString("pos", "—"), 19, INK, true));
            TextView meaning = label(item.optString("translation"), 18, GREEN, true);
            box.addView(meaning);
            box.addView(label(learningStore.statusLabel(item) + " · "
                    + item.optInt("contextCount", 1) + " 个语境 · 点击 "
                    + item.optInt("clickCount") + " 次", 13, MUTED, false));
            box.addView(space(10));
            EditText correction = new EditText(this);
            correction.setText(item.optString("translation"));
            correction.setTextColor(INK);
            correction.setTextSize(15);
            correction.setVisibility(View.GONE);
            correction.setBackground(rounded(GREEN_LIGHT, 12));
            correction.setPadding(dp(12), dp(8), dp(12), dp(8));
            Button save = primaryButton("保存释义");
            save.setVisibility(View.GONE);
            Button edit = secondaryButton("修正释义");
            edit.setOnClickListener(view -> {
                correction.setVisibility(View.VISIBLE);
                save.setVisibility(View.VISIBLE);
                edit.setVisibility(View.GONE);
            });
            save.setOnClickListener(view -> {
                String corrected = correction.getText().toString().trim();
                if (corrected.isEmpty()) return;
                learningStore.correctMeaning(senseKey, corrected, item.optString("sentence"));
                meaning.setText(corrected);
                correction.setVisibility(View.GONE);
                save.setVisibility(View.GONE);
                edit.setVisibility(View.VISIBLE);
            });
            Button remove = secondaryButton("移出生词本");
            remove.setOnClickListener(view -> {
                int position = scroll.getScrollY();
                learningStore.removeWord(senseKey);
                showVocabularyManager(position);
            });
            box.addView(edit);
            box.addView(correction);
            box.addView(save);
            box.addView(space(6));
            box.addView(remove);
            page.addView(box);
            page.addView(space(10));
        }
        if (learningStore.totalCount() > words.size()) {
            page.addView(label("当前只显示最近的 200 个词。", 13, MUTED, false));
        }
        scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                                  int oldLeft, int oldTop, int oldRight, int oldBottom) {
                scroll.removeOnLayoutChangeListener(this);
                if (content.getChildAt(0) == scroll) scroll.scrollTo(0, scrollY);
            }
        });
        setScreen(scroll, false);
    }

    private void showStats() {
        onHomeScreen = false;
        selectNav("统计");
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(label("阅读统计", 28, INK, true));
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
        page.addView(assessmentProgressCard());
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

    private LinearLayout assessmentProgressCard() {
        LinearLayout box = card(SURFACE);
        box.addView(label("阶段能力检测", 18, INK, true));
        box.addView(space(8));
        JSONArray history = assessmentHistory();
        if (history.length() == 0) {
            int remaining = Math.max(0, 10 - prefs.getInt("totalArticles", 0));
            box.addView(label("再完成 " + remaining
                    + " 篇后建立第一份固定难度基线。", 14, MUTED, false));
            return box;
        }
        JSONObject latest = history.optJSONObject(history.length() - 1);
        box.addView(label("最近一次：正确率 " + latest.optInt("accuracyPercent")
                + "% · 每百词查词 " + latest.optInt("clicksPerHundred")
                + " 次 · " + latest.optInt("wordsPerMinute") + " 词/分钟",
                14, MUTED, false));
        if (history.length() >= 2) {
            JSONObject previous = history.optJSONObject(history.length() - 2);
            int accuracyDelta = latest.optInt("accuracyPercent")
                    - previous.optInt("accuracyPercent");
            int clickImprovement = previous.optInt("clicksPerHundred")
                    - latest.optInt("clicksPerHundred");
            int speedDelta = latest.optInt("wordsPerMinute")
                    - previous.optInt("wordsPerMinute");
            box.addView(space(6));
            box.addView(label("相比上次：正确率 " + signed(accuracyDelta) + " 个百分点"
                    + " · 查词变化 " + signed(-clickImprovement) + " 次/百词"
                    + " · 速度 " + signed(speedDelta) + " 词/分钟",
                    14, accuracyDelta >= 0 && clickImprovement >= 0 ? GREEN : MUTED, true));
        }
        int nextAt = (prefs.getInt("assessmentCount", 0) + 1) * 10;
        int remaining = Math.max(0, nextAt - prefs.getInt("totalArticles", 0));
        box.addView(space(8));
        box.addView(label(remaining == 0 ? "下一次阶段检测已到期"
                : "距离下一次检测还有 " + remaining + " 篇", 13, GOLD, true));
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
        enterReadingPage(article, "reader");
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

        TextView body = label("", 19, INK, false);
        body.setLineSpacing(dp(7), 1.05f);
        body.setTextIsSelectable(false);
        body.setHighlightColor(Color.TRANSPARENT);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setText(makeClickableArticle(article));
        int savedScroll = session.readerScroll;
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (session != null) session.readerScroll = y;
        });
        scroll.post(() -> scroll.scrollTo(0, savedScroll));
        page.addView(body);
        page.addView(space(28));

        Button finish = primaryButton("完成阅读，开始答题");
        finish.setOnClickListener(view -> showQuiz(article));
        page.addView(finish);
        page.addView(space(24));
        setScreen(scroll, false);
    }

    private SpannableString makeClickableArticle(JSONObject article) {
        String body = article.optString("body");
        SpannableString text = makeClickableText(article, body, null);
        int paragraphStart = 0;
        while (paragraphStart < body.length()) {
            int newline = body.indexOf('\n', paragraphStart);
            int paragraphEnd = newline >= 0 ? newline : body.length();
            if (!body.substring(paragraphStart, paragraphEnd).trim().isEmpty()) {
                text.setSpan(new LeadingMarginSpan.Standard(dp(38), 0),
                        paragraphStart, paragraphEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (newline < 0) break;
            paragraphStart = newline + 1;
        }
        return text;
    }

    private SpannableString makeClickableText(JSONObject article, String value,
                                              String fixedContext) {
        SpannableString text = new SpannableString(value);
        Matcher matcher = WORD_PATTERN.matcher(value);
        while (matcher.find()) {
            final String word = matcher.group();
            final int wordStart = matcher.start();
            final String sentence = fixedContext == null
                    ? findSentence(value, wordStart) : fixedContext;
            text.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    if (wordBubble != null) wordBubble.dismiss();
                    showWordCard(article, word, sentence, (TextView) widget, wordStart);
                }

                @Override
                public void updateDrawState(TextPaint paint) {
                    paint.setColor(INK);
                    paint.setUnderlineText(false);
                    paint.setTypeface(Typeface.create(paint.getTypeface(), Typeface.NORMAL));
                }
            }, matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            String key = word.toLowerCase(Locale.ROOT).replace('’', '\'');
            JSONObject glossary = article.optJSONObject("glossary");
            JSONObject entry = glossary == null ? null : glossary.optJSONObject(key);
            if (entry == null) entry = commonEntry(key);
            String lemma = entry == null ? roughLemma(key) : entry.optString("lemma", key);
            text.setSpan(new LookupMark(lemma), matcher.start(), matcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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
        showWordCard(article, displayedWord, sentence, null, 0);
    }

    private void showWordCard(JSONObject article, String displayedWord, String sentence,
                              TextView bubbleAnchor, int wordOffset) {
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

        String generatedTranslation = contextEntry != null
                ? contextEntry.optString("translation", entry.optString("translation"))
                : entry == null ? "该词释义待题库处理器补充"
                : entry.optString("translation");
        String overrideKey = "meaningOverride:" + article.optString("id") + ":" + key
                + ":" + Integer.toHexString(sentence.hashCode());
        String translation = prefs.getString(overrideKey, generatedTranslation);
        TextView translationView = label(translation, 24, GREEN, true);
        sheet.addView(translationView);
        String pos = contextEntry != null
                ? contextEntry.optString("pos", entry.optString("pos", "—"))
                : entry == null ? "—" : entry.optString("pos", "—");
        String forms = entry == null ? "—" : entry.optString("forms", "—");
        String meanings = entry == null ? translation : entry.optString("meanings", translation);
        JSONObject translations = article.optJSONObject("sentenceTranslations");
        String chinese = translations == null ? "" : translations.optString(sentence);
        if (showingPassage && session != null) { session.clicks++; saveSession(); }
        if (learningStore.containsLemma(lemma)) {
            learningStore.recordWord(lemma, displayedWord, translation, pos, forms, meanings,
                    sentence, chinese, article.optString("id"), article.optString("title"), history);
        }
        if (bubbleAnchor != null) {
            showTranslationBubble(bubbleAnchor, wordOffset, translation,
                    () -> showWordCard(article, displayedWord, sentence));
            return;
        }
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

        JSONArray phrases = article.optJSONArray("phrases");
        boolean phraseHeaderShown = false;
        int shownPhrases = 0;
        if (phrases != null) {
            for (int i = 0; i < phrases.length() && shownPhrases < 3; i++) {
                JSONObject phrase = phrases.optJSONObject(i);
                if (phrase == null || !sentence.equals(phrase.optString("sentence"))
                        || !phraseContainsWord(phrase.optString("phrase"), displayedWord)) {
                    continue;
                }
                if (!phraseHeaderShown) {
                    sheet.addView(label("所在搭配", 14, GOLD, true));
                    phraseHeaderShown = true;
                }
                sheet.addView(label(phrase.optString("phrase") + "  ·  "
                        + phrase.optString("translation"), 17, INK, true));
                String note = phrase.optString("note");
                if (!note.isEmpty()) sheet.addView(label(note, 13, MUTED, false));
                sheet.addView(space(8));
                shownPhrases++;
            }
            if (phraseHeaderShown) sheet.addView(space(4));
        }

        sheet.addView(label("所在句", 14, MUTED, true));
        sheet.addView(label(sentence, 15, INK, false));
        sheet.addView(space(8));
        sheet.addView(label(chinese.isEmpty() ? "本句翻译待题库处理器补充。" : chinese,
                15, GREEN, false));
        sheet.addView(space(18));

        android.widget.Switch waveToggle = new android.widget.Switch(this);
        waveToggle.setText("加入生词复习");
        waveToggle.setTextSize(16);
        waveToggle.setTextColor(INK);
        waveToggle.setPadding(0, dp(8), 0, dp(8));
        waveToggle.setMinHeight(dp(48));
        waveToggle.setChecked(learningStore.containsLemma(lemma));
        waveToggle.setOnCheckedChangeListener((button, checked) -> {
            if (checked) {
                learningStore.recordWord(lemma, displayedWord, translation, pos, forms, meanings,
                        sentence, chinese, article.optString("id"), article.optString("title"), history);
            } else {
                learningStore.removeLemma(lemma);
            }
            invalidateLookupMarks(content);
        });
        sheet.addView(waveToggle);
        sheet.addView(space(12));
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
        animateEnter(sheet, 1f, dp(18));
    }

    private void showTranslationBubble(TextView anchor, int offset, String translation, Runnable expand) {
        TextView tip = label(translation, 17, INK, true);
        tip.setPadding(dp(18), dp(12), dp(18), dp(12));
        tip.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(40));
        tip.setBackground(rounded(SURFACE, 20));
        tip.measure(View.MeasureSpec.makeMeasureSpec(
                getResources().getDisplayMetrics().widthPixels - dp(40), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        int line = anchor.getLayout().getLineForOffset(offset);
        Matcher word = WORD_PATTERN.matcher(anchor.getText());
        word.region(offset, anchor.getText().length());
        int end = word.lookingAt() ? word.end() : offset + 1;
        Path selection = new Path();
        anchor.getLayout().getSelectionPath(offset,
                Math.min(end, anchor.getLayout().getLineEnd(line)), selection);
        android.graphics.RectF bounds = new android.graphics.RectF();
        selection.computeBounds(bounds, true);
        float wordCenter = location[0] + anchor.getTotalPaddingLeft()
                + bounds.centerX() - anchor.getScrollX();
        int width = Math.max(tip.getMeasuredWidth(), dp(48));
        int arrowHeight = dp(8);
        int height = tip.getMeasuredHeight() + arrowHeight;
        android.graphics.Rect visible = new android.graphics.Rect();
        anchor.getWindowVisibleDisplayFrame(visible);
        int x = Math.max(visible.left + dp(12), Math.min((int) wordCenter - width / 2,
                visible.right - width - dp(12)));
        int y = location[1] + anchor.getTotalPaddingTop()
                + anchor.getLayout().getLineTop(line) - anchor.getScrollY() - height - dp(3);
        boolean above = y >= visible.top;
        if (!above) y = location[1] + anchor.getTotalPaddingTop()
                + anchor.getLayout().getLineBottom(line) - anchor.getScrollY() + dp(3);
        final float arrowCenter = wordCenter - x;
        FrameLayout bubble = new FrameLayout(this) {
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(SURFACE);
                Path arrow = new Path();
                float base = above ? getHeight() - arrowHeight : arrowHeight;
                arrow.moveTo(arrowCenter - dp(7), base);
                arrow.lineTo(arrowCenter, above ? getHeight() : 0);
                arrow.lineTo(arrowCenter + dp(7), base);
                arrow.close();
                canvas.drawPath(arrow, paint);
            }
        };
        bubble.setWillNotDraw(false);
        bubble.setPadding(0, above ? 0 : arrowHeight, 0, above ? arrowHeight : 0);
        bubble.addView(tip, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        PopupWindow popup = new PopupWindow(bubble, width, height, false);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(10));
        popup.setOutsideTouchable(true);
        if (Build.VERSION.SDK_INT >= 29) popup.setTouchModal(false);
        wordBubble = popup;
        bubble.setOnClickListener(v -> { popup.dismiss(); expand.run(); });
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT, x, y);
        animateEnter(bubble, 0.94f, 0);
    }

    private boolean phraseContainsWord(String phrase, String word) {
        String normalizedWord = word.toLowerCase(Locale.ROOT).replace('’', '\'');
        Matcher matcher = WORD_PATTERN.matcher(phrase.toLowerCase(Locale.ROOT).replace('’', '\''));
        while (matcher.find()) {
            if (normalizedWord.equals(matcher.group())) return true;
        }
        return false;
    }

    private JSONObject commonEntry(String word) {
        if (fallbackEntries.containsKey(word)) return fallbackEntries.get(word);
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
        if (values == null) {
            fallbackEntries.put(word, null);
            return null;
        }
        JSONObject result = new JSONObject();
        try {
            result.put("lemma", values[0]);
            result.put("translation", values[1]);
            result.put("pos", values[2]);
            result.put("forms", values[3]);
            result.put("meanings", values[4]);
        } catch (Exception ignored) { }
        fallbackEntries.put(word, result);
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
        enterReadingPage(article, "quiz");
        JSONArray questions = article.optJSONArray("questions");
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);
        page.addView(backRow("返回文章", () -> showReader(article)));
        page.addView(space(12));
        page.addView(label("完成题目", 28, INK, true));
        page.addView(space(18));

        int savedQuizScroll = session.quizScroll;
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            if (session != null) session.quizScroll = y;
        });
        scroll.post(() -> scroll.scrollTo(0, savedQuizScroll));
        ArrayList<RadioGroup> groups = new ArrayList<>();
        for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.optJSONObject(i);
            LinearLayout box = card(SURFACE);
            SpannableStringBuilder prompt = new SpannableStringBuilder()
                    .append((i + 1) + ". ")
                    .append(makeClickableText(article, question.optString("prompt"),
                            question.optString("prompt")));
            TextView promptView = label("", 17, INK, true);
            promptView.setText(prompt);
            promptView.setMovementMethod(LinkMovementMethod.getInstance());
            promptView.setHighlightColor(Color.TRANSPARENT);
            box.addView(promptView);
            box.addView(space(10));
            RadioGroup group = new RadioGroup(this);
            JSONArray options = question.optJSONArray("options");
            for (int j = 0; j < options.length(); j++) {
                RadioButton option = new RadioButton(this) {
                    @Override protected void onDraw(Canvas canvas) {
                        super.onDraw(canvas);
                        drawLookupMarks(this, canvas);
                    }
                };
                option.setId(View.generateViewId());
                option.setTag(j);
                String optionText = options.optString(j);
                option.setText(new SpannableStringBuilder()
                        .append(String.format(Locale.US, "%c. ", (char) ('A' + j)))
                        .append(makeClickableText(article, optionText, optionText)));
                option.setTextSize(15);
                option.setTextColor(INK);
                option.setPadding(0, dp(6), 0, dp(6));
                option.setHighlightColor(Color.TRANSPARENT);
                option.setMovementMethod(LinkMovementMethod.getInstance());
                group.addView(option);
                if (session.answers[i] == j) group.check(option.getId());
            }
            final int questionIndex = i;
            group.setOnCheckedChangeListener((radioGroup, checkedId) -> {
                RadioButton checked = radioGroup.findViewById(checkedId);
                if (session != null && checked != null) {
                    session.answers[questionIndex] = (int) checked.getTag();
                    saveSession();
                }
            });
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
            submit.setEnabled(false);
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
        page.addView(label("答对 " + correct + " / " + selected.length + " 题", 16, INK, false));
        page.addView(space(20));
        if (activeAssessment) {
            JSONObject result = latestAssessment();
            LinearLayout assessment = card(GREEN_LIGHT);
            assessment.addView(label("阶段检测结果", 19, GREEN, true));
            assessment.addView(space(6));
            assessment.addView(label("正确率 " + result.optInt("accuracyPercent")
                    + "% · 每百词查词 " + result.optInt("clicksPerHundred")
                    + " 次 · " + result.optInt("wordsPerMinute") + " 词/分钟",
                    15, INK, true));
            assessment.addView(label("检测难度 " + result.optInt("difficultyScore")
                    + "；今后会继续从同一难度区间抽题比较。", 13, MUTED, false));
            page.addView(assessment);
            page.addView(space(16));
        }

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
            if (evidence.isEmpty() || !article.optString("body").contains(evidence)
                    || evidence.split("\\s+").length < 4) {
                evidence = "";
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
            if (evidence.isEmpty()) box.addView(label("证据解析待补充", 14, MUTED, false));
            JSONArray optionExplanations = q.optJSONArray("optionExplanations");
            box.addView(space(12));
            box.addView(label("选项分析", 14, GREEN, true));
            for (int j = 0; j < options.length(); j++) {
                if (right && j != answer) continue;
                if (!right && j != answer && j != selected[i]) continue;
                String reason = optionExplanations == null ? "" : optionExplanations.optString(j);
                if (reason.isEmpty() || reason.equals("该选项不能由原文证据支持。")
                        || reason.equals("该选项与原文证据及题目给定答案一致。")) {
                    reason = "本选项解析待补充";
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
        onReadingFlow = false;
        showingPassage = false;
        prefs.edit().remove("session:" + article.optString("id"))
                .remove("resumeArticleId").commit();
        session = null;
        setScreen(scroll, false);
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
        long readingSeconds = readingSeconds();
        SharedPreferences.Editor editor = prefs.edit()
                .putBoolean(guard, true)
                .putInt(week + ":articles", prefs.getInt(week + ":articles", 0) + 1)
                .putInt(week + ":words", prefs.getInt(week + ":words", 0)
                        + article.optInt("wordCount"))
                .putInt(week + ":questions", prefs.getInt(week + ":questions", 0)
                        + selected.length)
                .putInt(week + ":correct", prefs.getInt(week + ":correct", 0) + correct)
                .putInt(week + ":clicks", prefs.getInt(week + ":clicks", 0)
                        + readingClicks())
                .putLong(week + ":seconds", prefs.getLong(week + ":seconds", 0L)
                        + readingSeconds);
        Map<String, Integer> errorCounts = new HashMap<>();
        for (String error : errors) errorCounts.put(error, errorCounts.getOrDefault(error, 0) + 1);
        for (Map.Entry<String, Integer> error : errorCounts.entrySet()) {
            String key = week + ":error:" + error.getKey();
            editor.putInt(key, prefs.getInt(key, 0) + error.getValue());
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

    private void recordReviewToday() {
        String today = LocalDate.now().format(DATE_FORMAT);
        int completed = today.equals(prefs.getString("reviewDate", ""))
                ? prefs.getInt("reviewCountToday", 0) : 0;
        prefs.edit().putString("reviewDate", today)
                .putInt("reviewCountToday", completed + 1).apply();
    }

    private void markTodayComplete(JSONObject article, int[] selected) {
        String today = LocalDate.now().format(DATE_FORMAT);
        Set<String> dates = new HashSet<>(prefs.getStringSet("completedDates", new HashSet<>()));
        Set<String> ids = new HashSet<>(prefs.getStringSet("completedIds", new HashSet<>()));
        boolean firstAttempt = ids.add(article.optString("id"));
        if (firstAttempt) {
            updateAdaptiveLevel(article, selected);
            recordStudyAnalytics(article, selected);
            if (activeAssessment) recordAssessmentResult(article, selected);
        } else {
            prefs.edit().putInt("repeatAttempts", prefs.getInt("repeatAttempts", 0) + 1).apply();
        }
        SharedPreferences.Editor editor = prefs.edit().putStringSet("completedIds", ids)
                .putString("lastArticleSource", article.optString("source"));
        if (firstAttempt) {
            editor.putInt("totalArticles", prefs.getInt("totalArticles", 0) + 1)
                    .putInt("totalWords", prefs.getInt("totalWords", 0) + article.optInt("wordCount", 0));
        }
        if (dates.add(today)) {
            String yesterday = LocalDate.now().minusDays(1).format(DATE_FORMAT);
            int streak = yesterday.equals(prefs.getString("lastCompletedDate", ""))
                    ? prefs.getInt("streak", 0) + 1 : 1;
            editor.putStringSet("completedDates", dates).putString("lastCompletedDate", today)
                    .putInt("streak", streak)
                    .putInt("longestStreak", Math.max(streak, prefs.getInt("longestStreak", 0)));
        }
        editor.commit();
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
        float clicksPerHundred = readingClicks() * 100f
                / Math.max(1, article.optInt("wordCount", 1));
        long seconds = readingSeconds();
        float score = prefs.getFloat("abilityScore", 1f);
        float lookupPenalty = Math.max(0f, clicksPerHundred - 8f) / 24f;
        float observedLevel = articleLevel(article)
                + (accuracy - 0.70f) * 1.2f - lookupPenalty;
        observedLevel = Math.max(0f, Math.min(2f, observedLevel));
        score = Math.max(0f, Math.min(2f, score * 0.70f + observedLevel * 0.30f));
        prefs.edit()
                .putFloat("abilityScore", score)
                .putFloat("lastAccuracy", accuracy)
                .putFloat("lastClicksPerHundred", clicksPerHundred)
                .putLong("lastReadingSeconds", seconds)
                .apply();
    }

    private void recordAssessmentResult(JSONObject article, int[] selected) {
        String guard = "assessmentRecorded:" + article.optString("id");
        if (prefs.getBoolean(guard, false)) return;
        JSONArray questions = article.optJSONArray("questions");
        if (questions == null || selected.length == 0) return;
        int correct = 0;
        for (int i = 0; i < selected.length; i++) {
            JSONObject question = questions.optJSONObject(i);
            if (question != null && selected[i] == question.optInt("answer")) correct++;
        }
        long seconds = readingSeconds();
        int words = Math.max(1, article.optInt("wordCount", 1));
        JSONObject result = new JSONObject();
        try {
            result.put("index", prefs.getInt("assessmentCount", 0) + 1);
            result.put("date", LocalDate.now().format(DATE_FORMAT));
            result.put("articleId", article.optString("id"));
            result.put("difficultyScore", article.optInt("difficultyScore", 42));
            result.put("wordCount", words);
            result.put("accuracyPercent", Math.round(correct * 100f / selected.length));
            result.put("clicksPerHundred", Math.round(readingClicks() * 100f / words));
            result.put("wordsPerMinute", Math.round(words * 60f / seconds));
            result.put("readingSeconds", seconds);
        } catch (Exception ignored) { }
        JSONArray history = assessmentHistory();
        history.put(result);
        prefs.edit()
                .putString("assessmentHistory", history.toString())
                .putInt("assessmentCount", prefs.getInt("assessmentCount", 0) + 1)
                .putBoolean(guard, true)
                .remove("currentAssessmentArticleId")
                .apply();
    }

    private JSONArray assessmentHistory() {
        try {
            return new JSONArray(prefs.getString("assessmentHistory", "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private JSONObject latestAssessment() {
        JSONArray history = assessmentHistory();
        JSONObject result = history.optJSONObject(history.length() - 1);
        return result == null ? new JSONObject() : result;
    }

    private String signed(int value) {
        return value > 0 ? "+" + value : String.valueOf(value);
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
        installPressAnimation(back);
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
        box.setBackground(rounded(color, 26));
        box.setElevation(dp(2));
        return box;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    static final class LookupMark {
        final String lemma;
        LookupMark(String lemma) { this.lemma = lemma; }
    }

    private void invalidateLookupMarks(View view) {
        if (view instanceof TextView) view.invalidate();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) invalidateLookupMarks(group.getChildAt(i));
        }
    }

    private void drawLookupMarks(TextView view, Canvas canvas) {
        if (!(view.getText() instanceof Spanned) || view.getLayout() == null) return;
        Spanned text = (Spanned) view.getText();
        android.text.Layout layout = view.getLayout();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(GREEN);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        canvas.save();
        canvas.translate(view.getTotalPaddingLeft(), view.getTotalPaddingTop());
        Set<String> vocabulary = learningStore.vocabularyLemmas();
        for (LookupMark mark : text.getSpans(0, text.length(), LookupMark.class)) {
            if (!vocabulary.contains(mark.lemma)) continue;
            int start = text.getSpanStart(mark), end = text.getSpanEnd(mark);
            for (int line = layout.getLineForOffset(start);
                    line <= layout.getLineForOffset(end - 1); line++) {
                int from = Math.max(start, layout.getLineStart(line));
                int to = Math.min(end, layout.getLineEnd(line));
                Path selection = new Path();
                layout.getSelectionPath(from, to, selection);
                android.graphics.RectF bounds = new android.graphics.RectF();
                selection.computeBounds(bounds, true);
                float x = bounds.left, finish = bounds.right;
                float y = layout.getLineBaseline(line) + dp(3);
                Path wave = new Path();
                wave.moveTo(x, y);
                boolean up = true;
                while (x < finish) {
                    float next = Math.min(x + dp(4), finish);
                    wave.quadTo((x + next) / 2, y + (up ? -dp(2) : dp(2)), next, y);
                    x = next;
                    up = !up;
                }
                canvas.drawPath(wave, paint);
            }
        }
        canvas.restore();
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this) {
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                drawLookupMarks(this, canvas);
            }
        };
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(2), 1.08f);
        view.setFontFeatureSettings("kern");
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button primaryButton(String value) {
        Button button = new Button(this);
        installPressAnimation(button);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(Color.WHITE);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(rounded(PRIMARY, 26));
        button.setMinHeight(dp(52));
        return button;
    }

    private Button secondaryButton(String value) {
        Button button = new Button(this);
        installPressAnimation(button);
        button.setText(value);
        button.setTextSize(16);
        button.setTextColor(GREEN);
        button.setAllCaps(false);
        button.setBackground(rounded(GREEN_LIGHT, 26));
        button.setMinHeight(dp(50));
        return button;
    }

    private boolean motionEnabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    private void animateEnter(View view, float scale, int offset) {
        if (!motionEnabled()) return;
        AnimationSet animation = new AnimationSet(true);
        animation.addAnimation(new AlphaAnimation(0f, 1f));
        animation.addAnimation(new TranslateAnimation(0, 0, offset, 0));
        if (scale != 1f) animation.addAnimation(new ScaleAnimation(scale, 1f, scale, 1f,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f));
        animation.setInterpolator(new DecelerateInterpolator());
        animation.setDuration(180);
        view.startAnimation(animation);
    }

    private void installPressAnimation(View view) {
        if (!motionEnabled()) return;
        StateListAnimator states = new StateListAnimator();
        AnimatorSet pressed = new AnimatorSet();
        pressed.playTogether(ObjectAnimator.ofFloat(view, "scaleX", 0.97f),
                ObjectAnimator.ofFloat(view, "scaleY", 0.97f));
        pressed.setDuration(80);
        states.addState(new int[]{android.R.attr.state_pressed, android.R.attr.state_enabled}, pressed);
        AnimatorSet released = new AnimatorSet();
        released.playTogether(ObjectAnimator.ofFloat(view, "scaleX", 1f),
                ObjectAnimator.ofFloat(view, "scaleY", 1f));
        released.setDuration(140);
        states.addState(new int[]{}, released);
        view.setStateListAnimator(states);
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
