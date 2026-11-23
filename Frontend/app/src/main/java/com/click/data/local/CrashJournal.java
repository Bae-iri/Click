package com.click.data.local;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import org.json.JSONObject;
import java.util.List;

/**
 * Records why the app died into the device-local diagnostics journal so testers can share it
 * through the existing diagnostics menu. Nothing is uploaded. Only exception types, messages
 * (truncated) and stack frames are kept; no screen text, input or credentials.
 */
public final class CrashJournal {
    static final int MAX_STACK_CHARS = 6000;
    static final int MAX_MESSAGE_CHARS = 300;
    private static final String PREFS = "crash-journal";
    private static final String LAST_EXIT = "last_exit_ms";

    private CrashJournal() {}

    public static void install(Context context) {
        if (!CandidateDiagnosticLog.enabled()) return;
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                new CandidateDiagnosticLog(app).record(new JSONObject()
                        .put("event", "app_crash")
                        .put("thread", thread.getName())
                        .put("stack", describe(error)));
            } catch (Throwable ignored) { /* Never block the platform crash path. */ }
            if (previous != null) previous.uncaughtException(thread, error);
        });
        recordPreviousExits(app);
    }

    /** Captures deaths the handler cannot see: native crashes, ANRs and system kills. */
    private static void recordPreviousExits(Context app) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ActivityManager manager = app.getSystemService(ActivityManager.class);
            if (manager == null) return;
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(null, 0, 5);
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long seen = prefs.getLong(LAST_EXIT, 0L), newest = seen;
            CandidateDiagnosticLog log = new CandidateDiagnosticLog(app);
            for (ApplicationExitInfo exit : exits) {
                if (exit.getTimestamp() <= seen || !abnormal(exit.getReason())) continue;
                newest = Math.max(newest, exit.getTimestamp());
                log.record(new JSONObject()
                        .put("event", "app_exit")
                        .put("reason", exit.getReason())
                        .put("status", exit.getStatus())
                        .put("process", exit.getProcessName())
                        .put("importance", exit.getImportance())
                        .put("description", truncate(exit.getDescription(), MAX_MESSAGE_CHARS))
                        .put("exited_at_ms", exit.getTimestamp()));
            }
            if (newest != seen) prefs.edit().putLong(LAST_EXIT, newest).apply();
        } catch (Throwable ignored) { /* Diagnostics must not change startup behavior. */ }
    }

    static boolean abnormal(int reason) {
        return reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_ANR
                || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                || reason == ApplicationExitInfo.REASON_LOW_MEMORY
                || reason == ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE;
    }

    static String describe(Throwable error) {
        StringBuilder out = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            if (depth > 0) out.append("Caused by: ");
            out.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null) out.append(": ").append(truncate(message, MAX_MESSAGE_CHARS));
            out.append('\n');
            StackTraceElement[] frames = current.getStackTrace();
            int limit = depth == 0 ? 40 : 15;
            for (int i = 0; i < Math.min(limit, frames.length); i++) out.append("  at ").append(frames[i]).append('\n');
            if (frames.length > limit) out.append("  ... ").append(frames.length - limit).append(" more\n");
            if (out.length() >= MAX_STACK_CHARS) break;
        }
        return truncate(out.toString(), MAX_STACK_CHARS);
    }

    static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
