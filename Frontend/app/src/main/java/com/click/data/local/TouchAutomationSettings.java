package com.click.data.local;
import android.content.Context;
import android.content.SharedPreferences;
public final class TouchAutomationSettings {
 private final SharedPreferences preferences;
 public TouchAutomationSettings(Context context) { preferences = context.getSharedPreferences("touch_automation", Context.MODE_PRIVATE); }
 public boolean isEnabled() { return preferences.getBoolean("enabled", false); }
 public void setEnabled(boolean enabled) { preferences.edit().putBoolean("enabled", enabled).apply(); }
}
