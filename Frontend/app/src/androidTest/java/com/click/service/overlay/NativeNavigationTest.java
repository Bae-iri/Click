package com.click.service.overlay;

import android.app.UiAutomation;
import android.content.*;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.*;
import com.click.service.accessibility.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in real native collection/target check; only navigates, never changes setting values. */
@RunWith(AndroidJUnit4.class)
public class NativeNavigationTest {
 private Context context;
 private void connect() {
  context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  String original=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
  ComponentName click=new ComponentName(context,ClickAccessibilityService.class);List<String> others=new ArrayList<>();boolean enabled=false;
  if(original!=null)for(String entry:original.split(":")){if(click.equals(ComponentName.unflattenFromString(entry)))enabled=true;else others.add(entry);}
  assertTrue("Click accessibility must already be enabled",enabled);
  automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);
  try{Settings.Secure.putString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",others));SystemClock.sleep(300);}
  finally{Settings.Secure.putString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,original);automation.dropShellPermissionIdentity();}
 }
 private ScreenSnapshot screen(boolean settings) {
  for(int i=0;i<40;i++){
   SystemClock.sleep(200);ScreenSnapshot s=ClickAccessibilityService.freshSnapshot();
   if(s!=null && s.activeWindowId!=null && s.navigation!=null && (settings?TouchAutomationPolicy.settingsPackage(s.packageName):NavigationRecovery.isHomeScreen(s)))return s;
  }
  throw new AssertionError("No fresh native navigation screen");
 }
 private boolean hasDisplay(ScreenSnapshot s) {
  return s.nodes.stream().anyMatch(n->n.visibleToUser && "디스플레이".equals(n.text)
      && TouchAutomationPolicy.clickableTarget(s,n.nodeId,n.bounds)!=null);
 }
 @Test public void settingsFromMiddleSearchesUpAndFindsDisplay() {
  assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveScroll")));connect();
  context.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  ScreenSnapshot s=screen(true);
  try {
   // Establish the start using native endpoint evidence, then deliberately begin at a lower page.
   for(int i=0;i<20 && !s.navigation.atStart();i++){
    assertTrue(ClickAccessibilityService.performSettingsScroll(false,s.packageName,s.activeWindowId));SystemClock.sleep(700);s=screen(true);
   }
   assertTrue("Observe actual Settings beginning",s.navigation.atStart());
   for(int i=0;i<2;i++){assertTrue(ClickAccessibilityService.performSettingsScroll(true,s.packageName,s.activeWindowId));SystemClock.sleep(700);s=screen(true);}
   assertFalse("Start search in middle",s.navigation.atStart());assertFalse("Display is initially offscreen",hasDisplay(s));
   AutomaticScrollSearch search=new AutomaticScrollSearch();boolean found=false;int moves=0;
   for(int i=0;i<30;i++){
    if(hasDisplay(s)){found=true;break;}
    AutomaticScrollSearch.Plan plan=search.plan("device-settings",s.navigation);
    if(i==0)assertFalse("Middle Settings searches backward first",plan.forward);
    assertNotEquals("Must not give up before finding Display",AutomaticScrollSearch.Status.FINISHED,plan.status);
    if(plan.status==AutomaticScrollSearch.Status.MOVE){assertTrue(ClickAccessibilityService.performSettingsScroll(plan.forward,s.packageName,s.activeWindowId));moves++;}
    SystemClock.sleep(700);s=screen(true);
   }
   assertTrue("Find and approve the actual Display row",found);
   android.util.Log.i("ClickNavigationTest","settings middle_to_display=true moves="+moves+" axis="+s.navigation.axis);
  } finally {context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
 }
 @Test public void launcherSettingsTargetAndAxisAreUsable() {
  assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveScroll")));connect();
  context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  ScreenSnapshot s=screen(false);Set<String> seen=new HashSet<>();boolean found=false;int moves=0;
  try {
   for(int i=0;i<12;i++){
    for(AccessibilityNodeSnapshot n:s.nodes)if(n.visibleToUser && ("설정".equals(n.text)||"설정".equals(n.contentDescription)||"Settings".equals(n.text))){
     if(TouchAutomationPolicy.clickableTarget(s,n.nodeId,n.bounds)!=null){found=true;break;}
    }
    if(found)break;
    if(!seen.add(s.navigation.fingerprint))break;
    if(!ClickAccessibilityService.swipeHomePage(true,s.packageName,s.activeWindowId))break;
    moves++;SystemClock.sleep(800);s=screen(false);
   }
   assertTrue("Actual Settings icon must be eligible",found);
   android.util.Log.i("ClickNavigationTest","launcher settings_target=true axis="+s.navigation.axis+" moves="+moves);
  } finally {
   for(int i=0;i<moves;i++){ClickAccessibilityService.swipeHomePage(false,s.packageName,s.activeWindowId);SystemClock.sleep(700);s=screen(false);}
  }
 }
}
