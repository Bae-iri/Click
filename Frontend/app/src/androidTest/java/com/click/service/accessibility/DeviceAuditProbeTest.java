package com.click.service.accessibility;
import android.app.UiAutomation;
import android.content.Intent;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.*;
import com.click.service.accessibility.AccessibilitySnapshotCollector;
import org.json.*;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Bounded public native fixtures for reproducing device failures. No model calls or value changes. */
public class DeviceAuditProbeTest {
 @Test public void recordPublicPageBoundaries()throws Exception{
  var args=InstrumentationRegistry.getArguments();assumeTrue("true".equals(args.getString("deviceAuditProbe")));
  var inst=InstrumentationRegistry.getInstrumentation();var app=inst.getTargetContext();assertTrue(app.getPackageName().endsWith(".deviceaudit"));
  UiAutomation ui=inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);var info=ui.getServiceInfo();
  info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;ui.setServiceInfo(info);
  SettingsScreenPrivacy privacy;VisionPrivacy vocabulary;
  try(var v=app.getAssets().open("vision_vocabulary.json");var p=app.getAssets().open("screen_privacy_policy.json")){privacy=SettingsScreenPrivacy.load(v,p);}
  try(var v=app.getAssets().open("vision_vocabulary.json")){vocabulary=VisionPrivacy.load(v);}
  JSONArray cases=new JSONArray();
  for(String[] page:new String[][]{{"accessibility",Settings.ACTION_ACCESSIBILITY_SETTINGS},{"wifi",Settings.ACTION_WIRELESS_SETTINGS},
      {"bluetooth",Settings.ACTION_WIRELESS_SETTINGS},{"font_size",Settings.ACTION_DISPLAY_SETTINGS},{"volume",Settings.ACTION_SOUND_SETTINGS}}){
   try(var fd=ui.executeShellCommand("am start -W -a "+page[1]+" -f 0x10008000");var in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){in.readAllBytes();}
   SystemClock.sleep(1100);ScreenSnapshot screen=null;
   for(var window:ui.getWindows()){var root=window.getRoot();if(root==null)continue;
    try{String pkg=String.valueOf(root.getPackageName());if(pkg.equals("com.android.settings")||pkg.equals("com.samsung.accessibility"))
        screen=new AccessibilitySnapshotCollector().collect(root,pkg,app,null);}finally{root.recycle();}}
   assertNotNull(screen);String title=ScreenIdentity.pageTitle(screen),setting="navigation_"+page[0];
   JSONObject row=new JSONObject().put("task",page[0]).put("page_title",title)
    .put("environment",ScreenEnvironment.classify(screen,true,false,app.getPackageName()).kind().name())
    .put("title_allowed",title!=null&&vocabulary.exportLabel(title,setting)!=null);
   try{row.put("filtered",privacy.filter(new JSONObject().put("accessibility_tree",new JSONObject(screen.toJson())),setting));}
   catch(Exception e){row.put("filter_error",e.getClass().getSimpleName());}
   cases.put(row);
  }
  Files.write(new File(app.getCacheDir(),"device-audit-public-probes.json").toPath(),new JSONObject().put("cases",cases).toString(2).getBytes(StandardCharsets.UTF_8));
 }
}
