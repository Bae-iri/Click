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
}
