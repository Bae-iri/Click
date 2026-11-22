package com.click.service.accessibility;

import android.app.Application;
import android.view.accessibility.AccessibilityNodeInfo;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** A stale snapshot path must read as "target not found", never crash the app process. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class NodePathTest {
    private AccessibilityNodeInfo parent(int count){
        AccessibilityNodeInfo n=mock(AccessibilityNodeInfo.class);when(n.getChildCount()).thenReturn(count);return n;
    }

    @Test public void walksAnExistingPath(){
        AccessibilityNodeInfo root=parent(3),middle=parent(2),leaf=parent(0);
        when(root.getChild(2)).thenReturn(middle);when(middle.getChild(1)).thenReturn(leaf);
        assertSame(leaf,NodePath.resolve(root,"node_0_2_1"));
        verify(middle).recycle();verify(root,never()).recycle();
    }

    @Test public void rootIdReturnsRoot(){
        AccessibilityNodeInfo root=parent(0);
        assertSame(root,NodePath.resolve(root,"node_0"));
    }

    @Test public void childRemovedSinceTheSnapshotIsNotFound(){
        // Galaxy Note20 crash: the path recorded index 5 after Settings redrew down to 5 children.
        AccessibilityNodeInfo root=parent(5);
        assertNull(NodePath.resolve(root,"node_0_5"));
        verify(root,never()).getChild(5);
    }

    @Test public void platformBoundsExceptionIsNotFound(){
        AccessibilityNodeInfo root=parent(6),middle=parent(4);
        when(root.getChild(5)).thenReturn(middle);
        when(middle.getChild(3)).thenThrow(new ArrayIndexOutOfBoundsException("length=3; index=3"));
        assertNull(NodePath.resolve(root,"node_0_5_3"));
        verify(middle).recycle();
    }

    @Test public void staleNodeAndMalformedIdsAreNotFound(){
        AccessibilityNodeInfo root=parent(2);
        when(root.getChild(0)).thenThrow(new IllegalStateException("stale"));
        assertNull(NodePath.child(root,0));
        assertNull(NodePath.child(root,-1));
        assertNull(NodePath.child(null,0));
        assertNull(NodePath.resolve(root,"node_0_x"));
        assertNull(NodePath.resolve(null,"node_0_1"));
    }
}
