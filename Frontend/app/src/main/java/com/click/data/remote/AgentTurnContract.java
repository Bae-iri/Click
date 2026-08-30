package com.click.data.remote;

import com.click.data.snapshot.ScreenSnapshot;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

public final class AgentTurnContract {
    public static final String PATH = "/api/v1/agent/turn";

    private AgentTurnContract() {}

    public static final class Request {
        public final String requestId;
        public final String sessionId;
        public final String rawGoal;
        public final ScreenSnapshot accessibilityTree;
        public final String userConfirmation;

        public Request(String sessionId, String rawGoal, ScreenSnapshot tree, String confirmation) {
            this.requestId = UUID.randomUUID().toString();
            this.sessionId = sessionId;
            this.rawGoal = rawGoal;
            this.accessibilityTree = tree;
            this.userConfirmation = confirmation;
        }

        public String toJson() throws JSONException {
            JSONObject object = new JSONObject();
            object.put("request_id", requestId);
            object.put("session_id", sessionId);
            if (rawGoal != null) object.put("raw_goal", rawGoal);
            object.put("accessibility_tree", new JSONObject(accessibilityTree.toJson()));
            if (userConfirmation != null) object.put("user_confirmation", userConfirmation);
            return object.toString();
        }
    }

    public static final class GuideCommand {
        public final String commandType;
        public final String actionType;
        public final String message;
        public final int[] targetBounds;
        public final boolean requiresUserAction;
        public final JSONObject renderPolicy;

        GuideCommand(JSONObject object) throws JSONException {
            commandType = object.getString("command_type");
            actionType = object.getString("action_type");
            message = object.optString("message", "");
            requiresUserAction = object.optBoolean("requires_user_action", false);
            renderPolicy = object.optJSONObject("render_policy");
            JSONArray bounds = object.optJSONArray("target_bounds");
            if (bounds == null || bounds.length() != 4) targetBounds = null;
            else targetBounds = new int[]{bounds.getInt(0), bounds.getInt(1), bounds.getInt(2), bounds.getInt(3)};
        }
    }

    public static final class Response {
        public final String requestId;
        public final String sessionId;
        public final String taskStatus;
        public final GuideCommand guideCommand;
        public final JSONObject metadata;

        public Response(String json) throws JSONException {
            JSONObject object = new JSONObject(json);
            requestId = object.getString("request_id");
            sessionId = object.getString("session_id");
            taskStatus = object.getString("task_status");
            guideCommand = new GuideCommand(object.getJSONObject("guide_command"));
            metadata = object.optJSONObject("metadata");
        }
    }
}
