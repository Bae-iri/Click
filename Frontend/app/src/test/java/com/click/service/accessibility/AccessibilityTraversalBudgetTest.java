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
@Config(sdk=34,application=Application.class)
public class AccessibilityTraversalBudgetTest {
    @Test public void depthLimitDiscardsTheWholeSnapshotAndKeepsRootOwnership() {
        AccessibilityNodeInfo root=mock(AccessibilityNodeInfo.class);
        AccessibilityNodeInfo parent=root;
        for(int depth=0;depth<AccessibilityTraversalBudget.MAX_DEPTH+2;depth++) {
            AccessibilityNodeInfo child=mock(AccessibilityNodeInfo.class);
            when(parent.getChildCount()).thenReturn(1);when(parent.getChild(0)).thenReturn(child);
            parent=child;
        }
        var screen=new AccessibilitySnapshotCollector().collect(root,"com.android.settings",1080,2400,null);
        assertTrue(screen.nodes.isEmpty());
        assertFalse(screen.privacyMaskingReady);
        assertNull(screen.navigation);
        verify(root,never()).recycle();
    }
    @Test public void nodeLimitStopsWideTreesBeforeAllChildrenAreRead() {
        AccessibilityNodeInfo root=mock(AccessibilityNodeInfo.class),child=mock(AccessibilityNodeInfo.class);
        when(root.getChildCount()).thenReturn(1000000);
        when(root.getChild(anyInt())).thenReturn(child);
        var screen=new AccessibilitySnapshotCollector().collect(root,"com.android.settings",1080,2400,null);
        assertTrue(screen.nodes.isEmpty());assertFalse(screen.privacyMaskingReady);
        verify(root,atMost(AccessibilityTraversalBudget.MAX_NODES)).getChild(anyInt());
        verify(root,never()).recycle();
    }
    @Test public void timeBudgetUsesAMonotonicClock() {
        long[] clock={0};
        var budget=new AccessibilityTraversalBudget(100,10,150,()->clock[0]);
        budget.take(0);clock[0]=150;
        assertThrows(AccessibilityTraversalBudget.Exhausted.class,()->budget.take(1));
    }
    @Test public void oversizedGovernmentTreeCannotYieldALoginTarget() {
        AccessibilityNodeInfo root=mock(AccessibilityNodeInfo.class);
        when(root.getPackageName()).thenReturn(com.click.data.snapshot.Gov24LoginPrivacy.PACKAGE);
        when(root.getChildCount()).thenReturn(1);when(root.getChild(0)).thenReturn(root);
        assertNull(Gov24LoginEntry.inspect(root));
    }
}
