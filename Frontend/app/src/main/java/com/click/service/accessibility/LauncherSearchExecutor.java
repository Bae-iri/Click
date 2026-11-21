package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.LauncherAppSearch;

/** Local-only native executor. Every action is tied to a newly collected launcher window. */
final class LauncherSearchExecutor {
    static boolean execute(AccessibilityService service, Handler handler, Runnable captureSnapshot,
            AccessibilityNodeInfo root, ScreenSnapshot expected, ScreenSnapshot fresh,
            LauncherAppSearch.Step step, java.util.Collection<String> appNames) {
        if(service==null || root==null || expected==null || fresh==null || step==null)return false;
        android.app.KeyguardManager lock=service.getSystemService(android.app.KeyguardManager.class);
        if(lock!=null && lock.isDeviceLocked())return false;
        if(!com.click.service.overlay.LauncherAppSearch.safe(fresh)
                || !java.util.Objects.equals(expected.packageName,fresh.packageName)
                || !java.util.Objects.equals(expected.activeWindowId,fresh.activeWindowId)
                || !com.click.service.overlay.LauncherAppSearch.fingerprint(expected).equals(
                    com.click.service.overlay.LauncherAppSearch.fingerprint(fresh)))return false;
        switch(step.kind()) {
            case TAP_APP, TAP_FOLDER, OPEN_DRAWER: {
                var target=step.target();
                if(target==null)return step.kind()==com.click.service.overlay.LauncherAppSearch.Kind.OPEN_DRAWER
                        && !com.click.service.overlay.LauncherAppSearch.folderOpen(fresh)
                        && new NativeScrollExecutor(service,handler,captureSnapshot)
                            .openAppDrawer(fresh.packageName,fresh.activeWindowId);
                com.click.data.snapshot.AccessibilityNodeSnapshot current=null;
                for(var n:fresh.nodes)if(n.nodeId.equals(target.nodeId))current=n;
                if(!com.click.service.overlay.LauncherAppSearch.usable(fresh,current)
                        || !java.util.Arrays.equals(target.bounds,current.bounds)
                        || !java.util.Objects.equals(target.text,current.text)
                        || !java.util.Objects.equals(target.contentDescription,current.contentDescription)
                        || !java.util.Objects.equals(target.className,current.className)
                        || !java.util.Objects.equals(target.resourceId,current.resourceId))return false;
                if(step.kind()==LauncherAppSearch.Kind.TAP_FOLDER) {
                    var folder=LauncherAppSearch.targetFolder(fresh,appNames);
                    if(folder==null || !folder.nodeId.equals(current.nodeId))return false;
                }
                if(step.kind()==com.click.service.overlay.LauncherAppSearch.Kind.TAP_APP) {
                    var app=com.click.service.overlay.LauncherAppSearch.app(fresh,appNames);
                    if(app==null || !app.nodeId.equals(current.nodeId))return false;
                }
                AccessibilityNodeInfo node=NativeNavigationReader.nodeAt(root,current.nodeId);
                if(node==null)return false;
                try{
                    android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);
                    if(!node.isVisibleToUser() || !node.isEnabled() || !node.isClickable() || node.isEditable() || node.isPassword()
                            || node.getWindowId()!=fresh.activeWindowId
                            || !fresh.packageName.equals(String.valueOf(node.getPackageName()))
                            || !java.util.Objects.equals(current.resourceId,node.getViewIdResourceName())
                            || !java.util.Objects.equals(current.className,node.getClassName()==null?null:node.getClassName().toString())
                            || !java.util.Arrays.equals(current.bounds,new int[]{bounds.left,bounds.top,bounds.right,bounds.bottom}))return false;
                    return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                finally{if(node!=root)node.recycle();}
            }
            case BACK:
                return com.click.service.overlay.LauncherAppSearch.folderOpen(fresh)
                        && service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            case SCROLL:
                var executor=new NativeScrollExecutor(service,handler,captureSnapshot);
                if(com.click.service.overlay.LauncherAppSearch.folderOpen(fresh))
                    return com.click.service.overlay.LauncherAppSearch.folderScrollable(fresh) && executor.performScroll(step.forward());
                boolean drawer=com.click.data.snapshot.ScreenEnvironment.classify(fresh,true,false,service.getPackageName()).kind()
                        ==com.click.data.snapshot.ScreenEnvironment.Kind.APP_DRAWER;
                return executor.swipeHomePage(step.forward(),fresh.packageName,fresh.activeWindowId,
                        drawer?com.click.data.snapshot.NavigationState.Axis.VERTICAL:com.click.data.snapshot.NavigationState.Axis.HORIZONTAL);
            default: return false;
        }
    }
}
