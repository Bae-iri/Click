package com.click.data.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Fail-closed export policy for the current settings graph; no free-form OCR text. */
public final class VisionPrivacy {
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");
    private final Map<String, Set<String>> groups = new HashMap<>();
    private final Set<String> settingsLabels = new HashSet<>();

    public VisionPrivacy(JSONObject vocabulary) throws JSONException {
        if (vocabulary.getInt("version") != 1) throw new JSONException("Unsupported vocabulary");
        Set<String> common = labels(vocabulary.getJSONArray("common"));
        JSONObject definitions = vocabulary.getJSONObject("groups");
        java.util.Iterator<String> keys = definitions.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Set<String> allowed = labels(definitions.getJSONArray(key));
            allowed.addAll(common);
            settingsLabels.addAll(allowed);
            groups.put(key, Collections.unmodifiableSet(allowed));
        }
    }

    public static VisionPrivacy load(InputStream source) throws IOException, JSONException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = source.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return new VisionPrivacy(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())));
    }

    public boolean supports(String vocabularyKey) {
        return vocabularyKey != null && groups.containsKey(vocabularyKey);
    }

    private static Set<String> labels(JSONArray values) throws JSONException {
        Set<String> result = new HashSet<>();
        for (int i = 0; i < values.length(); i++) result.add(normalize(values.getString(i)));
        return result;
    }

    public String exportLabel(String text, String vocabularyKey) {
        if (text == null) throw new IllegalArgumentException("Missing OCR text");
        String label = normalize(text);
        Set<String> allowed = groups.get(vocabularyKey);
        return allowed != null && allowed.contains(label) ? label : null;
    }

    /** Native AX export uses the finite union; the backend narrows it to the active graph. */
    public String exportSettingsLabel(String text) {
        if (text == null) return null;
        String label = normalize(text);
        return settingsLabels.contains(label) ? label : null;
    }

    private static String normalize(String text) {
        return WHITESPACE.matcher(text.trim().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    public static void requireMaskBounds(int[] bounds, int width, int height) {
        if (bounds == null || bounds.length != 4 || width <= 0 || height <= 0
                || bounds[0] < 0 || bounds[1] < 0 || bounds[0] >= bounds[2] || bounds[1] >= bounds[3]
                || bounds[2] > width || bounds[3] > height) {
            throw new IllegalArgumentException("Cannot safely mask input field");
        }
    }
}
