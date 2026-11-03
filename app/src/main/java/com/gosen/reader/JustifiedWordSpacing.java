package com.gosen.reader;

import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.widget.TextView;

/** Justify using measured space spans so Layout's hit-testing and drawing agree. */
final class JustifiedWordSpacing {
    static void apply(TextView view, int width) {
        int available = width - view.getTotalPaddingLeft() - view.getTotalPaddingRight();
        if (available <= 0 || view.getText().length() == 0) return;
        SpannableString text = new SpannableString(view.getText());
        for (Space span : text.getSpans(0, text.length(), Space.class)) text.removeSpan(span);
        StaticLayout natural = StaticLayout.Builder.obtain(text, 0, text.length(), view.getPaint(), available)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(TextDirectionHeuristics.LTR)
                .setIncludePad(view.getIncludeFontPadding())
                .setLineSpacing(view.getLineSpacingExtra(), view.getLineSpacingMultiplier())
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build();
        for (int line = 0; line < natural.getLineCount(); line++) {
            int start = natural.getLineStart(line), end = natural.getLineEnd(line);
            if (end >= text.length() || text.charAt(end - 1) == '\n') continue;
            int visibleEnd = natural.getLineVisibleEnd(line);
            int spaces = 0;
            for (int i = start; i < visibleEnd; i++) if (stretchable(text, i)) spaces++;
            if (spaces == 0) continue;
            // Leave half a pixel for rounding so justification cannot wrap the final word.
            float extra = (available - natural.getLineMax(line) - 0.5f) / spaces;
            if (extra <= 0) continue;
            for (int i = start; i < visibleEnd; i++) {
                if (stretchable(text, i)) text.setSpan(new Space(extra), i, i + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        view.setText(text);
    }

    private static boolean stretchable(SpannableString text, int index) {
        return text.charAt(index) == ' '
                && text.getSpans(index, index + 1, ReplacementSpan.class).length == 0;
    }

    static final class Space extends MetricAffectingSpan {
        private final float extra;
        Space(float extra) { this.extra = extra; }
        @Override public void updateMeasureState(TextPaint paint) {
            paint.setWordSpacing(paint.getWordSpacing() + extra);
        }
        @Override public void updateDrawState(TextPaint paint) { updateMeasureState(paint); }
    }
}
