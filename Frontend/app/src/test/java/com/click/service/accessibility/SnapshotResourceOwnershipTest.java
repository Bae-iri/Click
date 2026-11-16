package com.click.service.accessibility;

import android.app.Application;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class)
public class SnapshotResourceOwnershipTest {
    @Test public void failingChildReadReleasesItsHandleAndLeavesRootOwnershipWithCaller() {
        AccessibilityNodeInfo root=mock(AccessibilityNodeInfo.class),child=mock(AccessibilityNodeInfo.class);
        when(root.getChildCount()).thenReturn(1);when(root.getChild(0)).thenReturn(child);
        when(root.getClassName()).thenReturn("android.widget.FrameLayout");
        doAnswer(call->{((Rect)call.getArgument(0)).set(0,0,1080,2400);return null;})
            .when(root).getBoundsInScreen(any(Rect.class));
        doThrow(new IllegalStateException("synthetic stale child")).when(child).getBoundsInScreen(any(Rect.class));
        assertThrows(IllegalStateException.class,()->new AccessibilitySnapshotCollector().collect(root,
            "com.android.settings",1080,2400,null));
        verify(child).recycle();
        verify(root,never()).recycle();
    }
}
