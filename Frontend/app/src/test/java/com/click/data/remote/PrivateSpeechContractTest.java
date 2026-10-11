package com.click.data.remote;

import org.json.JSONObject;
import org.junit.Test;

public class PrivateSpeechContractTest {
    private JSONObject contract(String provider, String model) throws Exception {
        JSONObject fields = new JSONObject().put("provider", new JSONObject().put("const", provider))
                .put("model", new JSONObject().put("const", model));
        JSONObject schema = new JSONObject().put("properties", fields);
        JSONObject media = new JSONObject().put("application/json", new JSONObject().put("schema", schema));
        JSONObject response = new JSONObject().put("200", new JSONObject().put("content", media));
        JSONObject operation = new JSONObject().put("post", new JSONObject().put("responses", response));
        return new JSONObject().put("paths", new JSONObject().put("/api/v1/speech/transcriptions", operation));
    }
    @Test public void acceptsPrivateQwenWorkerContract() throws Exception {
        PrivateSpeechContract.requireSelfHosted(contract("self_hosted", PrivateSpeechContract.MODEL));
    }
    @Test(expected = org.json.JSONException.class) public void refusesOldExternalProviderBeforeAudioUpload() throws Exception {
        PrivateSpeechContract.requireSelfHosted(contract("openai", "gpt-transcribe"));
    }
    @Test(expected = org.json.JSONException.class) public void refusesUnverifiableServer() throws Exception {
        PrivateSpeechContract.requireSelfHosted(new JSONObject());
    }
    @Test(expected = org.json.JSONException.class) public void refusesDifferentModelContract() throws Exception {
        PrivateSpeechContract.requireSelfHosted(contract("self_hosted", "unknown"));
    }
}
