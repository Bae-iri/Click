package com.click.service.accessibility;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.NavigationState;
import com.click.data.snapshot.ScreenSnapshot;
import java.util.Arrays;
import java.util.HashSet;

/** Local geometry only; scrolling never establishes an action or setting change. */
public final class TargetVisibility {
    public static final class Plan {
        public final boolean forward;
        public final int[] viewport;
        public final int distance;
        private Plan(boolean forward, int[] viewport, int distance) {
            this.forward=forward; this.viewport=viewport; this.distance=distance;
        }
    }

    public static Plan plan(ScreenSnapshot screen, String nodeId, int[] bounds, float density) {
        if(screen==null || screen.activeWindowId==null || !TouchAutomationPolicy.settingsPackage(screen.packageName)
                || nodeId==null || bounds==null || !Float.isFinite(density) || density<=0) return null;
        AccessibilityNodeSnapshot target=find(screen,nodeId);
        if(!valid(screen,target) || !Arrays.equals(bounds,target.bounds) || target.progress!=null) return null;
        int[] row=target.bounds;
        HashSet<String> seen=new HashSet<>();
        AccessibilityNodeSnapshot parent=find(screen,target.parentId);
        while(valid(screen,parent) && seen.add(parent.nodeId)) {
            if(parent.scrollable) {
                String type=parent.className==null?"":parent.className;
                NavigationState navigation=screen.navigation;
                if(type.contains("HorizontalScrollView") || navigation!=null && parent.nodeId.equals(navigation.containerId)
                        && navigation.axis==NavigationState.Axis.HORIZONTAL) return null;
                int top=Math.max(0,parent.bounds[1]), bottom=Math.min(screen.screenHeight,parent.bounds[3]);
                int left=Math.max(0,parent.bounds[0]), right=Math.min(screen.screenWidth,parent.bounds[2]);
                int margin=Math.round(24*density), height=bottom-top;
                if(height<=margin*4 || right<=left || row[3]-row[1]>height-margin*2) return null;
                boolean down=row[3]>bottom-margin, up=row[1]<top+margin;
                if(!down && !up) return null;
                // At a real list boundary, accept a fully exposed row without optional padding.
                if(navigation!=null && parent.nodeId.equals(navigation.containerId)
                        && row[1]>=top && row[3]<=bottom && row[3]-row[1]>=Math.round(48*density)
                        && (down && navigation.atEnd() || up && navigation.atStart())) return null;
                return new Plan(down,new int[]{left,top,right,bottom},Math.min(Math.round(96*density),height/4));
            }
            // A text target may be inside a partially clipped, two-line clickable row.
            if(parent.clickable && !parent.checkable && parent.progress==null) row=parent.bounds;
            parent=find(screen,parent.parentId);
        }
        return null;
    }

    private static boolean valid(ScreenSnapshot screen, AccessibilityNodeSnapshot node) {
        return node!=null && node.visibleToUser && node.enabled && !node.sensitiveInput
                && node.windowId==screen.activeWindowId && screen.packageName.equals(node.packageName)
                && node.bounds!=null && node.bounds.length==4 && node.bounds[2]>node.bounds[0] && node.bounds[3]>node.bounds[1];
    }
    private static AccessibilityNodeSnapshot find(ScreenSnapshot screen,String id) {
        if(id!=null) for(AccessibilityNodeSnapshot node:screen.nodes) if(id.equals(node.nodeId)) return node;
        return null;
    }
    private TargetVisibility() {}
}
