package com.click.service.overlay;

import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONObject;

/** Supported reversible slider tasks share approval, execution and result UI. */
public enum SettingsAdjustment {
    FONT_SIZE("font_size", "글자 크기"),
    BRIGHTNESS("brightness", "화면 밝기"),
    MEDIA_VOLUME("volume", "미디어 음량");

    public final String setting, label;
    SettingsAdjustment(String setting, String label) { this.setting = setting; this.label = label; }

    public static SettingsAdjustment forGoal(String goalType) {
        for (SettingsAdjustment task : values())
            if (("settings." + task.setting + ".increase").equals(goalType)
                    || ("settings." + task.setting + ".decrease").equals(goalType)) return task;
        return null;
    }

    public static SettingsAdjustment from(JSONObject goal) {
        if (goal == null || !"android_settings".equals(goal.optString("target_app"))) return null;
        JSONObject slots = goal.optJSONObject("slots");
        if (slots == null || !("increase".equals(slots.optString("direction"))
                || "decrease".equals(slots.optString("direction")))) return null;
        for (SettingsAdjustment task : values()) {
            if (task.setting.equals(slots.optString("setting"))
                    && (!goal.has("goal_type") || task == forGoal(goal.optString("goal_type")))) return task;
        }
        return null;
    }

    public static boolean matches(JSONObject accepted, JSONObject reported) {
        SettingsAdjustment task = from(accepted);
        return task != null && task == from(reported)
                && accepted.optJSONObject("slots").optString("direction")
                    .equals(reported.optJSONObject("slots").optString("direction"));
    }

    /** Persisted device values, never just the slider preview. */
    public Float value(ScreenSnapshot screen) {
        if (screen == null || !screen.privacyMaskingReady) return null;
        if (this == FONT_SIZE) {
            Float value = screen.appliedFontScale;
            return value != null && Float.isFinite(value) && value > 0 && value <= 10 ? value : null;
        }
        Integer value = this == BRIGHTNESS ? screen.appliedBrightness : screen.appliedMediaVolume;
        return value != null && value >= 0 && value <= (this == BRIGHTNESS ? 65535 : 1000)
                ? value.floatValue() : null;
    }
}
