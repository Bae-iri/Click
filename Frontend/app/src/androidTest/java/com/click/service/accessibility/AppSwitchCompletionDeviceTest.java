package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.AppTaskCompletion;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Read-only AOSP Display probe. Run once per theme with -e switchProbe true. */
@RunWith(AndroidJUnit4.class)
public class AppSwitchCompletionDeviceTest {
    @Test public void compareBothRequestsToAppliedTheme() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("switchProbe")));
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        UiAutomation ui=instrumentation.getUiAutomation();
        AccessibilityServiceInfo info=ui.getServiceInfo();
        info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        ui.setServiceInfo(info);
        context.startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for(int frame=0;frame<2;frame++){
            JSONObject observed=null,target=null;
            ScreenSnapshot snapshot=null;
            long deadline=SystemClock.elapsedRealtime()+12000;
            while(target==null&&SystemClock.elapsedRealtime()<deadline){
                SystemClock.sleep(150);
                AccessibilityNodeInfo root=ui.getRootInActiveWindow();
                if(root==null)continue;
                try{
                    if(!"com.android.settings".contentEquals(String.valueOf(root.getPackageName())))continue;
                    snapshot=new AccessibilitySnapshotCollector().collect(root,"com.android.settings",context,null);
                    if(snapshot==null)continue;
                    observed=AppTaskObservation.capture(snapshot);
                    JSONArray nodes=observed.getJSONArray("nodes");
                    for(int i=0;i<nodes.length();i++){
                        JSONObject n=nodes.getJSONObject(i);
                        if("toggle".equals(n.optString("role"))&&"Dark theme".equals(n.optString("label")))target=n;
                    }
                }finally{root.recycle();}
            }
            assertNotNull("Open the English AOSP Display page with Dark theme visible",target);
            int applied=context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            assertTrue(applied==Configuration.UI_MODE_NIGHT_YES||applied==Configuration.UI_MODE_NIGHT_NO);
            boolean night=applied==Configuration.UI_MODE_NIGHT_YES;
            assertEquals(night,target.getBoolean("checked"));
            for(boolean desired:new boolean[]{true,false}){
                JSONObject check=new JSONObject().put("package",snapshot.packageName).put("label",target.getString("label"))
                    .put("resource",target.getString("resource")).put("role","toggle").put("checked",desired);
                JSONObject completion=new JSONObject().put("checks",new JSONArray().put(check));
                String goal="Turn "+(desired?"on":"off")+" Dark theme";
                assertEquals(desired==night?AppTaskCompletion.Verdict.MATCHED:AppTaskCompletion.Verdict.NOT_MET,
                    AppTaskCompletion.checkForGoal(goal,completion,observed,snapshot));
            }
            // Only this tested public switch goes to the optional host replay.
            JSONObject sample=new JSONObject().put("applied",night).put("package",snapshot.packageName).put("switch",target);
            Files.write(new File(context.getCacheDir(),"switch-probe.json").toPath(),sample.toString().getBytes(StandardCharsets.UTF_8));
            if(frame==0)SystemClock.sleep(450);
        }
    }
}
