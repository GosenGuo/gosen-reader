package com.gosen.reader;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int GREEN = Color.rgb(47, 107, 79);
    private static final int GREEN_LIGHT = Color.rgb(229, 239, 232);
    private static final int CREAM = Color.rgb(250, 248, 242);
    private static final int INK = Color.rgb(39, 43, 40);
    private static final int MUTED = Color.rgb(102, 107, 103);
    private static final int GOLD = Color.rgb(233, 180, 76);
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(CREAM);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        prefs = getSharedPreferences("reader", Context.MODE_PRIVATE);
        repository = new ContentRepository(this);
        refillManager = new ContentRefillManager(this);
        buildShell();
        showHome();
        repository.checkForUpdates((success, changed) -> {
            if (changed && onHomeScreen) showHome();
            maybeRequestContentRefill();
        });
        updateManager = new AppUpdateManager(this);
        updateManager.checkForUpdates();
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CREAM);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(12), dp(6), dp(12), dp(8));
        nav.setBackgroundColor(Color.WHITE);
        addNavButton("首页", this::showHome);
        addNavButton("题库", this::showLibrary);
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
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(view -> action.run());
        nav.addView(button, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1));
    }

    private void setScreen(View view, boolean showNav) {
        content.removeAllViews();
        content.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        nav.setVisibility(showNav ? View.VISIBLE : View.GONE);
    }

    private void showHome() {
        onHomeScreen = true;
        ScrollView scroll = screenScroll();
        LinearLayout page = page();
        scroll.addView(page);

        TextView greeting = label("你好，Gosen", 28, INK, true);
        page.addView(greeting);
        page.addView(label("每天读一篇，让阅读变成习惯。", 15, MUTED, false));
        page.addView(space(24));

        LinearLayout streakCard = card(GREEN);
        streakCard.addView(label("连续阅读", 14, Color.WHITE, false));
        TextView streak = label(prefs.getInt("streak", 0) + " 天", 38,
                Color.WHITE, true);
        streak.setPadding(0, dp(4), 0, dp(4));
        streakCard.addView(streak);
        String doneText = isTodayDone() ? "今日已完成 ✓" : "今天完成 1 篇即可打卡";
        streakCard.addView(label(doneText, 14, Color.WHITE, false));
        page.addView(streakCard);
        page.addView(space(18));

        page.addView(label("今日阅读", 20, INK, true));
        page.addView(space(10));
        if (repository.size() == 0) {
            LinearLayout empty = card(Color.WHITE);
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
        LinearLayout progress = card(Color.WHITE);
        progress.addView(label("已完成 " + completedDaysThisMonth() + " 天", 18, INK, true));
        progress.addView(space(8));
        TextView hint = label("目标：每天一篇 · 正确率不影响打卡", 14, MUTED, false);
        progress.addView(hint);
        page.addView(progress);

        setScreen(scroll, true);
    }

    private JSONObject todayArticle() {
        int index = (LocalDate.now().getDayOfYear() - 1) % repository.size();
        return repository.get(index);
    }

    private LinearLayout articleCard(JSONObject article, boolean prominent) {
        LinearLayout box = card(Color.WHITE);
        TextView tag = label(article.optString("difficulty", "高中") + "  ·  "
                + article.optInt("wordCount", 0) + " 词", 13, GREEN, true);
        box.addView(tag);
        box.addView(space(8));
        box.addView(label(article.optString("title"), prominent ? 23 : 18, INK, true));
        box.addView(space(6));
        box.addView(label(article.optString("source", "英语阅读"), 13, MUTED, false));
        box.addView(space(16));
        Button read = primaryButton(isCompleted(article.optString("id"))
                ? "再次阅读" : "开始阅读");
        read.setOnClickListener(view -> showReader(article));
        box.addView(read);
        return box;
    }

    private void showLibrary() {
        onHomeScreen = false;
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

    private void showStats() {
        onHomeScreen = false;
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

        LinearLayout clicks = card(Color.WHITE);
        clicks.addView(label("单词点击", 18, INK, true));
        clicks.addView(space(6));
        clicks.addView(label("累计点击 " + prefs.getInt("totalClicks", 0)
                + " 次；相同词形会尽量归入原形统计。", 14, MUTED, false));
        page.addView(clicks);
        setScreen(scroll, true);
    }

    private LinearLayout statCard(String title, String value) {
        LinearLayout box = card(Color.WHITE);
        box.addView(label(title, 13, MUTED, false));
        box.addView(space(8));
        box.addView(label(value, 24, GREEN, true));
        return box;
    }

    private void showReader(JSONObject article) {
        onHomeScreen = false;
        activeArticle = article;
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

        Button finish = primaryButton("完成阅读，开始答题");
        finish.setOnClickListener(view -> showQuiz(article));
        page.addView(finish);
        page.addView(space(24));
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
        sheet.setBackground(rounded(Color.WHITE, 22));

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
        sheet.addView(label("词性  " + pos, 14, MUTED, false));
        sheet.addView(space(14));

        if (entry != null) {
            sheet.addView(label("常见含义", 14, MUTED, true));
            sheet.addView(label(entry.optString("meanings", translation), 16, INK, false));
            sheet.addView(space(12));
            sheet.addView(label("常见词形", 14, MUTED, true));
            sheet.addView(label(entry.optString("forms", "—"), 16, INK, false));
            sheet.addView(space(14));
        }

        sheet.addView(label("所在句", 14, MUTED, true));
        sheet.addView(label(sentence, 15, INK, false));
        sheet.addView(space(8));
        JSONObject translations = article.optJSONObject("sentenceTranslations");
        String chinese = translations == null ? "" : translations.optString(sentence);
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
            LinearLayout box = card(Color.WHITE);
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
            markTodayComplete(article);
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
            LinearLayout box = card(Color.WHITE);
            boolean right = selected[i] == answer;
            box.addView(label((i + 1) + ". " + (right ? "回答正确" : "需要复盘"),
                    17, right ? GREEN : Color.rgb(176, 76, 65), true));
            box.addView(space(6));
            box.addView(label("你的答案：" + (char) ('A' + selected[i]) + "  ·  正确答案："
                    + (char) ('A' + answer), 14, INK, false));
            box.addView(label(options.optString(answer), 15, GREEN, true));
            box.addView(space(8));
            box.addView(label(q.optString("explanation"), 14, MUTED, false));
            page.addView(box);
            page.addView(space(12));
        }
        Button home = primaryButton("返回首页");
        home.setOnClickListener(view -> showHome());
        page.addView(home);
        page.addView(space(24));
        setScreen(scroll, false);
    }

    private void markTodayComplete(JSONObject article) {
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
                    .apply();
        } else {
            prefs.edit().putStringSet("completedIds", ids).apply();
        }
        maybeRequestContentRefill();
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
        button.setBackground(rounded(GREEN, 14));
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
