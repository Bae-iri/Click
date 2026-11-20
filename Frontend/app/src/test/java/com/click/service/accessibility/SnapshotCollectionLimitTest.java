package com.click.service.accessibility;

import android.app.Application;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class SnapshotCollectionLimitTest {
    private AccessibilityNodeInfo node(String text,boolean clickable) {
        var node=mock(AccessibilityNodeInfo.class);
        when(node.getPackageName()).thenReturn("kr.go.minwon.m");
        when(node.getText()).thenReturn(text);when(node.getClassName()).thenReturn("android.widget.TextView");
        when(node.isVisibleToUser()).thenReturn(true);when(node.isEnabled()).thenReturn(true);
        when(node.isClickable()).thenReturn(clickable);
        doAnswer(call->{((Rect)call.getArgument(0)).set(0,0,100,100);return null;}).when(node).getBoundsInScreen(any());
        return node;
    }
    @Test public void hugeChildCountCannotPublishAPartialSnapshotOrAnEarlyLoginTarget() {
        var root=node("정부24",false);var services=node("자주 찾는 서비스",false);var login=node("로그인",true);
        when(root.getChildCount()).thenReturn(Integer.MAX_VALUE);
        when(root.getChild(anyInt())).thenAnswer(call->switch((int)call.getArgument(0)){case 0->services;case 1->login;default->null;});
        assertNull(new AccessibilitySnapshotCollector().collect(root,"kr.go.minwon.m",100,100,null));
        verify(root,atMost(SnapshotTraversalBudget.MAX_NODES)).getChild(anyInt());
        clearInvocations(root);
        assertNull(Gov24LoginEntry.inspect(root));
        verify(root,atMost(SnapshotTraversalBudget.MAX_NODES)).getChild(anyInt());
    }
    @Test public void deepOrCyclicTreesAreRejectedBeforeTheJavaStackIsExhausted() {
        var root=node("정부24",false);when(root.getChildCount()).thenReturn(1);when(root.getChild(0)).thenReturn(root);
        assertNull(new AccessibilitySnapshotCollector().collect(root,"kr.go.minwon.m",100,100,null));
        assertNull(Gov24LoginEntry.inspect(root));
        verify(root,atMost(2*(SnapshotTraversalBudget.MAX_DEPTH+1))).getChild(0);
    }
    @Test public void navigationPassesCannotResetTheObservationTimeLimit() {
        AtomicLong clock=new AtomicLong();var budget=new SnapshotTraversalBudget(2,3,200,clock::get);
        budget.visit(0);budget.nextPass();clock.set(200);
        try{budget.visit(0);fail();}catch(SnapshotTraversalBudget.Incomplete expected){}
    }
}
