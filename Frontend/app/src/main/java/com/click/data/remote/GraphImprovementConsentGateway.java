package com.click.data.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.click.data.local.AuthTokenStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional participation in shared route improvement; never uploads UI behavior. */
public final class GraphImprovementConsentGateway {
    public interface Callback {
        void onResult(boolean consented);
        void onFailure();
    }
    private final AuthTokenStore tokens;
    private final AuthenticatedHttpClient http;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public GraphImprovementConsentGateway(Context context) {
        tokens = new AuthTokenStore(context);
        http = new AuthenticatedHttpClient(context);
    }

    private String path() throws Exception {
        String user = tokens.userId();
        if (user == null || user.isEmpty()) throw new IllegalStateException("Login required");
        return "/api/v1/users/" + URLEncoder.encode(user, "UTF-8") + "/consents";
    }

    public void read(Callback callback) {
        executor.execute(() -> {
            try {
                JSONArray rows = new JSONArray(http.get(path()).body);
                boolean consented = false;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    if ("model_training".equals(row.optString("purpose"))) consented = row.optBoolean("consented");
                }
                final boolean result = consented;
                main.post(() -> callback.onResult(result));
            } catch (Exception error) { main.post(callback::onFailure); }
        });
    }

    public void set(boolean consented, Callback callback) {
        executor.execute(() -> {
            try {
                http.post(path(), new JSONObject().put("purpose", "model_training")
                        .put("consented", consented).put("policy_version", "graph-improvement.v1").toString());
                main.post(() -> callback.onResult(consented));
            } catch (Exception error) { main.post(callback::onFailure); }
        });
    }

    public void close() { executor.shutdown(); }
}
