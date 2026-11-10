package com.click.service.accessibility;

import android.app.UiAutomation;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.SettingsScreenPrivacy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in read-only Samsung probe. Opens Screen zoom and never moves its slider. */
@RunWith(AndroidJUnit4.class)
public class NativeDisplaySizeProbeTest {
    @Test public void publicNativeZoomSliderAndIndependentDensityAreReadable() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveProbe")));
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var context=instrumentation.getTargetContext();
        int original=context.getResources().getConfiguration().densityDpi;
        UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
        try(var fd=automation.executeShellCommand("am start -W -a com.samsung.settings.ScreenSize -p com.android.settings -f 0x10008000");
            var input=new ParcelFileDescriptor.AutoCloseInputStream(fd)) {input.readAllBytes();}
        ScreenSnapshot screen=null;
        for(int i=0;i<30;i++) {
            SystemClock.sleep(200);AccessibilityNodeInfo root=automation.getRootInActiveWindow();
            if(root==null)continue;
            try {
                String pkg=String.valueOf(root.getPackageName());if(!"com.android.settings".equals(pkg))continue;
                var metrics=context.getResources().getDisplayMetrics();
                screen=new AccessibilitySnapshotCollector().collect(root,pkg,metrics.widthPixels,metrics.heightPixels,null)
                    .withDisplayDensity(context.getResources().getConfiguration().densityDpi);
                if(screen.nodes.stream().anyMatch(n->"slider".equals(n.role) && n.visibleToUser && n.enabled))break;
            } finally {root.recycle();}
        }
        assertNotNull("Native screen zoom must be readable",screen);
        // Samsung exposes virtual tick nodes; the server must ground one logical control.
        assertTrue("A native zoom range must be readable",screen.nodes.stream()
            .anyMatch(n->"slider".equals(n.role) && n.visibleToUser && n.enabled));
        SettingsScreenPrivacy policy;
        try(var v=context.getAssets().open("vision_vocabulary.json");var p=context.getAssets().open("screen_privacy_policy.json")) {policy=SettingsScreenPrivacy.load(v,p);}
        JSONObject safe=policy.filter(new JSONObject().put("accessibility_tree",new JSONObject(screen.toJson())),"display_size");
        assertEquals(original,safe.getJSONObject("accessibility_tree").getJSONObject("applied_settings").getInt("display_density_dpi"));
        assertEquals(original,context.getResources().getConfiguration().densityDpi);
        try(var output=new FileOutputStream(new File(context.getCacheDir(),"native-display-size-probe.json"))) {output.write(safe.toString().getBytes(StandardCharsets.UTF_8));}
    }
}
