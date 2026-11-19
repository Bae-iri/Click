package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

public final class AuthTokenStore {
    private final SharedPreferences preferences;

    public AuthTokenStore(Context context) {
        try {
            MasterKey key = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
            preferences = EncryptedSharedPreferences.create(context, "click_auth_tokens", key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception error) {
            throw new IllegalStateException("보안 토큰 저장소를 열 수 없습니다.", error);
        }
    }

    public synchronized void save(JSONObject response) {
        long now = System.currentTimeMillis();
        preferences.edit()
                .putString("user_id", response.optString("user_id"))
                .putString("access_token", response.optString("access_token"))
                .putString("refresh_token", response.optString("refresh_token"))
                .putLong("access_expires_at", now + response.optLong("expires_in") * 1000L)
                .putLong("refresh_expires_at", now + response.optLong("refresh_expires_in") * 1000L)
                .apply();
    }

    public DeviceTaskJournal taskJournal(){return new DeviceTaskJournal(preferences,userId(),this::userId);}
    public AppTaskStore appTasks(){return new AppTaskStore(preferences,userId(),this::userId);}
    public void noteGraphConsent(boolean enabled){String user=userId();if(user==null)return;preferences.edit().putBoolean(AppTaskStore.consentKey(user),enabled).apply();if(!enabled)appTasks().retainExperienceConsent(null);}

    public String accessToken() { return preferences.getString("access_token", null); }
    public String refreshToken() { return preferences.getString("refresh_token", null); }
    public String userId() { return preferences.getString("user_id", null); }
    public boolean hasRefreshToken() {
        return refreshToken() != null && preferences.getLong("refresh_expires_at", 0L) > System.currentTimeMillis();
    }
    public boolean accessExpired() {
        return preferences.getLong("access_expires_at", 0L) <= System.currentTimeMillis() + 10_000L;
    }
    public void clear() { preferences.edit().clear().apply(); }
}
