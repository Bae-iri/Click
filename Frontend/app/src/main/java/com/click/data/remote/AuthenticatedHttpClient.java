package com.click.data.remote;

import android.content.Context;
import com.click.data.local.AuthTokenStore;

final class AuthenticatedHttpClient {
    private final AuthTokenStore tokens;
    AuthenticatedHttpClient(Context context) { this(new AuthTokenStore(context.getApplicationContext())); }
    AuthenticatedHttpClient(AuthTokenStore tokens) { this.tokens = tokens; }

    HttpResult post(String path, String body) throws Exception { return post(path, body, 15000); }
    HttpResult post(String path, String body, int readTimeout) throws Exception {
        return execute("POST", path, token -> AuthGateway.post(path, body, token, readTimeout));
    }
    HttpResult get(String path) throws Exception {
        return execute("GET", path, token -> AuthGateway.get(path, token));
    }
    HttpResult delete(String path) throws Exception {
        return execute("DELETE", path, token -> AuthGateway.delete(path, token));
    }
    HttpResult postForSession(String path, String body, String owner, String generation) throws Exception {
        return execute("POST", path, token -> AuthGateway.post(path, body, token, 15000), owner, generation, true);
    }
    HttpResult deleteForSession(String path, String owner, String generation) throws Exception {
        return execute("DELETE", path, token -> AuthGateway.delete(path, token), owner, generation, true);
    }
    private void requireSession(String owner, String generation) throws java.io.IOException {
        if (!java.util.Objects.equals(owner, tokens.userId())
                || !java.util.Objects.equals(generation, tokens.sessionGeneration()))
            throw new java.io.IOException("AUTH_REQUIRED");
    }
    private interface Call { HttpResult send(String token) throws Exception; }
    private HttpResult execute(String method, String path, Call call) throws Exception {
        return execute(method, path, call, null, null, false);
    }
    private HttpResult execute(String method, String path, Call call, String expectedOwner,
                               String expectedGeneration, boolean bound) throws Exception {
        try (RequestBudget budget = RequestBudget.ifAbsent()) {
            final String owner, generation;
            String sent;
            boolean expired;
            synchronized (AuthTokenStore.SESSION_LOCK) {
                owner = bound ? expectedOwner : tokens.userId();
                generation = bound ? expectedGeneration : tokens.sessionGeneration();
                requireSession(owner, generation);
                sent = tokens.accessToken();
                expired = sent == null || tokens.accessExpired();
            }
            if (expired && !AuthGateway.refresh(tokens, sent)) throw new java.io.IOException("AUTH_REQUIRED");
            synchronized (AuthTokenStore.SESSION_LOCK) {
                requireSession(owner, generation);
                sent = tokens.accessToken();
            }
            HttpResult result = call.send(sent);
            if (result.status == 401 && AuthGateway.refresh(tokens, sent)) {
                synchronized (AuthTokenStore.SESSION_LOCK) {
                    requireSession(owner, generation);
                    sent = tokens.accessToken();
                }
                result = call.send(sent);
            }
            synchronized (AuthTokenStore.SESSION_LOCK) { requireSession(owner, generation); }
            if (result.status < 200 || result.status >= 300) throw new ApiException(method, path, result);
            return result;
        }
    }
}
