package com.click.data.remote;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ValidationServerContractTest {
    private final String fingerprint = "a".repeat(64);
    private JSONObject environment() throws Exception {
        return new JSONObject().put("schema_version", "click-validation-environment.v1")
                .put("environment_id", "test-env").put("fingerprint", fingerprint);
    }
    @Test public void acceptsPairedEnvironment() throws Exception {
        ValidationServerContract.requireMatch(environment(), "test-env", fingerprint);
    }
    @Test(expected=org.json.JSONException.class) public void rejectsAnotherServer() throws Exception {
        ValidationServerContract.requireMatch(environment(), "other-env", fingerprint);
    }
    @Test(expected=org.json.JSONException.class) public void rejectsOldBackendCodeOrGraphs() throws Exception {
        ValidationServerContract.requireMatch(environment().put("fingerprint", "b".repeat(64)), "test-env", fingerprint);
    }
    @Test(expected=org.json.JSONException.class) public void rejectsMissingIdentity() throws Exception {
        ValidationServerContract.requireMatch(new JSONObject(), "test-env", fingerprint);
    }
    @Test public void preservesServerTraceOnErrors() {
        ApiException error = new ApiException("POST", "/api/v1/agent/commands",
                new HttpResult(503, "{}", null, "server-generated-trace"));
        assertEquals("server-generated-trace", error.traceId);
    }
}
