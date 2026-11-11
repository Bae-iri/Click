package com.click.data.remote;

import android.app.UiAutomation;
import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Six isolated model decisions on the same public launcher, without executing any guide. */
@RunWith(AndroidJUnit4.class)
public class LiveSettingsEntryDiagnosticsTest {
    @Test public void compareFontAndWifiOnTheSameLauncherWithoutActing() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveSettingsEntry")));
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
        SettingsScreenPrivacy privacy;
        try(InputStream v=app.getAssets().open("vision_vocabulary.json");InputStream p=app.getAssets().open("screen_privacy_policy.json")) {
            privacy=SettingsScreenPrivacy.load(v,p);
        }
        AuthenticatedHttpClient http=new AuthenticatedHttpClient(app);
        AgentServerContract contract=AgentServerContract.fromOpenApi(new JSONObject(AuthGateway.get("/openapi.json",null).body));
        JSONObject recorded=new JSONObject(new String(Files.readAllBytes(new File(app.getCacheDir(),"agent-observation-probe.json").toPath()),StandardCharsets.UTF_8))
            .getJSONObject("native").getJSONObject("safe_screen");
        assertEquals("Record a native launcher with AgentObservationProbeTest first", "com.sec.android.app.launcher",recorded.getString("package_name"));
        JSONObject report=new JSONObject().put("source","recorded_filtered_native_launcher").put("physical_actions",0).put("cases",new JSONArray());
        try {
            for(int round=0;round<3;round++)for(String setting:new String[]{"font_size","wifi"}) {
                AccessibilityNodeInfo root=automation.getRootInActiveWindow();assertNotNull(root);
                JSONObject screen=new JSONObject(recorded.toString()).put("captured_at_ms",System.currentTimeMillis())
                    .put("observation_id",UUID.randomUUID().toString());
                try {
                    assertEquals("Keep the same launcher open","com.sec.android.app.launcher",String.valueOf(root.getPackageName()));
                } finally {root.recycle();}
                String session=UUID.randomUUID().toString();JSONObject response=null;
                try {
                    JSONObject slots=new JSONObject().put("setting",setting).put(setting.equals("wifi")?"state":"direction",setting.equals("wifi")?"on":"increase");
                    JSONObject request=new JSONObject().put("contract_version",contract.contractVersion).put("request_id",UUID.randomUUID().toString())
                        .put("session_id",session).put("expected_version",0).put("command_type","start_task")
                        .put("goal",new JSONObject().put("catalog_version","2.3.0").put("target_app","android_settings")
                            .put("goal_type","settings."+setting+(setting.equals("wifi")?".enable":".increase")).put("slots",slots))
                        .put("device_context",new JSONObject().put("platform","android").put("locale","ko-KR"))
                        .put("accessibility_tree",screen);
                    if(contract.interactiveWorkflow)request.put("workflow_version","interactive.v1");
                    response=new JSONObject(http.post(AgentTurnContract.PATH,privacy.filter(request,setting).toString()).body);
                    JSONObject guide=response.getJSONObject("guide_command"),meta=response.optJSONObject("metadata");
                    JSONObject debug=meta==null?null:meta.optJSONObject("debug");
                    JSONObject selection=debug==null?null:debug.optJSONObject("ui_selection");
                    JSONObject item=new JSONObject().put("round",round+1).put("setting",setting).put("state",response.optString("state_id"))
                        .put("action",guide.optString("action_type")).put("target_present",!guide.isNull("target_node_id"))
                        .put("recovery",guide.getJSONObject("render_policy").optJSONObject("recovery"));
                    if(selection!=null) {
                        JSONObject bounded=new JSONObject();
                        for(String key:new String[]{"status","confidence","raw_confidence","calibrated_confidence","selected_probability","candidate_count","grounded_candidate_count","choice"})
                            if(selection.has(key))bounded.put(key,selection.get(key));
                        item.put("selection",bounded);
                    }
                    report.getJSONArray("cases").put(item);
                } finally {
                    if(response!=null)http.post("/api/v1/agent/sessions/"+session+"/cancel",new JSONObject().put("expected_version",response.getInt("session_version")).toString());
                }
            }
        } finally {
            Files.write(new File(app.getCacheDir(),"settings-entry-diagnostics.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
}
