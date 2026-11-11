package com.click.service.accessibility;

import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.local.TouchAutomationSettings;
import com.click.data.snapshot.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class GuidedSliderExecutionTest {
 private ClickAccessibilityService service;
 private AccessibilityNodeInfo root, node;
 private ScreenSnapshot screen;
 private final int[] bounds={0,100,500,200};
 private MockedStatic<ClickAccessibilityService> calls;
 private void field(String name,Object value) throws Exception {
  Field f=ClickAccessibilityService.class.getDeclaredField(name);f.setAccessible(true);f.set(service,value);
 }
 @Before public void setup() throws Exception {
  service=spy(Robolectric.buildService(ClickAccessibilityService.class).create().get());
  field("connectedService",new WeakReference<>(service));
  field("mainHandler",new Handler(Looper.getMainLooper()));
  new TouchAutomationSettings(service).setEnabled(true);
  root=mock(AccessibilityNodeInfo.class);node=mock(AccessibilityNodeInfo.class);
  doReturn(root).when(service).getRootInActiveWindow();when(root.getChild(1)).thenReturn(node);
  when(node.getPackageName()).thenReturn("com.android.settings");
  when(node.getClassName()).thenReturn("android.widget.SeekBar");
  when(node.getViewIdResourceName()).thenReturn("com.android.settings:id/font_size");
  when(node.getWindowId()).thenReturn(5);when(node.isEnabled()).thenReturn(true);when(node.isVisibleToUser()).thenReturn(true);
  doAnswer(i->{ ((Rect)i.getArgument(0)).set(0,100,500,200);return null; }).when(node).getBoundsInScreen(any());
  when(node.getRangeInfo()).thenReturn(AccessibilityNodeInfo.RangeInfo.obtain(0,0,7,2));
  when(node.getActionList()).thenReturn(Arrays.asList(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD,AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD));
  AccessibilityNodeSnapshot target=new AccessibilityNodeSnapshot("node_0_1","node_0",true,"com.android.settings",null,null,"com.android.settings:id/font_size","android.widget.SeekBar","slider",bounds,false,true,false,false,false,2f,5,false,0f,7f);
  screen=new ScreenSnapshot("com.android.settings","2026-10-28T00:00:00Z",1080,2400,Arrays.asList(target),5);
  calls=mockStatic(ClickAccessibilityService.class,CALLS_REAL_METHODS);
  calls.when(ClickAccessibilityService::freshSnapshot).thenAnswer(i -> screen);
 }
 @After public void close() throws Exception { calls.close();field("connectedService",new WeakReference<>(null)); }
 @Test public void executesNativeIncreaseAndDecrease() {
  when(node.performAction(anyInt())).thenReturn(true);
  assertTrue(ClickAccessibilityService.performGuidedSlider(screen,"node_0_1",bounds,1));
  verify(node).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
  assertTrue(ClickAccessibilityService.performGuidedSlider(screen,"node_0_1",bounds,-1));
  verify(node).performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
 }
 @Test public void offNeverExecutesSlider() {
  new TouchAutomationSettings(service).setEnabled(false);
  assertFalse(ClickAccessibilityService.performGuidedSlider(screen,"node_0_1",bounds,1));
  verify(node,never()).performAction(anyInt());
 }
 @Test public void delegatedAdjustmentRejectsNonFiniteAndTooSmallAllowances() {
  for(float allowance:new float[]{Float.NaN,Float.POSITIVE_INFINITY,0f,-1f,0.1f})
   assertFalse(ClickAccessibilityService.performGuidedSlider(screen,"node_0_1",bounds,1,"font_size",allowance));
  verify(node,never()).performAction(anyInt());
  verify(node,never()).performAction(anyInt(),any());
  verify(service,never()).dispatchGesture(any(),any(),any());
 }
 @Test public void delegatedAdjustmentSetsABoundedValueWithoutUnboundedScrollFallback() {
  when(node.getActionList()).thenReturn(Arrays.asList(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS,
      AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD));
  when(node.performAction(eq(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()),any())).thenReturn(true);
  assertTrue(ClickAccessibilityService.performGuidedSlider(screen,"node_0_1",bounds,1,"font_size",0.2f));
  var args=org.mockito.ArgumentCaptor.forClass(android.os.Bundle.class);
  verify(node).performAction(eq(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()),args.capture());
  assertEquals(3f,args.getValue().getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE),0f);
  verify(node,never()).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
 }
 @Test public void resultPollingSurvivesMissingRootAndRecreatedWindow() {
  AccessibilityNodeSnapshot original=new AccessibilityNodeSnapshot("node_0_1","node_0",true,"com.android.settings",null,null,
      "com.android.settings:id/font_size","android.widget.SeekBar","slider",bounds,false,true,false,false,false,2f,5,false,0f,7f);
  screen=new ScreenSnapshot("com.android.settings",java.time.Instant.now().toString(),1080,2400,Arrays.asList(original),5,true,1f);
  when(node.getViewIdResourceName()).thenReturn(original.resourceId);
  when(node.performAction(anyInt())).thenReturn(true);
  try (MockedStatic<com.click.service.overlay.ClickOverlayService> events=mockStatic(com.click.service.overlay.ClickOverlayService.class)) {
   assertTrue(ClickAccessibilityService.performGuidedSlider(screen,original.nodeId,bounds,1));
   calls.when(ClickAccessibilityService::freshSnapshot).thenReturn(null);
   org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
   AccessibilityNodeSnapshot after=new AccessibilityNodeSnapshot("node_0_9","node_0",true,"com.android.settings",null,null,
       original.resourceId,"android.widget.SeekBar","slider",bounds,false,true,false,false,false,3f,8,false,0f,7f);
   calls.when(ClickAccessibilityService::freshSnapshot).thenAnswer(i -> new ScreenSnapshot("com.android.settings",
       java.time.Instant.now().plusMillis(1000).toString(),1080,2400,Arrays.asList(after),8,true,1.2f));
   org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
   events.verify(() -> com.click.service.overlay.ClickOverlayService.onVerifiedAutomaticAction(eq("drag_completed"),
       eq(original.packageName),eq(5),eq(original.nodeId),eq(original.resourceId),eq(bounds),anyLong()));
  }
 }
 @Test public void changedValueAndUnsupportedActionsAreRejected() {
  when(node.getRangeInfo()).thenReturn(AccessibilityNodeInfo.RangeInfo.obtain(0,0,7,3));
  assertFalse(ClickAccessibilityService.performGuidedSlider(screen,"node_0_1",bounds,1));
  when(node.getRangeInfo()).thenReturn(AccessibilityNodeInfo.RangeInfo.obtain(0,0,7,2));
  when(node.getActionList()).thenReturn(Collections.emptyList());
  doReturn(false).when(service).dispatchGesture(any(),any(),any());
  assertFalse(ClickAccessibilityService.performGuidedSlider(screen,"node_0_1",bounds,1));
  verify(node,never()).performAction(anyInt());
 }
 private void navigation(boolean nested) {
  AccessibilityNodeSnapshot label = new AccessibilityNodeSnapshot(nested ? "node_0_1_0" : "node_0_1",nested ? "node_0_1" : "node_0",true,"com.android.settings","디스플레이",null,null,"android.widget.TextView",null,bounds,!nested,true,false,false,false,null,5);
  AccessibilityNodeSnapshot parent = new AccessibilityNodeSnapshot("node_0_1","node_0",true,"com.android.settings",null,null,null,"android.widget.TextView",null,bounds,true,true,false,false,false,null,5);
  screen=new ScreenSnapshot("com.android.settings","2026-10-28T00:00:00Z",1080,2400,nested ? Arrays.asList(label,parent) : Arrays.asList(label),5);
  when(node.getClassName()).thenReturn("android.widget.TextView");
  when(node.getViewIdResourceName()).thenReturn(null);
  when(node.getText()).thenReturn(nested ? null : "디스플레이");
  when(node.isClickable()).thenReturn(true);
  when(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true);
 }
 @Test public void clicksSafeNavigationRowForNestedLabel() {
  navigation(true);
  assertTrue(ClickAccessibilityService.performGuidedTap(screen,"node_0_1_0",bounds));
  verify(node).performAction(AccessibilityNodeInfo.ACTION_CLICK);
 }
 @Test public void doesNotClickLiveTargetWithChangedLabel() {
  navigation(false);when(node.getText()).thenReturn("초기화");
  assertFalse(ClickAccessibilityService.performGuidedTap(screen,"node_0_1",bounds));
  verify(node,never()).performAction(anyInt());
 }
 @Test public void offAlsoBlocksNavigationTap() {
  navigation(false);new TouchAutomationSettings(service).setEnabled(false);
  assertFalse(ClickAccessibilityService.performGuidedTap(screen,"node_0_1",bounds));
  verify(node,never()).performAction(anyInt());
 }
 @Test public void customSliderWithOnlySetProgressActuallyReceivesNextValue() {
  AccessibilityNodeSnapshot n=new AccessibilityNodeSnapshot("node_0_1","node_0",true,"com.android.settings",null,null,"com.android.settings:id/font_size","android.widget.SemSeekBar","slider",bounds,false,true,false,false,false,2f,5,false,0f,7f);
  screen=new ScreenSnapshot("com.android.settings","2026-10-28T00:00:00Z",1080,2400,Arrays.asList(n),5);
  when(node.getClassName()).thenReturn("android.widget.SemSeekBar");
  when(node.getActionList()).thenReturn(Arrays.asList(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS));
  when(node.performAction(eq(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()),any())).thenReturn(true);
  assertTrue(ClickAccessibilityService.performGuidedSlider(screen,"node_0_1",bounds,1));
  org.mockito.ArgumentCaptor<android.os.Bundle> args=org.mockito.ArgumentCaptor.forClass(android.os.Bundle.class);
  verify(node).performAction(eq(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()),args.capture());
  assertEquals(3f,args.getValue().getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE),0f);
 }
 @Test public void rejectedNativeClickUsesActualTapGesture() {
  navigation(false);when(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(false);
  doReturn(true).when(service).dispatchGesture(any(),any(),any());
  assertTrue(ClickAccessibilityService.performGuidedTap(screen,"node_0_1",bounds));
  verify(service).dispatchGesture(any(android.accessibilityservice.GestureDescription.class),any(),any());
 }
 @Test public void unsupportedSliderActionsUseActualDragGesture() {
  when(node.getActionList()).thenReturn(Collections.emptyList());
  doReturn(true).when(service).dispatchGesture(any(),any(),any());
  assertTrue(ClickAccessibilityService.performGuidedSlider(screen,"node_0_1",bounds,1));
  verify(service).dispatchGesture(any(android.accessibilityservice.GestureDescription.class),any(),any());
 }
 @Test public void delayedSliderUpdateIsVerifiedAfterMoreThanOneCapture() {
  calls.when(ClickAccessibilityService::requestFreshSnapshot).thenAnswer(i->null);
  when(node.performAction(anyInt())).thenReturn(true);
  try (org.mockito.MockedStatic<com.click.service.overlay.ClickOverlayService> overlay=mockStatic(com.click.service.overlay.ClickOverlayService.class)) {
   assertTrue(ClickAccessibilityService.performGuidedSlider(screen,"node_0_1",bounds,1));
   org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
   overlay.verifyNoInteractions();
   AccessibilityNodeSnapshot changed=new AccessibilityNodeSnapshot("node_0_1","node_0",true,"com.android.settings",null,null,"com.android.settings:id/font_size","android.widget.SeekBar","slider",bounds,false,true,false,false,false,3f,5,false,0f,7f);
   screen=new ScreenSnapshot("com.android.settings",java.time.Instant.now().toString(),1080,2400,Arrays.asList(changed),5);
   org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
   overlay.verify(()->com.click.service.overlay.ClickOverlayService.onVerifiedAutomaticAction(eq("drag_completed"),eq("com.android.settings"),eq(5),eq("node_0_1"),eq("com.android.settings:id/font_size"),any(),anyLong()));
  }
 }
 private void splitNavigation(int switchLeft) {
  int[] rowBounds={0,100,1080,300};int[] textBounds={30,140,700,220};
  var page=new AccessibilityNodeSnapshot("node_0_0","node_0",true,"com.android.settings","연결",null,null,"android.widget.TextView","text",new int[]{0,0,900,80},false,true,false,false,false,null,5);
  var row=new AccessibilityNodeSnapshot("node_0_1","node_0",true,"com.android.settings",null,null,null,"android.widget.LinearLayout","menu_item",rowBounds,true,true,false,false,false,null,5);
  var title=new AccessibilityNodeSnapshot("node_0_1_0","node_0_1",true,"com.android.settings","블루투스",null,"android:id/title","android.widget.TextView","text",textBounds,false,true,false,false,false,null,5);
  var toggle=new AccessibilityNodeSnapshot("node_0_1_1","node_0_1",true,"com.android.settings",null,null,"android:id/switch_widget","android.widget.Switch","switch",new int[]{switchLeft,140,1040,220},true,true,false,false,true,null,5);
  var rootSnapshot=new AccessibilityNodeSnapshot("node_0",null,true,"com.android.settings",null,null,null,"android.widget.FrameLayout","container",new int[]{0,0,1080,2400},false,true,false,false,false,null,5);
  screen=new ScreenSnapshot("com.android.settings","now",1080,2400,List.of(page,row,title,toggle,rootSnapshot),5);
  AccessibilityNodeInfo titleNode=mock(AccessibilityNodeInfo.class);when(node.getChild(0)).thenReturn(titleNode);
  when(titleNode.getPackageName()).thenReturn("com.android.settings");when(titleNode.getWindowId()).thenReturn(5);
  when(titleNode.getText()).thenReturn("블루투스");when(titleNode.getClassName()).thenReturn("android.widget.TextView");
  when(titleNode.getViewIdResourceName()).thenReturn("android:id/title");when(titleNode.isEnabled()).thenReturn(true);when(titleNode.isVisibleToUser()).thenReturn(true);
  doAnswer(c->{((Rect)c.getArgument(0)).set(30,140,700,220);return null;}).when(titleNode).getBoundsInScreen(any());
  doReturn(true).when(service).dispatchGesture(any(),any(),any());
 }
 @Test public void mixedMenuGestureHitsTheNativeTitleAndNeverClicksTheParentOrSwitch() {
  splitNavigation(850);
  assertTrue(ClickAccessibilityService.performGuidedNavigationTap(screen,"node_0_1",screen.nodes.get(1).bounds,"settings.bluetooth.enable","S_CATEGORY"));
  org.mockito.ArgumentCaptor<android.accessibilityservice.GestureDescription> gesture=org.mockito.ArgumentCaptor.forClass(android.accessibilityservice.GestureDescription.class);
  verify(service).dispatchGesture(gesture.capture(),any(),any());
  android.graphics.RectF hit=new android.graphics.RectF();gesture.getValue().getStroke(0).getPath().computeBounds(hit,true);
  assertEquals(365f,hit.left,.1f);assertEquals(180f,hit.top,.1f);assertTrue(hit.right<850);
  verify(node,never()).performAction(anyInt());
 }
 @Test public void mixedMenuCannotDispatchWhenTheSwitchOverlapsTheTitle() {
  splitNavigation(400);
  assertFalse(ClickAccessibilityService.performGuidedNavigationTap(screen,"node_0_1",screen.nodes.get(1).bounds,"settings.bluetooth.enable","S_CATEGORY"));
  verify(service,never()).dispatchGesture(any(),any(),any());verify(node,never()).performAction(anyInt());
 }

}
