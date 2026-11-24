package com.click.data.remote;

import android.app.Activity;

import com.click.BuildConfig;
import com.kakao.sdk.auth.model.OAuthToken;
import com.kakao.sdk.common.KakaoSdk;
import com.kakao.sdk.common.model.ClientError;
import com.kakao.sdk.common.model.ClientErrorCause;
import com.kakao.sdk.user.UserApiClient;

import kotlin.Unit;
import kotlin.jvm.functions.Function2;

/** Kakao SDK owns browser redirects and native login; Backend verifies the resulting token. */
public final class KakaoSignIn {
    public interface Callback {
        void authenticated(String accessToken);
        void failed(String message);
    }

    public static void initialize(android.content.Context context) {
        if (!BuildConfig.KAKAO_NATIVE_APP_KEY.isEmpty()) {
            KakaoSdk.init(context, BuildConfig.KAKAO_NATIVE_APP_KEY);
        }
    }

    public static void start(Activity activity, Callback callback) {
        if (BuildConfig.KAKAO_NATIVE_APP_KEY.isEmpty()) {
            callback.failed("카카오 로그인을 준비 중이에요. 전화번호로 시작해 주세요.");
            return;
        }
        // An account may also have been deleted on another device. Never silently re-register.
        confirmRegistration(activity, () -> authenticate(activity, callback),
                () -> callback.failed("카카오로 시작하기를 취소했어요."));
    }

    static void confirmRegistration(Activity activity, Runnable proceed, Runnable cancel) {
        java.util.concurrent.atomic.AtomicBoolean answered = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable accept = () -> { if (answered.compareAndSet(false, true)) proceed.run(); };
        Runnable reject = () -> { if (answered.compareAndSet(false, true)) cancel.run(); };
        new android.app.AlertDialog.Builder(activity)
                .setTitle("카카오로 클릭을 시작할까요?")
                .setMessage("클릭 계정이 없으면 새 계정이 만들어져요. 탈퇴한 계정의 정보는 복구되지 않아요."
                        + (new com.click.data.local.KakaoAccountState(activity).unlinkPending()
                        ? "\n\n이전 카카오 연결 해제가 완료되지 않았어요. 카카오계정의 연결된 서비스에서 해제할 수 있어요." : ""))
                .setPositiveButton("확인하고 계속", (dialog, which) -> accept.run())
                .setNegativeButton("취소", (dialog, which) -> reject.run())
                .setOnCancelListener(dialog -> reject.run())
                .show();
    }

    private static void authenticate(Activity activity, Callback callback) {
        UserApiClient client = UserApiClient.Companion.getInstance();
        Function2<OAuthToken, Throwable, Unit> complete = (token, error) -> {
            if (activity.isDestroyed() || activity.isFinishing()) return Unit.INSTANCE;
            if (error != null || token == null) {
                callback.failed(cancelled(error) ? "카카오 로그인을 취소했어요."
                        : "카카오 로그인을 완료하지 못했어요. 다시 시도해 주세요.");
            } else callback.authenticated(token.getAccessToken());
            return Unit.INSTANCE;
        };
        try {
            if (client.isKakaoTalkLoginAvailable(activity)) {
                client.loginWithKakaoTalk(activity, (token, error) -> {
                    if (activity.isDestroyed() || activity.isFinishing()) return Unit.INSTANCE;
                    if (error != null && !cancelled(error)) {
                        client.loginWithKakaoAccount(activity, complete);
                        return Unit.INSTANCE;
                    }
                    return complete.invoke(token, error);
                });
            } else client.loginWithKakaoAccount(activity, complete);
        } catch (RuntimeException error) {
            callback.failed("카카오 로그인을 열지 못했어요. 전화번호로 시작해 주세요.");
        }
    }

    /** Called after Click confirms deletion; failure is surfaced without claiming unlink succeeded. */
    public static void unlink(android.content.Context context, java.util.function.Consumer<Boolean> completion) {
        com.click.data.local.KakaoAccountState state = new com.click.data.local.KakaoAccountState(context);
        state.unlinkPending(true);
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        java.util.concurrent.atomic.AtomicBoolean finished = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable timeout = () -> { if (finished.compareAndSet(false, true)) completion.accept(false); };
        handler.postDelayed(timeout, 15000L);
        java.util.function.Consumer<Boolean> finish = success -> {
            if (!finished.compareAndSet(false, true)) return;
            handler.removeCallbacks(timeout);
            state.unlinkPending(!success);
            completion.accept(success);
        };
        try {
            if (BuildConfig.KAKAO_NATIVE_APP_KEY.isEmpty()) { finish.accept(false); return; }
            UserApiClient.Companion.getInstance().unlink(error -> {
                boolean success = error == null;
                finish.accept(success);
                return Unit.INSTANCE;
            });
        } catch (RuntimeException error) { finish.accept(false); }
    }

    private static boolean cancelled(Throwable error) {
        return error instanceof ClientError && ((ClientError) error).getReason() == ClientErrorCause.Cancelled;
    }

    private KakaoSignIn() {}
}
