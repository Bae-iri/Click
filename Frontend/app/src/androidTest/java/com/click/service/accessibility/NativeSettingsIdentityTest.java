package com.click.service.accessibility;
import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.accessibility.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Local-only native AX check. No HTTP calls and no setting mutation. */
@RunWith(AndroidJUnit4.class)
public class NativeSettingsIdentityTest {
 private boolean top(AccessibilityNodeInfo n) {
  if(n.isScrollable() && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD))return true;
  for(int j=0;j<n.getChildCount();j++){AccessibilityNodeInfo c=n.getChild(j);if(c!=null)try{if(top(c))return true;}finally{c.recycle();}}
  return false;
 }
 @Test public void brightnessControlIdentityAndRangeAreNative() throws Exception {
  assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("nativeIdentity")));
  Instrumentation i=InstrumentationRegistry.getInstrumentation();Context app=i.getTargetContext();
  assertFalse(app.getSystemService(KeyguardManager.class).isDeviceLocked());
  UiAutomation ui=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();
  info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
  ui.setServiceInfo(info);
  float font=Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE);
  JSONObject result=new JSONObject().put("scope","native_local_identity_no_http_no_setting_change").put("controls",new JSONArray());
  try {
   app.startActivity(new Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
   ScreenSnapshot screen=null;
   for(int n=0;n<30;n++) {
    SystemClock.sleep(300);
    for(AccessibilityWindowInfo w:ui.getWindows()) { AccessibilityNodeInfo root=w.getRoot();
     if(root!=null)try {
      if(!"com.android.settings".contentEquals(root.getPackageName()==null?"":root.getPackageName()))continue;
      android.util.DisplayMetrics m=app.getResources().getDisplayMetrics();
      screen=new AccessibilitySnapshotCollector().collect(root,"com.android.settings",m.widthPixels,m.heightPixels,font);
      if(screen.nodes.stream().noneMatch(v->v.resourceId!=null&&v.resourceId.endsWith("/brightness_seekbar")))top(root);
     }finally{root.recycle();}
    }
    if(screen!=null&&screen.nodes.stream().anyMatch(v->v.resourceId!=null&&v.resourceId.endsWith("/brightness_seekbar")))break;
   }
   assertNotNull("Readable native Settings tree",screen);int matches=0;
   for(AccessibilityNodeSnapshot n:screen.nodes)if("slider".equals(n.role)||n.resourceId!=null&&n.resourceId.endsWith("/brightness_seekbar")) {
    boolean match=SettingsSliderTarget.matches(screen,n,"brightness");if(match)matches++;
    result.getJSONArray("controls").put(new JSONObject().put("resource",n.resourceId).put("class",n.className)
      .put("progress",n.progress).put("min",n.rangeMin).put("max",n.rangeMax).put("brightness_identity",match));
   }
   result.put("brightness_matches",matches);assertEquals("One actual brightness control",1,matches);
   SettingsScreenPrivacy privacy;
   try(InputStream v=app.getAssets().open("vision_vocabulary.json");InputStream policy=app.getAssets().open("screen_privacy_policy.json")){privacy=SettingsScreenPrivacy.load(v,policy);}
   JSONObject safe=privacy.filter(new JSONObject().put("accessibility_tree",new JSONObject(screen.toJson())));
   JSONArray nodes=safe.getJSONObject("accessibility_tree").getJSONArray("nodes");
   boolean exported=false;
   for(int n=0;n<nodes.length();n++)if("com.android.settings:id/brightness_seekbar".equals(nodes.getJSONObject(n).optString("resource_id"))){
    JSONObject range=nodes.getJSONObject(n).getJSONObject("extras");
    assertTrue(range.has("progress") && range.has("min") && range.has("max"));
    assertTrue(range.getDouble("max")>range.getDouble("min"));
    assertTrue(range.getDouble("progress")>=range.getDouble("min") && range.getDouble("progress")<=range.getDouble("max"));
    result.put("exported_range",range);exported=true;
   }
   assertTrue("Numeric slider evidence must survive the privacy boundary",exported);
   assertEquals(font,Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE),.001f);
   result.put("passed",true);
  } finally {
   Files.write(new File(app.getCacheDir(),"native-settings-identity.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
  }
 }
 @Test public void mediaControlIdentityAndRangeAreNative() throws Exception {
  assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("nativeIdentity")));
  Instrumentation i=InstrumentationRegistry.getInstrumentation();Context app=i.getTargetContext();
  assertFalse(app.getSystemService(KeyguardManager.class).isDeviceLocked());
  UiAutomation ui=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();
  info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;ui.setServiceInfo(info);
  app.startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
  JSONObject report=new JSONObject().put("scope","native_media_identity_no_http_no_setting_change");
  android.media.AudioManager audio=app.getSystemService(android.media.AudioManager.class);
  report.put("system_volume",audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)).put("system_max",audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC));
  try {
   for(int attempt=0;attempt<30;attempt++) {
    SystemClock.sleep(250);
    for(AccessibilityWindowInfo w:ui.getWindows()) {AccessibilityNodeInfo root=w.getRoot();if(root==null)continue;
     try {
      if(!"com.android.settings".contentEquals(root.getPackageName()==null?"":root.getPackageName()))continue;
      android.util.DisplayMetrics m=app.getResources().getDisplayMetrics();
      ScreenSnapshot screen=new AccessibilitySnapshotCollector().collect(root,"com.android.settings",m.widthPixels,m.heightPixels,null);
      JSONArray controls=new JSONArray();JSONArray labels=new JSONArray();
      for(AccessibilityNodeSnapshot n:screen.nodes) {
       if(java.util.Set.of("음량","소리 및 진동","미디어","벨소리","알림","시스템").contains(n.text==null?"":n.text))labels.put(n.text);
       if("slider".equals(n.role))controls.put(new JSONObject().put("progress",n.progress).put("min",n.rangeMin).put("max",n.rangeMax).put("resource",n.resourceId));
      }
      report.put("controls",controls).put("approved_labels",labels);
      for(AccessibilityNodeSnapshot n:screen.nodes)if(SettingsSliderTarget.matches(screen,n,"volume")) {
       report.put("progress",n.progress).put("min",n.rangeMin).put("max",n.rangeMax).put("passed",true);return;
      }
      for(AccessibilityNodeInfo row:root.findAccessibilityNodeInfosByText("음량")) {
       try {if("음량".contentEquals(row.getText()==null?"":row.getText())) {
        if(row.isClickable())row.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        else {AccessibilityNodeInfo parent=row.getParent();for(int depth=0;parent!=null && depth<4;depth++) {
         AccessibilityNodeInfo next=null;try{if(parent.isClickable()){parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);break;}next=parent.getParent();}finally{parent.recycle();}parent=next;
        }}
       }}finally{row.recycle();}
      }
     }finally{root.recycle();}
    }
   }
   fail("Native media slider must be identified");
  }finally{Files.write(new File(app.getCacheDir(),"native-media-identity.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));}
 }
 @Test public void captureCurrentSettingsMenuAfterPrivacyFilter() throws Exception {
  assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("nativeIdentity")));
  Instrumentation i=InstrumentationRegistry.getInstrumentation();Context app=i.getTargetContext();
  UiAutomation ui=i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  android.accessibilityservice.AccessibilityServiceInfo info=ui.getServiceInfo();
  info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS | android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
  ui.setServiceInfo(info);
  SettingsScreenPrivacy privacy;
  try(InputStream v=app.getAssets().open("vision_vocabulary.json");InputStream policy=app.getAssets().open("screen_privacy_policy.json")){privacy=SettingsScreenPrivacy.load(v,policy);}
  for(int attempt=0;attempt<25;attempt++) {
   SystemClock.sleep(200);
   for(AccessibilityWindowInfo w:ui.getWindows()) {AccessibilityNodeInfo root=w.getRoot();if(root==null)continue;
    try {
     if(!"com.android.settings".contentEquals(root.getPackageName()==null?"":root.getPackageName()))continue;
     android.util.DisplayMetrics m=app.getResources().getDisplayMetrics();
     ScreenSnapshot screen=new AccessibilitySnapshotCollector().collect(root,"com.android.settings",m.widthPixels,m.heightPixels,
         Settings.System.getFloat(app.getContentResolver(),Settings.System.FONT_SCALE));
     JSONObject safe=privacy.filter(new JSONObject().put("accessibility_tree",new JSONObject(screen.toJson())));
     Files.write(new File(app.getCacheDir(),"native-settings-menu-filtered.json").toPath(),safe.toString(2).getBytes(StandardCharsets.UTF_8));
     return;
    }finally{root.recycle();}
   }
  }
  fail("Readable native Settings menu");
 }
}
