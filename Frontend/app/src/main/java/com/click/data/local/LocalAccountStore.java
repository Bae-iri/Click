package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;

public final class LocalAccountStore {
    private static final String STORE = "click_local_account";
    private final SharedPreferences preferences;

    public LocalAccountStore(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        preferences.edit().remove("age").remove("job").remove("gender").apply();
    }

    public void saveProfile(String name, String email) {
        preferences.edit()
                .putString("name", name == null ? "" : name)
                .putString("email", email)
                .putBoolean("signed_up", true)
                .apply();
    }

    public String name() {
        return preferences.getString("name", "");
    }

    public String email() {
        return preferences.getString("email", "");
    }

    public boolean hasAccount() {
        return preferences.getBoolean("signed_up", false);
    }

    public void deleteAccount() {
        preferences.edit().clear().apply();
    }
}
