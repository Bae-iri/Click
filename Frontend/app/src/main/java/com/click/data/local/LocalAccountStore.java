package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;

public final class LocalAccountStore {
    private static final String STORE = "click_local_account";
    private final SharedPreferences preferences;

    public LocalAccountStore(Context context) {
        preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    public void saveProfile(String email, String age, String job, String gender) {
        saveProfile(name(), email, age, job, gender);
    }

    public void saveProfile(String name, String email, String age, String job, String gender) {
        preferences.edit()
                .putString("name", name == null ? "" : name)
                .putString("email", email)
                .putString("age", age)
                .putString("job", job)
                .putString("gender", gender)
                .putBoolean("signed_up", true)
                .apply();
    }

    public String name() {
        return preferences.getString("name", "");
    }

    public String email() {
        return preferences.getString("email", "");
    }

    public String age() {
        return preferences.getString("age", "");
    }

    public String job() {
        return preferences.getString("job", "");
    }

    public String gender() {
        return preferences.getString("gender", "");
    }

    public boolean hasAccount() {
        return preferences.getBoolean("signed_up", false);
    }

    public void deleteAccount() {
        preferences.edit().clear().apply();
    }
}
