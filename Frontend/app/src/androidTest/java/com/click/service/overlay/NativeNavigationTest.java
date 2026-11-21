package com.click.service.overlay;

import android.app.UiAutomation;
import android.content.*;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
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
 @Test public void wallpaperDetourReturnsToTheSettingsPageWithOneBack() throws Exception {
  assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("wallpaperBack")));
  var instrumentation=InstrumentationRegistry.getInstrumentation();
  context=instrumentation.getTargetContext();
  UiAutomation ui=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  String settings="com.android.settings",wallpaper="com.samsung.android.app.dressroom";
  try(var launch=new android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("am start -W -a android.settings.SETTINGS"))) {
   byte[] buffer=new byte[512];while(launch.read(buffer)!=-1) { /* Wait for the activity. */ }
  }
  var home=awaitWindow(ui,settings);home.recycle();
  ScreenSnapshot before=DeviceOutcomeSnapshot.settings(ui,context);assertNotNull(before);
  boolean opened=false;
  for(int attempt=0;attempt<16 && !opened;attempt++) {
   var root=awaitWindow(ui,settings);
   try {
    opened=clickWallpaperRow(root);
    if(!opened)scrollSettingsList(root,attempt<5?AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
   }finally{root.recycle();}
   if(!opened)SystemClock.sleep(350);
  }
  assertTrue("Wallpaper row in Samsung Settings",opened);
  var picker=awaitWindow(ui,wallpaper);
  try {
   ScreenSnapshot detour=new ScreenSnapshot(wallpaper,java.time.Instant.now().toString(),before.screenWidth,before.screenHeight,List.of(),picker.getWindowId());
   var scene=ScreenEnvironment.classify(detour,true,false,context.getPackageName());
   assertEquals(ObservationRecovery.Back.ALLOW_AUTOMATIC,ObservationRecovery.settingsBack(detour,scene.kind(),before,"font_size",null,false));
  }finally{
   picker.recycle();
   assertTrue(ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
  }
  var parent=awaitWindow(ui,settings);parent.recycle();
  ScreenSnapshot after=DeviceOutcomeSnapshot.settings(ui,context);assertNotNull(after);
  assertEquals("Back returns to the original Settings page",ScreenIdentity.pageTitle(before),ScreenIdentity.pageTitle(after));
 }
 private static AccessibilityNodeInfo awaitWindow(UiAutomation ui,String pkg) {
  long deadline=SystemClock.elapsedRealtime()+5000;
  do {
   var root=ui.getRootInActiveWindow();
   if(root!=null) { if(pkg.contentEquals(String.valueOf(root.getPackageName())))return root;root.recycle(); }
   SystemClock.sleep(100);
  } while(SystemClock.elapsedRealtime()<deadline);
  throw new AssertionError("Expected foreground window "+pkg);
 }
 private static boolean clickWallpaperRow(AccessibilityNodeInfo root) {
  List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByText("배경화면");
  try {
   for(var label:matches) {
    if(!label.isVisibleToUser()||!Set.of("배경화면 및 스타일","배경화면").contains(String.valueOf(label.getText())))continue;
    var row=AccessibilityNodeInfo.obtain(label);
    while(row!=null) {
     if(row.isClickable()) { try{return row.performAction(AccessibilityNodeInfo.ACTION_CLICK);}finally{row.recycle();} }
     var parent=row.getParent();row.recycle();row=parent;
    }
   }
   return false;
  }finally{for(var match:matches)match.recycle();}
 }
 private static boolean scrollSettingsList(AccessibilityNodeInfo node,int action) {
  if(node.isScrollable()&&node.performAction(action))return true;
  for(int i=0;i<node.getChildCount();i++){
   var child=node.getChild(i);if(child==null)continue;
   try{if(scrollSettingsList(child,action))return true;}finally{child.recycle();}
  }
  return false;
 }
 private void connect() {
  context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  String original=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
  int originalGlobal=Settings.Secure.getInt(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
  assertEquals("Existing global accessibility must already be enabled",1,originalGlobal);
  ComponentName click=new ComponentName(context,ClickAccessibilityService.class);List<String> others=new ArrayList<>();boolean enabled=false;
  if(original!=null)for(String entry:original.split(":")){if(click.equals(ComponentName.unflattenFromString(entry)))enabled=true;else others.add(entry);}
  assertTrue("Click accessibility must already be enabled",enabled);
  automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);
  try{Settings.Secure.putString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",others));SystemClock.sleep(1500);}
  finally{
   Settings.Secure.putString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,original);
   // Android may disable its global flag when disconnecting the last service.
   // Restore the exact pre-test ON state, never grant a new service.
   Settings.Secure.putInt(context.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,originalGlobal);
   automation.dropShellPermissionIdentity();
  }
  for(int i=0;i<50 && connected()==null;i++)SystemClock.sleep(200);
  assertNotNull("Existing accessibility service must rebind before navigation",connected());
 }
 private ClickAccessibilityService connected() {
  try {
   java.lang.reflect.Field field=ClickAccessibilityService.class.getDeclaredField("connectedService");field.setAccessible(true);
   return (ClickAccessibilityService)((java.lang.ref.WeakReference<?>)field.get(null)).get();
  }catch(ReflectiveOperationException error){throw new AssertionError(error);}
 }
 private void launch(boolean settings) {
  UiAutomation automation=InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
  String command=settings?"am start -W -a android.settings.SETTINGS -f 0x10008000"
    :"am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -f 0x10000000";
  try(android.os.ParcelFileDescriptor fd=automation.executeShellCommand(command);
      java.io.InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) {
   byte[] buffer=new byte[1024];while(in.read(buffer)!=-1) { }
  }catch(java.io.IOException error){throw new AssertionError(error);}
 }
 private ScreenSnapshot screen(boolean settings) {
  ScreenSnapshot latest=null;
  for(int i=0;i<40;i++){
   SystemClock.sleep(200);ScreenSnapshot s=ClickAccessibilityService.freshSnapshot();latest=s;
   if(s!=null && s.activeWindowId!=null && s.navigation!=null && (settings?TouchAutomationPolicy.settingsPackage(s.packageName):NavigationRecovery.isHomeScreen(s)))return s;
  }
  boolean locked=context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();
  ClickAccessibilityService service=connected();
  String foreground="unavailable";
  if(service!=null){
   android.view.accessibility.AccessibilityNodeInfo root=service.getRootInActiveWindow();
   if(root!=null)try{String pkg=String.valueOf(root.getPackageName());foreground=pkg.equals("com.click")?"click":TouchAutomationPolicy.settingsPackage(pkg)?"settings":NavigationRecovery.isHome(pkg)?"launcher":"other";}finally{root.recycle();}
  }
  throw new AssertionError("No fresh native navigation screen: snapshot="+(latest!=null)
    +" navigation="+(latest!=null && latest.navigation!=null)+" settings="+(latest!=null && TouchAutomationPolicy.settingsPackage(latest.packageName))
    +" launcher="+NavigationRecovery.isHomeScreen(latest)+" keyguard="+locked+" connected="+(service!=null)+" foreground="+foreground);
 }
 private boolean hasDisplay(ScreenSnapshot s) {
  return s.nodes.stream().anyMatch(n->n.visibleToUser && "디스플레이".equals(n.text)
      && TouchAutomationPolicy.clickableTarget(s,n.nodeId,n.bounds)!=null);
 }
 @Test public void settingsFromMiddleSearchesUpAndFindsDisplay() {
  assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveScroll")));connect();
  launch(true);
  ScreenSnapshot s=screen(true);
  try {
   // With a large system font, Display may be below the first viewport.
   // Use the production planner to establish a real target, then begin the
   // tested search below it instead of assuming a fixed row/page position.
   AutomaticScrollSearch setupSearch=new AutomaticScrollSearch();
   for(int i=0;i<30 && !hasDisplay(s);i++){
    AutomaticScrollSearch.Plan plan=setupSearch.plan("device-fixture",s.navigation);
    assertNotEquals("Display fixture must be discoverable",AutomaticScrollSearch.Status.FINISHED,plan.status);
    if(plan.status==AutomaticScrollSearch.Status.MOVE)
      ClickAccessibilityService.performSettingsScroll(plan.forward,s.packageName,s.activeWindowId);
    SystemClock.sleep(700);s=screen(true);
   }
   assertTrue("Establish actual Display row before moving it offscreen",hasDisplay(s));
   for(int i=0;i<6 && hasDisplay(s);i++){
    assertTrue(ClickAccessibilityService.performSettingsScroll(true,s.packageName,s.activeWindowId));
    SystemClock.sleep(700);s=screen(true);
   }
   android.util.Log.i("ClickNavigationTest","settings fixture first="+s.navigation.firstIndex
     +" last="+s.navigation.lastIndex+" count="+s.navigation.itemCount
     +" backward="+s.navigation.backwardAvailable+" forward="+s.navigation.forwardAvailable);
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
  } finally {launch(false);}
 }
 @Test public void launcherSettingsTargetAndAxisAreUsable() {
  assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("liveScroll")));connect();
  launch(false);
  ScreenSnapshot s=screen(false);Set<String> seen=new HashSet<>();boolean found=false;int moves=0;
  try {
   for(int i=0;i<12;i++){
    for(AccessibilityNodeSnapshot n:s.nodes)if(n.visibleToUser && (TouchAutomationPolicy.isSettingsAppLabel(n.text)||TouchAutomationPolicy.isSettingsAppLabel(n.contentDescription))){
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
