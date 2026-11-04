package com.click.designpreview;

import android.app.Activity;

/** Account and device integration owned by the single installed Click application. */
public interface ClickHost {
    interface Callback { void complete(boolean success, boolean duplicateAccount, String message); }
    interface PasswordRulesCallback {
        void complete(int minimum, int maximum);
        void failed();
    }
    void loadRegistrationRules(Activity activity, PasswordRulesCallback callback);
    boolean signedIn();
    boolean accessibilityEnabled();
    void register(Activity activity, String name, String phone, String password, Callback callback);
    void login(Activity activity, String phone, String password, Callback callback);
    void loginWithKakao(Activity activity, Callback callback);
    void openHome(Activity activity);
    void openAccessibilityGuide(Activity activity);
    void openAccessibilitySettings(Activity activity);
    void openPostPermissionTutorial(Activity activity);
    boolean postPermissionTutorialWasLaunched();

    static ClickHost from(Activity activity) { return (ClickHost) activity.getApplication(); }
}
