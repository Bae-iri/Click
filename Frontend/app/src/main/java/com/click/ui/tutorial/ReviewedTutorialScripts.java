package com.click.ui.tutorial;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Build-time, reviewed copy only. No model calls or mutable remote scripts in the tutorial. */
public final class ReviewedTutorialScripts {
    private final Map<String, String> scripts = new HashMap<>();
    public ReviewedTutorialScripts(Context context) {
        try (java.io.InputStream input = context.getAssets().open("tutorial_narration.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            JSONObject root = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (root.getInt("version") != 1) return;
            JSONArray entries = root.getJSONArray("scripts");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String source = entry.getString("source");
                String script = entry.getString("script").trim();
                if (entry.optBoolean("approved") && !entry.optString("reviewed_by").trim().isEmpty()
                        && !script.isEmpty() && script.length() <= 600 && !scripts.containsKey(source))
                    scripts.put(source, script);
            }
        } catch (Exception ignored) { scripts.clear(); }
    }
    public String forSource(String source) { return scripts.getOrDefault(source, source); }
}
