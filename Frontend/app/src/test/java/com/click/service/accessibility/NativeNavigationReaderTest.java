package com.click.service.accessibility;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.NavigationState;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
public class NativeNavigationReaderTest {
 private AccessibilityNodeInfo node(String type,int width,int height) {
  AccessibilityNodeInfo n=mock(AccessibilityNodeInfo.class);
  when(n.getClassName()).thenReturn(type);when(n.isEnabled()).thenReturn(true);when(n.isVisibleToUser()).thenReturn(true);
  doAnswer(call->{((Rect)call.getArgument(0)).set(0,0,width,height);return null;}).when(n).getBoundsInScreen(any());
  return n;
 }
 private void children(AccessibilityNodeInfo parent,AccessibilityNodeInfo... children) {
  when(parent.getChildCount()).thenReturn(children.length);
  for(int i=0;i<children.length;i++)when(parent.getChild(i)).thenReturn(children[i]);
 }
 @Test public void selectsMainVerticalListInsteadOfNestedHorizontalCarouselAndSlider() {
  AccessibilityNodeInfo root=node("FrameLayout",1080,2400),list=node("RecyclerView",1080,2000),carousel=node("ViewPager",1000,200),slider=node("SeekBar",1080,2000);
  for(AccessibilityNodeInfo n:List.of(list,carousel,slider))when(n.isScrollable()).thenReturn(true);
  when(list.getActionList()).thenReturn(List.of(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP));
  when(carousel.getActionList()).thenReturn(List.of(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT));
  children(root,slider,carousel,list);
  NavigationState state=NativeNavigationReader.read(root);
  assertEquals("node_0_2",state.containerId);assertEquals(NavigationState.Axis.VERTICAL,state.axis);
 }
 @Test public void readsNativeItemIndicesAndEndpointsWithoutTransmittingLabels() {
  AccessibilityNodeInfo list=node("ListView",1080,2000),first=node("TextView",1000,100),last=node("TextView",1000,100);
  when(list.getCollectionInfo()).thenReturn(AccessibilityNodeInfo.CollectionInfo.obtain(30,1,false));
  when(first.getCollectionItemInfo()).thenReturn(AccessibilityNodeInfo.CollectionItemInfo.obtain(10,1,0,1,false));
  when(last.getCollectionItemInfo()).thenReturn(AccessibilityNodeInfo.CollectionItemInfo.obtain(15,1,0,1,false));
  when(first.getText()).thenReturn("row");children(list,first,last);
  NavigationState state=NativeNavigationReader.read(list);
  assertFalse(state.atStart());assertFalse(state.atEnd());assertEquals(10,state.firstIndex);assertEquals(15,state.lastIndex);
  when(first.getCollectionItemInfo()).thenReturn(AccessibilityNodeInfo.CollectionItemInfo.obtain(0,1,0,1,false));
  assertTrue(NativeNavigationReader.read(list).atStart());
  when(last.getCollectionItemInfo()).thenReturn(AccessibilityNodeInfo.CollectionItemInfo.obtain(29,1,0,1,false));
  assertTrue(NativeNavigationReader.read(list).atEnd());
  assertFalse(state.fingerprint.contains("row"));
 }
 @Test public void pagerHasHorizontalAxisAndChangingClockDoesNotPretendToBeANewPage() {
  AccessibilityNodeInfo pager=node("ViewPager",1080,2000),icon=node("TextView",200,200),clock=node("TextClock",200,100);
  when(icon.getText()).thenReturn("설정");when(clock.getText()).thenReturn("10:00");children(pager,icon,clock);
  NavigationState before=NativeNavigationReader.read(pager);assertEquals(NavigationState.Axis.HORIZONTAL,before.axis);
  when(clock.getText()).thenReturn("10:01");assertEquals(before.fingerprint,NativeNavigationReader.read(pager).fingerprint);
  when(icon.getText()).thenReturn("다른 페이지");assertNotEquals(before.fingerprint,NativeNavigationReader.read(pager).fingerprint);
 }
 @Test public void navigationMetadataSurvivesLocalCopiesButDoesNotEnterTheNetworkSnapshot() {
  NavigationState state=new NavigationState("private_container",NavigationState.Axis.VERTICAL,true,true,10,15,30,"local_only_hash");
  com.click.data.snapshot.ScreenSnapshot screen=new com.click.data.snapshot.ScreenSnapshot("com.android.settings",
    "2026-11-09T00:00:00Z",1080,2400,List.of(),5).withNavigation(state)
    .withScreenRevision("revision").withAdjustmentValues(100,5).withWifiState(3);
  assertSame(state,screen.navigation);
  String json=screen.toJson();assertFalse(json.contains("local_only_hash"));assertFalse(json.contains("private_container"));
  assertFalse(json.contains("navigation"));
 }
 @Test public void pageIndicesAndFingerprintShareOneContentTraversal() {
  AccessibilityNodeInfo list=node("ListView",1080,2000),item=node("TextView",1000,100);
  when(item.getText()).thenReturn("설정 항목");
  when(item.getCollectionItemInfo()).thenReturn(AccessibilityNodeInfo.CollectionItemInfo.obtain(5,1,0,1,false));
  children(list,item);
  NavigationState state=NativeNavigationReader.read(list);
  assertEquals(5,state.firstIndex);assertEquals(5,state.lastIndex);
  // One selection pass and one combined content pass; previously three full walks.
  verify(list,times(2)).getChild(0);
 }
 @Test public void ignoredLabelsStillContributeIndicesWithoutEnteringPageIdentity() {
  AccessibilityNodeInfo list=node("ListView",1080,2000),summary=node("TextView",1000,100),item=node("TextView",1000,100);
  when(summary.getViewIdResourceName()).thenReturn("settings:id/summary");
  when(item.getCollectionItemInfo()).thenReturn(AccessibilityNodeInfo.CollectionItemInfo.obtain(7,1,0,1,false));
  when(item.getText()).thenReturn("old status");children(summary,item);children(list,summary);
  NavigationState before=NativeNavigationReader.read(list);
  assertEquals(7,before.lastIndex);
  when(item.getText()).thenReturn("changed status");
  assertEquals(before.fingerprint,NativeNavigationReader.read(list).fingerprint);
 }
}
