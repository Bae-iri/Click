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

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AuthenticatedHttpClient http;

    public AgentTurnGateway(Context context) {
        http = new AuthenticatedHttpClient(context);
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
        HttpResult result = http.post(AgentTurnContract.PATH, request.toJson());
        return new AgentTurnContract.Response(result.body);
    }

    public void close() { executor.shutdownNow(); }
}
