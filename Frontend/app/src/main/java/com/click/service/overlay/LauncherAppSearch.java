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
    private boolean drawerTried, insideFolder, stopped;
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
    public Step next(ScreenSnapshot screen) {
        if(pausedAt>=0)return step(Kind.WAIT,null,"paused");
        if (started < 0) started = clock.getAsLong();
        if (stopped || actions >= 40 || clock.getAsLong() - started >= 45000) return finish("search_budget_exhausted");
        if (!safe(screen)) return finish("launcher_not_observable");
        String view = fingerprint(screen);
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
            if (!folderScrollable(screen)) return issue(Kind.BACK, null, true, view, "leave_searched_folder");
            AutomaticScrollSearch.Plan p = folderPages.plan(folderKey, frame(screen));
            if (p.status == AutomaticScrollSearch.Status.WAIT) return step(Kind.WAIT, null, p.reason);
            if (p.status == AutomaticScrollSearch.Status.MOVE)
                return issue(Kind.SCROLL, null, p.forward, view, "scan_folder_page");
            return issue(Kind.BACK, null, true, view, "leave_searched_folder");
        }
        if (!drawerTried && !drawer(screen)) {
            drawerTried = true;
            return issue(Kind.OPEN_DRAWER, drawerButton(screen), true, view, "open_app_drawer");
        }
        for (AccessibilityNodeSnapshot n : screen.nodes) {
            String key = screen.packageName + ":" + view + ":" + identity(n);
            if (folder(n) && usable(screen, n) && folders.add(key)) {
                folderKey = key;
                return issue(Kind.TAP_FOLDER, n, true, view, "inspect_folder");
            }
        }
        AutomaticScrollSearch.Plan p = pages.plan("launcher", drawer(screen) ? "drawer" : "home", frame(screen));
        if (p.status == AutomaticScrollSearch.Status.FINISHED) return finish(p.reason);
        if (p.status == AutomaticScrollSearch.Status.WAIT) return step(Kind.WAIT, null, p.reason);
        return issue(Kind.SCROLL, null, p.forward, view, "scan_launcher_page");
    }
    private Step issue(Kind kind, AccessibilityNodeSnapshot target, boolean forward, String view, String reason) {
        actions++; waiting = 0; before = view; pending = new Step(kind, target, forward, reason); return pending;
    }
    private Step finish(String reason) { stopped = true; return step(Kind.FINISHED, null, reason); }
    private static Step step(Kind k, AccessibilityNodeSnapshot n, String reason) { return new Step(k,n,true,reason); }
    public static boolean safe(ScreenSnapshot s) {
        if (s == null || s.activeWindowId == null || !s.privacyMaskingReady || !NavigationRecovery.isHome(s.packageName)
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
        if (s.navigation==null)return false;
        var n=byId(s,s.navigation.containerId);
        for(int i=0;i<12 && n!=null;i++,n=byId(s,n.parentId))
            if(folderContainer(n))return true;
        return false;
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
    private static boolean drawer(ScreenSnapshot s) {
        return ScreenEnvironment.classify(s,true,false,"com.click").kind()==ScreenEnvironment.Kind.APP_DRAWER;
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
