package com.click.data.remote;

import android.content.Context;

import com.click.data.local.AuthTokenStore;

final class AuthenticatedHttpClient {
    private final AuthTokenStore tokens;

    AuthenticatedHttpClient(Context context) { tokens = new AuthTokenStore(context.getApplicationContext()); }

    HttpResult post(String path, String body) throws Exception {
        return post(path, body, 15000);
    }

    HttpResult post(String path, String body, int readTimeout) throws Exception {
        return execute("POST",path,token->AuthGateway.post(path,body,token,readTimeout));
    }

    HttpResult get(String path) throws Exception {
        return execute("GET",path,token->AuthGateway.get(path,token));
    }

    HttpResult delete(String path) throws Exception {
        return execute("DELETE",path,token->AuthGateway.delete(path,token));
    }

    private interface Call { HttpResult send(String token) throws Exception; }

    private HttpResult execute(String method,String path,Call call) throws Exception {
        // Preserve an enclosing route deadline, including auth refresh and retry.
        try (RequestBudget budget=RequestBudget.ifAbsent()) {
            if (tokens.accessToken()==null || (tokens.accessExpired()&&!AuthGateway.refresh(tokens)))
                throw new java.io.IOException("AUTH_REQUIRED");
            HttpResult result=call.send(tokens.accessToken());
            if(result.status==401 && AuthGateway.refresh(tokens))result=call.send(tokens.accessToken());
            if(result.status<200 || result.status>=300)throw new ApiException(method,path,result);
            return result;
        }
    }
}
