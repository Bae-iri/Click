package com.click.data.remote;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.click.data.local.AuthTokenStore;
import com.click.data.local.LocalAccountStore;
import com.click.data.local.TouchAutomationSettings;
import com.click.service.overlay.ClickOverlayService;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Clear local account state only after the server confirms the requested action. */
public final class AccountActionsGateway {
    private final Context context;
    private final AuthTokenStore tokens;
    private final AuthenticatedHttpClient http;
    private final Executor executor;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AccountActionsGateway(Context context) {
        this(context, new AuthTokenStore(context), new AuthenticatedHttpClient(context),
                Executors.newSingleThreadExecutor());
    }

    AccountActionsGateway(Context context, AuthTokenStore tokens, AuthenticatedHttpClient http, Executor executor) {
        this.context = context.getApplicationContext();
        this.tokens = tokens;
        this.http = http;
        this.executor = executor;
    }

    public void logout(AuthGateway.Callback callback) { submit(false, callback); }

    public void deleteAccount(AuthGateway.Callback callback) { submit(true, callback); }

    private void submit(boolean delete, AuthGateway.Callback callback) {
        executor.execute(() -> {
            try {
                if (delete) {
                    String userId = tokens.userId();
                    if (userId == null || userId.isEmpty()) throw new IOException("AUTH_REQUIRED");
                    http.delete("/api/v1/users/" + URLEncoder.encode(userId, "UTF-8"));
                } else {
                    http.post("/api/v1/auth/logout", "{}");
                }
            } catch (Exception error) {
                main.post(() -> callback.onFailure(error));
                return;
            }
            main.post(() -> {
                context.stopService(new Intent(context, ClickOverlayService.class));
                new TouchAutomationSettings(context).setEnabled(false);
                new LocalAccountStore(context).deleteAccount();
                tokens.clear();
                callback.onSuccess();
            });
        });
    }

    public void close() {
        // Let an already confirmed account action finish, including during Activity recreation.
        if (executor instanceof ExecutorService) ((ExecutorService) executor).shutdown();
    }
}
