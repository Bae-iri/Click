package com.click.service.accessibility;

import android.app.Application;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34,application=Application.class)
public class Gov24PublicNavigationTest {
    private AccessibilityNodeInfo root() {
        var root=mock(AccessibilityNodeInfo.class);when(root.getPackageName()).thenReturn(Gov24LoginPrivacy.PACKAGE);
        when(root.getWindowId()).thenReturn(42);when(root.isVisibleToUser()).thenReturn(true);
        when(root.isEnabled()).thenReturn(true);when(root.isScrollable()).thenReturn(true);
        return root;
    }
    private ScreenSnapshot screen(String label,boolean input) {
        var node=new AccessibilityNodeSnapshot("heading",null,true,Gov24LoginPrivacy.PACKAGE,label,null,null,
            input?"android.widget.EditText":"android.view.View",input?"text_field":"text",new int[]{10,100,500,180},
            false,true,false,false,false,null,42,input,null,null,false);
        return new ScreenSnapshot(Gov24LoginPrivacy.PACKAGE,java.time.Instant.now().toString(),1080,2400,List.of(node),42);
    }
    @Test public void privateOrUnrecognizedScreenCannotTriggerScrolling() {
        var root=root();
        assertFalse(Gov24PublicNavigation.reveal(root,screen("PRIVATE",true),"gov24.resident_copy.open"));
        assertFalse(Gov24PublicNavigation.reveal(root,screen("알 수 없는 화면",false),"gov24.resident_copy.open"));
        verify(root,never()).performAction(anyInt());
    }
    @Test public void recognizedPublicServiceListCanScrollWithoutClickingAnything() {
        var root=root();when(root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)).thenReturn(true);
        try(var nativeNodes=mockStatic(AccessibilityNodeInfo.class,CALLS_REAL_METHODS)) {
            nativeNodes.when(()->AccessibilityNodeInfo.obtain(any(AccessibilityNodeInfo.class))).thenAnswer(call->call.getArgument(0));
            assertTrue(Gov24PublicNavigation.reveal(root,screen("민원서비스",false),"gov24.resident_copy.open"));
        }
        verify(root).performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        verify(root,never()).performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }
    @Test public void freshNativePrivateFieldCancelsScrollEvenIfThePriorSnapshotWasPublic() {
        var root=root();var input=mock(AccessibilityNodeInfo.class);
        when(input.isEditable()).thenReturn(true);when(input.isVisibleToUser()).thenReturn(true);
        when(root.getChildCount()).thenReturn(1);when(root.getChild(0)).thenReturn(input);
        try(var nativeNodes=mockStatic(AccessibilityNodeInfo.class,CALLS_REAL_METHODS)) {
            nativeNodes.when(()->AccessibilityNodeInfo.obtain(any(AccessibilityNodeInfo.class))).thenAnswer(call->call.getArgument(0));
            assertFalse(Gov24PublicNavigation.reveal(root,screen("민원서비스",false),"gov24.resident_copy.open"));
        }
        verify(input,never()).getText();verify(root,never()).performAction(anyInt());
    }
}
