package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import com.click.data.snapshot.NavigationState;
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
        if(!LauncherAppSearch.matchesPlannedView(expected, fresh, step))return skipped("planned_view_changed");
        switch(step.kind()) {
            case TAP_APP, TAP_FOLDER, OPEN_DRAWER: {
                var target=step.target();
                if(target==null)return step.kind()==LauncherAppSearch.Kind.OPEN_DRAWER
                        && LauncherAppSearch.app(fresh,appNames)==null
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
                // A launcher window can stay active across the drawer, home and news panel.
                // Dispatch on the verified collection itself; a stale node cannot swipe the workspace.
                var expectedList=LauncherAppSearch.drawerPageTarget(fresh);
                if(expectedList==null && LauncherAppSearch.folderScrollable(fresh) && LauncherAppSearch.folderOpen(fresh))
                    expectedList=fresh.nodes.stream().filter(node->fresh.navigation.containerId.equals(node.nodeId)).findFirst().orElse(null);
                if(expectedList==null)return skipped("page_owner_missing");
                AccessibilityNodeInfo list=NativeNavigationReader.nodeAt(root,expectedList.nodeId);
                if(list==null)return skipped("page_node_missing");
                try {
                    if(list.getWindowId()!=fresh.activeWindowId || !fresh.packageName.equals(String.valueOf(list.getPackageName()))
                            || !java.util.Objects.equals(expectedList.resourceId,list.getViewIdResourceName())
                            || !java.util.Objects.equals(expectedList.className,String.valueOf(list.getClassName()))
                            || !list.isVisibleToUser() || !list.isEnabled())return skipped("page_node_changed");
                    var scroller=new NativeScrollExecutor(service,handler,captureSnapshot);
                    if((fresh.packageName+":id/apptray_view").equals(expectedList.resourceId) && !NativeNavigationReader.collection(list))
                        return scrollTray(list, fresh, step, scroller);
                    if(scrollPage(list,fresh.navigation,step.forward()))return true;
                    return !LauncherAppSearch.folderOpen(fresh) && scroller.swipeDrawerPage(fresh,step,list,fresh.navigation);
                }finally{if(list!=root)list.recycle();}
            default: return false;
        }
    }

    private static boolean scrollTray(AccessibilityNodeInfo tray, ScreenSnapshot screen, LauncherAppSearch.Step step, NativeScrollExecutor scroller) {
        String resource = screen.packageName + ":id/apps_content";
        var pager = NativeNavigationReader.drawerPager(tray,resource,screen.packageName,screen.activeWindowId);
        if (pager==null) return skipped("drawer_pager_missing");
        try {
            var navigation = screen.navigation.axis==NavigationState.Axis.UNKNOWN ? NativeNavigationReader.read(pager) : screen.navigation;
            if(navigation==null)return skipped("drawer_pager_changed");
            return scrollPage(pager,navigation,step.forward()) || scroller.swipeDrawerPage(screen,step,pager,navigation);
        } finally { pager.recycle(); }
    }

    private static boolean scrollPage(AccessibilityNodeInfo list, NavigationState navigation, boolean forward) {
        if (!NativeNavigationReader.collection(list)) return skipped("not_a_collection");
        if (android.os.Build.VERSION.SDK_INT >= 29 && navigation.axis != NavigationState.Axis.UNKNOWN) {
            AccessibilityAction page = navigation.axis == NavigationState.Axis.HORIZONTAL
                    ? (forward ? AccessibilityAction.ACTION_PAGE_RIGHT : AccessibilityAction.ACTION_PAGE_LEFT)
                    : (forward ? AccessibilityAction.ACTION_PAGE_DOWN : AccessibilityAction.ACTION_PAGE_UP);
            if (list.getActionList().contains(page)) return list.performAction(page.getId()) || skipped("page_action_declined");
        }
        return NativeScrollExecutor.scrollNode(list,navigation,forward,true) || skipped("scroll_action_declined");
    }
    private static boolean skipped(String reason) {
        android.util.Log.i("ClickScroll","launcher_action_skipped reason="+reason);
        return false;
    }
}
