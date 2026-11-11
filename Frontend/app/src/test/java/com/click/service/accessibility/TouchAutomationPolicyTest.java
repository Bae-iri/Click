package com.click.service.accessibility;
import com.click.data.snapshot.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class TouchAutomationPolicyTest {
 private final int[] bounds = {0,100,500,200};
 private ScreenSnapshot screen(String pkg, String label, boolean checkable) {
  AccessibilityNodeSnapshot n = new AccessibilityNodeSnapshot("node_0_1","node_0",true,pkg,label,null,null,"android.widget.Button",null,bounds,true,true,false,false,checkable,null,5);
  return new ScreenSnapshot(pkg,"2026-10-28T00:00:00Z",1080,2400,Arrays.asList(n),5);
 }
 @Test public void allowsSettingsNavigationOnly() {
  assertNotNull(TouchAutomationPolicy.target(screen("com.android.settings","디스플레이",false),"node_0_1",bounds));
  for (String label : Arrays.asList("결제", "전화 걸기", "메시지 보내기", "초기화", "허용"))
   assertNull(TouchAutomationPolicy.target(screen("com.android.settings",label,false),"node_0_1",bounds));
  assertNull(TouchAutomationPolicy.target(screen("com.example.shop","디스플레이",false),"node_0_1",bounds));
 }
 @Test public void rejectsMissingMovedAndToggleTargets() {
  ScreenSnapshot s=screen("com.android.settings","디스플레이",false);
  assertNull(TouchAutomationPolicy.target(s,"node_0_2",bounds));
  assertNull(TouchAutomationPolicy.target(s,"node_0_1",new int[]{0,200,500,300}));
  assertNull(TouchAutomationPolicy.target(screen("com.android.settings","디스플레이",true),"node_0_1",bounds));
 }
 private AccessibilityNodeSnapshot row(String id,String parent,String label,int[] box,boolean clickable,boolean checkable) {
  return new AccessibilityNodeSnapshot(id,parent,true,"com.android.settings",label,null,null,"android.widget.TextView",null,box,clickable,true,false,false,checkable,null,5);
 }
 @Test public void resolvesLabelInsideSafeClickableRow() {
  AccessibilityNodeSnapshot label=row("node_0_1_0","node_0_1","디스플레이",bounds,false,false);
  AccessibilityNodeSnapshot parent=row("node_0_1","node_0",null,new int[]{0,90,600,210},true,false);
  ScreenSnapshot screen=new ScreenSnapshot("com.android.settings","2026-10-28T00:00:00Z",1080,2400,Arrays.asList(label,parent),5);
  assertEquals(parent,TouchAutomationPolicy.clickableTarget(screen,label.nodeId,bounds));
 }
 @Test public void refusesAncestorWithAnotherControlOrConflictingLabel() {
  AccessibilityNodeSnapshot label=row("node_0_1_0","node_0_1","디스플레이",bounds,false,false);
  AccessibilityNodeSnapshot parent=row("node_0_1","node_0",null,new int[]{0,90,600,210},true,false);
  for (AccessibilityNodeSnapshot extra : Arrays.asList(
    row("node_0_1_1","node_0_1",null,bounds,true,true),
    row("node_0_1_1","node_0_1","초기화",bounds,false,false))) {
   ScreenSnapshot screen=new ScreenSnapshot("com.android.settings","2026-10-28T00:00:00Z",1080,2400,Arrays.asList(label,parent,extra),5);
   assertNull(TouchAutomationPolicy.clickableTarget(screen,label.nodeId,bounds));
  }
 }
 @Test public void launcherAutomationOnlyOpensSettingsNotOtherApps() {
  assertNotNull(TouchAutomationPolicy.clickableTarget(screen("com.android.launcher3","설정",false),"node_0_1",bounds));
  assertNull(TouchAutomationPolicy.clickableTarget(screen("com.android.launcher3","디스플레이",false),"node_0_1",bounds));
  assertNull(TouchAutomationPolicy.clickableTarget(screen("com.android.launcher3","전화",false),"node_0_1",bounds));
 }
 @Test public void namedRowWithTitleAndSubtitleCanBeClicked() {
  AccessibilityNodeSnapshot parent=row("node_0_1","node_0","밝기와 화면 설정",new int[]{0,90,600,250},true,false);
  AccessibilityNodeSnapshot title=row("node_0_1_0","node_0_1","디스플레이",bounds,false,false);
  AccessibilityNodeSnapshot subtitle=row("node_0_1_1","node_0_1","밝기, 글꼴, 화면 크기",new int[]{0,200,500,240},false,false);
  ScreenSnapshot s=new ScreenSnapshot("com.android.settings","2026-10-28T00:00:00Z",1080,2400,Arrays.asList(parent,title,subtitle),5);
  assertEquals(parent,TouchAutomationPolicy.clickableTarget(s,parent.nodeId,parent.bounds));
  assertEquals(parent,TouchAutomationPolicy.clickableTarget(s,title.nodeId,title.bounds));
 }
 @Test public void opaqueNativeClickFlagDoesNotRemoveKnownPhysicalTarget() {
  AccessibilityNodeSnapshot label=row("node_0_1","node_0","디스플레이",bounds,false,false);
  ScreenSnapshot s=new ScreenSnapshot("com.android.settings","2026-10-28T00:00:00Z",1080,2400,Arrays.asList(label),5);
  assertEquals(label,TouchAutomationPolicy.clickableTarget(s,label.nodeId,bounds));
 }
 @Test public void unrelatedRevisionDoesNotBlockSameControlButSubtreeChangeDoes() {
  ScreenSnapshot before=screen("com.android.settings","디스플레이",false);
  assertTrue(TouchAutomationPolicy.sameTarget(before,before.withScreenRevision("new-revision"),"node_0_1",bounds));
  assertFalse(TouchAutomationPolicy.sameTarget(before,screen("com.android.settings","초기화",false),"node_0_1",bounds));
 }
 @Test public void graphSupportedFontAliasesAreExecutable() {
  for (String text : Arrays.asList("글꼴 크기", "폰트 크기", "FONT SIZE", "Text size", "글꼴 크기 및 스타일"))
   assertNotNull(TouchAutomationPolicy.clickableTarget(screen("com.android.settings",text,false),"node_0_1",bounds));
 }
 @Test public void soundVibrateMuteButtonsAndTheirTextCannotBeUsedAsNavigationMenus() {
  for(String resource:List.of("low_button","mid_button","high_button")) {
   AccessibilityNodeSnapshot mode=new AccessibilityNodeSnapshot("mode",null,true,"com.android.settings",null,null,
     "com.android.settings:id/"+resource,"android.widget.LinearLayout","menu_item",new int[]{0,90,600,210},true,true,false,false,false,null,5);
   AccessibilityNodeSnapshot title=row("title","mode","소리",bounds,false,false);
   ScreenSnapshot s=new ScreenSnapshot("com.android.settings","2026-10-28T00:00:00Z",1080,2400,List.of(mode,title),5);
   assertNull(TouchAutomationPolicy.clickableTarget(s,mode.nodeId,mode.bounds));
   assertNull(TouchAutomationPolicy.clickableTarget(s,title.nodeId,title.bounds));
  }
 }
 @Test public void launcherIconDecorationDoesNotHideSettingsButFolderStaysAmbiguous() {
  String pkg="com.sec.android.app.launcher";
  AccessibilityNodeSnapshot icon=new AccessibilityNodeSnapshot("icon",null,true,pkg,"설정",null,null,
    "android.widget.LinearLayout",null,bounds,true,true,false,false,false,null,5);
  AccessibilityNodeSnapshot image=new AccessibilityNodeSnapshot("image","icon",true,pkg,null,null,null,
    "android.widget.ImageView",null,bounds,true,true,false,false,false,null,5);
  ScreenSnapshot s=new ScreenSnapshot(pkg,"2026-11-09T00:00:00Z",1080,2400,List.of(icon,image),5);
  assertEquals(icon,TouchAutomationPolicy.clickableTarget(s,"icon",bounds));
  AccessibilityNodeSnapshot another=new AccessibilityNodeSnapshot("another","icon",true,pkg,"다른 앱",null,null,
    "android.widget.TextView",null,bounds,true,true,false,false,false,null,5);
  s=new ScreenSnapshot(pkg,"2026-11-09T00:00:00Z",1080,2400,List.of(icon,image,another),5);
  assertNull(TouchAutomationPolicy.clickableTarget(s,"icon",bounds));
 }
 @Test public void launcherAccessibilitySuffixUsesTheSameSettingsIdentityAsItsTitle() {
  for(String name:List.of("설정, 앱", "Settings, App", "Settings, 1 notification")) {
   ScreenSnapshot original=screen("com.sec.android.app.launcher",name,false);
   AccessibilityNodeSnapshot title=new AccessibilityNodeSnapshot("title","node_0_1",true,original.packageName,
     "설정",null,null,"android.widget.TextView",null,bounds,false,true,false,false,false,null,5);
   ScreenSnapshot s=new ScreenSnapshot(original.packageName,original.capturedAt,1080,2400,
     List.of(original.nodes.get(0),title),5);
   assertNotNull(name,TouchAutomationPolicy.clickableTarget(s,"node_0_1",bounds));
  }
  assertNull(TouchAutomationPolicy.clickableTarget(screen("com.android.launcher3","설정 도우미",false),"node_0_1",bounds));
 }
 @Test public void acceptsApprovedExpandedMenusWhileRefusingDeviceIdentityRowsAndValues() {
  for(String label:List.of("연결","블루투스","Wi-Fi","배터리","알림"))
   assertNotNull(label,TouchAutomationPolicy.clickableTarget(screen("com.android.settings",label,false),"node_0_1",bounds));
  assertNull(TouchAutomationPolicy.clickableTarget(screen("com.android.settings","블루투스",true),"node_0_1",bounds));
  AccessibilityNodeSnapshot privateRow=new AccessibilityNodeSnapshot("device","root",true,"com.android.settings","알림",null,
   "com.android.settings:id/bluetooth_device","android.widget.TextView","menu_item",bounds,true,true,false,false,false,null,5);
  ScreenSnapshot privateScreen=new ScreenSnapshot("com.android.settings","now",1080,2400,List.of(privateRow),5);
  assertNull(TouchAutomationPolicy.clickableTarget(privateScreen,"device",bounds));
 }

 @Test public void expandedMenuMustBelongToTheAcceptedGoalAndState() {
  ScreenSnapshot connection=screen("com.android.settings","블루투스",false);
  assertNotNull(TouchAutomationPolicy.clickableTarget(connection,"node_0_1",bounds,"settings.bluetooth.enable","S_CATEGORY"));
  assertNull(TouchAutomationPolicy.clickableTarget(connection,"node_0_1",bounds,"settings.font_size.increase","S2_DISPLAY_MENU"));
  assertNull(TouchAutomationPolicy.clickableTarget(connection,"node_0_1",bounds,"settings.bluetooth.enable","S_DONE"));
 }

 private ScreenSnapshot splitRow(String heading,int switchLeft,String rowLabel) {
  AccessibilityNodeSnapshot page=row("heading",null,heading,new int[]{0,0,900,80},false,false);
  AccessibilityNodeSnapshot parent=row("parent",null,null,new int[]{0,100,1080,300},true,false);
  AccessibilityNodeSnapshot title=new AccessibilityNodeSnapshot("label","parent",true,"com.android.settings",rowLabel,null,"android:id/title",
    "android.widget.TextView","text",new int[]{30,140,700,220},false,true,false,false,false,null,5);
  AccessibilityNodeSnapshot sw=new AccessibilityNodeSnapshot("switch","parent",true,"com.android.settings",null,null,"android:id/switch_widget",
    "android.widget.Switch","switch",new int[]{switchLeft,140,1040,220},true,true,false,false,true,null,5);
  return new ScreenSnapshot("com.android.settings","now",1080,2400,List.of(page,parent,title,sw),5);
 }
 @Test public void splitSwitchMenuUsesOnlyItsProvenDisjointTitleRegion() {
  ScreenSnapshot safe=splitRow("연결",850,"블루투스");
  assertNull(TouchAutomationPolicy.clickableTarget(safe,"parent",safe.nodes.get(1).bounds));
  assertEquals("label",TouchAutomationPolicy.clickableTarget(safe,"parent",safe.nodes.get(1).bounds,"settings.bluetooth.enable","S_CATEGORY").nodeId);
  ScreenSnapshot overlap=splitRow("연결",400,"블루투스");
  assertNull(TouchAutomationPolicy.clickableTarget(overlap,"parent",overlap.nodes.get(1).bounds,"settings.bluetooth.enable","S_CATEGORY"));
  ScreenSnapshot wrongPage=splitRow("알림",850,"블루투스");
  assertNull(TouchAutomationPolicy.clickableTarget(wrongPage,"parent",wrongPage.nodes.get(1).bounds,"settings.bluetooth.enable","S_CATEGORY"));
  assertNull(TouchAutomationPolicy.clickableTarget(safe,"parent",safe.nodes.get(1).bounds,"settings.font_size.increase","S2_DISPLAY_MENU"));
 }

}
