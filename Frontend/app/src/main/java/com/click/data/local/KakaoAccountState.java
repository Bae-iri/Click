package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;

/** Non-identifying entry state. Kakao credentials remain exclusively in the SDK. */
public final class KakaoAccountState {
    private final SharedPreferences preferences;
    public KakaoAccountState(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("click_kakao_entry", Context.MODE_PRIVATE);
    }
    public void unlinkPending(boolean pending) { preferences.edit().putBoolean("unlink_pending", pending).apply(); }
    public boolean unlinkPending() { return preferences.getBoolean("unlink_pending", false); }
}
