package com.click.data.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class VoiceQueueGateway {
    public interface Callback {
        void onSuccess(Response response);
        void onFailure(Throwable error);
    }

    public static final class Request {
        public final String id;
        public final String rawGoal;
        public final String status;
        public final String agentSessionId;

        Request(JSONObject json) {
            id = json.optString("id", null);
            rawGoal = json.optString("raw_goal", null);
            status = json.optString("status", null);
            agentSessionId = json.optString("agent_session_id", null);
        }
    }

    public static final class Response {
        public final long queueVersion;
        public final boolean awaitingReplacement;
        public final boolean claimed;
        public final Request request;
        public final Request running;
        public final int queuedCount;
        public final java.util.List<Request> queued;

        Response(String body) throws Exception {
            JSONObject json = new JSONObject(body);
            queueVersion = json.optLong("queue_version");
            awaitingReplacement = json.optBoolean("awaiting_replacement");
            claimed = json.optBoolean("claimed");
            request = json.optJSONObject("request") == null ? null : new Request(json.getJSONObject("request"));
            running = json.optJSONObject("running") == null ? null : new Request(json.getJSONObject("running"));
            JSONArray queued = json.optJSONArray("queued");
            java.util.List<Request> items = new java.util.ArrayList<>();
            if (queued != null) for (int i = 0; i < queued.length(); i++) items.add(new Request(queued.getJSONObject(i)));
            this.queued = java.util.Collections.unmodifiableList(items);
            queuedCount = items.size();
        }
    }

    private final AuthenticatedHttpClient http;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public VoiceQueueGateway(Context context) { http = new AuthenticatedHttpClient(context); }

    public String enqueue(String rawGoal, Callback callback) {
        String id = UUID.randomUUID().toString();
        enqueue(id, rawGoal, callback);
        return id;
    }

    public void enqueue(String id, String rawGoal, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("id", id).put("raw_goal", rawGoal).put("priority", "NORMAL"); }
        catch (Exception error) { callback.onFailure(error); return; }
        post("/api/v1/voice-requests/enqueue", body, callback);
    }

    public String interrupt(String rawGoal, Callback callback) {
        return interrupt(rawGoal, false, callback);
    }

    public String interrupt(String rawGoal, boolean clearQueued, Callback callback) {
        String id = UUID.randomUUID().toString();
        JSONObject body = new JSONObject();
        try { body.put("id", id).put("raw_goal", rawGoal).put("clear_queued", clearQueued); }
        catch (Exception error) { callback.onFailure(error); return id; }
        post("/api/v1/voice-requests/interrupt-and-clear", body, callback);
        return id;
    }

    public void claim(Callback callback) {
        post("/api/v1/voice-requests/next/claim", new JSONObject(), callback);
    }

    public void complete(String id, Callback callback) {
        post("/api/v1/voice-requests/" + id + "/complete", new JSONObject(), callback);
    }

    public void cancel(String id, String reason, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("reason", reason); }
        catch (Exception error) { callback.onFailure(error); return; }
        post("/api/v1/voice-requests/" + id + "/cancel", body, callback);
    }

    public void fail(String id, String code, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("failure_code", code); }
        catch (Exception error) { callback.onFailure(error); return; }
        post("/api/v1/voice-requests/" + id + "/fail", body, callback);
    }

    private void post(String path, JSONObject body, Callback callback) {
        executor.execute(() -> {
            try {
                HttpResult result;
                try {
                    result = http.post(path, body.toString());
                } catch (java.io.IOException first) {
                    if (first.getMessage() == null || (!first.getMessage().contains("QUEUE_BUSY")
                            && !first.getMessage().contains("QUEUE_CONFLICT"))) throw first;
                    result = http.post(path, body.toString());
                }
                Response response = new Response(result.body);
                main.post(() -> callback.onSuccess(response));
            } catch (Throwable error) {
                main.post(() -> callback.onFailure(error));
            }
        });
    }

    public void close() { executor.shutdownNow(); }
}
