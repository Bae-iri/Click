package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.json.JSONObject;

public final class AuthTokenStore {
    /** One credential transaction across every gateway/store instance in this process. */
    public static final Object SESSION_LOCK = new Object();
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

    AuthTokenStore(SharedPreferences preferences) { this.preferences = preferences; }

    public void save(JSONObject response) { save(response, null); }

    public void saveAuthentication(JSONObject response, String provider) { save(response, provider); }

    private void save(JSONObject response, String provider) {
        // A partial success response must never replace a usable login with empty credentials.
        String user = response.optString("user_id", "");
        String access = response.optString("access_token", "");
        String refresh = response.optString("refresh_token", "");
        long accessSeconds = response.optLong("expires_in", 0);
        long refreshSeconds = response.optLong("refresh_expires_in", 0);
        if (user.trim().isEmpty() || access.length() < 40 || access.length() > 128
                || refresh.length() < 40 || refresh.length() > 128
                || accessSeconds <= 0 || accessSeconds > 86400
                || refreshSeconds <= 0 || refreshSeconds > 366L * 86400)
            throw new IllegalArgumentException("Invalid authentication response");
        synchronized (SESSION_LOCK) {
            long now = System.currentTimeMillis();
            SharedPreferences.Editor editor = preferences.edit()
                    .putString("user_id", user).putString("access_token", access)
                    .putString("refresh_token", refresh)
                    .putLong("access_expires_at", now + accessSeconds * 1000L)
                    .putLong("refresh_expires_at", now + refreshSeconds * 1000L);
            if (provider != null) {
                editor.putString("auth_provider", provider)
                        .putString("auth_generation", java.util.UUID.randomUUID().toString());
            }
            if (!editor.commit()) throw new IllegalStateException("로그인 정보를 저장하지 못했어요.");
        }
    }

    public String authenticationProvider() { return preferences.getString("auth_provider", "unknown"); }
    public String sessionGeneration() { return preferences.getString("auth_generation", null); }

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
    public void clear() { synchronized (SESSION_LOCK) { preferences.edit().clear().commit(); } }
}
