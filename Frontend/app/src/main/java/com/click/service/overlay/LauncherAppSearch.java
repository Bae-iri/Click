package com.click.service.overlay;

import com.click.data.snapshot.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Bounded local exploration. Labels and folder contents never leave this controller. */
public final class LauncherAppSearch {
    public enum Kind { TAP_APP, TAP_FOLDER, OPEN_DRAWER, SCROLL, BACK, WAIT, FINISHED }
    public record Step(Kind kind, AccessibilityNodeSnapshot target, boolean forward, String reason) {}
    private final Set<String> names;
    private final LongSupplier clock;
    private final Set<String> folders = new HashSet<>();
    private final AutomaticScrollSearch pages, folderPages;
    private long started = -1, pausedAt = -1, pausedDuration;
    private int actions, waiting;
    private boolean drawerTried, sawDrawer, insideFolder;
    private String stopReason, interruptingPackage;
    private Step pending;
    private String before, folderKey;

    public LauncherAppSearch(Collection<String> names) { this(names, () -> android.os.SystemClock.uptimeMillis()); }
    LauncherAppSearch(Collection<String> names, LongSupplier clock) {
        this.names = new HashSet<>(names); this.clock = clock;
        pages=new AutomaticScrollSearch(AutomaticScrollSearch.Mode.LAUNCHER,this::activeNow);
        folderPages=new AutomaticScrollSearch(AutomaticScrollSearch.Mode.LAUNCHER,this::activeNow);
    }
    public void pause() { if(pausedAt<0)pausedAt=clock.getAsLong(); }
    public void resume() { if(pausedAt>=0){long duration=clock.getAsLong()-pausedAt;if(started>=0)started+=duration;pausedDuration+=duration;pausedAt=-1;} }
    private long activeNow() { return (pausedAt>=0?pausedAt:clock.getAsLong())-pausedDuration; }
    /** Observe departures even between search ticks. Returning to the same launcher window cannot revive a swipe. */
    public void observeLocation(ScreenSnapshot screen) {
        if(started<0 || stopReason!=null || screen==null || screen.activeWindowId==null || screen.activeWindowId<0
                || screen.packageName==null || AppTaskObservation.isClickPackage(screen.packageName))return;
        var kind=ScreenEnvironment.classify(screen,true,false,"com.click").kind();
        if(kind==ScreenEnvironment.Kind.OTHER_APP){
            interruptingPackage=screen.packageName;
            stopReason="left_launcher_search";
        }else if(kind==ScreenEnvironment.Kind.APP_DRAWER){
            sawDrawer=true;
        }else if(sawDrawer && !screen.nodes.isEmpty() && (kind==ScreenEnvironment.Kind.HOME || kind==ScreenEnvironment.Kind.LAUNCHER)
                && !folderOpen(screen)){
            stopReason="left_launcher_search";
        }
    }
    boolean leftLauncher(){return "left_launcher_search".equals(stopReason);}
    boolean interruptedBy(String pkg){return pkg!=null && pkg.equals(interruptingPackage);}
    boolean pending(Step step){return stopReason==null && pending==step;}
    void rejected(Step step){if(pending(step))finish("launcher_action_not_applied");}
    public Step next(ScreenSnapshot screen) {
        if(pausedAt>=0)return step(Kind.WAIT,null,"paused");
        if (started < 0) started = clock.getAsLong();
        observeLocation(screen);
        if (stopReason!=null)return step(Kind.FINISHED,null,stopReason);
        if (actions >= 40 || clock.getAsLong() - started >= 45000) return finish("search_budget_exhausted");
        if (!safe(screen)) return finish("launcher_not_observable");
        String view = fingerprint(screen);
        boolean drawer=isDrawer(screen);
        if(pending!=null && pending.kind==Kind.OPEN_DRAWER){
            if(drawer || app(screen,names)!=null)pending=null;
            else if(++waiting<3)return step(Kind.WAIT,null,"awaiting_app_drawer");
            else return finish("app_drawer_not_observed");
        }
        if (pending != null) {
            if (Objects.equals(before, view)) {
                if (++waiting < 3) return step(Kind.WAIT, null, "awaiting_screen_change");
                if (pending.kind == Kind.TAP_FOLDER || pending.kind == Kind.BACK || pending.kind == Kind.TAP_APP)
                    return finish("action_not_confirmed");
                pending = null;
            } else {
                if (pending.kind == Kind.TAP_FOLDER) {
                    if (!folderOpen(screen)) return finish("folder_open_unconfirmed");
                    insideFolder = true; folderPages.clear();
                } else if (pending.kind == Kind.BACK) {
                    if (folderOpen(screen)) return finish("folder_close_unconfirmed");
                    insideFolder = false;
                }
                pending = null;
            }
        }
        AccessibilityNodeSnapshot app = app(screen, names);
        if (app != null) return issue(Kind.TAP_APP, app, true, view, "app_visible");
        if (!insideFolder && folderOpen(screen)) { insideFolder = true; folderKey = "already_open"; }
        if (insideFolder) {
            if ("already_open".equals(folderKey) || !folderScrollable(screen)) return issue(Kind.BACK, null, true, view, "leave_searched_folder");
            AutomaticScrollSearch.Plan p = folderPages.plan(folderKey, frame(screen));
            if (p.status == AutomaticScrollSearch.Status.WAIT) return step(Kind.WAIT, null, p.reason);
            if (p.status == AutomaticScrollSearch.Status.MOVE)
                return issue(Kind.SCROLL, null, p.forward, view, "scan_folder_page");
            return issue(Kind.BACK, null, true, view, "leave_searched_folder");
        }
        var folder = targetFolder(screen, names);
        if (folder != null) {
            String key = screen.packageName + ":" + identity(folder);
            if (folders.add(key)) {
                folderKey = key;
                return issue(Kind.TAP_FOLDER, folder, true, view, "app_in_folder_preview");
            }
        }
        if (!drawerTried && !drawer) {
            drawerTried = true;
            return issue(Kind.OPEN_DRAWER, drawerButton(screen), true, view, "open_app_drawer");
        }
        if(!drawer)return finish("app_drawer_not_observed");
        if(!drawerScrollable(screen))return finish("app_drawer_not_scrollable");
        AutomaticScrollSearch.Plan p = pages.plan("drawer",frame(screen));
        if (p.status == AutomaticScrollSearch.Status.FINISHED) return finish(p.reason);
        if (p.status == AutomaticScrollSearch.Status.WAIT) return step(Kind.WAIT, null, p.reason);
        return issue(Kind.SCROLL, null, p.forward, view, "scan_launcher_page");
    }
    private Step issue(Kind kind, AccessibilityNodeSnapshot target, boolean forward, String view, String reason) {
        actions++; waiting = 0; before = view; pending = new Step(kind, target, forward, reason); return pending;
    }
    private Step finish(String reason) { stopReason=reason; return step(Kind.FINISHED,null,reason); }
    private static Step step(Kind k, AccessibilityNodeSnapshot n, String reason) { return new Step(k,n,true,reason); }
    public static boolean safe(ScreenSnapshot s) {
        if (s == null || s.activeWindowId == null || s.activeWindowId < 0 || !s.privacyMaskingReady || !NavigationRecovery.isHome(s.packageName)
                || s.windowContext.keyboardVisible()) return false;
        var kind = ScreenEnvironment.classify(s,true,false,"com.click").kind();
        if (kind != ScreenEnvironment.Kind.HOME && kind != ScreenEnvironment.Kind.APP_DRAWER
                && kind != ScreenEnvironment.Kind.LAUNCHER) return false;
        for (var n : s.nodes) if (n.visibleToUser && n.sensitiveInput && (n.passwordInput() || n.hasInputContent())) return false;
        return true;
    }
    public static boolean usable(ScreenSnapshot s, AccessibilityNodeSnapshot n) {
        return n != null && n.visibleToUser && n.enabled && n.clickable && !n.sensitiveInput
                && !n.scrollable && !n.checkable && Objects.equals(s.packageName,n.packageName)
                && s.activeWindowId != null && s.activeWindowId == n.windowId && n.bounds != null && n.bounds.length == 4
                && n.bounds[0] >= 0 && n.bounds[1] >= 0 && n.bounds[2] <= s.screenWidth && n.bounds[3] <= s.screenHeight
                && n.bounds[2] > n.bounds[0] && n.bounds[3] > n.bounds[1]
                && n.bounds[2]-n.bounds[0] <= s.screenWidth/2 && n.bounds[3]-n.bounds[1] <= s.screenHeight/2;
    }
    public static boolean folder(AccessibilityNodeSnapshot n) {
        String type = lower(n.className), id = resource(n.resourceId), desc = lower(n.contentDescription);
        return type.endsWith("foldericon") || type.endsWith("foldericonview") || Set.of("folder_icon","foldericon").contains(id)
                || desc.matches(".*(?:폴더|folder)(?:[, :].*|$)");
    }
    public static boolean folderOpen(ScreenSnapshot s) {
        for (var n : s.nodes) if (n.visibleToUser && Objects.equals(s.packageName,n.packageName)
                && s.activeWindowId != null && n.windowId == s.activeWindowId
                && (folderContainer(n) || "folder_name".equals(resource(n.resourceId)))) return true;
        return false;
    }
    public static boolean folderScrollable(ScreenSnapshot s) {
        if (s.navigation==null || s.activeWindowId==null)return false;
        var n=byId(s,s.navigation.containerId);
        for(int i=0;i<12 && n!=null && n.windowId==s.activeWindowId && Objects.equals(n.packageName,s.packageName);i++,n=byId(s,n.parentId))
            if(n.visibleToUser && !n.sensitiveInput && folderContainer(n))return true;
        return false;
    }
    public static boolean drawerScrollable(ScreenSnapshot s) {
        return drawerPageTarget(s)!=null;
    }
    public static AccessibilityNodeSnapshot drawerPageTarget(ScreenSnapshot s) {
        if(s==null || !isDrawer(s) || s.navigation==null || s.activeWindowId==null || folderOpen(s))return null;
        var list = byId(s,s.navigation.containerId);
        var n=list;
        for(int depth=0;depth<20 && n!=null && n.windowId==s.activeWindowId && Objects.equals(n.packageName,s.packageName);depth++,n=byId(s,n.parentId)) {
            if(ScreenEnvironment.drawerContainer(n))return list;
        }
        // In One UI's compact accessibility tree the tray is visible but its pager
        // is omitted from child traversal. The native executor can query inside this tray by ID.
        if(list==null || list.parentId!=null || list.scrollable || s.navigation.axis!=NavigationState.Axis.UNKNOWN)return null;
        AccessibilityNodeSnapshot tray = null;
        for(var node:s.nodes) {
            if(node.windowId!=s.activeWindowId || !Objects.equals(node.packageName,s.packageName)
                    || !ScreenEnvironment.drawerContainer(node) || !"apptray_view".equals(resource(node.resourceId)))continue;
            if(tray!=null)return null;
            tray=node;
        }
        return tray;
    }
    public static AccessibilityNodeSnapshot app(ScreenSnapshot s, Collection<String> names) {
        if (!safe(s)) return null;
        AccessibilityNodeSnapshot found = null;
        for (var label : s.nodes) {
            if (!label.visibleToUser || label.sensitiveInput || !Objects.equals(s.packageName,label.packageName)
                    || label.windowId != s.activeWindowId || folder(label)
                    || !ClickOverlayService.appIconLabel(label.text,names) && !ClickOverlayService.appIconLabel(label.contentDescription,names)) continue;
            var n = label;
            for (int i=0; i<3 && n!=null && !n.clickable; i++) n = byId(s,n.parentId);
            if (!usable(s,n) || folder(n) || inFolderIcon(s,n) || folderOpen(s) && !withinOpenFolder(s,n)) continue;
            if (found != null && !found.nodeId.equals(n.nodeId)) return null;
            found = n;
        }
        return found;
    }
    /** Only a child app icon can identify a closed folder. Its own title is user-editable. */
    public static AccessibilityNodeSnapshot targetFolder(ScreenSnapshot screen, Collection<String> names) {
        if (!safe(screen) || folderOpen(screen)) return null;
        AccessibilityNodeSnapshot found = null;
        for (var child : screen.nodes) {
            if (!child.visibleToUser || child.sensitiveInput || child.windowId != screen.activeWindowId
                    || !screen.packageName.equals(child.packageName) || folder(child)
                    || !("icon".equals(resource(child.resourceId)) || "app_icon".equals(resource(child.resourceId))
                        || lower(child.className).endsWith("bubbletextview"))) continue;
            if (!ClickOverlayService.appIconLabel(child.text, names)
                    && !ClickOverlayService.appIconLabel(child.contentDescription, names)) continue;
            var parent = byId(screen, child.parentId);
            for (int i = 0; i < 12 && parent != null && !folder(parent); i++) parent = byId(screen, parent.parentId);
            if (parent == null || !usable(screen, parent)) continue;
            if (found != null && !found.nodeId.equals(parent.nodeId)) return null;
            found = parent;
        }
        return found;
    }
    private static boolean withinOpenFolder(ScreenSnapshot s, AccessibilityNodeSnapshot n) {
        for(int i=0;i<20 && n!=null;i++,n=byId(s,n.parentId))if(folderContainer(n))return true;
        return false;
    }
    private static boolean folderContainer(AccessibilityNodeSnapshot n) {
        return Set.of("folder_content","folder_view","folder_container").contains(resource(n.resourceId))
                || lower(n.className).endsWith(".folder") || lower(n.className).endsWith("folderpagedview");
    }
    private static boolean inFolderIcon(ScreenSnapshot s, AccessibilityNodeSnapshot n) {
        for (int i=0; i<20 && n!=null; i++, n=byId(s,n.parentId))
            if (folder(n) || lower(n.className).contains("appwidget") || lower(n.resourceId).contains("widget")) return true;
        return false;
    }
    private static AccessibilityNodeSnapshot byId(ScreenSnapshot s,String id) {
        if (id != null) for (var n:s.nodes) if (id.equals(n.nodeId)) return n; return null;
    }
    private static AccessibilityNodeSnapshot drawerButton(ScreenSnapshot s) {
        AccessibilityNodeSnapshot result=null;
        for (var n:s.nodes) if (usable(s,n) && !folder(n)
                && (Set.of("apps_button","all_apps_button").contains(resource(n.resourceId))
                    || Set.of("앱스","모든 앱","all apps","apps").contains(lower(n.contentDescription)))) {
            if(result!=null)return null; result=n;
        }
        return result;
    }
    public static boolean isDrawer(ScreenSnapshot s) {
        return ScreenEnvironment.classify(s,true,false,"com.click").kind()==ScreenEnvironment.Kind.APP_DRAWER;
    }
    public static boolean matchesPlannedView(ScreenSnapshot expected, ScreenSnapshot fresh, Step step) {
        if (!safe(expected) || !safe(fresh) || !expected.packageName.equals(fresh.packageName)
                || !Objects.equals(expected.activeWindowId, fresh.activeWindowId)) return false;
        if (step.kind == Kind.OPEN_DRAWER && step.target == null) {
            if (isDrawer(expected) || isDrawer(fresh) || folderOpen(expected) || folderOpen(fresh)) return false;
            // A clock tick can redraw the same home page before the upward gesture.
            List<String> anchors = homeIcons(expected);
            if (!anchors.isEmpty()) return anchors.equals(homeIcons(fresh));
        }
        return fingerprint(expected).equals(fingerprint(fresh));
    }
    private static List<String> homeIcons(ScreenSnapshot screen) {
        List<String> icons = new ArrayList<>();
        for (var node : screen.nodes) {
            if (usable(screen, node) && (folder(node) || !inFolderIcon(screen, node))) icons.add(identity(node));
        }
        return icons;
    }
    private static NavigationState frame(ScreenSnapshot s) {
        var n=s.navigation;
        return new NavigationState(n==null?null:n.containerId,n==null?NavigationState.Axis.UNKNOWN:n.axis,
                n==null?null:n.backwardAvailable,n==null?null:n.forwardAvailable,
                n==null?-1:n.firstIndex,n==null?-1:n.lastIndex,n==null?-1:n.itemCount,fingerprint(s));
    }
    /** Stable across native node-ID churn; never used alone to authorize a target. */
    public static String fingerprint(ScreenSnapshot s) {
        StringBuilder out=new StringBuilder(s.packageName);
        for(var n:s.nodes) if(n.visibleToUser && !n.sensitiveInput) out.append('|').append(identity(n));
        return out.toString();
    }
    private static String identity(AccessibilityNodeSnapshot n) {
        return n.resourceId+":"+n.className+":"+n.text+":"+n.contentDescription+":"+Arrays.toString(n.bounds);
    }
    private static String resource(String v) { String s=lower(v);return s.substring(s.lastIndexOf('/')+1); }
    private static String lower(String s) { return s==null?"":s.trim().toLowerCase(Locale.ROOT); }
}
