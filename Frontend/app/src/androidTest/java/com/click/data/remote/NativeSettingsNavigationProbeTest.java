package com.click.data.remote;

import android.app.UiAutomation;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.local.TouchAutomationSettings;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.accessibility.TouchAutomationPolicy;
import com.click.service.overlay.ClickOverlayService;
import java.io.File;
import java.nio.file.Files;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Separates Samsung's native ACTION_CLICK from product recovery, without choosing a tone. */
public class NativeSettingsNavigationProbeTest {
    @Test public void ringtoneNativeClickAndPhysicalControl() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("auditNativeNavigationProbe")));
        var inst=InstrumentationRegistry.getInstrumentation();var app=inst.getTargetContext();
        assertTrue(app.getPackageName().endsWith(".deviceaudit"));
        UiAutomation ui=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info=ui.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ui.setServiceInfo(info);app.stopService(new Intent(app,ClickOverlayService.class));
        var touch=new TouchAutomationSettings(app);boolean previous=touch.isEnabled();touch.setEnabled(true);
        JSONObject out=new JSONObject().put("purpose","component_diagnosis_not_product_success_rate");
        JSONArray title=new JSONArray().put("벨소리");
        try {
            shell(ui,"am start -W -a android.settings.SOUND_SETTINGS -f 0x10008000");SystemClock.sleep(800);
            var before=ClickAccessibilityService.freshSnapshot();assertNotNull(before);
            AccessibilityNodeSnapshot label=null,target=null;
            for(var node:before.nodes)if(node.visibleToUser&&"벨소리".equals(node.text))label=node;
            assertNotNull("PUBLIC_RINGTONE_ROW_UNAVAILABLE",label);
            for(var node:before.nodes)if((label.nodeId.equals(node.nodeId)||label.nodeId.startsWith(node.nodeId+"_"))
                    &&TouchAutomationPolicy.clickableTarget(before,node.nodeId,node.bounds,"settings.navigation.ringtone","S2_CATEGORY")!=null){
                if(target==null||node.nodeId.length()>target.nodeId.length())target=node;
            }
            assertNotNull("SAFE_NATIVE_RINGTONE_TARGET_UNAVAILABLE",target);
            boolean dispatched=ClickAccessibilityService.performGuidedNavigationTap(before,target.nodeId,target.bounds,"settings.navigation.ringtone","S2_CATEGORY");
            out.put("native_click_dispatched",dispatched);
            JudgmentEvidence.Proof proof=null;long deadline=SystemClock.elapsedRealtime()+2000;
            while(SystemClock.elapsedRealtime()<deadline&&proof==null){proof=JudgmentEvidence.settingsHeading(ui,title,before.screenHeight);SystemClock.sleep(60);}
            out.put("native_click_goal_reached",proof!=null);
            if(proof==null){
                var fresh=ClickAccessibilityService.freshSnapshot();
                boolean stable=TouchAutomationPolicy.sameTarget(before,fresh,target.nodeId,target.bounds);
                out.put("same_native_target_before_physical_control",stable);
                if(stable){
                    int x=(label.bounds[0]+label.bounds[2])/2,y=(label.bounds[1]+label.bounds[3])/2;
                    shell(ui,"input tap "+x+" "+y);SystemClock.sleep(500);
                    proof=JudgmentEvidence.settingsHeading(ui,title,before.screenHeight);
                    out.put("physical_control_goal_reached",proof!=null);
                }
            }
            if(proof!=null){
                out.put("evidence_first",JudgmentEvidence.capture(ui,app.getCacheDir(),proof));SystemClock.sleep(1100);
                var last=JudgmentEvidence.settingsHeading(ui,title,before.screenHeight);
                out.put("public_title_stable",last!=null);
                if(last!=null)out.put("evidence_last",JudgmentEvidence.capture(ui,app.getCacheDir(),last));
            }
        }finally{
            touch.setEnabled(previous);
            Files.write(new File(app.getCacheDir(),"native-settings-navigation-probe.json").toPath(),out.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private void shell(UiAutomation ui,String command)throws Exception{
        try(var fd=ui.executeShellCommand(command);var in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){in.readAllBytes();}
    }
}
