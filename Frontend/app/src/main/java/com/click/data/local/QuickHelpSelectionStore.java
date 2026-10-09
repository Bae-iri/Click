package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;

/** Keeps a card's explicit choice attached to its queue request across service restarts. */
public final class QuickHelpSelectionStore {
    private final SharedPreferences preferences;

    public QuickHelpSelectionStore(Context context) {
        preferences = context.getSharedPreferences("quick_help_selections", Context.MODE_PRIVATE);
    }

    public void save(String requestId, String goalType) {
        preferences.edit().putString(requestId, goalType).apply();
    }

    public String get(String requestId) { return preferences.getString(requestId, null); }
    public void remove(String requestId) { preferences.edit().remove(requestId).apply(); }
    public void clear() { preferences.edit().clear().apply(); }
}
