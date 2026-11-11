package com.click.service.accessibility;

import com.click.data.snapshot.*;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class TargetVisibilityTest {
    private AccessibilityNodeSnapshot node(String id,String parent,int top,int bottom,boolean scroll) {
        return new AccessibilityNodeSnapshot(id,parent,true,"com.android.settings","디스플레이",null,null,
                "androidx.recyclerview.widget.RecyclerView",scroll?"list":"button",new int[]{0,top,1080,bottom},
                !scroll,true,false,scroll,false,null,42);
    }
    private ScreenSnapshot screen(int top,int bottom) {
        return new ScreenSnapshot("com.android.settings","2026-11-10T00:00:00Z",1080,2400,
                List.of(node("list",null,200,2200,true),node("display","list",top,bottom,false)),42);
    }
    private TargetVisibility.Plan plan(ScreenSnapshot screen) {
        return TargetVisibility.plan(screen,"display",screen.nodes.get(1).bounds,3);
    }
    @Test public void bottomClippedRowMovesUpWithAShortScroll() {
        var p=plan(screen(2150,2280));assertNotNull(p);assertTrue(p.forward);assertEquals(288,p.distance);
    }
    @Test public void topClippedRowMovesDownAndCenteredRowStaysStill() {
        assertFalse(plan(screen(150,350)).forward);assertNull(plan(screen(700,900)));
    }
    @Test public void descendantTextIncludesItsClickableRow() {
        var s=screen(2150,2280);var label=node("label","display",2160,2180,false);
        var withLabel=new ScreenSnapshot(s.packageName,s.capturedAt,1080,2400,List.of(s.nodes.get(0),s.nodes.get(1),label),42);
        assertNotNull(TargetVisibility.plan(withLabel,"label",label.bounds,3));
    }
    @Test public void staleBoundsAndOtherWindowsCannotRequestScroll() {
        var s=screen(2150,2280);
        assertNull(TargetVisibility.plan(s,"display",new int[]{0,2100,1080,2200},3));
        assertNull(plan(new ScreenSnapshot(s.packageName,s.capturedAt,1080,2400,s.nodes,99)));
    }
    @Test public void horizontalCollectionsAndNonSettingsPagesAreExcluded() {
        var s=screen(2150,2280).withNavigation(new NavigationState("list",NavigationState.Axis.HORIZONTAL,true,true,-1,-1,-1,"page"));
        assertNull(plan(s));
        assertNull(plan(new ScreenSnapshot("other.app",s.capturedAt,1080,2400,s.nodes,42)));
    }
    @Test public void fullyExposedBoundaryRowDoesNotOscillateForPadding() {
        var end=screen(2000,2200).withNavigation(new NavigationState("list",NavigationState.Axis.VERTICAL,true,false,8,9,10,"end"));
        assertNull(plan(end));
        assertNotNull(plan(screen(2180,2200).withNavigation(end.navigation)));
    }
}
