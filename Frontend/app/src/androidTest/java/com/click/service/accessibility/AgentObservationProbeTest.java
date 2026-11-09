package com.click.service.accessibility;

import android.app.*;
import android.content.Context;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Native observation probe; optional restart restores only an existing service grant.
 * Never writes screen strings, SSIDs, credentials or provider payloads. */
@RunWith(AndroidJUnit4.class)
public class AgentObservationProbeTest {
    private JSONObject assess(ScreenSnapshot screen, SettingsScreenPrivacy policy) throws Exception {
        JSONObject result=new JSONObject().put("available",screen!=null);
        if(screen==null)return result;
        int inputs=0,passwords=0,mixed=0,switches=0,invisible=0;
        JSONArray inputShapes=new JSONArray();
        JSONObject tree=new JSONObject(screen.toJson());
        for(int i=0;i<tree.getJSONArray("nodes").length();i++) {
            JSONObject n=tree.getJSONArray("nodes").getJSONObject(i);
            if(!n.optBoolean("visible_to_user")){invisible++;continue;}
            if(!screen.packageName.equals(n.optString("package_name")))mixed++;
            if(n.optBoolean("checkable"))switches++;
            JSONObject extras=n.optJSONObject("extras");
            if("text_field".equals(n.optString("role")) || extras!=null && extras.optBoolean("editable")) {
                inputs++;
                if(extras!=null && extras.optBoolean("is_password"))passwords++;
                inputShapes.put(new JSONObject().put("resource_id",n.optString("resource_id"))
                    .put("class_name",n.optString("class_name")).put("content_present",extras!=null && extras.optBoolean("input_content_present")));
            }
        }
        result.put("package",screen.packageName).put("mask_ready",screen.privacyMaskingReady)
            .put("nodes",screen.nodes.size()).put("inputs",inputs).put("passwords",passwords)
            .put("mixed_packages",mixed).put("switches",switches).put("invisible",invisible).put("input_shapes",inputShapes);
        try {
            JSONObject safe=policy.filter(new JSONObject().put("accessibility_tree",tree),
                    InstrumentationRegistry.getArguments().getString("observationSetting"));
            JSONArray exported=safe.getJSONObject("accessibility_tree").getJSONArray("nodes");int checkedControls=0;
            for(int i=0;i<exported.length();i++)if(exported.getJSONObject(i).optBoolean("checkable"))checkedControls++;
            result.put("privacy","allowed").put("exported_nodes",exported.length()).put("exported_checked_controls",checkedControls);
            // This is the existing HTTP boundary's filtered tree, never a raw dump.
            result.put("safe_screen",safe.getJSONObject("accessibility_tree"));
        } catch(SettingsScreenPrivacy.Blocked blocked) {result.put("privacy",blocked.reason.name());}
        catch(SettingsScreenPrivacy.Incomplete incomplete) {result.put("privacy","INCOMPLETE");}
        return result;
    }
    @Test public void compareServiceAndNativeObservationWithoutChangingSettings() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();Context app=instrumentation.getTargetContext();
        UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
        SettingsScreenPrivacy policy;
        try(InputStream v=app.getAssets().open("vision_vocabulary.json");InputStream p=app.getAssets().open("screen_privacy_policy.json")){policy=SettingsScreenPrivacy.load(v,p);}
        JSONObject report=new JSONObject().put("locked",app.getSystemService(KeyguardManager.class).isDeviceLocked());
        try {
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(
                    com.click.BuildConfig.CLICK_API_BASE_URL + "/version").openConnection();
            connection.setConnectTimeout(3000); connection.setReadTimeout(3000);
            JSONObject build;
            try (InputStream in = connection.getInputStream()) {
                build = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } finally { connection.disconnect(); }
            report.put("server_observation_contract", build.optJSONObject("observation_contract"));
            report.put("server_source_sha256", build.optString("source_sha256"));
        } catch (Exception unavailable) { report.put("server_version_status", unavailable.getClass().getSimpleName()); }
        JSONObject assetHashes = new JSONObject();
        for (String file : new String[]{"screen_privacy_policy.json", "vision_vocabulary.json"}) {
            try (InputStream in = app.getAssets().open(file)) {
                byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
                StringBuilder hash = new StringBuilder();
                for(byte value:digest)hash.append(String.format(java.util.Locale.ROOT,"%02x",value & 255));
                assetHashes.put(file,hash.toString());
            }
        }
        report.put("installed_policy_hashes", assetHashes);
        if ("true".equals(InstrumentationRegistry.getArguments().getString("restartExistingAccessibility"))) {
            String original=android.provider.Settings.Secure.getString(app.getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            android.content.ComponentName component=new android.content.ComponentName(app,ClickAccessibilityService.class);
            java.util.List<String> others=new java.util.ArrayList<>();boolean enabled=false;
            if(original!=null)for(String entry:original.split(":")) {
                if(component.equals(android.content.ComponentName.unflattenFromString(entry)))enabled=true;
                else if(!entry.isBlank())others.add(entry);
            }
            // Restart only a service the user already enabled. Never enable a
            // new service, change ACCESSIBILITY_ENABLED or affect other services.
            if(enabled) {
                automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
                try {
                    android.provider.Settings.Secure.putString(app.getContentResolver(),
                            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",others));
                    SystemClock.sleep(200);
                } finally {
                    android.provider.Settings.Secure.putString(app.getContentResolver(),
                            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,original);
                    automation.dropShellPermissionIdentity();
                }
                report.put("restarted_existing_service",true);
            }
        }
        ScreenSnapshot service=null;
        for(int attempt=0;attempt<30 && service==null;attempt++) {
            service=ClickAccessibilityService.freshSnapshot();
            if(service==null)SystemClock.sleep(200);
        }
        report.put("service",assess(service,policy));
        AccessibilityNodeInfo root=automation.getRootInActiveWindow();
        if(root!=null)try {
            android.util.DisplayMetrics metrics=app.getResources().getDisplayMetrics();
            ScreenSnapshot nativeScreen=new AccessibilitySnapshotCollector().collect(root,String.valueOf(root.getPackageName()),metrics.widthPixels,metrics.heightPixels,null)
                .withWifiState(app.getSystemService(android.net.wifi.WifiManager.class).getWifiState())
                .withNightMode(app.getSystemService(android.app.UiModeManager.class).getNightMode());
            report.put("native",assess(nativeScreen,policy));
        } finally {root.recycle();}
        try(FileOutputStream output=new FileOutputStream(new File(app.getCacheDir(),"agent-observation-probe.json"))){output.write(report.toString().getBytes(StandardCharsets.UTF_8));}
        assertTrue("Probe report saved",report.has("service"));
        int expected=0;
        String expectedValue=InstrumentationRegistry.getArguments().getString("expectedPublicSwitches");
        if(expectedValue!=null)expected=Integer.parseInt(expectedValue);
        if(expected>0) {
            assertEquals("Native privacy filter must retain the public controls",expected,
                    report.getJSONObject("native").getInt("exported_checked_controls"));
            if(report.getJSONObject("service").optBoolean("available"))assertEquals(
                    "Service HTTP boundary must retain the public controls",expected,
                    report.getJSONObject("service").getInt("exported_checked_controls"));
        }
    }
}
