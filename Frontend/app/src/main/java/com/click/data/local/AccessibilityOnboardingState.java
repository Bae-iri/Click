package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists the explicit onboarding, home or app-settings destination until it resumes. */
public final class AccessibilityOnboardingState {
    private static final String STORE = "click_accessibility_onboarding";
    private static final String PENDING = "pending";
    private static final String TUTORIAL_LAUNCHED = "tutorial_launched";
    private static final String RETURN_TO_SETTINGS = "return_to_settings";
    private static final String OPEN_TUTORIAL = "explicit_onboarding";
    private final SharedPreferences preferences;

    public AccessibilityOnboardingState(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public void begin() {
        begin(false);
    }

    public void begin(boolean returnToSettings) {
        if (returnToSettings) beginReview(true);
        else beginOnboarding();
    }

    public void beginOnboarding() {
        preferences.edit().putBoolean(PENDING, true).putBoolean(OPEN_TUTORIAL, true)
                .putBoolean(RETURN_TO_SETTINGS, false).putBoolean(TUTORIAL_LAUNCHED, false).apply();
    }

    public void beginReview(boolean returnToSettings) {
        preferences.edit().putBoolean(PENDING, true).putBoolean(OPEN_TUTORIAL, false)
                .putBoolean(RETURN_TO_SETTINGS, returnToSettings).apply();
    }

    public boolean opensTutorial() { return preferences.getBoolean(OPEN_TUTORIAL, false); }
    public boolean returnsToHome() { return !opensTutorial() && !returnsToSettings(); }

    public void cancelPending() {
        preferences.edit().putBoolean(PENDING, false).putBoolean(OPEN_TUTORIAL, false)
                .putBoolean(RETURN_TO_SETTINGS, false).apply();
    }

    public void markHomeReturned() { cancelPending(); }

    public boolean returnsToSettings() { return preferences.getBoolean(RETURN_TO_SETTINGS, false); }

    public void markSettingsReturned() {
        cancelPending();
    }

    public boolean isPending() { return preferences.getBoolean(PENDING, false); }

    public boolean wasTutorialLaunched() {
        return preferences.getBoolean(TUTORIAL_LAUNCHED, false);
    }

    public void markTutorialLaunched() {
        preferences.edit().putBoolean(PENDING, false).putBoolean(RETURN_TO_SETTINGS, false)
                .putBoolean(OPEN_TUTORIAL, false).putBoolean(TUTORIAL_LAUNCHED, true).apply();
    }
}
