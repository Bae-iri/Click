package com.click;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import com.click.core.permission.PermissionStatus;
import com.click.data.local.AccessibilityOnboardingState;
import com.click.data.local.AuthTokenStore;
import com.click.data.local.LocalAccountStore;
import com.click.data.remote.ApiException;
import com.click.data.remote.AuthGateway;
import com.click.data.remote.AuthErrorMessages;
import com.click.data.remote.KakaoSignIn;
import com.click.designpreview.ClickHost;
import com.click.service.accessibility.ClickAccessibilityService;

public final class ClickApplication extends Application implements ClickHost {
    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new com.click.service.overlay.AppOverlayLifecycle());
        KakaoSignIn.initialize(this);
        registerActivityLifecycleCallbacks(new AppVisibilityCallbacks());
    }
    private int startedActivities;
    private boolean appForeground;
    private final android.os.Handler visibilityHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable appStopped = () -> {
        if (startedActivities != 0) return;
        appForeground = false;
        com.click.service.overlay.ClickOverlayService.onAppVisibilityChanged(false);
    };

    public boolean isAppForeground() { return appForeground; }

    private final class AppVisibilityCallbacks implements ActivityLifecycleCallbacks {
        @Override public void onActivityStarted(Activity activity) {
            visibilityHandler.removeCallbacks(appStopped);
            startedActivities++;
            appForeground = true;
            com.click.service.overlay.ClickOverlayService.onAppVisibilityChanged(true);
            }
        @Override public void onActivityStopped(Activity activity) {
            startedActivities--;
            // The replacement activity owns visibility during a configuration change.
            if (startedActivities == 0 && !activity.isChangingConfigurations()) {
                visibilityHandler.post(appStopped);
            }
        }
        @Override public void onActivityCreated(Activity activity, android.os.Bundle state) { }
        @Override public void onActivityResumed(Activity activity) {
            if (!(activity instanceof com.click.designpreview.DesignPreviewActivity))
                com.click.ui.common.ClickUiSpeed.reveal(activity.findViewById(android.R.id.content));
        }
        @Override public void onActivityPaused(Activity activity) {
            com.click.ui.common.ClickUiSpeed.finishReveal(activity.findViewById(android.R.id.content));
        }
        @Override public void onActivitySaveInstanceState(Activity activity, android.os.Bundle state) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }

    @Override public boolean signedIn() { return new AuthTokenStore(this).hasRefreshToken(); }
    @Override public boolean accessibilityEnabled() { return PermissionStatus.isAccessibilityEnabled(this); }
    @Override public boolean accessibilityConnected() {
        return accessibilityEnabled() && ClickAccessibilityService.accessibilityWindowManager() != null;
    }

    @Override public void loadRegistrationRules(Activity activity, PasswordRulesCallback callback) {
        AuthGateway gateway = new AuthGateway(this);
        gateway.loadRegistrationRules(new AuthGateway.RegistrationRulesCallback() {
            @Override public void onSuccess(int minimum, int maximum) {
                gateway.close();
                if (!activity.isDestroyed()) callback.complete(minimum, maximum);
            }
            @Override public void onFailure(Throwable error) {
                gateway.close();
                Log.w("ClickAuth", "Registration rules failed: " + (error instanceof ApiException
                        ? "HTTP " + ((ApiException) error).status : error.getClass().getSimpleName()));
                if (!activity.isDestroyed()) callback.failed();
            }
        });
    }

    @Override public void register(Activity activity, String name, String email, String password, Callback callback) {
        AuthGateway gateway = new AuthGateway(this);
        gateway.register(name, email, password, new AuthGateway.Callback() {
            @Override public void onSuccess() {
                gateway.close();
                new LocalAccountStore(ClickApplication.this).saveProfile(
                        name, email);
                if (!activity.isDestroyed()) callback.complete(true, false, "");
            }
            @Override public void onFailure(Throwable error) {
                gateway.close();
                boolean duplicate = error instanceof ApiException && ((ApiException) error).status == 409;
                // API response bodies may contain rejected credentials; never log them.
                Log.e("ClickAuth", "Registration failed: " + (error instanceof ApiException
                        ? "HTTP " + ((ApiException) error).status : error.getClass().getSimpleName()));
                String message = AuthErrorMessages.registration(error);
                if (!activity.isDestroyed()) callback.complete(false, duplicate, message);
            }
        });
    }

    @Override public void login(Activity activity, String email, String password, Callback callback) {
        AuthGateway gateway = new AuthGateway(this);
        gateway.login(email, password, new AuthGateway.Callback() {
            @Override public void onSuccess() {
                gateway.close();
                LocalAccountStore account = new LocalAccountStore(ClickApplication.this);
                account.saveProfile(account.name(), email);
                if (!activity.isDestroyed()) callback.complete(true, false, "");
            }
            @Override public void onFailure(Throwable error) {
                gateway.close();
                String message = error instanceof ApiException && ((ApiException) error).status == 401
                        ? "전화번호나 비밀번호를 다시 확인해 주세요."
                        : "로그인하지 못했어요. 연결 상태를 확인하고 다시 시도해 주세요.";
                if (!activity.isDestroyed()) callback.complete(false, false, message);
            }
        });
    }

    @Override public void openHome(Activity activity) {
        activity.startActivity(new Intent(activity, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }

    @Override public void loginWithKakao(Activity activity, Callback callback) {
        KakaoSignIn.start(activity, new KakaoSignIn.Callback() {
            @Override public void authenticated(String accessToken) {
                AuthGateway gateway = new AuthGateway(ClickApplication.this);
                gateway.loginWithKakao(accessToken, new AuthGateway.Callback() {
                    @Override public void onSuccess() {
                        gateway.close();
                        // Never carry another account's locally stored profile into this identity.
                        new LocalAccountStore(ClickApplication.this).deleteAccount();
                        if (!activity.isDestroyed()) callback.complete(true, false, "");
                    }
                    @Override public void onFailure(Throwable error) {
                        gateway.close();
                        String message = error instanceof ApiException && ((ApiException) error).status == 401
                                ? "카카오 인증이 만료됐어요. 다시 로그인해 주세요."
                                : "지금은 카카오 로그인을 이용할 수 없어요. 다시 시도하거나 전화번호로 시작해 주세요.";
                        if (!activity.isDestroyed()) callback.complete(false, false, message);
                    }
                });
            }
            @Override public void failed(String message) {
                if (!activity.isDestroyed()) callback.complete(false, false, message);
            }
        });
    }

    @Override public void openAccessibilityGuide(Activity activity) {
        activity.startActivity(new Intent(activity,
                com.click.designpreview.PermissionGuideActivity.class));
    }

    @Override public void openAccessibilitySettings(Activity activity) {
        new AccessibilityOnboardingState(this).begin(activity.getIntent().getBooleanExtra(
                com.click.designpreview.PermissionGuideActivity.EXTRA_RETURN_TO_SETTINGS, false));
        // This system action is not exposed in the public SDK and may be absent or
        // restricted by the device's Settings app. Fall back to the accessibility list.
        Intent intent = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(this, ClickAccessibilityService.class).flattenToString());
        try { activity.startActivity(intent); }
        catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    @Override public void openPostPermissionTutorial(Activity activity) {
        new AccessibilityOnboardingState(this).markTutorialLaunched();
        activity.startActivity(new Intent(activity, TutorialReadyActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override public void returnToSettingsAfterAccessibility(Activity activity) {
        new AccessibilityOnboardingState(this).markSettingsReturned();
        activity.startActivity(new Intent(activity, MenuActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }

    @Override public boolean postPermissionTutorialWasLaunched() {
        return new AccessibilityOnboardingState(this).wasTutorialLaunched();
    }
}
