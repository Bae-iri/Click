package com.click.data.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.ApplicationExitInfo;
import org.junit.Test;

public class CrashJournalTest {
    @Test public void describesTypeMessageFramesAndCause() {
        Throwable error = new IllegalStateException("outer", new NullPointerException("inner"));
        String text = CrashJournal.describe(error);
        assertTrue(text.startsWith("java.lang.IllegalStateException: outer\n"));
        assertTrue(text.contains("  at com.click.data.local.CrashJournalTest"));
        assertTrue(text.contains("Caused by: java.lang.NullPointerException: inner"));
    }

    @Test public void keepsRecordsWithinTheJournalLineLimit() {
        Throwable error = new RuntimeException("x".repeat(5000));
        error.setStackTrace(deepStack(500));
        String text = CrashJournal.describe(error);
        assertTrue(text.length() <= CrashJournal.MAX_STACK_CHARS + 1);
        assertTrue(text.contains("… more") || text.contains(" more\n") || text.endsWith("…"));
    }

    @Test public void truncatesNullAndLongValues() {
        assertEquals("", CrashJournal.truncate(null, 5));
        assertEquals("abcde…", CrashJournal.truncate("abcdefgh", 5));
    }

    @Test public void onlyAbnormalExitsAreRecorded() {
        assertTrue(CrashJournal.abnormal(ApplicationExitInfo.REASON_CRASH));
        assertTrue(CrashJournal.abnormal(ApplicationExitInfo.REASON_CRASH_NATIVE));
        assertTrue(CrashJournal.abnormal(ApplicationExitInfo.REASON_ANR));
        assertFalse(CrashJournal.abnormal(ApplicationExitInfo.REASON_USER_REQUESTED));
        assertFalse(CrashJournal.abnormal(ApplicationExitInfo.REASON_EXIT_SELF));
    }

    private static StackTraceElement[] deepStack(int size) {
        StackTraceElement[] frames = new StackTraceElement[size];
        for (int i = 0; i < size; i++) frames[i] = new StackTraceElement("com.click.Deep", "frame" + i, "Deep.java", i);
        return frames;
    }
}
