package com.click.service.overlay;

import com.click.data.remote.AgentTurnContract;
import com.click.data.snapshot.ScreenSnapshot;
import java.time.Instant;
import java.util.Objects;

/** A persisted setting change can justify asking the user, not inventing an action event. */
final class AdjustmentResultCheck {
    private String episode, goalType, packageName;
    private Float baseline;
    private long capturedAt;

    void bind(String episode, String goalType, AgentTurnContract.GuideCommand guide, ScreenSnapshot screen) {
        if (!Objects.equals(this.episode, episode) || !Objects.equals(this.goalType, goalType)) clear();
        SettingsAdjustment task = SettingsAdjustment.forGoal(goalType);
        if (baseline != null || episode == null || guide == null || screen == null || task == null
                || !("highlight".equals(guide.commandType) && "drag".equals(guide.actionType)
                    || "confirmation".equals(guide.commandType) && "observe_only".equals(guide.actionType)
                        && "drag".equals(guide.proposedAction))
                || guide.visionFallback() != null
                || guide.renderPolicy != null && guide.renderPolicy.optBoolean("vision_derived")
                || !guide.requiresUserAction || !settings(screen.packageName) || task.value(screen) == null
                || screen.nodes.stream().noneMatch(node -> node.visibleToUser && node.enabled && !node.sensitiveInput
                    && screen.activeWindowId != null && node.windowId == screen.activeWindowId
                    && Objects.equals(node.packageName, screen.packageName)
                    && "slider".equals(node.role) && Objects.equals(node.nodeId, guide.targetNodeId)
                    && java.util.Arrays.equals(node.bounds, guide.targetBounds))) return;
        long time = time(screen);
        if (time <= 0) return;
        this.episode = episode; this.goalType = goalType; packageName = screen.packageName;
        baseline = task.value(screen); capturedAt = time;
    }

    boolean changed(String episode, String goalType, ScreenSnapshot screen) {
        SettingsAdjustment task = SettingsAdjustment.forGoal(goalType);
        if (baseline == null || screen == null || task == null || !Objects.equals(this.episode, episode)
                || !Objects.equals(this.goalType, goalType) || !Objects.equals(packageName, screen.packageName)
                || task.value(screen) == null || time(screen) <= capturedAt) return false;
        return goalType.endsWith(".increase") ? task.value(screen) > baseline : task.value(screen) < baseline;
    }

    void clear() { episode = goalType = packageName = null; baseline = null; capturedAt = 0; }

    private static boolean settings(String name) {
        return "com.android.settings".equals(name) || "com.samsung.android.settings".equals(name);
    }

    private static long time(ScreenSnapshot screen) {
        try { return Instant.parse(screen.capturedAt).toEpochMilli(); }
        catch (RuntimeException invalid) { return 0; }
    }
}
