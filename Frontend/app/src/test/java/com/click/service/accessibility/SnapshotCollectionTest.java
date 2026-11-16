package com.click.service.accessibility;

import android.app.Application;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class SnapshotCollectionTest {
    @Test public void childrenAreReleasedAndCallerRetainsOwnershipOfRoot() {
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo child = mock(AccessibilityNodeInfo.class);
        when(root.getChildCount()).thenReturn(2);
        when(root.getChild(0)).thenReturn(child);
        // A node can disappear between getChildCount() and getChild().
        new AccessibilitySnapshotCollector().collect(root, "com.android.settings", 1080, 2400, null);
        verify(child).recycle();
        verify(root, never()).recycle();
    }

    @Test public void childrenAreReleasedEvenIfTheirReadFails() {
        AccessibilityNodeInfo root = mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo child = mock(AccessibilityNodeInfo.class);
        when(root.getChildCount()).thenReturn(1);
        when(root.getChild(0)).thenReturn(child);
        when(child.getClassName()).thenThrow(new IllegalStateException("detached"));
        assertThrows(IllegalStateException.class, () ->
                new AccessibilitySnapshotCollector().collect(root, "com.android.settings", 1080, 2400, null));
        verify(child).recycle();
        verify(root, never()).recycle();
    }
}
