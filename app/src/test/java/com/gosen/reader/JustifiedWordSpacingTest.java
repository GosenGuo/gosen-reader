package com.gosen.reader;

import static org.junit.Assert.*;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.view.View;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class JustifiedWordSpacingTest {
    private void layout(TextView view, int width) {
        view.forceLayout();
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.AT_MOST));
        view.layout(0, 0, width, view.getMeasuredHeight());
    }

    private void assertAligned(TextView view) {
        Layout layout = view.getLayout();
        assertTrue(layout.getLineCount() > 1);
        for (int line = 0; line < layout.getLineCount() - 1; line++) {
            int end = layout.getLineVisibleEnd(line);
            int wordStart = end - 1;
            while (wordStart > layout.getLineStart(line)
                    && view.getText().charAt(wordStart - 1) != ' ') wordStart--;
            Path path = new Path();
            layout.getSelectionPath(wordStart, end, path);
            RectF bounds = new RectF();
            path.computeBounds(bounds, true);
            assertEquals(layout.getParagraphRight(line) - 0.5f, bounds.right, 1.5f);
            assertEquals(layout.getPrimaryHorizontal(wordStart), bounds.left, 0.5f);
            assertTrue(bounds.width() > 0);
        }
    }

    @Test public void selectionAndPointerCoordinatesTrackJustifiedWordsAfterResize() {
        String source = "Alpha beta gamma delta epsilon zeta theta iota kappa lambda mu nu "
                + "xi omicron pi rho sigma tau upsilon phi chi psi omega.";
        TextView view = new TextView(RuntimeEnvironment.getApplication());
        view.setLayoutParams(new android.view.ViewGroup.LayoutParams(360,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        view.setTextSize(22);
        view.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        SpannableString text = new SpannableString(source);
        text.setSpan(new LeadingMarginSpan.Standard(38, 0), 0, source.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(text);
        layout(view, 360);
        JustifiedWordSpacing.apply(view, 360);
        layout(view, 360);
        assertEquals(source, view.getText().toString());
        assertAligned(view);
        JustifiedWordSpacing.apply(view, 420);
        layout(view, 420);
        assertAligned(view);
        float before = view.getLayout().getPrimaryHorizontal(6);
        JustifiedWordSpacing.apply(view, 420);
        layout(view, 420);
        assertEquals(before, view.getLayout().getPrimaryHorizontal(6), 0.1f);
    }
}
