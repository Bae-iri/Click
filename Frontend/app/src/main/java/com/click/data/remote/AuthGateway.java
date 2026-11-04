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
    // One cache per app/server, shared by screen loading and the later registration request.
    private static final RegistrationRulesLoader REGISTRATION_RULES = new RegistrationRulesLoader(
            () -> get("/openapi.json", null, 30000), android.os.SystemClock::elapsedRealtime);

    public interface Callback {
        void onSuccess();
        void onFailure(Throwable error);
    }

    public interface RegistrationRulesCallback {
        void onSuccess(int minimumPasswordLength, int maximumPasswordLength);
        void onFailure(Throwable error);
    }

    private final AuthTokenStore tokens;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public AuthGateway(Context context) { tokens = new AuthTokenStore(context.getApplicationContext()); }

    public void register(String name, String email, String password, Callback callback) {
        executor.execute(() -> {
            try {
                RegistrationContract contract = registrationContract();
                JSONObject body = contract.request(name, email, password);
                completeAuthentication("/api/v1/auth/register", body, callback);
            } catch (Exception error) {
                main.post(() -> callback.onFailure(error));
            }
        });
    }

    public void loadRegistrationRules(RegistrationRulesCallback callback) {
        executor.execute(() -> {
            try {
                RegistrationContract contract = registrationContract();
                main.post(() -> callback.onSuccess(contract.minimumPasswordLength, contract.maximumPasswordLength));
            } catch (Exception error) {
                main.post(() -> callback.onFailure(error));
            }
        });
    }

    private static RegistrationContract registrationContract() throws Exception {
        return REGISTRATION_RULES.load();
    }

    public void login(String email, String password, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("phone_number", email).put("password", password); }
        catch (Exception error) { callback.onFailure(error); return; }
        authenticate("/api/v1/auth/login", body, callback);
    }

    public void loginWithKakao(String accessToken, Callback callback) {
        JSONObject body = new JSONObject();
        try { body.put("access_token", accessToken); }
        catch (Exception error) { callback.onFailure(error); return; }
        authenticate("/api/v1/auth/kakao", body, callback);
    }

    private void authenticate(String path, JSONObject body, Callback callback) {
        executor.execute(() -> {
            try {
                completeAuthentication(path, body, callback);
            } catch (Throwable error) {
                main.post(() -> callback.onFailure(error));
            }
        });
    }

    private void completeAuthentication(String path, JSONObject body, Callback callback) throws Exception {
        HttpResult result = post(path, body.toString(), null);
        if (result.status < 200 || result.status >= 300) throw apiError(path, result);
        tokens.save(new JSONObject(result.body));
        main.post(callback::onSuccess);
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
        return post(path, body, bearer, 15000);
    }

    static HttpResult post(String path, String body, String bearer, int readTimeout) throws IOException {
        requireValidationEnvironment(path);
        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.CLICK_API_BASE_URL + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(readTimeout);
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
        String traceId = connection.getHeaderField("X-Request-ID");
        connection.disconnect();
        return new HttpResult(status, response, conflict, traceId);
    }

    static HttpResult get(String path, String bearer) throws IOException {
        return get(path, bearer, 15000);
    }

    private static HttpResult get(String path, String bearer, int readTimeout) throws IOException {
        return requestWithoutBody("GET", path, bearer, readTimeout);
    }

    static HttpResult delete(String path, String bearer) throws IOException {
        return requestWithoutBody("DELETE", path, bearer, 15000);
    }

    private static HttpResult requestWithoutBody(String method, String path, String bearer, int readTimeout) throws IOException {
        requireValidationEnvironment(path);
        HttpURLConnection connection = (HttpURLConnection) new URL(BuildConfig.CLICK_API_BASE_URL + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(readTimeout);
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer " + bearer);
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String response = read(stream);
            String conflict = connection.getHeaderField("X-Conflict-Code");
            return new HttpResult(status, response, conflict, connection.getHeaderField("X-Request-ID"));
        } finally {
            connection.disconnect();
        }
    }

    static ApiException apiError(String path, HttpResult result) {
        return new ApiException("POST", path, result);
    }

    private static void requireValidationEnvironment(String path) throws IOException {
        if (BuildConfig.CLICK_VALIDATION_ENVIRONMENT_ID.isEmpty() || "/validation/environment".equals(path)) return;
        HttpResult identity = get("/validation/environment", null, 6000);
        if (identity.status != 200) throw new IOException("VALIDATION_ENVIRONMENT_MISMATCH");
        try {
            ValidationServerContract.requireMatch(new JSONObject(identity.body),
                    BuildConfig.CLICK_VALIDATION_ENVIRONMENT_ID, BuildConfig.CLICK_VALIDATION_FINGERPRINT);
        } catch (org.json.JSONException error) {
            throw new IOException("VALIDATION_ENVIRONMENT_MISMATCH");
        }
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
