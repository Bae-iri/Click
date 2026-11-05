package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** An explicitly empty collection stays empty after the app is reopened. */
public final class HomeFavoritesStore {
    private static final String KEY = "selected_ids";
    private final SharedPreferences preferences;

    public HomeFavoritesStore(Context context) {
        preferences = context.getSharedPreferences("home_favorites", Context.MODE_PRIVATE);
        if (!preferences.getBoolean("accessibility_added_v1", false)) {
            Set<String> ids = selectedIds();
            ids.add("accessibility");
            preferences.edit().putStringSet(KEY, ids).putBoolean("accessibility_added_v1", true).apply();
        }
    }

    public Set<String> selectedIds() {
        Set<String> defaults = new HashSet<>(Arrays.asList(
                "font_size", "brightness", "media_volume", "phone", "tutorial", "accessibility"));
        return new HashSet<>(preferences.getStringSet(KEY, defaults));
    }

    public boolean contains(String id) { return selectedIds().contains(id); }

    public void setSelected(String id, boolean selected) {
        Set<String> ids = selectedIds();
        if (selected) ids.add(id); else ids.remove(id);
        preferences.edit().putStringSet(KEY, ids).apply();
    }
}
