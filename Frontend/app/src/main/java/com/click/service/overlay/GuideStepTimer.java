package com.click.service.overlay;

/** Measures the first observed tap after a tap guide is displayed. No screen text is retained. */
public final class GuideStepTimer {
    public static final class Observation {
        public final String sessionId;
        public final String stateId;
        public final int step;
        public final long durationMs;

        Observation(String sessionId, String stateId, int step, long durationMs) {
            this.sessionId = sessionId;
            this.stateId = stateId;
            this.step = step;
            this.durationMs = durationMs;
        }
    }

    private String sessionId;
    private String stateId;
    private String packageName;
    private int step;
    private long shownAtUptimeMs;
    private boolean consumed;

    public void start(String sessionId, String stateId, int step,
                      String packageName, long shownAtUptimeMs) {
        clear();
        if (sessionId == null || stateId == null
                || packageName == null || step < 0 || shownAtUptimeMs <= 0) return;
        this.sessionId = sessionId;
        this.stateId = stateId;
        this.step = step;
        this.packageName = packageName;
        this.shownAtUptimeMs = shownAtUptimeMs;
    }

    public Observation firstTap(String tappedPackage, long eventUptimeMs) {
        if (consumed || sessionId == null || !packageName.equals(tappedPackage)) return null;
        long duration = eventUptimeMs - shownAtUptimeMs;
        if (duration < 0 || duration > 86_400_000L) return null;
        consumed = true;
        return new Observation(sessionId, stateId, step, duration);
    }

    public void clear() {
        sessionId = null;
        stateId = null;
        packageName = null;
        step = 0;
        shownAtUptimeMs = 0;
        consumed = false;
    }
}
