package com.click.data.remote;

import android.util.Log;
import android.os.Build;

import com.click.data.snapshot.ScreenSnapshot;
import com.click.domain.goal.GoalCatalogClassifier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class AgentTurnContract {
    public static final String PATH = "/api/v1/agent/commands";
    public static final String CONTRACT_VERSION = "agent-fe-contract.v0.2";

    private AgentTurnContract() {}

    public static final class Request {
        public final String requestId;
        public final String sessionId;
        public final String rawGoal;
        public final ScreenSnapshot accessibilityTree;
        public final String userConfirmation;
        public final GoalCatalogClassifier.Match typedGoal;
        private String explicitCommandType;
        public Integer expectedVersion;
        public String episodeId;
        public String confirmationId;
        /** Optional {@code user_action_event} payload, see {@link AgentActionEvents}. */
        public JSONObject userActionEvent;
        /** Optional {@code vision_snapshot} fallback payload. */
        public JSONObject visionSnapshot;

        public Request(String sessionId, String rawGoal, ScreenSnapshot tree, String confirmation) {
            this(sessionId, rawGoal, tree, confirmation, null);
        }

        public Request(String sessionId, String rawGoal, ScreenSnapshot tree, String confirmation,
                       GoalCatalogClassifier.Match typedGoal) {
            this.requestId = UUID.randomUUID().toString();
            this.sessionId = sessionId;
            this.rawGoal = rawGoal;
            this.accessibilityTree = tree;
            this.userConfirmation = confirmation;
            this.typedGoal = typedGoal;
        }

        public Request withActionEvent(JSONObject event) {
            this.userActionEvent = event;
            return this;
        }

        public Request withVisionSnapshot(JSONObject snapshot) {
            this.visionSnapshot = snapshot;
            return this;
        }

        public static Request control(String sessionId, int version, String episodeId,
                                      String commandType, ScreenSnapshot tree) {
            Request request = new Request(sessionId, null, tree, null);
            request.explicitCommandType = commandType;
            request.expectedVersion = version;
            request.episodeId = episodeId;
            return request;
        }

        public static Request replace(String sessionId, int version, String episodeId,
                                      String sourceText, ScreenSnapshot tree) {
            return replace(sessionId, version, episodeId, sourceText, tree, null);
        }

        public static Request replace(String sessionId, int version, String episodeId,
                                      String sourceText, ScreenSnapshot tree,
                                      GoalCatalogClassifier.Match typedGoal) {
            Request request = new Request(sessionId, sourceText, tree, null, typedGoal);
            request.explicitCommandType = "replace_task";
            request.expectedVersion = version;
            request.episodeId = episodeId;
            return request;
        }

        public Request withSessionState(int version, String episodeId, String confirmationId) {
            this.expectedVersion = version;
            this.episodeId = episodeId;
            this.confirmationId = confirmationId;
            return this;
        }

        public String toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("contract_version", CONTRACT_VERSION);
            object.put("request_id", requestId);
            object.put("session_id", sessionId);
            object.put("expected_version", expectedVersion == null ? 0 : expectedVersion);
            String commandType = explicitCommandType != null ? explicitCommandType : rawGoal != null ? "start_task"
                    : userConfirmation != null ? "respond_confirmation" : "observe";
            object.put("command_type", commandType);
            if (episodeId != null) object.put("episode_id", episodeId);
            if (rawGoal != null) {
                object.put("source_text", rawGoal);
                if (typedGoal != null) {
                    object.put("goal", typedGoal.toTypedGoalJson());
                } else {
                    // Compatibility for callers not yet wired to the shared catalog.
                    String normalizedGoal = rawGoal.toLowerCase(java.util.Locale.ROOT)
                            .replaceAll("[\\s,.!?]", "");
                    boolean decrease = normalizedGoal.contains("줄여") || normalizedGoal.contains("작게")
                            || normalizedGoal.contains("낮춰") || normalizedGoal.contains("축소")
                            || normalizedGoal.contains("너무커") || normalizedGoal.contains("커서");
                    String direction = decrease ? "decrease" : "increase";
                    object.put("goal", new JSONObject()
                            .put("catalog_version", "2.0.0")
                            .put("goal_type", "settings.font_size." + direction)
                            .put("target_app", "android_settings")
                            .put("slots", new JSONObject().put("setting", "font_size").put("direction", direction)));
                }
                object.put("device_context", new JSONObject()
                        .put("platform", "android")
                        .put("manufacturer", Build.MANUFACTURER)
                        .put("os_version", Build.VERSION.RELEASE)
                        .put("locale", java.util.Locale.getDefault().toLanguageTag())
                        .put("foreground_package", accessibilityTree.packageName)
                        .put("capabilities", new JSONObject()
                                .put("accessibility_tree", true)
                                .put("guide_overlay", true)
                                .put("automatic_scroll", false)));
            }
            if (accessibilityTree != null) {
                object.put("accessibility_tree", new JSONObject(accessibilityTree.toJson()));
            }
            if (userConfirmation != null) object.put("user_confirmation", userConfirmation);
            if (expectedVersion != null) object.put("expected_version", expectedVersion);
            if (confirmationId != null) object.put("confirmation_id", confirmationId);
            if (userActionEvent != null) object.put("user_action_event", userActionEvent);
            if (visionSnapshot != null) object.put("vision_snapshot", visionSnapshot);
            return object.toString();
        }
    }

    public static final class GuideCommand {
        public final String guideCommandId;
        public final String commandType;
        public final String actionType;
        public final String message;
        public final String targetNodeId;
        public final int[] targetBounds;
        public final boolean requiresUserAction;
        public final JSONObject renderPolicy;

        GuideCommand(JSONObject object) throws JSONException {
            guideCommandId = optNullableString(object, "guide_command_id");
            commandType = object.getString("command_type");
            actionType = object.getString("action_type");
            message = object.optString("message", "");
            targetNodeId = optNullableString(object, "target_node_id");
            requiresUserAction = object.optBoolean("requires_user_action", false);
            renderPolicy = object.optJSONObject("render_policy");
            JSONArray bounds = object.optJSONArray("target_bounds");
            if (bounds == null || bounds.length() != 4) targetBounds = null;
            else targetBounds = new int[]{bounds.getInt(0), bounds.getInt(1), bounds.getInt(2), bounds.getInt(3)};
        }

        /** Present when the Agent needs the user to reach a screen where the settings app is openable. */
        public JSONObject recoveryAction() {
            return renderPolicy == null ? null : renderPolicy.optJSONObject("recovery_action");
        }

        /** Present on a plain target-match failure: FE should re-observe with a fresh tree. */
        public JSONObject fallbackAction() {
            return renderPolicy == null ? null : renderPolicy.optJSONObject("fallback_action");
        }

        /** Present when the Agent asks for a vision snapshot (needs consent for raw screenshots). */
        public JSONObject visionFallback() {
            return renderPolicy == null ? null : renderPolicy.optJSONObject("vision_fallback");
        }

        /** What the Agent expects on the next turn, wherever the policy carries it. */
        public JSONObject expectedNextObservation() {
            if (renderPolicy == null) return null;
            JSONObject direct = renderPolicy.optJSONObject("expected_next_observation");
            if (direct != null) return direct;
            JSONObject fallback = fallbackAction();
            if (fallback != null && fallback.optJSONObject("expected_next_observation") != null) {
                return fallback.optJSONObject("expected_next_observation");
            }
            JSONObject recovery = recoveryAction();
            if (recovery != null && recovery.optJSONObject("expected_next_observation") != null) {
                return recovery.optJSONObject("expected_next_observation");
            }
            return null;
        }
    }

    public static final class Response {
        public final String requestId;
        public final String sessionId;
        public final int sessionVersion;
        public final String episodeId;
        public final String taskStatus;
        public final GuideCommand guideCommand;
        public final JSONObject metadata;

        public Response(String json) throws JSONException {
            JSONObject object = new JSONObject(json);
            requestId = object.getString("request_id");
            sessionId = object.getString("session_id");
            sessionVersion = object.optInt("session_version", 0);
            episodeId = object.optString("episode_id", null);
            taskStatus = object.getString("task_status");
            JSONObject guide = object.optJSONObject("guide_command");
            guideCommand = guide == null ? null : new GuideCommand(guide);
            metadata = object.optJSONObject("metadata");
        }

        /** {@code metadata.debug} is the agreed reference for FE/BE integration debugging. */
        public void logDebug(String tag) {
            JSONObject debug = metadata == null ? null : metadata.optJSONObject("debug");
            if (debug == null) return;
            Log.i(tag, "agent.debug.observation " + debug.opt("observation"));
            Log.i(tag, "agent.debug.fallback " + debug.opt("fallback"));
            Log.i(tag, "agent.debug.agent_chain " + debug.opt("agent_chain"));
            Log.i(tag, "agent.debug.vision_fallback " + debug.opt("vision_fallback"));
        }
    }

    private static String optNullableString(JSONObject object, String key) {
        return object.isNull(key) ? null : object.optString(key, null);
    }
}
