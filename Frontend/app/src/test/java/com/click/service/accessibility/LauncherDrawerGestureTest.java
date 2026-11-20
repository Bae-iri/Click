package com.click.service.accessibility;

import android.accessibilityservice.*;
import android.graphics.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LauncherDrawerGestureTest {
    @Test public void opensDrawerBySwipingUpRatherThanPagingHomeHorizontally(){
        AccessibilityService service=mock(AccessibilityService.class);AccessibilityNodeInfo root=mock(AccessibilityNodeInfo.class);
        when(service.getPackageName()).thenReturn("com.clueclick.app");
        when(service.getRootInActiveWindow()).thenReturn(root);when(service.getWindows()).thenReturn(List.of());
        when(root.getPackageName()).thenReturn("com.sec.android.app.launcher");when(root.getWindowId()).thenReturn(7);
        doAnswer(i->{((Rect)i.getArgument(0)).set(0,0,1080,2400);return null;}).when(root).getBoundsInScreen(any());
        when(service.dispatchGesture(any(),any(),any())).thenReturn(true);
        assertTrue(new NativeScrollExecutor(service,null,()->{}).openAppDrawer("com.sec.android.app.launcher",7));
        var capture=ArgumentCaptor.forClass(GestureDescription.class);verify(service).dispatchGesture(capture.capture(),any(),isNull());
        var stroke=capture.getValue().getStroke(0);var measure=new PathMeasure(stroke.getPath(),false);float[] start=new float[2],end=new float[2];
        measure.getPosTan(0,start,null);measure.getPosTan(measure.getLength(),end,null);
        assertEquals(start[0],end[0],1);assertTrue(end[1]<start[1]);assertEquals(420L,stroke.getDuration());
    }
    @Test public void changedForegroundWindowNeverReceivesDrawerGesture(){
        AccessibilityService service=mock(AccessibilityService.class);AccessibilityNodeInfo root=mock(AccessibilityNodeInfo.class);
        when(service.getPackageName()).thenReturn("com.clueclick.app");
        when(service.getWindows()).thenReturn(List.of());when(service.getRootInActiveWindow()).thenReturn(root);
        when(root.getPackageName()).thenReturn("com.bank");when(root.getWindowId()).thenReturn(8);
        assertFalse(new NativeScrollExecutor(service,null,()->{}).openAppDrawer("com.sec.android.app.launcher",7));
        verify(service,never()).dispatchGesture(any(),any(),any());
    }
}
