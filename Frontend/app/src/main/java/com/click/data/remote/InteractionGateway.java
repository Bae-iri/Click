package com.click.data.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Classifies speech/text before it enters the executable request queue. */
public class InteractionGateway {
    public interface Callback { void onSuccess(Response response); void onFailure(Throwable error); }
    public interface SaveCallback { void onSaved(); void onFailure(Throwable error); }
    public static final class Response {
        public final String intent, message, feedbackId, detail;
        public Response(String body) throws Exception {
            JSONObject json = new JSONObject(body);
            intent = json.getString("intent");
            if (!intent.equals("task") && !intent.equals("negative_feedback") && !intent.equals("positive_feedback"))
                throw new IllegalArgumentException("Unknown interaction intent");
            JSONObject feedback = json.optJSONObject("feedback");
            message = json.optString("message", null);
            feedbackId = feedback == null ? null : feedback.getString("id");
            detail = feedback == null ? null : feedback.optString("detail", null);
            if (!intent.equals("task") && (feedbackId == null || message == null))
                throw new IllegalArgumentException("Feedback response is incomplete");
        }
        public boolean isFeedback() { return !"task".equals(intent); }
    }
    private final AuthenticatedHttpClient http;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    public InteractionGateway(Context context) { http = new AuthenticatedHttpClient(context); }

    public void classify(String id, String text, String sessionId, Callback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("id", id).put("text", text);
                if (sessionId != null) body.put("session_id", sessionId);
                Response response = new Response(http.post("/api/v1/interactions/classify", body.toString()).body);
                main.post(() -> callback.onSuccess(response));
            } catch (Exception error) { main.post(() -> callback.onFailure(error)); }
        });
    }
    public void submit(String id, String detail, SaveCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject().put("detail", detail);
                http.post("/api/v1/feedback/" + id + "/detail", body.toString());
                main.post(callback::onSaved);
            } catch (Exception error) { main.post(() -> callback.onFailure(error)); }
        });
    }
    public void close() { executor.shutdownNow(); }
}
