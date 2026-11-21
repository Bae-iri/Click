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
    @Test public void unrecognizedClickableItemIsNotTreatedAsFolder(){
        nativeIcon("TextView");var s=screen(icon("Shop","TextView"));assertFalse(execute(s,s,LauncherAppSearch.Kind.TAP_FOLDER));verify(root,never()).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
}
