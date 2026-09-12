package com.click.data.remote;

import android.content.Context;

import com.click.data.local.AuthTokenStore;

final class AuthenticatedHttpClient {
    private final AuthTokenStore tokens;

    AuthenticatedHttpClient(Context context) { tokens = new AuthTokenStore(context.getApplicationContext()); }

    HttpResult post(String path, String body) throws Exception {
        if (tokens.accessToken() == null || (tokens.accessExpired() && !AuthGateway.refresh(tokens))) {
            throw new java.io.IOException("AUTH_REQUIRED");
        }
        HttpResult result = AuthGateway.post(path, body, tokens.accessToken());
        if (result.status == 401 && AuthGateway.refresh(tokens)) {
            result = AuthGateway.post(path, body, tokens.accessToken());
        }
        if (result.status < 200 || result.status >= 300) throw AuthGateway.apiError(result);
        return result;
    }
}
