package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.CoupangScreenGuide;
import com.click.data.snapshot.SettingsScreenPrivacy;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.*;

/** Read-only device probe: opens Coupang; never types, grants permissions or purchases. */
@RunWith(AndroidJUnit4.class)
public class CoupangNativeScreenDeviceTest {
    @Test public void readsInstalledCoupangPublicScreen() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var context=instrumentation.getTargetContext();
        var launch=context.getPackageManager().getLaunchIntentForPackage("com.coupang.mobile");
        assumeNotNull(launch);
        UiAutomation automation=instrumentation.getUiAutomation();
        var info=automation.getServiceInfo();
        info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        assumeTrue(android.os.Build.VERSION.SDK_INT>=30);
        var display=context.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
        var collector=new AccessibilitySnapshotCollector();
        android.view.accessibility.AccessibilityNodeInfo root;
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        idle(automation);
        root=automation.getRootInActiveWindow();assertNotNull(root);
        assertEquals("com.coupang.mobile",root.getPackageName().toString());
        var screen=collector.collect(root,root.getPackageName().toString(),display.width(),display.height(),null);
        root.recycle();
        var guide=CoupangScreenGuide.identify(screen);
        SettingsScreenPrivacy privacy;
        try(var vocabulary=context.getAssets().open("vision_vocabulary.json");var policy=context.getAssets().open("screen_privacy_policy.json")) {
            privacy=SettingsScreenPrivacy.load(vocabulary,policy);
        }
        int safe=0;
        try {
            var filtered=privacy.filter(new JSONObject().put("accessibility_tree",new JSONObject(screen.toJson())),"commerce");
            safe=filtered.getJSONObject("accessibility_tree").getJSONArray("nodes").length();
        } catch(SettingsScreenPrivacy.Incomplete unreadable) {assertNotNull("An identified load error needs local guidance",guide);}
        assertTrue("Coupang must have readable public controls or an identified local error",safe>0 || guide!=null);
        report("coupang_nodes="+screen.nodes.size()+" safe_nodes="+safe+" local_guide="+(guide==null?"none":guide.kind)
                +" target_resource="+(guide==null || guide.target==null?"none":guide.target.resourceId));
    }
    private void idle(UiAutomation automation) {
        try {automation.waitForIdle(500,5000);}catch(java.util.concurrent.TimeoutException ignored){}
    }
    private void report(String message) {
        Bundle bundle=new Bundle();bundle.putString("stream",message+"\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0,bundle);
    }
}
