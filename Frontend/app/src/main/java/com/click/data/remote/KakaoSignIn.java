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

    private static boolean cancelled(Throwable error) {
        return error instanceof ClientError && ((ClientError) error).getReason() == ClientErrorCause.Cancelled;
    }

    private KakaoSignIn() {}
}
