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

/** Opt-in read-only metadata probe. Unknown labels and device names never leave the device. */
@RunWith(AndroidJUnit4.class)
public class BluetoothObservationProbeTest {
 @Test public void inspectReviewedLabelsAndShapes() throws Exception {
  org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("inspectBluetooth")));
  var instrumentation=InstrumentationRegistry.getInstrumentation();var app=instrumentation.getTargetContext();
  UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  var info=automation.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;automation.setServiceInfo(info);
  AccessibilityNodeInfo root=null;for(int i=0;i<30 && root==null;i++){root=automation.getRootInActiveWindow();if(root==null)android.os.SystemClock.sleep(200);}assertNotNull(root);
  ScreenSnapshot screen;
  try {var metrics=app.getResources().getDisplayMetrics();screen=new AccessibilitySnapshotCollector().collect(root,String.valueOf(root.getPackageName()),metrics.widthPixels,metrics.heightPixels,null);}
  finally {root.recycle();}
  VisionPrivacy vocabulary;try(var in=app.getAssets().open("vision_vocabulary.json")){vocabulary=VisionPrivacy.load(in);}
  JSONArray shapes=new JSONArray();
  for(var n:screen.nodes) {
   String resource=n.resourceId;
   if(resource!=null && !resource.matches("[a-zA-Z0-9_.]+:id/[a-zA-Z0-9_]+"))resource=null;
   shapes.put(new JSONObject().put("id",n.nodeId).put("parent",n.parentId).put("class",n.className)
    .put("resource",resource).put("role",n.role).put("visible",n.visibleToUser).put("clickable",n.clickable)
    .put("enabled",n.enabled).put("scrollable",n.scrollable).put("checkable",n.checkable)
    .put("safe_text",vocabulary.exportSettingsLabel(n.text)).put("safe_description",vocabulary.exportSettingsLabel(n.contentDescription))
    .put("unknown_text_present",n.text!=null && vocabulary.exportSettingsLabel(n.text)==null)
    .put("unknown_description_present",n.contentDescription!=null && vocabulary.exportSettingsLabel(n.contentDescription)==null));
  }
  JSONObject report=new JSONObject().put("package",screen.packageName).put("nodes",shapes)
    .put("setting_changes",0).put("external_screen_requests",0);
  try(var out=new FileOutputStream(new File(app.getCacheDir(),"bluetooth-shapes-probe.json"))){out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
 }
}
