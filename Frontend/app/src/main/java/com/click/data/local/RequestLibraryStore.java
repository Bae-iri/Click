package com.click.data.local;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/** Recent requests on this device, isolated by account; saved requests survive recent-list trimming. */
public final class RequestLibraryStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;
    public static final class Entry {
        public final String id, request, goalType;
        public final long timestamp;
        public final boolean favorite;
        Entry(JSONObject value) {
            id = value.optString("id"); request = value.optString("request");
            goalType = value.optString("goal_type"); timestamp = value.optLong("timestamp");
            favorite = value.optBoolean("favorite");
        }
    }
    public RequestLibraryStore(Context context) {
        String user = new AuthTokenStore(context).userId();
        String scope = user == null || user.isEmpty() ? "guest" : UUID.nameUUIDFromBytes(
                user.getBytes(StandardCharsets.UTF_8)).toString();
        preferences = context.getSharedPreferences("request_library_" + scope, Context.MODE_PRIVATE);
    }
    public List<Entry> entries(boolean savedOnly) {
        synchronized (LOCK) {
            List<Entry> result = new ArrayList<>();
            JSONArray values = read();
            for (int i = 0; i < values.length(); i++) {
                Entry entry = new Entry(values.optJSONObject(i));
                if (savedOnly ? entry.favorite : !values.optJSONObject(i).optBoolean("recent_hidden")
                        && result.size() < 50) result.add(entry);
            }
            return result;
        }
    }
    public void record(String request, String goalType) {
        if (request == null || request.trim().isEmpty()) return;
        synchronized (LOCK) {
            String text = request.trim();
            String id = UUID.nameUUIDFromBytes(text.getBytes(StandardCharsets.UTF_8)).toString();
            JSONArray old = read(), next = new JSONArray();
            JSONObject current = new JSONObject();
            try {
                for (int i = 0; i < old.length(); i++)
                    if (id.equals(old.getJSONObject(i).optString("id"))) current = old.getJSONObject(i);
                current.put("id", id).put("request", text).put("timestamp", System.currentTimeMillis())
                        .put("recent_hidden", false);
                if (goalType != null) current.put("goal_type", goalType);
                next.put(current);
                for (int i = 0; i < old.length(); i++) {
                    JSONObject value = old.getJSONObject(i);
                    if (!id.equals(value.optString("id")) && (next.length() < 50 || value.optBoolean("favorite"))) next.put(value);
                }
                preferences.edit().putString("entries", next.toString()).apply();
            } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }
    }
    public void setFavorite(String id, boolean favorite) {
        synchronized (LOCK) {
            JSONArray values = read();
            try {
                for (int i = 0; i < values.length(); i++)
                    if (id.equals(values.getJSONObject(i).optString("id"))) values.getJSONObject(i).put("favorite", favorite);
                preferences.edit().putString("entries", values.toString()).apply();
            } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }
    }
    /** Display order is independent of request timestamps and recent-history retention. */
    public List<String> orderedIds(List<String> available, boolean recent) {
        synchronized (LOCK) {
            java.util.LinkedHashSet<String> remaining = new java.util.LinkedHashSet<>(available);
            List<String> ordered = new ArrayList<>();
            try {
                JSONArray saved = new JSONArray(preferences.getString(recent ? "recent_order" : "favorite_order", "[]"));
                for (int i = 0; i < saved.length(); i++) {
                    String id = saved.optString(i);
                    if (remaining.remove(id)) ordered.add(id);
                }
            } catch (org.json.JSONException ignored) { }
            ordered.addAll(remaining);
            return ordered;
        }
    }

    public void moveToTop(String id, List<String> available, boolean recent) {
        moveToPosition(id, 0, available, recent);
    }

    public void moveToPosition(String id, int index, List<String> available, boolean recent) {
        synchronized (LOCK) {
            List<String> ordered = orderedIds(available, recent);
            if (!ordered.remove(id)) return;
            ordered.add(Math.max(0, Math.min(index, ordered.size())), id);
            preferences.edit().putString(recent ? "recent_order" : "favorite_order",
                    new JSONArray(ordered).toString()).apply();
        }
    }

    /** Removing history keeps a separately saved favorite available. */
    public void removeRecent(String id) {
        synchronized (LOCK) {
            JSONArray values = read(), kept = new JSONArray();
            try {
                for (int i = 0; i < values.length(); i++) {
                    JSONObject value = values.getJSONObject(i);
                    if (id.equals(value.optString("id"))) {
                        if (!value.optBoolean("favorite")) continue;
                        value.put("recent_hidden", true);
                    }
                    kept.put(value);
                }
                JSONArray order = new JSONArray(preferences.getString("recent_order", "[]"));
                JSONArray remaining = new JSONArray();
                for (int i = 0; i < order.length(); i++)
                    if (!("request:" + id).equals(order.optString(i))) remaining.put(order.optString(i));
                preferences.edit().putString("entries", kept.toString())
                        .putString("recent_order", remaining.toString()).apply();
            } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        }
    }
    private JSONArray read() {
        try { return new JSONArray(preferences.getString("entries", "[]")); }
        catch (org.json.JSONException error) { return new JSONArray(); }
    }
}
