package com.click.service.accessibility;

import android.app.UiAutomation;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.*;
import java.nio.file.Files;
import java.io.File;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Only collection metadata and reviewed Settings-label match flags; no app-label dump. */
@RunWith(AndroidJUnit4.class)
@org.junit.FixMethodOrder(org.junit.runners.MethodSorters.NAME_ASCENDING)
public class LauncherNavigationProbeTest {
 @Test public void readNavigationMetadataWithoutScrolling() throws Exception {
  org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("readLauncherNavigation")));
  var instrumentation=InstrumentationRegistry.getInstrumentation();
  UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  var info=automation.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;automation.setServiceInfo(info);
  AccessibilityNodeInfo root=automation.getRootInActiveWindow();assertNotNull(root);
  JSONObject report=new JSONObject().put("package",String.valueOf(root.getPackageName())).put("physical_scrolls",0);
  try {
   NavigationState state=NativeNavigationReader.read(root);
   if(state!=null) {
    report.put("axis",state.axis.name()).put("container_id",state.containerId).put("first",state.firstIndex)
      .put("last",state.lastIndex).put("count",state.itemCount).put("at_start",state.atStart()).put("at_end",state.atEnd());
    AccessibilityNodeInfo selected=NativeNavigationReader.nodeAt(root,state.containerId);
    if(selected!=null)try {
     report.put("container_class",String.valueOf(selected.getClassName())).put("container_resource",selected.getViewIdResourceName());
     JSONArray actions=new JSONArray();for(var action:selected.getActionList())actions.put(action.getId());report.put("action_ids",actions);
     var collection=selected.getCollectionInfo();if(collection!=null)report.put("rows",collection.getRowCount()).put("columns",collection.getColumnCount());
    }finally{if(selected!=root)selected.recycle();}
   }
   ScreenSnapshot screen=ClickAccessibilityService.freshSnapshot();report.put("service_available",screen!=null);
   if(screen!=null) {
    JSONArray matches=new JSONArray();
    for(var node:screen.nodes)if(TouchAutomationPolicy.isSettingsAppLabel(node.text)||TouchAutomationPolicy.isSettingsAppLabel(node.contentDescription)) {
     matches.put(new JSONObject().put("visible",node.visibleToUser).put("enabled",node.enabled)
      .put("clickable",node.clickable).put("role",node.role).put("class",node.className)
      .put("resource",node.resourceId).put("actionable",TouchAutomationPolicy.clickableTarget(screen,node.nodeId,node.bounds)!=null));
    }
    report.put("settings_matches",matches);
   }
  }finally{root.recycle();}
  Files.write(new File(instrumentation.getTargetContext().getCacheDir(),"launcher-navigation-probe.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }
 @Test public void zExerciseOneLauncherPageWithoutClickingAnyIcon() throws Exception {
  org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("exerciseLauncherPaging")));
  var instrumentation=InstrumentationRegistry.getInstrumentation();
  instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  ScreenSnapshot before=ClickAccessibilityService.freshSnapshot();assertNotNull(before);
  assertTrue(com.click.service.overlay.NavigationRecovery.isHomeScreen(before));
  JSONObject report=new JSONObject().put("setting_adjustment_actions",0).put("automatic_icon_taps",0);
  int visible=0;
  for(var node:before.nodes)if(node.visibleToUser && node.enabled
      && (TouchAutomationPolicy.isSettingsAppLabel(node.text)||TouchAutomationPolicy.isSettingsAppLabel(node.contentDescription))
      && TouchAutomationPolicy.clickableTarget(before,node.nodeId,node.bounds)!=null)visible++;
  report.put("visible_settings_before",visible);
  if(visible>0) {
   report.put("scroll_attempts",0).put("reason","visible_settings_has_priority");
  } else {
   assertNotNull(before.navigation);assertEquals(NavigationState.Axis.HORIZONTAL,before.navigation.axis);
   boolean accepted=ClickAccessibilityService.swipeHomePage(true,before.packageName,before.activeWindowId);
   android.os.SystemClock.sleep(1000);
   ScreenSnapshot after=ClickAccessibilityService.freshSnapshot();assertNotNull(after);
   report.put("scroll_attempts",1).put("dispatch_accepted",accepted).put("axis",before.navigation.axis.name())
     .put("launcher_unchanged",before.packageName.equals(after.packageName))
     .put("page_content_changed",after.navigation!=null && !before.navigation.fingerprint.equals(after.navigation.fingerprint))
     .put("axis_after",after.navigation==null?"UNKNOWN":after.navigation.axis.name());
   int found=0;for(var node:after.nodes)if(node.visibleToUser && node.enabled
       && (TouchAutomationPolicy.isSettingsAppLabel(node.text)||TouchAutomationPolicy.isSettingsAppLabel(node.contentDescription))
       && TouchAutomationPolicy.clickableTarget(after,node.nodeId,node.bounds)!=null)found++;
   report.put("visible_settings_after",found);
  }
  Files.write(new File(instrumentation.getTargetContext().getCacheDir(),"launcher-page-execution.json").toPath(),
      report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
 }

}
