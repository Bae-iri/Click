package com.click.designpreview;

/** Counts only foreground time; callers supply a monotonic clock. */
final class ForegroundCountdown {
    private long remainingMillis;
    private long startedAt = -1L;

    ForegroundCountdown(long remainingMillis) {
        this.remainingMillis = Math.max(0L, remainingMillis);
    }

    void resume(long now) {
        if (startedAt < 0L) startedAt = now;
    }

    void pause(long now) {
        remainingMillis = remaining(now);
        startedAt = -1L;
    }

    long remaining(long now) {
        return Math.max(0L, remainingMillis - (startedAt < 0L ? 0L : now - startedAt));
    }
}
