package com.click.service.overlay;

import com.click.data.remote.AgentTurnContract;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.SliderAdjustment;
import com.click.service.accessibility.TouchAutomationPolicy;
import com.click.ui.overlay.GuideDragDirection;
import org.json.JSONObject;
import java.util.Arrays;

/** Only the user's already-confirmed font-size goal can cover this final one-step approval. */
public final class AutomaticFontSizeApproval {
    private AutomaticFontSizeApproval() {}
    public static boolean eligible(AgentTurnContract.GuideCommand command, JSONObject metadata, ScreenSnapshot screen) {
        if (!"confirmation".equals(command.commandType) || !"drag".equals(command.proposedAction)
                || !"observe_only".equals(command.actionType) || !command.requiresUserAction
                || screen == null || !screen.privacyMaskingReady || !TouchAutomationPolicy.settingsPackage(screen.packageName)
                || command.targetNodeId == null || command.targetBounds == null) return false;
        JSONObject goal = metadata == null ? null : metadata.optJSONObject("typed_goal");
        JSONObject slots = goal == null ? null : goal.optJSONObject("slots");
        if (goal == null || !"android_settings".equals(goal.optString("target_app")) || slots == null
                || !"font_size".equals(slots.optString("setting"))) return false;
        if (command.renderPolicy != null) {
            java.util.Iterator<String> keys = command.renderPolicy.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!java.util.Set.of("runtime_strategy", "attempt_id", "step_id").contains(key)) return false;
            }
            if (!"continue".equals(command.renderPolicy.optString("runtime_strategy", "continue"))) return false;
        }
        int direction = GuideDragDirection.resolve(command.renderPolicy, metadata);
        for (AccessibilityNodeSnapshot node : screen.nodes) {
            if (command.targetNodeId.equals(node.nodeId) && node.visibleToUser && node.enabled && !node.sensitiveInput
                    && !node.checkable && screen.activeWindowId != null && node.windowId == screen.activeWindowId
                    && java.util.Objects.equals(node.packageName, screen.packageName)
                    && Arrays.equals(command.targetBounds, node.bounds)
                    && node.progress != null && node.rangeMin != null && node.rangeMax != null
                    && ("slider".equals(node.role) || node.className != null
                        && node.className.toLowerCase(java.util.Locale.ROOT).contains("seekbar"))
                    && SliderAdjustment.canMove(node.progress, node.rangeMin, node.rangeMax, direction)) return true;
        }
        return false;
    }
}
