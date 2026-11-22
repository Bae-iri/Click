package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import com.click.data.snapshot.NavigationState;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.LauncherAppSearch;

/** Executes native scrolling; session budgets and direction selection belong to the overlay. */
final class NativeScrollExecutor {
    private static final String TAG = "ClickSnapshot";
    private final AccessibilityService service;
    private final Handler handler;
    private final Runnable captureSnapshot;

    NativeScrollExecutor(AccessibilityService service, Handler handler, Runnable captureSnapshot) {
        this.service = service;
        this.handler = handler;
        this.captureSnapshot = captureSnapshot;
    }

    boolean swipeHomePage(boolean towardNextPage, String expectedPackage, Integer expectedWindow) {
        return swipeHomePage(towardNextPage,expectedPackage,expectedWindow,com.click.data.snapshot.NavigationState.Axis.HORIZONTAL);
    }
    boolean swipeHomePage(boolean towardNextPage, String expectedPackage, Integer expectedWindow,
            com.click.data.snapshot.NavigationState.Axis fallbackAxis) {
        AccessibilityNodeInfo root = ForegroundWindowReader.readRoot(service);
        if (root == null) return false;
        String capturedPackage; int capturedWindow;
        try {
            String pkg = root.getPackageName() == null ? "" : root.getPackageName().toString();
            capturedPackage=pkg;capturedWindow=root.getWindowId();
            if (!com.click.service.overlay.NavigationRecovery.isHome(pkg)
                    || expectedPackage != null && !expectedPackage.equals(pkg)
                    || expectedWindow != null && root.getWindowId() != expectedWindow) return false;
            com.click.data.snapshot.NavigationState navigation=NativeNavigationReader.read(root);
            // Samsung PagedView advertises generic forward/back and page-up/
            // down actions without an axis. For horizontal pages, use only an
            // explicit left/right native action; otherwise use the observed
            // collection's horizontal gesture instead of an ambiguous action.
            AccessibilityNodeInfo container=navigation==null?null:NativeNavigationReader.nodeAt(root,navigation.containerId);
            boolean nativeDirection=true;
            if(container!=null)try {
                if(navigation.axis==com.click.data.snapshot.NavigationState.Axis.HORIZONTAL) {
                    int action=towardNextPage?AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()
                            :AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId();
                    nativeDirection=container.getActionList().stream().anyMatch(a->a.getId()==action);
                }
            }finally{if(container!=root && container!=null)container.recycle();}
            if (nativeDirection && scrollCollection(root,navigation,towardNextPage, navigation==null || navigation.axis!=com.click.data.snapshot.NavigationState.Axis.HORIZONTAL)) {
                if(handler!=null)handler.postDelayed(captureSnapshot,650L);
                return true;
            }
            if(navigation!=null && (towardNextPage?navigation.atEnd():navigation.atStart()))return false;
        } finally { root.recycle(); }
        AccessibilityNodeInfo fresh=ForegroundWindowReader.readRoot(service);
        if(fresh==null)return false;
        Rect viewport=new Rect();com.click.data.snapshot.NavigationState.Axis axis;
        try {
            String pkg=String.valueOf(fresh.getPackageName());
            if(!pkg.equals(capturedPackage) || fresh.getWindowId()!=capturedWindow)return false;
            com.click.data.snapshot.NavigationState navigation=NativeNavigationReader.read(fresh);
            axis=navigation==null?fallbackAxis:navigation.axis;
            if(axis==com.click.data.snapshot.NavigationState.Axis.UNKNOWN)axis=fallbackAxis;
            AccessibilityNodeInfo list=navigation==null?fresh:NativeNavigationReader.nodeAt(fresh,navigation.containerId);
            if(list==null)return false;
            try{list.getBoundsInScreen(viewport);}finally{if(list!=fresh)list.recycle();}
        } finally{fresh.recycle();}
        if(viewport.isEmpty())viewport.set(0,0,service.getResources().getDisplayMetrics().widthPixels,
                service.getResources().getDisplayMetrics().heightPixels);
        return swipePage(viewport,axis,towardNextPage);
    }

    boolean swipeDrawerPage(ScreenSnapshot planned, LauncherAppSearch.Step step, AccessibilityNodeInfo pager, NavigationState navigation) {
        String resource = pager.getViewIdResourceName();
        if (resource == null || resource.endsWith("/workspace") || step.kind() != LauncherAppSearch.Kind.SCROLL) return false;
        var current = ClickAccessibilityService.freshSnapshot();
        if (!LauncherAppSearch.matchesPlannedView(planned, current, step) || !LauncherAppSearch.drawerScrollable(current))
            return drawerSwipeSkipped("drawer_changed");
        if (navigation.axis == NavigationState.Axis.UNKNOWN
                || Boolean.FALSE.equals(step.forward() ? navigation.forwardAvailable : navigation.backwardAvailable)) return drawerSwipeSkipped("direction_unavailable");
        Rect viewport = liveDrawerBounds(current, resource);
        if (viewport == null || !viewport.intersect(0, 0, current.screenWidth, current.screenHeight)) return drawerSwipeSkipped("pager_unavailable");
        return swipePage(viewport, navigation.axis, step.forward()) || drawerSwipeSkipped("gesture_unavailable");
    }
    private static boolean drawerSwipeSkipped(String reason) {
        Log.i("ClickScroll", "drawer_swipe_skipped reason="+reason);
        return false;
    }

    private Rect liveDrawerBounds(ScreenSnapshot screen, String pagerId) {
        var owner = LauncherAppSearch.drawerPageTarget(screen);
        var root = ForegroundWindowReader.readRoot(service);
        if (root == null) return null;
        try {
            if (owner == null || root.getWindowId() != screen.activeWindowId
                    || !screen.packageName.equals(String.valueOf(root.getPackageName()))) return null;
            var drawer = NativeNavigationReader.nodeAt(root, owner.nodeId);
            if (drawer == null) return null;
            try {
                if (!drawer.isVisibleToUser() || owner.resourceId == null
                        || !owner.resourceId.equals(drawer.getViewIdResourceName())) return null;
                // A compact One UI tree can find its pager by view ID even though a
                // cached node's refresh/action is declined. Reacquire it inside the live tray.
                boolean self = pagerId.equals(owner.resourceId);
                var pager = self ? drawer : NativeNavigationReader.drawerPager(drawer,pagerId,screen.packageName,screen.activeWindowId);
                if (pager == null) return null;
                try {
                    if (!NativeNavigationReader.collection(pager) || pager.getWindowId()!=screen.activeWindowId
                            || !screen.packageName.equals(String.valueOf(pager.getPackageName()))) return null;
                    Rect bounds = new Rect();
                    pager.getBoundsInScreen(bounds);
                    return bounds;
                } finally { if (!self) pager.recycle(); }
            } finally { if (drawer != root) drawer.recycle(); }
        } finally { root.recycle(); }
    }

    private boolean swipePage(Rect viewport, NavigationState.Axis axis, boolean towardNextPage) {
        Path path=new Path();
        if(axis==com.click.data.snapshot.NavigationState.Axis.VERTICAL) {
            float x=uncoveredLine(viewport,true);if(x<0)return false;
            path.moveTo(x,viewport.top+viewport.height()*(towardNextPage?.82f:.18f));
            path.lineTo(x,viewport.top+viewport.height()*(towardNextPage?.18f:.82f));
        } else {
            float y=uncoveredLine(viewport,false);if(y<0)return false;
            path.moveTo(viewport.left+viewport.width()*(towardNextPage?.82f:.18f),y);
            path.lineTo(viewport.left+viewport.width()*(towardNextPage?.18f:.82f),y);
        }
        android.accessibilityservice.GestureDescription gesture = new android.accessibilityservice.GestureDescription.Builder()
                .addStroke(new android.accessibilityservice.GestureDescription.StrokeDescription(path,0L,420L)).build();
        return service.dispatchGesture(gesture,new GestureResultCallback(){
            @Override public void onCompleted(android.accessibilityservice.GestureDescription value){if(handler!=null)handler.postDelayed(captureSnapshot,450L);}
            @Override public void onCancelled(android.accessibilityservice.GestureDescription value){if(handler!=null)handler.post(captureSnapshot);}
        },handler);
    }

    /** A home-to-drawer gesture, deliberately independent of the home pager's horizontal axis. */
    boolean openAppDrawer(String expectedPackage, int expectedWindow) {
        AccessibilityNodeInfo root=ForegroundWindowReader.readRoot(service);
        if(root==null)return false;
        Rect viewport=new Rect();
        try {
            if(!com.click.service.overlay.NavigationRecovery.isHome(expectedPackage)
                    || !expectedPackage.equals(String.valueOf(root.getPackageName())) || root.getWindowId()!=expectedWindow)return false;
            root.getBoundsInScreen(viewport);
        } finally { root.recycle(); }
        if(viewport.isEmpty())return false;
        float x=uncoveredLine(viewport,true);if(x<0)return false;
        Path path=new Path();path.moveTo(x,viewport.top+viewport.height()*.82f);
        path.lineTo(x,viewport.top+viewport.height()*.22f);
        var gesture=new android.accessibilityservice.GestureDescription.Builder().addStroke(
                new android.accessibilityservice.GestureDescription.StrokeDescription(path,0L,420L)).build();
        return service.dispatchGesture(gesture,new GestureResultCallback(){
            @Override public void onCompleted(android.accessibilityservice.GestureDescription value){if(handler!=null)handler.postDelayed(captureSnapshot,450L);}
            @Override public void onCancelled(android.accessibilityservice.GestureDescription value){if(handler!=null)handler.post(captureSnapshot);}
        },handler);
    }

    boolean performScroll(boolean forward) {
        AccessibilityNodeInfo root=ForegroundWindowReader.readRoot(service);if(root==null)return false;
        try {
            // Launcher paging needs a live drawer/folder target from LauncherAppSearch.
            if(com.click.service.overlay.NavigationRecovery.isHome(String.valueOf(root.getPackageName())))return false;
            return scrollCollection(root,NativeNavigationReader.read(root),forward);
        }finally{root.recycle();}
    }

    boolean performSettingsScroll(boolean forward,String expectedPackage,int expectedWindow) {
        if(!TouchAutomationPolicy.settingsPackage(expectedPackage))return false;
        AccessibilityNodeInfo root=ForegroundWindowReader.readRoot(service);if(root==null)return false;
        try {
            if(root.getWindowId()!=expectedWindow || !expectedPackage.equals(String.valueOf(root.getPackageName())))return false;
            return scrollCollection(root,NativeNavigationReader.read(root),forward);
        }finally{root.recycle();}
    }

    boolean revealSettingsTarget(String nodeId,int[] bounds,String expectedPackage,int expectedWindow) {
        if(!TouchAutomationPolicy.settingsPackage(expectedPackage))return false;
        AccessibilityNodeInfo root=ForegroundWindowReader.readRoot(service);if(root==null)return revealSkipped("root_unavailable");
        TargetVisibility.Plan plan;
        java.util.List<Rect> controls=new java.util.ArrayList<>();
        try {
            if(root.getWindowId()!=expectedWindow || !expectedPackage.equals(String.valueOf(root.getPackageName())))return revealSkipped("window_changed");
            android.util.DisplayMetrics metrics=service.getResources().getDisplayMetrics();
            var screen=new AccessibilitySnapshotCollector().collect(root,expectedPackage,service,null);
            plan=TargetVisibility.plan(screen,nodeId,bounds,metrics.density);
            for(var node:screen.nodes) if(node.visibleToUser && node.bounds!=null
                    && ("slider".equals(node.role) || "text_field".equals(node.role) || node.checkable))
                controls.add(new Rect(node.bounds[0],node.bounds[1],node.bounds[2],node.bounds[3]));
        }finally{root.recycle();}
        if(plan==null)return revealSkipped("target_or_viewport_changed");
        Rect viewport=new Rect(plan.viewport[0],plan.viewport[1],plan.viewport[2],plan.viewport[3]);
        controls.addAll(overlayBounds());
        float[] segment=ScrollGesturePath.vertical(viewport,plan.distance,plan.forward,controls,24);
        if(segment==null)return revealSkipped("blocked_gesture_lane");
        Path path=new Path();path.moveTo(segment[0],segment[1]);path.lineTo(segment[2],segment[3]);
        var gesture=new android.accessibilityservice.GestureDescription.Builder().addStroke(
                new android.accessibilityservice.GestureDescription.StrokeDescription(path,0L,300L)).build();
        boolean dispatched=service.dispatchGesture(gesture,new GestureResultCallback(){
            @Override public void onCompleted(android.accessibilityservice.GestureDescription value){if(handler!=null)handler.postDelayed(captureSnapshot,350L);}
            @Override public void onCancelled(android.accessibilityservice.GestureDescription value){if(handler!=null)handler.post(captureSnapshot);}
        },handler);
        return dispatched || revealSkipped("gesture_rejected");
    }

    private static boolean revealSkipped(String reason) {
        Log.i("ClickScroll","target_reveal_skipped reason="+reason);return false;
    }

    private static boolean scrollCollection(AccessibilityNodeInfo root,com.click.data.snapshot.NavigationState navigation,boolean forward) {
        return scrollCollection(root,navigation,forward,true);
    }
    private static boolean scrollCollection(AccessibilityNodeInfo root,com.click.data.snapshot.NavigationState navigation,boolean forward,boolean allowGeneric) {
        if(navigation==null)return false;
        AccessibilityNodeInfo list=NativeNavigationReader.nodeAt(root,navigation.containerId);if(list==null)return false;
        try {return scrollNode(list,navigation,forward,allowGeneric);}
        finally{if(list!=root)list.recycle();}
    }

    static boolean scrollNode(AccessibilityNodeInfo list,com.click.data.snapshot.NavigationState navigation,boolean forward,boolean allowGeneric) {
        if(!NativeNavigationReader.collection(list))return false;
        int generic=forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        int directional=navigation.axis==com.click.data.snapshot.NavigationState.Axis.HORIZONTAL
            ? (forward?AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId():AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId())
            : navigation.axis==com.click.data.snapshot.NavigationState.Axis.VERTICAL
                ? (forward?AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId():AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()):generic;
        boolean advertised=list.getActionList().stream().anyMatch(action->action.getId()==directional);
        if(directional==generic)return list.performAction(generic);
        if(advertised && list.performAction(directional))return true;
        return allowGeneric && list.getActionList().stream().anyMatch(action->action.getId()==generic) && list.performAction(generic);
    }

    private float uncoveredLine(Rect viewport,boolean vertical) {
        return uncoveredLine(viewport,vertical,java.util.Collections.emptyList(),new float[]{.5f,.3f,.7f,.2f,.8f});
    }

    private float uncoveredLine(Rect viewport,boolean vertical,java.util.List<Rect> controls,float[] fractions) {
        java.util.List<Rect> covered=new java.util.ArrayList<>(controls);
        covered.addAll(overlayBounds());
        for(float fraction:fractions) {
            float position=vertical?viewport.left+viewport.width()*fraction:viewport.top+viewport.height()*fraction;
            boolean clear=true;
            for(Rect rect:covered)if(vertical?position>=rect.left-24 && position<=rect.right+24:position>=rect.top-24 && position<=rect.bottom+24){clear=false;break;}
            if(clear)return position;
        }
        return -1;
    }

    private java.util.List<Rect> overlayBounds() {
        java.util.List<Rect> covered=new java.util.ArrayList<>();
        for(AccessibilityWindowInfo window:service.getWindows())try {
            if(window.getType()!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)continue;
            AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
            try{if(root.isVisibleToUser() && service.getPackageName().equals(String.valueOf(root.getPackageName()))) {
                Rect bounds=new Rect();window.getBoundsInScreen(bounds);covered.add(bounds);
            }}finally{root.recycle();}
        }finally{window.recycle();}
        return covered;
    }

}
