package com.click.service.accessibility;

import android.app.UiAutomation;
import android.content.Context;
import android.media.AudioManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.SettingsControlResources;
import com.click.data.snapshot.SettingsScreenPrivacy;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in read-only probe. Opens Sound settings; never taps a mode or changes its value. */
@RunWith(AndroidJUnit4.class)
public class NativeRingerChoiceProbeTest {
    @Test public void collectPublicNativeSoundChoicesAndIndependentMode() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveProbe")));
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                |android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        try(ParcelFileDescriptor fd=automation.executeShellCommand("am start -W -a android.settings.SOUND_SETTINGS -f 0x10008000");
            InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buffer=new byte[1024];while(input.read(buffer)!=-1) { }
        }
        AudioManager audio=context.getSystemService(AudioManager.class);assertNotNull(audio);
        ScreenSnapshot screen=null;
        for(int i=0;i<30;i++) {
            SystemClock.sleep(200);AccessibilityNodeInfo root=automation.getRootInActiveWindow();
            if(root==null)continue;
            try {
                String pkg=String.valueOf(root.getPackageName());
                if(!("com.android.settings".equals(pkg)||"com.samsung.android.settings".equals(pkg)))continue;
                var metrics=context.getResources().getDisplayMetrics();
                screen=new AccessibilitySnapshotCollector().collect(root,pkg,metrics.widthPixels,metrics.heightPixels,null)
                        .withRingerMode(audio.getRingerMode());
                if(screen.nodes.stream().filter(n->n.visibleToUser&&n.enabled&&SettingsControlResources.soundModeChoice(n.resourceId)).count()>=3)break;
            } finally {root.recycle();}
        }
        assertNotNull("Native Sound page must be readable",screen);
        assertEquals("All three native sound choices must be visible",3,
                screen.nodes.stream().filter(n->n.visibleToUser&&n.enabled&&SettingsControlResources.soundModeChoice(n.resourceId)).count());
        SettingsScreenPrivacy policy;
        try(InputStream vocabulary=context.getAssets().open("vision_vocabulary.json");
            InputStream privacy=context.getAssets().open("screen_privacy_policy.json")) {
            policy=SettingsScreenPrivacy.load(vocabulary,privacy);
        }
        JSONObject safe=policy.filter(new JSONObject().put("accessibility_tree",new JSONObject(screen.toJson())),"mute");
        JSONObject applied=safe.getJSONObject("accessibility_tree").getJSONObject("applied_settings");
        assertEquals(audio.getRingerMode(),applied.getInt("ringer_mode"));
        assertEquals("android.media.AudioManager.getRingerMode",applied.getString("ringer_mode_source"));
        try(OutputStream output=new FileOutputStream(new File(context.getCacheDir(),"native-ringer-choice-probe.json"))) {
            output.write(safe.toString().getBytes(StandardCharsets.UTF_8));
        }
        android.util.Log.i("ClickModeProbe","native_choices=3 system_enum_valid=true settings_unchanged=true");
    }
}
