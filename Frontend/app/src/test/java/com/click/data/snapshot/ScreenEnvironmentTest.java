package com.click.data.snapshot;
import org.junit.Test;
import org.json.*;
import java.util.*;
import static org.junit.Assert.*;
public class ScreenEnvironmentTest {
 private ScreenSnapshot scene(JSONObject item) throws Exception {
  List<AccessibilityNodeSnapshot> nodes=new ArrayList<>();String pkg=item.getString("package");
  if(!item.isNull("title"))nodes.add(new AccessibilityNodeSnapshot("title",null,true,pkg,item.getString("title"),null,null,
     "android.widget.TextView","text",new int[]{20,20,900,80},false,true,false,false,false,null,7));
  if(item.has("resource"))nodes.add(new AccessibilityNodeSnapshot("structure",null,true,pkg,null,null,item.getString("resource"),
     "android.view.View","container",new int[]{0,100,1080,2200},false,true,false,false,false,null,7));
  if(item.optBoolean("sensitive") || item.optBoolean("empty_search"))nodes.add(new AccessibilityNodeSnapshot("input",null,true,pkg,
     item.optBoolean("empty_search")?null:"private",null,item.optBoolean("empty_search")?"android:id/search_src_text":null,
     "android.widget.EditText","text_input",new int[]{20,300,900,400},true,true,false,false,false,null,7,true,null,null,false));
  if(item.optBoolean("control")) {
   nodes.add(new AccessibilityNodeSnapshot("wrapper",null,true,pkg,null,null,"com.android.systemui:id/brightness_dialog_slider","android.view.View","container",new int[]{0,100,1080,400},false,true,false,false,false,null,7));
   nodes.add(new AccessibilityNodeSnapshot("slider","wrapper",true,pkg,null,null,"com.android.systemui:id/slider","android.widget.SeekBar","slider",new int[]{20,200,1000,300},false,true,false,false,false,0.4f,7,false,0f,1f));
  }
  JSONObject hints=item.optJSONObject("hints");if(hints==null)hints=new JSONObject();
  return new ScreenSnapshot(pkg,"2026-11-10T00:00:00Z",1080,2400,nodes,7).withWindowContext(new NativeWindowContext(
   hints.optString("settings_activity","UNKNOWN"),hints.optString("window_kind","UNKNOWN"),hints.optBoolean("keyboard_visible")));
 }
 @Test public void sameFiniteEnvironmentCasesRunOnAndroidAndServer() throws Exception {
  try(var in=getClass().getResourceAsStream("/screen_environment_cases.json")){
   JSONArray cases=new JSONArray(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
   for(int i=0;i<cases.length();i++){
    JSONObject item=cases.getJSONObject(i);
    assertEquals(item.getString("name"),item.getString("kind"),ScreenEnvironment.classify(scene(item),item.optBoolean("connected",true),item.optBoolean("locked"),"com.click").kind().name());
   }
  }
 }
 @Test public void clonedSnapshotsKeepFiniteWindowEvidenceAndConsentRemainsExact() throws Exception {
  var base=scene(new JSONObject().put("package","com.android.settings").put("title","설정"));
  var activity=base.withWindowContext(new NativeWindowContext("SUBPAGE","APPLICATION",false));
  var copy=activity.withScreenRevision("r").withAdjustmentValues(100,5).withWifiState(3).withNightMode(2).withRingerMode(1).withDisplayDensity(420);
  assertEquals(activity.windowContext,copy.windowContext);assertFalse(base.sameNativeContent(activity));
  var context=new JSONObject(copy.toJson()).getJSONObject("window_context");
  assertEquals(3,context.length());assertTrue(context.has("settings_activity") && context.has("window_kind") && context.has("keyboard_visible"));
 }
 @Test public void arbitraryActivityOrWindowNamesCannotBeSerialized(){
  assertThrows(IllegalArgumentException.class,()->new NativeWindowContext("private activity","APPLICATION",false));
  assertThrows(IllegalArgumentException.class,()->new NativeWindowContext("HOME","private title",false));
 }
 @Test public void everyDeclaredFailureHasOneCategoryAndBoundedAutomaticRetries() throws Exception {
  String[] reasons={"GOAL_AMBIGUOUS","OTHER_APP","SETTINGS_OFF_PATH","RECENTS_VISIBLE","DIALOG_VISIBLE","DEVICE_LOCKED",
   "ACCESSIBILITY_DISCONNECTED","OBSERVATION_EMPTY","TARGET_NOT_VISIBLE","TARGET_AMBIGUOUS","LOW_CONFIDENCE",
   "ACTION_DISPATCH_FAILED","ALREADY_SET","ACTION_RESULT_UNOBSERVABLE","STALE_RESPONSE","NETWORK_UNAVAILABLE","SENSITIVE_INPUT","CONTRACT_MISMATCH"};
  for(String reason:reasons){JSONObject rule=ScreenEnvironmentPolicy.recovery(reason);assertFalse(reason,rule.optString("category").isEmpty());assertTrue(rule.getInt("automatic_retries")<=3);assertTrue(rule.getBoolean("keep_goal"));}
 }
}
