package com.click.data.local;

import com.click.BuildConfig;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONObject;

/** Device-local, closed diagnostics. Never contains UI text, input, images or node identities. */
public final class SettingsExperimentDiagnostics {
    private SettingsExperimentDiagnostics() {}
    public static void record(String stage,String status,String reason,ScreenSnapshot screen,boolean policyLoaded) {
        if(BuildConfig.SETTINGS_EXPERIMENT_ARM.isEmpty())return;
        try {
            String scope=screen==null?"missing":java.util.Set.of("com.android.settings","com.samsung.android.settings")
                .contains(screen.packageName)?"settings":"other";
            JSONObject entry=new JSONObject().put("event","device_preflight")
                .put("arm",BuildConfig.SETTINGS_EXPERIMENT_ARM).put("stage",stage).put("status",status)
                .put("reason_code",reason).put("screen_scope",scope).put("privacy_policy_loaded",policyLoaded)
                .put("node_count",screen==null?0:screen.nodes.size()).put("at_ms",System.currentTimeMillis());
            android.util.Log.i("ClickExperiment",entry.toString());
        }catch(Exception ignored){}
    }
}
