package com.click.data.snapshot;

import java.util.Set;

/** Native value choices are context for menu navigation, not menu destinations. */
public final class SettingsControlResources {
    private static final Set<String> SOUND_MODES=Set.of("com.android.settings:id/low_button",
            "com.android.settings:id/mid_button","com.android.settings:id/high_button");
    private SettingsControlResources() {}
    public static boolean soundModeChoice(String resourceId) {
        return resourceId!=null && SOUND_MODES.contains(resourceId);
    }
}
