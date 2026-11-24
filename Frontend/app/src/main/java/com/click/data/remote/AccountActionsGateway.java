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
    interface SocialConnection { void unlink(java.util.function.Consumer<Boolean> result); }
    private final SocialConnection social;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AccountActionsGateway(Context context) {
        this(context, new AuthTokenStore(context), new AuthenticatedHttpClient(context),
                Executors.newSingleThreadExecutor());
    }

    AccountActionsGateway(Context context, AuthTokenStore tokens, AuthenticatedHttpClient http, Executor executor) {
        this(context, tokens, http, executor, result -> KakaoSignIn.unlink(context, result));
    }

    AccountActionsGateway(Context context, AuthTokenStore tokens, AuthenticatedHttpClient http, Executor executor,
                          SocialConnection social) {
        this.context = context.getApplicationContext();
        this.tokens = tokens;
        this.http = http;
        this.executor = executor;
        this.social = social;
    }

    public void logout(AuthGateway.Callback callback) { submit(false, callback); }

    public void deleteAccount(AuthGateway.Callback callback) { submit(true, callback); }

    private void submit(boolean delete, AuthGateway.Callback callback) {
        executor.execute(() -> {
            final String generation, userId, provider;
            synchronized (AuthTokenStore.SESSION_LOCK) {
                generation = tokens.sessionGeneration();
                userId = tokens.userId();
                provider = tokens.authenticationProvider();
            }
            // Older Kakao builds intentionally remove the local phone profile on sign-in.
            final boolean kakao = "kakao".equals(provider) || ("unknown".equals(provider)
                    && !new LocalAccountStore(context).hasAccount());
            try {
                if (delete) {
                    if (userId == null || userId.isEmpty()) throw new IOException("AUTH_REQUIRED");
                    http.deleteForSession("/api/v1/users/" + URLEncoder.encode(userId, "UTF-8"), userId, generation);
                } else {
                    http.postForSession("/api/v1/auth/logout", "{}", userId, generation);
                }
            } catch (Exception error) {
                main.post(() -> callback.onFailure(error));
                return;
            }
            main.post(() -> {
                synchronized (AuthTokenStore.SESSION_LOCK) {
                    if (!java.util.Objects.equals(generation, tokens.sessionGeneration())) {
                        callback.onFailure(new IOException("ACCOUNT_CHANGED"));
                        return;
                    }
                    context.stopService(new Intent(context, ClickOverlayService.class));
                    new TouchAutomationSettings(context).setEnabled(false);
                    new LocalAccountStore(context).deleteAccount();
                    tokens.clear();
                }
                if (delete && kakao) {
                    social.unlink(success -> main.post(() -> {
                        if (success) callback.onSuccess();
                        else callback.onSuccessWithNotice("클릭 계정은 삭제됐어요. 카카오 연결 해제는 완료하지 못했어요. 카카오계정의 연결된 서비스에서 클릭 연결을 해제해 주세요.");
                    }));
                } else callback.onSuccess();
            });
        });
    }

    public void close() {
        // Let an already confirmed account action finish, including during Activity recreation.
        if (executor instanceof ExecutorService) ((ExecutorService) executor).shutdown();
    }
}
