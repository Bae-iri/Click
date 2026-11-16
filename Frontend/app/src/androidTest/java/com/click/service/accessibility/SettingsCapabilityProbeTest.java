package com.click.service.accessibility;
import android.app.UiAutomation;
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
/** Opt-in read-only test of native menu eligibility; no screenshot or device names. */
@RunWith(AndroidJUnit4.class)
public class SettingsCapabilityProbeTest {
 @Test public void connectionsRowsHaveSafeGoalScopedNavigationTargets() throws Exception {
  org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("verifySettingsCapabilities")));
  var inst=InstrumentationRegistry.getInstrumentation();var app=inst.getTargetContext();
  var automation=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  var info=automation.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;automation.setServiceInfo(info);
  AccessibilityNodeInfo root=null;for(int i=0;i<30&&root==null;i++){root=automation.getRootInActiveWindow();if(root==null)android.os.SystemClock.sleep(200);}assertNotNull(root);
  ScreenSnapshot screen;try{var m=app.getResources().getDisplayMetrics();screen=new AccessibilitySnapshotCollector().collect(root,String.valueOf(root.getPackageName()),m.widthPixels,m.heightPixels,null);}finally{root.recycle();}
  assertEquals("com.android.settings",screen.packageName);
  JSONArray targets=new JSONArray();
  for(String[] expected:new String[][]{{"블루투스","settings.bluetooth.enable"},{"Wi-Fi","settings.wifi.enable"}}) {
   AccessibilityNodeSnapshot label=null;for(var n:screen.nodes)if(n.visibleToUser&&expected[0].equalsIgnoreCase(n.text)){label=n;break;}assertNotNull(expected[0],label);
   var row=label;for(int depth=0;depth<8&&!row.clickable;depth++){AccessibilityNodeSnapshot parent=null;for(var n:screen.nodes)if(n.nodeId.equals(row.parentId)){parent=n;break;}assertNotNull(parent);row=parent;}
   var safe=TouchAutomationPolicy.clickableTarget(screen,row.nodeId,row.bounds,expected[1],"S_CATEGORY");assertNotNull(expected[0],safe);
   assertFalse(safe.checkable);
   targets.put(new JSONObject().put("menu",expected[0]).put("accepted",true).put("title_only",!safe.nodeId.equals(row.nodeId)).put("target_is_value_control",safe.checkable));
  }
  var report=new JSONObject().put("contract_sha256",SettingsCapabilities.sha256()).put("targets",targets).put("setting_changes",0).put("external_requests",0);
  try(var out=new FileOutputStream(new File(app.getCacheDir(),"settings-capability-probe.json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
 }
}
