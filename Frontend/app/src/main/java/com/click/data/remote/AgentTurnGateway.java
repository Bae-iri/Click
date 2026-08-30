package com.click.data.remote;

import android.os.Handler;
import android.os.Looper;

import com.click.BuildConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentTurnGateway {
    public interface Callback {
        void onSuccess(AgentTurnContract.Response response);
        void onFailure(Throwable error);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
        URL url = new URL(BuildConfig.CLICK_API_BASE_URL + AgentTurnContract.PATH);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(15000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = request.toJson().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String responseBody = read(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException("Agent API " + status + ": " + responseBody);
        }
        return new AgentTurnContract.Response(responseBody);
    }

    private String read(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    public void close() { executor.shutdownNow(); }
}
