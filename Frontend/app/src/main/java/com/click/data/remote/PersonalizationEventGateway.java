package com.click.data.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.click.data.local.AuthTokenStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Best-effort, consent-gated timing events; never blocks user guidance. */
public final class PersonalizationEventGateway {
    public interface ConsentCallback {
        void onResult(boolean consented);
        void onFailure();
    }

    private final AuthTokenStore tokens;
    private final AuthenticatedHttpClient http;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public PersonalizationEventGateway(Context context) {
        tokens = new AuthTokenStore(context);
        http = new AuthenticatedHttpClient(context);
    }

    public void recordFirstTap(String sessionId, String stateId, int step, long durationMs) {
        if (sessionId == null || stateId == null || step < 0
                || durationMs < 0 || durationMs > 86_400_000L) return;
        executor.execute(() -> {
            try {
                String userId = tokens.userId();
                if (userId == null || userId.isEmpty()) return;
                String base = userBase(userId);
                if (!hasConsent(base)) return;
                JSONObject body = new JSONObject()
                        .put("session_id", sessionId)
                        .put("event_type", "guide_action_observed")
                        .put("screen_name", stateId)
                        .put("metadata", new JSONObject()
                                .put("step", step)
                                .put("duration_ms", durationMs));
                http.post(base + "/events", body.toString());
            } catch (Exception error) {
                // Telemetry failures must not interrupt guidance or log tokens/URLs.
                Log.w("ClickTiming", "Guide timing event was not saved");
            }
        });
    }

    public void readConsent(ConsentCallback callback) {
        executor.execute(() -> {
            try {
                String userId = tokens.userId();
                if (userId == null || userId.isEmpty()) { main.post(callback::onFailure); return; }
                boolean consented = hasConsent(userBase(userId));
                main.post(() -> callback.onResult(consented));
            } catch (Exception error) {
                main.post(callback::onFailure);
            }
        });
    }

    public void setConsent(boolean consented, ConsentCallback callback) {
        executor.execute(() -> {
            try {
                String userId = tokens.userId();
                if (userId == null || userId.isEmpty()) { main.post(callback::onFailure); return; }
                JSONObject body = new JSONObject()
                        .put("purpose", "personalization")
                        .put("consented", consented)
                        .put("policy_version", "step-timing.v1");
                http.post(userBase(userId) + "/consents", body.toString());
                main.post(() -> callback.onResult(consented));
            } catch (Exception error) {
                main.post(callback::onFailure);
            }
        });
    }

    private String userBase(String userId) throws Exception {
        return "/api/v1/users/" + URLEncoder.encode(userId, "UTF-8");
    }

    private boolean hasConsent(String base) throws Exception {
        JSONArray consents = new JSONArray(http.get(base + "/consents").body);
        for (int index = 0; index < consents.length(); index++) {
            JSONObject consent = consents.getJSONObject(index);
            if ("personalization".equals(consent.optString("purpose"))) {
                return consent.optBoolean("consented");
            }
        }
        return false;
    }

    public void close() { executor.shutdown(); }
}
