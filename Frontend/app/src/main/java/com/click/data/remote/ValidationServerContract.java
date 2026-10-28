package com.click.data.remote;

import org.json.JSONException;
import org.json.JSONObject;

/** Configuration identity check for the separate validation APK. Not device test evidence. */
public final class ValidationServerContract {
    private ValidationServerContract() {}

    public static void requireMatch(JSONObject environment, String expectedId, String expectedFingerprint)
            throws JSONException {
        if (expectedId == null || expectedId.isEmpty() || expectedFingerprint == null
                || !expectedFingerprint.matches("[0-9a-f]{64}")
                || !"click-validation-environment.v1".equals(environment.optString("schema_version"))
                || !expectedId.equals(environment.optString("environment_id"))
                || !expectedFingerprint.equals(environment.optString("fingerprint"))) {
            throw new JSONException("VALIDATION_ENVIRONMENT_MISMATCH");
        }
    }
}
