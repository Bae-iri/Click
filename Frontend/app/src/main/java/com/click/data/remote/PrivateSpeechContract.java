package com.click.data.remote;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Refuse audio uploads to older Click servers that still declare external STT. */
public final class PrivateSpeechContract {
    public static final String MODEL = "openai/whisper-large-v3";
    private PrivateSpeechContract() {}

    public static void requireSelfHosted(JSONObject openApi) throws JSONException {
        JSONObject schema = openApi.getJSONObject("paths").getJSONObject("/api/v1/speech/transcriptions")
                .getJSONObject("post").getJSONObject("responses").getJSONObject("200")
                .getJSONObject("content").getJSONObject("application/json").getJSONObject("schema");
        if (schema.has("$ref")) {
            String ref = schema.getString("$ref"), prefix = "#/components/schemas/";
            if (!ref.startsWith(prefix)) throw new JSONException("Unsupported speech schema");
            schema = openApi.getJSONObject("components").getJSONObject("schemas").getJSONObject(ref.substring(prefix.length()));
        }
        JSONObject fields = schema.getJSONObject("properties");
        if (!exact(fields.getJSONObject("provider"), "self_hosted") || !exact(fields.getJSONObject("model"), MODEL))
            throw new JSONException("Private speech server is not ready");
    }

    private static boolean exact(JSONObject schema, String value) {
        JSONArray values = schema.optJSONArray("enum");
        return value.equals(schema.optString("const")) || values != null && values.length() == 1 && value.equals(values.optString(0));
    }
}
