package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.GestureResultCallback;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

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
            if (scrollCollection(root,navigation,towardNextPage)) {
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
            axis=navigation==null?com.click.data.snapshot.NavigationState.Axis.HORIZONTAL:navigation.axis;
            if(axis==com.click.data.snapshot.NavigationState.Axis.UNKNOWN)axis=com.click.data.snapshot.NavigationState.Axis.HORIZONTAL;
            AccessibilityNodeInfo list=navigation==null?fresh:NativeNavigationReader.nodeAt(fresh,navigation.containerId);
            if(list==null)return false;
            try{list.getBoundsInScreen(viewport);}finally{if(list!=fresh)list.recycle();}
        } finally{fresh.recycle();}
        if(viewport.isEmpty())viewport.set(0,0,service.getResources().getDisplayMetrics().widthPixels,
                service.getResources().getDisplayMetrics().heightPixels);
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

    boolean performScroll(boolean forward) {
        AccessibilityNodeInfo root=ForegroundWindowReader.readRoot(service);if(root==null)return false;
        try{return scrollCollection(root,NativeNavigationReader.read(root),forward);}finally{root.recycle();}
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
            var screen=new AccessibilitySnapshotCollector().collect(root,expectedPackage,metrics.widthPixels,metrics.heightPixels,null)
                    .withNavigation(NativeNavigationReader.read(root));
            plan=TargetVisibility.plan(screen,nodeId,bounds,metrics.density);
            for(var node:screen.nodes) if(node.visibleToUser && node.bounds!=null
                    && ("slider".equals(node.role) || "text_field".equals(node.role) || node.checkable))
                controls.add(new Rect(node.bounds[0],node.bounds[1],node.bounds[2],node.bounds[3]));
        }finally{root.recycle();}
        if(plan==null)return revealSkipped("target_or_viewport_changed");
        Rect viewport=new Rect(plan.viewport[0],plan.viewport[1],plan.viewport[2],plan.viewport[3]);
        float x=uncoveredLine(viewport,true,controls);if(x<0)return revealSkipped("blocked_gesture_lane");
        float y=viewport.exactCenterY(), delta=plan.forward?-plan.distance:plan.distance;
        Path path=new Path();path.moveTo(x,y-delta/2);path.lineTo(x,y+delta/2);
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
        if(navigation==null)return false;
        AccessibilityNodeInfo list=NativeNavigationReader.nodeAt(root,navigation.containerId);if(list==null)return false;
        try {
            if(!NativeNavigationReader.collection(list))return false;
            int generic=forward?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            int directional=navigation.axis==com.click.data.snapshot.NavigationState.Axis.HORIZONTAL
                ? (forward?AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId():AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId())
                : navigation.axis==com.click.data.snapshot.NavigationState.Axis.VERTICAL
                    ? (forward?AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId():AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId()):generic;
            boolean advertised=list.getActionList().stream().anyMatch(action->action.getId()==directional);
            if(directional==generic)return list.performAction(generic);
            if(advertised && list.performAction(directional))return true;
            return list.getActionList().stream().anyMatch(action->action.getId()==generic) && list.performAction(generic);
        }finally{if(list!=root)list.recycle();}
    }

    private float uncoveredLine(Rect viewport,boolean vertical) {
        return uncoveredLine(viewport,vertical,java.util.Collections.emptyList(),new float[]{.5f,.3f,.7f,.2f,.8f});
    }

    private float uncoveredLine(Rect viewport,boolean vertical,java.util.List<Rect> controls) {
        return uncoveredLine(viewport,vertical,controls,new float[]{.5f,.3f,.7f,.2f,.8f,.1f,.9f,.05f,.95f});
    }

    private float uncoveredLine(Rect viewport,boolean vertical,java.util.List<Rect> controls,float[] fractions) {
        java.util.List<Rect> covered=new java.util.ArrayList<>(controls);
        for(AccessibilityWindowInfo window:service.getWindows())try {
            if(window.getType()!=AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY)continue;
            AccessibilityNodeInfo root=window.getRoot();if(root==null)continue;
            try{if(root.isVisibleToUser() && service.getPackageName().equals(String.valueOf(root.getPackageName()))) {
                Rect bounds=new Rect();window.getBoundsInScreen(bounds);covered.add(bounds);
            }}finally{root.recycle();}
        }finally{window.recycle();}
        for(float fraction:fractions) {
            float position=vertical?viewport.left+viewport.width()*fraction:viewport.top+viewport.height()*fraction;
            boolean clear=true;
            for(Rect rect:covered)if(vertical?position>=rect.left-24 && position<=rect.right+24:position>=rect.top-24 && position<=rect.bottom+24){clear=false;break;}
            if(clear)return position;
        }
        return -1;
    }

}
