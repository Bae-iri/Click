package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/** The real request owns the course. A page change or local animation cannot complete it. */
public final class LiveTutorialStore {
    private final SharedPreferences prefs;

    public LiveTutorialStore(Context context) {
        String user = new AuthTokenStore(context).userId();
        String account = user == null || user.isEmpty() ? "guest"
                : UUID.nameUUIDFromBytes(user.getBytes(StandardCharsets.UTF_8)).toString();
        prefs = context.getSharedPreferences("live_clue_tutorial_" + account, Context.MODE_PRIVATE);
    }
    public LiveTutorialStore(SharedPreferences prefs) { this.prefs = prefs; }

    public String begin(String request) {
        String id = UUID.randomUUID().toString();
        prefs.edit().clear().putString("launch", id).putString("request_hash", hash(request))
                .putString("phase", "REQUEST").apply();
        return id;
    }
    public boolean accepts(String launch, String request) {
        return launch != null && "REQUEST".equals(phase()) && launch.equals(prefs.getString("launch", null))
                && hash(request).equals(prefs.getString("request_hash", null));
    }
    public boolean bindRequest(String launch, String request, String requestId) {
        if (requestId == null || !accepts(launch, request) || prefs.contains("request")) return false;
        prefs.edit().putString("request", requestId).apply();
        return true;
    }
    public boolean bindSession(String requestId, String sessionId) {
        if (!running() || requestId == null || sessionId == null
                || !requestId.equals(prefs.getString("request", null))) return false;
        prefs.edit().putString("session", sessionId).putString("phase", "LIVE").apply();
        return true;
    }
    public boolean presents(String requestId, String sessionId) {
        if (!running()) return false;
        if (requestId == null && sessionId == null) return "REQUEST".equals(phase());
        return requestId != null && requestId.equals(prefs.getString("request", null))
                && ("REQUEST".equals(phase()) || sessionId != null && sessionId.equals(prefs.getString("session", null)));
    }
    public boolean finish(String requestId, String sessionId, boolean successful) {
        if (!"LIVE".equals(phase()) || requestId == null || sessionId == null
                || !requestId.equals(prefs.getString("request", null)) || !sessionId.equals(prefs.getString("session", null))) return false;
        if (successful) prefs.edit().putString("phase", "HOME").apply();
        else clear();
        return true;
    }
    public boolean running() { return "REQUEST".equals(phase()) || "LIVE".equals(phase()); }
    public boolean awaitingHomeTour() { return "HOME".equals(phase()); }
    public String requestId() { return running() ? prefs.getString("request", null) : null; }
    public void finishHomeTour() { if (awaitingHomeTour()) clear(); }
    public void clear() { prefs.edit().clear().apply(); }
    private String phase() { return prefs.getString("phase", ""); }
    private static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (text == null ? "" : text.trim()).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (byte part : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
