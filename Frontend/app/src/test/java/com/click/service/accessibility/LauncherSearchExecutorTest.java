package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.*;
import com.click.service.overlay.LauncherAppSearch;
import java.time.Instant;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
public class LauncherSearchExecutorTest {
    private final String pkg="com.sec.android.app.launcher";
    private final AccessibilityService service=mock(AccessibilityService.class);
    private final AccessibilityNodeInfo root=mock(AccessibilityNodeInfo.class);
    private AccessibilityNodeSnapshot icon(String name,String cls){
        return new AccessibilityNodeSnapshot("node_0",null,true,pkg,name,name,"icon",cls,"button",new int[]{100,200,240,340},true,true,false,false,false,null,7);
    }
    private ScreenSnapshot screen(AccessibilityNodeSnapshot...nodes){return new ScreenSnapshot(pkg,Instant.now().toString(),1080,2400,List.of(nodes),7);}
    private void nativeIcon(String cls){
        when(root.getPackageName()).thenReturn(pkg);when(root.getWindowId()).thenReturn(7);
        when(root.getClassName()).thenReturn(cls);when(root.getViewIdResourceName()).thenReturn("icon");
        when(root.isEnabled()).thenReturn(true);when(root.isClickable()).thenReturn(true);when(root.isVisibleToUser()).thenReturn(true);
        doAnswer(i->{((Rect)i.getArgument(0)).set(100,200,240,340);return null;}).when(root).getBoundsInScreen(any());
        when(root.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true);
    }
    private boolean execute(ScreenSnapshot before,ScreenSnapshot fresh,LauncherAppSearch.Kind kind){
        return LauncherSearchExecutor.execute(service,null,()->{},root,before,fresh,new LauncherAppSearch.Step(kind,before.nodes.isEmpty()?null:before.nodes.get(0),true,"test"),Set.of("카메라"));
    }
    @Test public void matchingNativeAppIsClicked(){nativeIcon("TextView");var s=screen(icon("카메라","TextView"));assertTrue(execute(s,s,LauncherAppSearch.Kind.TAP_APP));verify(root).performAction(AccessibilityNodeInfo.ACTION_CLICK);}
    @Test public void onlyFolderWithCurrentTargetPreviewCanBeOpened(){
        nativeIcon("FolderIcon");
        var folder=icon("도구","FolderIcon");
        var preview=new AccessibilityNodeSnapshot("node_0_0","node_0",true,pkg,"카메라",null,"icon","TextView","button",
                new int[]{120,220,150,250},true,true,false,false,false,null,7);
        var confirmed=screen(folder,preview);
        assertFalse(execute(screen(folder),screen(folder),LauncherAppSearch.Kind.TAP_FOLDER));
        assertFalse(execute(confirmed,screen(folder),LauncherAppSearch.Kind.TAP_FOLDER));
        assertTrue(execute(confirmed,confirmed,LauncherAppSearch.Kind.TAP_FOLDER));
        verify(root,times(1)).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    @Test public void changedLayoutOrWindowDoesNotClickOldCoordinates(){
        nativeIcon("TextView");var s=screen(icon("카메라","TextView"));
        assertFalse(execute(s,screen(icon("시계","TextView")),LauncherAppSearch.Kind.TAP_APP));
        assertFalse(execute(s,new ScreenSnapshot(pkg,Instant.now().toString(),1080,2400,s.nodes,8),LauncherAppSearch.Kind.TAP_APP));
        verify(root,never()).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    @Test public void freshNativeNodeMustStillBeVisibleAndAtObservedBounds(){
        nativeIcon("TextView");var s=screen(icon("카메라","TextView"));when(root.isVisibleToUser()).thenReturn(false);
        assertFalse(execute(s,s,LauncherAppSearch.Kind.TAP_APP));when(root.isVisibleToUser()).thenReturn(true);
        doAnswer(i->{((Rect)i.getArgument(0)).set(300,400,440,540);return null;}).when(root).getBoundsInScreen(any());
        assertFalse(execute(s,s,LauncherAppSearch.Kind.TAP_APP));verify(root,never()).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    @Test public void deviceLockAndPasswordStopNativeActions(){
        nativeIcon("TextView");var s=screen(icon("카메라","TextView"));var lock=mock(KeyguardManager.class);
        when(service.getSystemService(KeyguardManager.class)).thenReturn(lock);when(lock.isDeviceLocked()).thenReturn(true);
        assertFalse(execute(s,s,LauncherAppSearch.Kind.TAP_APP));when(lock.isDeviceLocked()).thenReturn(false);when(root.isPassword()).thenReturn(true);
        assertFalse(execute(s,s,LauncherAppSearch.Kind.TAP_APP));verify(root,never()).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    @Test public void backIsForbiddenOutsideAnObservedOpenFolder(){
        var s=screen(icon("시계","TextView"));assertFalse(execute(s,s,LauncherAppSearch.Kind.BACK));verify(service,never()).performGlobalAction(anyInt());
    }
    @Test public void scrollTargetsOnlyTheLiveDrawerPagerEvenWhenHomeSharesItsWindow(){
        String drawer=pkg+":id/apps_content", home=pkg+":id/workspace", type="com.android.launcher3.PagedView";
        var pager=new AccessibilityNodeSnapshot("node_0",null,true,pkg,null,null,drawer,type,"container",new int[]{0,0,1080,2250},
                false,true,false,true,false,null,7);
        var snapshot=screen(pager).withNavigation(new NavigationState("node_0",NavigationState.Axis.HORIZONTAL,false,true,-1,-1,-1,"page"));
        when(root.getPackageName()).thenReturn(pkg);when(root.getWindowId()).thenReturn(7);when(root.getClassName()).thenReturn(type);
        when(root.isEnabled()).thenReturn(true);when(root.isVisibleToUser()).thenReturn(true);when(root.isScrollable()).thenReturn(true);
        when(root.getActionList()).thenReturn(List.of(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD));
        when(root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)).thenReturn(true);
        when(root.getViewIdResourceName()).thenReturn(home);
        assertFalse(execute(snapshot,snapshot,LauncherAppSearch.Kind.SCROLL));
        assertFalse(execute(snapshot,screen(icon("시계","TextView")),LauncherAppSearch.Kind.SCROLL));
        verify(root,never()).performAction(anyInt());
        when(root.getViewIdResourceName()).thenReturn(drawer);
        assertTrue(execute(snapshot,snapshot,LauncherAppSearch.Kind.SCROLL));
        verify(root).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        verify(service,never()).dispatchGesture(any(),any(),any());
    }
    @Test public void unrecognizedClickableItemIsNotTreatedAsFolder(){
        nativeIcon("TextView");var s=screen(icon("Shop","TextView"));assertFalse(execute(s,s,LauncherAppSearch.Kind.TAP_FOLDER));verify(root,never()).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    @Test public void flattenedOneUiTrayCanOnlyPageItsOwnLiveContent() {
        String trayId=pkg+":id/apptray_view", pagerId=pkg+":id/apps_content", type="android.widget.FrameLayout";
        var frame=new AccessibilityNodeSnapshot("node_0",null,true,pkg,null,null,null,type,"container",
                new int[]{0,0,1080,2400},false,true,false,false,false,null,7);
        var tray=new AccessibilityNodeSnapshot("node_0_0","node_0",true,pkg,null,null,trayId,type,"container",
                new int[]{0,50,1080,2250},false,true,false,false,false,null,7);
        var compact=screen(frame,tray).withNavigation(new NavigationState("node_0",NavigationState.Axis.UNKNOWN,null,null,-1,-1,-1,"icons"));
        var nativeTray=mock(AccessibilityNodeInfo.class);
        when(root.getChildCount()).thenReturn(1);
        when(root.getChild(0)).thenReturn(nativeTray);
        when(nativeTray.getPackageName()).thenReturn(pkg);when(nativeTray.getWindowId()).thenReturn(7);
        when(nativeTray.getClassName()).thenReturn(type);when(nativeTray.getViewIdResourceName()).thenReturn(trayId);
        when(nativeTray.isVisibleToUser()).thenReturn(true);when(nativeTray.isEnabled()).thenReturn(true);
        var pager=mock(AccessibilityNodeInfo.class);
        when(nativeTray.findAccessibilityNodeInfosByViewId(pagerId)).thenReturn(List.of(pager));
        when(pager.getPackageName()).thenReturn(pkg);when(pager.getWindowId()).thenReturn(7);
        when(pager.getClassName()).thenReturn("com.android.launcher3.PagedView");when(pager.isScrollable()).thenReturn(true);
        when(pager.isVisibleToUser()).thenReturn(true);when(pager.isEnabled()).thenReturn(true);
        var right=AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_RIGHT;
        when(pager.getActionList()).thenReturn(List.of(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD,right));
        when(pager.performAction(right.getId())).thenReturn(true);
        when(pager.getViewIdResourceName()).thenReturn(pkg+":id/workspace");
        assertFalse(execute(compact,compact,LauncherAppSearch.Kind.SCROLL));
        verify(pager,never()).performAction(anyInt());
        when(pager.getViewIdResourceName()).thenReturn(pagerId);
        try(var accessibility=mockStatic(ClickAccessibilityService.class)){
            accessibility.when(ClickAccessibilityService::freshSnapshot).thenReturn(screen(icon("시계","TextView")));
            when(pager.performAction(right.getId())).thenReturn(false);
            assertFalse(execute(compact,compact,LauncherAppSearch.Kind.SCROLL));
            verify(service,never()).dispatchGesture(any(),any(),any());
        }
        when(pager.performAction(right.getId())).thenReturn(true);
        assertTrue(execute(compact,compact,LauncherAppSearch.Kind.SCROLL));
        verify(pager,times(2)).performAction(right.getId());
        verify(service,never()).dispatchGesture(any(),any(),any());
        when(pager.getActionList()).thenReturn(List.of(AccessibilityNodeInfo.AccessibilityAction.ACTION_PAGE_LEFT));
        var lastPage=NativeNavigationReader.compactDrawerNavigation(root,compact);
        assertEquals(tray.nodeId,lastPage.containerId);
        assertTrue(lastPage.atEnd());assertFalse(lastPage.atStart());
        var search=new LauncherAppSearch(Set.of("카메라"));
        var previous=search.next(compact.withNavigation(lastPage));
        assertEquals(LauncherAppSearch.Kind.SCROLL,previous.kind());assertFalse(previous.forward());
    }
}
