package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;

/** Coordinates the Settings screen and accessibility service during first-run onboarding. */
public final class AccessibilityOnboardingState {
    private static final String STORE = "click_accessibility_onboarding";
    private static final String PENDING = "pending";
    private static final String TUTORIAL_LAUNCHED = "tutorial_launched";
    private final SharedPreferences preferences;

    public AccessibilityOnboardingState(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public void begin() {
        preferences.edit().putBoolean(PENDING, true).putBoolean(TUTORIAL_LAUNCHED, false).apply();
    }

    public boolean isPending() { return preferences.getBoolean(PENDING, false); }

    public boolean wasTutorialLaunched() {
        return preferences.getBoolean(TUTORIAL_LAUNCHED, false);
    }

    public void markTutorialLaunched() {
        preferences.edit().putBoolean(PENDING, false).putBoolean(TUTORIAL_LAUNCHED, true).apply();
    }
}
