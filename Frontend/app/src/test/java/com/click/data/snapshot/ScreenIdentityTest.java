package com.click.data.snapshot;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ScreenIdentityTest {
 private AccessibilityNodeSnapshot node(String id,String text,int y,boolean clickable,int window,Float value) {
  return new AccessibilityNodeSnapshot(id,null,true,"com.android.settings",text,null,"android:id/title", "android.widget.TextView",
    value==null?(clickable?"menu_item":"text"):"slider",new int[]{20,y,1000,y+60},clickable,true,false,false,false,value,window);
 }
 private ScreenSnapshot screen(int window,String menu,Float value) {
  return new ScreenSnapshot("com.android.settings","2026-11-09T00:00:00Z",1080,2400,
    List.of(node("title","디스플레이",20,false,window,null),node("target",menu,400,true,window,value)),window,true,1f);
 }
 @Test public void pageIdentityDoesNotImplySameLayoutTargetOrValue() {
  ScreenSnapshot original=screen(1,"글자 크기",2f),changed=screen(2,"밝기",3f);
  assertTrue(ScreenIdentity.samePage(original,changed));
  assertFalse(original.sameNativeContent(changed));
  assertNotEquals(ScreenIdentity.navigationFingerprint(original),ScreenIdentity.navigationFingerprint(changed));
 }
 @Test public void unrelatedPersistedValuesChangeObservationButNotNavigation() {
  ScreenSnapshot original=screen(1,"글자 크기",2f),changed=original.withWifiState(3).withNightMode(2).withAdjustmentValues(130,5);
  assertTrue(ScreenIdentity.samePage(original,changed));assertFalse(original.sameNativeContent(changed));
  assertNotEquals(original.contentFingerprint(),changed.contentFingerprint());
  assertEquals(ScreenIdentity.navigationFingerprint(original),ScreenIdentity.navigationFingerprint(changed));
 }
 @Test public void sliderValueIsResultEvidenceRatherThanNavigationIdentity() {
  ScreenSnapshot original=screen(1,"글자 크기",2f),changed=screen(1,"글자 크기",3f);
  assertTrue(ScreenIdentity.samePage(original,changed));assertFalse(original.sameNativeContent(changed));
  assertNotEquals(original.contentFingerprint(),changed.contentFingerprint());
  assertEquals(ScreenIdentity.navigationFingerprint(original),ScreenIdentity.navigationFingerprint(changed));
 }
 @Test public void absentIndependentHeadingNeverMeansKnownSamePage() {
  ScreenSnapshot empty=new ScreenSnapshot("com.android.settings","2026-11-09T00:00:00Z",1080,2400,List.of(),1);
  assertFalse(ScreenIdentity.samePage(empty,empty));assertNull(ScreenIdentity.pageTitle(empty));
 }
 @Test public void knownEmptySearchDoesNotHideARealTitleButTypedQueryStillDoes() {
  var title=node("title","디스플레이",20,false,1,null);
  for(String query:new String[]{null,"private query"}) {
   var input=new AccessibilityNodeSnapshot("search",null,true,"com.android.settings",query,null,"android:id/search_src_text",
    "android.widget.EditText","text_field",new int[]{20,100,900,180},true,true,false,false,false,null,1,true,null,null,false);
   var current=new ScreenSnapshot("com.android.settings","2026-11-18T00:00:00Z",1080,2400,List.of(title,input),1);
   assertEquals(query==null?"디스플레이":null,ScreenIdentity.pageTitle(current));
  }
 }
 @Test public void oldJavaStringHashCollisionCannotHideReplacedTarget() {
  assertEquals("Aa".hashCode(),"BB".hashCode());
  assertNotEquals(screen(1,"Aa",null).contentFingerprint(),screen(1,"BB",null).contentFingerprint());
  assertEquals(64,screen(1,"Aa",null).contentFingerprint().length());
 }
}
