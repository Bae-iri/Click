package com.click.service.overlay;

import com.click.data.remote.AgentTurnContract;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.Objects;

/** A persisted setting change can justify asking the user, not inventing an action event. */
final class AdjustmentResultCheck {
    private String episode, goalType, packageName;
    private Float baseline;
    private long capturedAt;
    private Long evidenceDeadline;
    private String guideId, baselineObservationId;
    private AccessibilityNodeSnapshot baselineTarget;

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
                    && com.click.service.accessibility.SettingsSliderTarget.matches(screen, node, task.setting)
                    && java.util.Arrays.equals(node.bounds, guide.targetBounds))) return;
        long time = time(screen);
        if (time <= 0) return;
        this.episode = episode; this.goalType = goalType; packageName = screen.packageName;
        baseline = task.value(screen); capturedAt = time;
        guideId = guide.guideCommandId; baselineObservationId = screen.observationId;
        baselineTarget = screen.nodes.stream().filter(node -> Objects.equals(node.nodeId, guide.targetNodeId)).findFirst().orElse(null);
    }

    boolean changed(String episode, String goalType, ScreenSnapshot screen) {
        SettingsAdjustment task = SettingsAdjustment.forGoal(goalType);
        if (baseline == null || screen == null || task == null || !Objects.equals(this.episode, episode)
                || !Objects.equals(this.goalType, goalType) || !Objects.equals(packageName, screen.packageName)
                || task.value(screen) == null || time(screen) <= capturedAt) return false;
        return goalType.endsWith(".increase") ? task.value(screen) > baseline : task.value(screen) < baseline;
    }

    long actionEvidenceDelay(String episode, String goalType, ScreenSnapshot screen, long uptime) {
        if (!changed(episode, goalType, screen)) return 0;
        if (evidenceDeadline == null) evidenceDeadline = uptime + 1500L;
        return Math.max(0L, evidenceDeadline - uptime);
    }

    boolean manualEvidenceNeedsSettling(String episode, String goalType, ScreenSnapshot first, ScreenSnapshot current) {
        if (!changed(episode,goalType,first) || !changed(episode,goalType,current)) return false;
        AccessibilityNodeSnapshot a = control(first), b = control(current);
        return a != null && b != null && (time(current)-time(first)<250L
                || !Objects.equals(a.progress,b.progress)
                || !Objects.equals(SettingsAdjustment.forGoal(goalType).value(first),SettingsAdjustment.forGoal(goalType).value(current)));
    }

    JSONObject manualEvidence(String episode, String goalType, ScreenSnapshot first, ScreenSnapshot current) {
        if (!changed(episode,goalType,first) || !changed(episode,goalType,current)
                || guideId == null || baselineObservationId == null || manualEvidenceNeedsSettling(episode,goalType,first,current)
                || time(current)-time(first)>10000L || first.observationId.equals(current.observationId)
                || first.observationId.equals(baselineObservationId) || current.observationId.equals(baselineObservationId)) return null;
        AccessibilityNodeSnapshot a=control(first), b=control(current);
        if (a==null || b==null || !(goalType.endsWith(".increase") ? b.progress>baselineTarget.progress : b.progress<baselineTarget.progress)) return null;
        try {
            return new JSONObject().put("schema_version","manual-setting-evidence.v1")
                    .put("guide_command_id",guideId).put("baseline_observation_id",baselineObservationId)
                    .put("observations",new JSONArray().put(frame(first,a)).put(frame(current,b)));
        } catch (org.json.JSONException invalid) { return null; }
    }

    private AccessibilityNodeSnapshot control(ScreenSnapshot screen) {
        if (baselineTarget==null || baselineTarget.progress==null || baselineTarget.rangeMin==null || baselineTarget.rangeMax==null
                || !Float.isFinite(baselineTarget.progress) || !Float.isFinite(baselineTarget.rangeMin) || !Float.isFinite(baselineTarget.rangeMax)
                || baselineTarget.rangeMin>=baselineTarget.rangeMax || screen==null || !screen.privacyMaskingReady
                || !Objects.equals(packageName,screen.packageName)) return null;
        for (var node:screen.nodes) {
            if (!"slider".equals(node.role) || node.progress==null || !Float.isFinite(node.progress)
                    || !Objects.equals(baselineTarget.className,node.className)
                    || !Objects.equals(baselineTarget.rangeMin,node.rangeMin) || !Objects.equals(baselineTarget.rangeMax,node.rangeMax)
                    || node.progress<node.rangeMin || node.progress>node.rangeMax
                    || !com.click.service.accessibility.SettingsSliderTarget.matches(screen,node,SettingsAdjustment.forGoal(goalType).setting)) continue;
            if (baselineTarget.resourceId!=null && !baselineTarget.resourceId.isEmpty()) {
                if (!baselineTarget.resourceId.equals(node.resourceId)) continue;
            } else if (node.resourceId!=null && !node.resourceId.isEmpty()
                    || !Objects.equals(baselineTarget.nodeId,node.nodeId) || baselineTarget.windowId!=node.windowId) continue;
            return node;
        }
        return null;
    }

    private JSONObject frame(ScreenSnapshot screen, AccessibilityNodeSnapshot node) throws org.json.JSONException {
        SettingsAdjustment task=SettingsAdjustment.forGoal(goalType);
        String source=task==SettingsAdjustment.FONT_SIZE ? "android.provider.Settings.System.FONT_SCALE"
                : task==SettingsAdjustment.BRIGHTNESS ? "android.provider.Settings.System.SCREEN_BRIGHTNESS" : "android.media.AudioManager.STREAM_MUSIC";
        return new JSONObject().put("observation_id",screen.observationId).put("captured_at_ms",time(screen))
                .put("package_name",screen.packageName).put("window_id",screen.activeWindowId)
                .put("target_node_id",node.nodeId).put("target_resource_id",node.resourceId==null?JSONObject.NULL:node.resourceId)
                .put("target_class_name",node.className==null?JSONObject.NULL:node.className)
                .put("control_value",node.progress).put("range_min",node.rangeMin).put("range_max",node.rangeMax)
                .put("applied_value",task.value(screen)).put("applied_source",source);
    }

    void clear() { episode = goalType = packageName = guideId = baselineObservationId = null; baselineTarget=null; baseline = null; capturedAt = 0; evidenceDeadline = null; }

    private static boolean settings(String name) {
        return "com.android.settings".equals(name) || "com.samsung.android.settings".equals(name);
    }

    private static long time(ScreenSnapshot screen) {
        try { return Instant.parse(screen.capturedAt).toEpochMilli(); }
        catch (RuntimeException invalid) { return 0; }
    }
}
