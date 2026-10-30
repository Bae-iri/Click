package com.click.service.overlay;

import com.click.data.remote.AgentTurnContract;
import com.click.data.snapshot.ScreenSnapshot;
import java.time.Instant;
import java.util.Objects;

/** A persisted setting change can justify asking the user, not inventing an action event. */
final class FontSizeResultCheck {
    private String episode, goalType, packageName;
    private Float baseline;
    private long capturedAt;

    void bind(String episode, String goalType, AgentTurnContract.GuideCommand guide, ScreenSnapshot screen) {
        if (!Objects.equals(this.episode, episode) || !Objects.equals(this.goalType, goalType)) clear();
        if (baseline != null || episode == null || guide == null || screen == null
                || !("settings.font_size.increase".equals(goalType) || "settings.font_size.decrease".equals(goalType))
                || !"highlight".equals(guide.commandType) || !"drag".equals(guide.actionType)
                || !guide.requiresUserAction || !settings(screen.packageName) || !valid(screen.appliedFontScale)
                || screen.nodes.stream().noneMatch(node -> node.visibleToUser && node.enabled
                    && "slider".equals(node.role) && Objects.equals(node.nodeId, guide.targetNodeId)
                    && java.util.Arrays.equals(node.bounds, guide.targetBounds))) return;
        long time = time(screen);
        if (time <= 0) return;
        this.episode = episode; this.goalType = goalType; packageName = screen.packageName;
        baseline = screen.appliedFontScale; capturedAt = time;
    }

    boolean changed(String episode, String goalType, ScreenSnapshot screen) {
        if (baseline == null || screen == null || !Objects.equals(this.episode, episode)
                || !Objects.equals(this.goalType, goalType) || !Objects.equals(packageName, screen.packageName)
                || !valid(screen.appliedFontScale) || time(screen) <= capturedAt) return false;
        return goalType.endsWith(".increase") ? screen.appliedFontScale > baseline : screen.appliedFontScale < baseline;
    }

    void clear() { episode = goalType = packageName = null; baseline = null; capturedAt = 0; }

    private static boolean settings(String name) {
        return "com.android.settings".equals(name) || "com.samsung.android.settings".equals(name);
    }

    private static boolean valid(Float value) { return value != null && Float.isFinite(value) && value > 0 && value <= 10; }

    private static long time(ScreenSnapshot screen) {
        try { return Instant.parse(screen.capturedAt).toEpochMilli(); }
        catch (RuntimeException invalid) { return 0; }
    }
}
