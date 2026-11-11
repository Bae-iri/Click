package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class)
public class NativeTargetRevealGeometryTest {
    private ClickAccessibilityService service;
    private AccessibilityNodeInfo root, row;
    private MockedStatic<ForegroundWindowReader> reader;
    private NativeScrollExecutor executor;
    private final int[] bounds={0,2000,1080,2203};

    private AccessibilityNodeInfo node(String type,int top,int bottom) {
        var node=mock(AccessibilityNodeInfo.class);
        when(node.getPackageName()).thenReturn("com.android.settings");
        when(node.getWindowId()).thenReturn(42);
        when(node.getClassName()).thenReturn(type);
        when(node.isVisibleToUser()).thenReturn(true);
        when(node.isEnabled()).thenReturn(true);
        doAnswer(c->{((Rect)c.getArgument(0)).set(0,top,1080,bottom);return null;})
                .when(node).getBoundsInScreen(any());
        return node;
    }
    @Before public void setup() {
        service=spy(Robolectric.buildService(ClickAccessibilityService.class).get());
        var metrics=service.getResources().getDisplayMetrics();
        metrics.widthPixels=1080;metrics.heightPixels=2173;metrics.density=2.8125f;
        var manager=mock(WindowManager.class);
        when(manager.getCurrentWindowMetrics()).thenReturn(new WindowMetrics(
                new Rect(0,0,1080,2400),WindowInsets.CONSUMED));
        doReturn(manager).when(service).getSystemService(WindowManager.class);
        root=node("androidx.recyclerview.widget.RecyclerView",317,2265);
        when(root.isScrollable()).thenReturn(true);
        when(root.getActionList()).thenReturn(List.of(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD,
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD));
        row=node("android.widget.LinearLayout",bounds[1],bounds[3]);
        when(row.isClickable()).thenReturn(true);
        when(root.getChildCount()).thenReturn(1);when(root.getChild(0)).thenReturn(row);
        doReturn(List.of()).when(service).getWindows();
        doReturn(true).when(service).dispatchGesture(any(),any(),any());
        reader=mockStatic(ForegroundWindowReader.class);
        reader.when(()->ForegroundWindowReader.readRoot(service)).thenReturn(root);
        executor=new NativeScrollExecutor(service,new Handler(Looper.getMainLooper()),()->{});
    }
    @After public void close() {if(reader!=null)reader.close();}

    @Test public void bottomRowUsesTheSameWindowCoordinatesAsObservation() {
        assertTrue("The 2203px row must not be clipped to the 2173px resource height",
                executor.revealSettingsTarget("node_0_0",bounds,"com.android.settings",42));
        verify(service).dispatchGesture(any(),any(),any());
        verify(row,never()).performAction(anyInt());
    }
    @Test public void changedTargetBoundsStillRejectTheGesture() {
        assertFalse(executor.revealSettingsTarget("node_0_0",new int[]{0,1990,1080,2193},"com.android.settings",42));
        verify(service,never()).dispatchGesture(any(),any(),any());
    }
    @Test public void changedWindowStillRejectsTheGesture() {
        assertFalse(executor.revealSettingsTarget("node_0_0",bounds,"com.android.settings",43));
        verify(service,never()).dispatchGesture(any(),any(),any());
    }
}
