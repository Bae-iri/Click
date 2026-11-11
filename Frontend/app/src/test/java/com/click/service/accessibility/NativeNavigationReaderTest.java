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
 @Test public void samsungWorkspacePagedViewIsHorizontal() {
  AccessibilityNodeInfo pager=node("com.android.launcher3.PagedView",1080,2000);
  when(pager.isScrollable()).thenReturn(true);
  when(pager.getPackageName()).thenReturn("com.sec.android.app.launcher");
  when(pager.getViewIdResourceName()).thenReturn("com.sec.android.app.launcher:id/workspace");
  when(pager.getActionList()).thenReturn(List.of(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD));
  assertEquals(NavigationState.Axis.HORIZONTAL,NativeNavigationReader.read(pager).axis);
 }
 @Test public void nestedIconGridIndicesAreNotLauncherPageEndpoints() {
  AccessibilityNodeInfo pager=node("ViewPager",1080,2000),grid=node("GridLayout",1000,1600);
  when(pager.getCollectionInfo()).thenReturn(AccessibilityNodeInfo.CollectionInfo.obtain(1,5,false));
  when(grid.getCollectionInfo()).thenReturn(AccessibilityNodeInfo.CollectionInfo.obtain(5,5,false));
  when(grid.getCollectionItemInfo()).thenReturn(AccessibilityNodeInfo.CollectionItemInfo.obtain(0,1,2,1,false));
  AccessibilityNodeInfo first=node("TextView",100,100),last=node("TextView",100,100);
  when(first.getCollectionItemInfo()).thenReturn(AccessibilityNodeInfo.CollectionItemInfo.obtain(0,1,0,1,false));
  when(last.getCollectionItemInfo()).thenReturn(AccessibilityNodeInfo.CollectionItemInfo.obtain(4,1,4,1,false));
  children(pager,grid);children(grid,first,last);
  NavigationState state=NativeNavigationReader.read(pager);
  assertEquals(2,state.firstIndex);assertEquals(2,state.lastIndex);
  assertFalse(state.atStart());assertFalse(state.atEnd());
 }
 @Test public void launcherWidgetCannotHijackPageSearch() {
  AccessibilityNodeInfo root=node("FrameLayout",1080,2400),pager=node("ViewPager",1080,2000);
  AccessibilityNodeInfo widget=node("android.appwidget.AppWidgetHostView",1080,1800),list=node("RecyclerView",1080,1700);
  when(root.getPackageName()).thenReturn("com.sec.android.app.launcher");
  when(list.isScrollable()).thenReturn(true);
  when(list.getActionList()).thenReturn(List.of(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN));
  children(root,widget,pager);children(widget,list);
  NavigationState state=NativeNavigationReader.read(root);
  assertEquals("node_0_1",state.containerId);assertEquals(NavigationState.Axis.HORIZONTAL,state.axis);
 }
 @Test public void layoutAnimationIsNotEvidenceOfANewPage() {
  AccessibilityNodeInfo pager=node("ViewPager",1080,2000),icon=node("TextView",200,200);
  when(icon.getText()).thenReturn("설정");children(pager,icon);
  String before=NativeNavigationReader.read(pager).fingerprint;
  doAnswer(call->{((Rect)call.getArgument(0)).set(5,0,205,200);return null;}).when(icon).getBoundsInScreen(any());
  assertEquals(before,NativeNavigationReader.read(pager).fingerprint);
 }

 @Test public void genericAppsAndWidgetsWrapperDoesNotHideANonScrollablePager() {
  AccessibilityNodeInfo root=node("FrameLayout",1080,2400),pager=node("com.android.launcher3.PagedView",1080,2000);
  when(root.getPackageName()).thenReturn("com.sec.android.app.launcher");
  when(root.getViewIdResourceName()).thenReturn("com.sec.android.app.launcher:id/apps_and_widgets");
  when(pager.isScrollable()).thenReturn(false);
  children(root,pager);
  NavigationState state=NativeNavigationReader.read(root);
  assertEquals("node_0_0",state.containerId);assertEquals(NavigationState.Axis.HORIZONTAL,state.axis);
 }

}
