package com.click.data.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentTurnGateway {
    public interface Callback {
        void onSuccess(AgentTurnContract.Response response);
        void onFailure(Throwable error);
    }
    public interface ContractCallback {
        void onSuccess(AgentServerContract contract);
        void onFailure(Throwable error);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AuthenticatedHttpClient http;
    private volatile AgentServerContract contract;
    private final com.click.data.snapshot.SettingsScreenPrivacy screenPrivacy;

    public AgentTurnGateway(Context context) {
        http = new AuthenticatedHttpClient(context);
        com.click.data.snapshot.SettingsScreenPrivacy loaded = null;
        try (java.io.InputStream vocabulary = context.getAssets().open("vision_vocabulary.json");
             java.io.InputStream policy = context.getAssets().open("screen_privacy_policy.json")) {
            loaded = com.click.data.snapshot.SettingsScreenPrivacy.load(vocabulary, policy);
        } catch (Exception ignored) { /* Missing policy fails closed before screen HTTP. */ }
        screenPrivacy = loaded;
    }

    /** Run before action events or one-use approvals are consumed by a request. */
    public boolean needsFreshObservation(com.click.data.snapshot.ScreenSnapshot screen) {
        if (screenPrivacy == null || screen == null) return false;
        try {
            screenPrivacy.filter(new org.json.JSONObject().put("accessibility_tree", new org.json.JSONObject(screen.toJson())));
        } catch (com.click.data.snapshot.SettingsScreenPrivacy.Incomplete incomplete) {
            return true;
        } catch (Exception blockedOrInvalid) {
            // The actual send path must enforce its original privacy/contract error.
        }
        return false;
    }

    public void send(AgentTurnContract.Request request, Callback callback) {
        executor.execute(() -> {
            try {
                AgentTurnContract.Response response = execute(request);
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Throwable error) {
                mainHandler.post(() -> callback.onFailure(error));
            }
        });
    }

    private AgentTurnContract.Response execute(AgentTurnContract.Request request) throws Exception {
        AgentServerContract server = loadContract();
        if (!server.supports(request.commandType()))
            throw new java.io.IOException("현재 서버에서 이 작업 단계를 지원하지 않아요.");
        org.json.JSONObject body = new org.json.JSONObject(server.encode(request));
        if ("cancel_task".equals(request.commandType()) || "pause_task".equals(request.commandType())) {
            body.remove("accessibility_tree");
            body.remove("vision_snapshot");
        }
        if (body.has("accessibility_tree") || body.has("vision_snapshot")) {
            if (screenPrivacy == null)
                throw new com.click.data.snapshot.SettingsScreenPrivacy.Blocked();
            body = screenPrivacy.filter(body, request.observationSetting,
                    request.accessibilityTree == null || request.accessibilityTree.privacyMaskingReady);
        }
        HttpResult result = http.post(AgentTurnContract.PATH, body.toString());
        AgentTurnContract.Response response = new AgentTurnContract.Response(result.body);
        if (response.metadata != null && result.traceId != null)
            response.metadata.put("backend_trace_id", result.traceId);
        return response;
    }

    public AgentServerContract currentContract() { return contract; }

    public void loadContract(ContractCallback callback) {
        executor.execute(() -> {
            try {
                AgentServerContract result = loadContract();
                mainHandler.post(() -> callback.onSuccess(result));
            } catch (Exception error) { mainHandler.post(() -> callback.onFailure(error)); }
        });
    }

    private AgentServerContract loadContract() throws Exception {
        if (contract == null) {
            HttpResult result = AuthGateway.get("/openapi.json", null);
            if (result.status < 200 || result.status >= 300) throw new ApiException("GET", "/openapi.json", result);
            contract = AgentServerContract.fromOpenApi(new org.json.JSONObject(result.body));
        }
        return contract;
    }

    public void close() { executor.shutdownNow(); }

    /** Recover the authoritative version after a lost response; contains no replayable guide. */
    public void synchronize(String sessionId, Callback callback) {
        executor.execute(() -> {
            try {
                org.json.JSONObject state = new org.json.JSONObject(http.get("/api/v1/agent/sessions/" + sessionId).body);
                org.json.JSONObject outcome = state.optJSONObject("task_outcome");
                org.json.JSONObject metadata = new org.json.JSONObject().put("task_outcome", outcome)
                        .put("task_id", state.opt("task_id"))
                        .put("requires_goal", state.optBoolean("requires_goal", false));
                if (outcome != null && "WAITING_RESULT".equals(state.optString("status")))
                    metadata.put("result_prompt", outcome.optJSONObject("prompt"));
                if (state.optJSONObject("result_prompt") != null)
                    metadata.put("result_prompt", state.getJSONObject("result_prompt"));
                if (state.optJSONObject("follow_up") != null)
                    metadata.put("follow_up", state.getJSONObject("follow_up"));
                AgentTurnContract.Response response = new AgentTurnContract.Response(new org.json.JSONObject()
                        .put("request_id", java.util.UUID.randomUUID().toString())
                        .put("session_id", sessionId).put("session_version", state.getInt("version"))
                        .put("episode_id", state.opt("episode_id")).put("task_status", state.getString("status"))
                        .put("response_type", "control").put("metadata", metadata).toString());
                mainHandler.post(() -> callback.onSuccess(response));
            } catch (Throwable error) { mainHandler.post(() -> callback.onFailure(error)); }
        });
    }
}
