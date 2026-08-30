package com.click.data.remote;

import com.click.data.snapshot.ScreenSnapshot;

import java.util.Collections;
import java.util.Map;

public final class GuidanceContract {
    public static final String CREATE_SESSION_PATH = "/api/v1/sessions";

    private GuidanceContract() {}

    public static String observationsPath(String sessionId) {
        return CREATE_SESSION_PATH + "/" + sessionId + "/observations";
    }

    public static String sessionPath(String sessionId) {
        return CREATE_SESSION_PATH + "/" + sessionId;
    }

    public static final class CreateSessionRequest {
        public final String goal;
        public final String locale;
        public final DeviceContext deviceContext;

        public CreateSessionRequest(String goal, String locale, DeviceContext deviceContext) {
            this.goal = goal;
            this.locale = locale;
            this.deviceContext = deviceContext;
        }
    }

    public static final class DeviceContext {
        public final String manufacturer;
        public final String model;
        public final String androidVersion;

        public DeviceContext(String manufacturer, String model, String androidVersion) {
            this.manufacturer = manufacturer;
            this.model = model;
            this.androidVersion = androidVersion;
        }
    }

    public static final class ObserveRequest {
        public final ScreenSnapshot snapshot;
        public final boolean confirmed;

        public ObserveRequest(ScreenSnapshot snapshot, boolean confirmed) {
            this.snapshot = snapshot;
            this.confirmed = confirmed;
        }
    }

    public static final class CreateSessionResponse {
        public final String sessionId;
        public final String taskId;
        public final String goalType;
        public final String graphVersion;
        public final String status;

        public CreateSessionResponse(String sessionId, String taskId, String goalType, String graphVersion, String status) {
            this.sessionId = sessionId;
            this.taskId = taskId;
            this.goalType = goalType;
            this.graphVersion = graphVersion;
            this.status = status;
        }
    }

    public static final class GuideCommand {
        public final String commandType;
        public final String actionType;
        public final String message;
        public final String targetNodeId;
        public final int[] targetBounds;
        public final String safetyLevel;
        public final boolean requiresUserAction;
        public final Map<String, Object> renderPolicy;

        public GuideCommand(
                String commandType,
                String actionType,
                String message,
                String targetNodeId,
                int[] targetBounds,
                String safetyLevel,
                boolean requiresUserAction,
                Map<String, Object> renderPolicy
        ) {
            this.commandType = commandType;
            this.actionType = actionType;
            this.message = message;
            this.targetNodeId = targetNodeId;
            this.targetBounds = targetBounds;
            this.safetyLevel = safetyLevel;
            this.requiresUserAction = requiresUserAction;
            this.renderPolicy = renderPolicy == null ? Collections.emptyMap() : renderPolicy;
        }
    }

    public static final class ObserveResponse {
        public final String sessionId;
        public final String taskId;
        public final int step;
        public final int totalSteps;
        public final String stateId;
        public final String taskStatus;
        public final GuideCommand guide;

        public ObserveResponse(String sessionId, String taskId, int step, int totalSteps,
                               String stateId, String taskStatus, GuideCommand guide) {
            this.sessionId = sessionId;
            this.taskId = taskId;
            this.step = step;
            this.totalSteps = totalSteps;
            this.stateId = stateId;
            this.taskStatus = taskStatus;
            this.guide = guide;
        }
    }
}
