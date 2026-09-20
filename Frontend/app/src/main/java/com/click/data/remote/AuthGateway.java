package com.click.data.remote;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.click.BuildConfig;
import com.click.data.local.AuthTokenStore;

import org.json.JSONObject;

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

public final class AuthGateway {
    public interface Callback {
        void onSuccess();
        void onFailure(Throwable error);
    }

    private final AuthTokenStore tokens;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public AuthGateway(Context context) { tokens = new AuthTokenStore(context.getApplicationContext()); }

    public void register(String email, String password, String ageGroup, String occupation, Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("email", email).put("password", password).put("age_group", ageGroup);
            if (occupation != null) body.put("occupation_category", occupation);
        } catch (Exception error) {
            callback.onFailure(error);
            return;
        }
        authenticate("/api/v1/auth/register", body, callback);
    }

    public void login(String email, String password, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("email", email).put("password", password); }
        catch (Exception error) { callback.onFailure(error); return; }
        authenticate("/api/v1/auth/login", body, callback);
    }

    private void authenticate(String path, JSONObject body, Callback callback) {
        executor.execute(() -> {
            try {
                HttpResult result = post(path, body.toString(), null);
                if (result.status < 200 || result.status >= 300) throw apiError(path, result);
                tokens.save(new JSONObject(result.body));
                main.post(callback::onSuccess);
            } catch (Throwable error) {
                main.post(() -> callback.onFailure(error));
            }
        });
    }

    static synchronized boolean refresh(AuthTokenStore tokens) throws Exception {
        if (!tokens.hasRefreshToken()) return false;
        JSONObject body = new JSONObject().put("refresh_token", tokens.refreshToken());
        HttpResult result = post("/api/v1/auth/refresh", body.toString(), null);
        if (result.status < 200 || result.status >= 300) {
            tokens.clear();
            throw apiError("/api/v1/auth/refresh", result);
        }
        tokens.save(new JSONObject(result.body));
        return true;
    }

    static HttpResult post(String path, String body, String bearer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.CLICK_API_BASE_URL + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(15000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = read(stream);
        String conflict = connection.getHeaderField("X-Conflict-Code");
        connection.disconnect();
        return new HttpResult(status, response, conflict);
    }

    static HttpResult get(String path, String bearer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.CLICK_API_BASE_URL + path).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(15000);
        if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = read(stream);
        String conflict = connection.getHeaderField("X-Conflict-Code");
        connection.disconnect();
        return new HttpResult(status, response, conflict);
    }

    static ApiException apiError(String path, HttpResult result) {
        return new ApiException("POST", path, result);
    }

    private static String read(InputStream stream) throws IOException {
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
