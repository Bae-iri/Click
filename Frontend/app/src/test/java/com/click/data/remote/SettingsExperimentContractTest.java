package com.click.data.remote;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsExperimentContractTest {
    private JSONObject schema() throws Exception {
        try (var input=getClass().getResourceAsStream("/openapi.json")) {
            return new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    @Test public void serverAdvertisesExplicitExperimentCapability() throws Exception {
        assertTrue(AgentServerContract.fromOpenApi(schema()).settingsExperiment);
    }
    @Test public void olderServerDoesNotAdvertiseExperimentCapability() throws Exception {
        var old=schema();
        old.getJSONObject("components").getJSONObject("schemas").getJSONObject("DeviceCapabilities")
            .getJSONObject("properties").remove("settings_experiment_arm");
        assertFalse(AgentServerContract.fromOpenApi(old).settingsExperiment);
    }
}
