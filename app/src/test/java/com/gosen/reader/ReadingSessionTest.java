package com.gosen.reader;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public class ReadingSessionTest {
    @Test public void countsOnlyForegroundPassageTimeAcrossReturns() {
        ReadingSession s = new ReadingSession("a", 4);
        s.resume(1000);
        s.resume(1500); // Duplicate onResume must not restart the timer.
        s.pause(11000);
        s.page = "quiz";
        s.resume(12000);
        assertEquals(10, s.seconds(60000));
        s.page = "reader";
        s.resume(61000);
        s.pause(66000);
        assertEquals(15, s.seconds(100000));
    }

    @Test public void restoresAnswersScrollRecallAndClicksWithoutBackgroundTime() throws Exception {
        ReadingSession s = new ReadingSession("a", 4);
        s.resume(1000);
        s.clicks = 7;
        s.readerScroll = 820;
        s.quizScroll = 350;
        s.recall = "讲的是兄弟之间的理解";
        s.answers[0] = 2;
        ReadingSession restored = ReadingSession.restore("a", 4, s.snapshot(21000).toString());
        assertEquals(20, restored.seconds(500000));
        assertArrayEquals(new int[]{2, -1, -1, -1}, restored.answers);
        assertEquals(820, restored.readerScroll);
        assertEquals(350, restored.quizScroll);
        assertEquals(7, restored.clicks);
        assertEquals(s.recall, restored.recall);
        restored.resume(500000);
        assertEquals(25, restored.seconds(505000));
    }

    @Test public void damagedOrMismatchedDraftStartsSafely() throws Exception {
        assertArrayEquals(new int[]{-1, -1}, ReadingSession.restore("a", 2, "bad json").answers);
        ReadingSession other = new ReadingSession("b", 2);
        other.answers[0] = 3;
        assertArrayEquals(new int[]{-1, -1}, ReadingSession.restore("a", 2, other.snapshot(0).toString()).answers);
    }
}
