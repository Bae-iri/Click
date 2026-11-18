package com.click.service.accessibility;
import android.app.Instrumentation;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.Gov24LoginPrivacy;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;
/** Reads the current real Government24 screen; never enters data or authenticates. */
@RunWith(AndroidJUnit4.class)
public class Gov24LoginDeviceTest {
    @Test public void observedScreenHonorsThePublicLoginBoundary() throws Exception {
        Instrumentation i=InstrumentationRegistry.getInstrumentation();
        var automation=i.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityNodeInfo root=automation.getRootInActiveWindow();assertNotNull(root);
        try {
            assertEquals(Gov24LoginPrivacy.PACKAGE,root.getPackageName().toString());
            var context=i.getTargetContext();
            var screen=new AccessibilitySnapshotCollector().collect(root,Gov24LoginPrivacy.PACKAGE,context,null);
            JSONObject safe=new Gov24LoginPrivacy().filter(screen);
            String expected=InstrumentationRegistry.getArguments().getString("expected","public");
            assertEquals(safe.toString(),expected,safe.getString("status"));
            if("handoff".equals(expected))assertEquals(0,safe.getJSONArray("nodes").length());
            else assertTrue(safe.getJSONArray("nodes").length()>0);
            Files.write(new File(context.getCacheDir(),"gov24-sanitized-observation.json").toPath(),safe.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }finally{root.recycle();}
    }
}
