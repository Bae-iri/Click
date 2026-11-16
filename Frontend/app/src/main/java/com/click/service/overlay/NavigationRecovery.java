package com.click.service.overlay;

import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.TouchAutomationPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Conservative, device-local navigation evidence. Fingerprints only, no persisted screen text. */
public final class NavigationRecovery {
    public enum Action { NONE, BACK, HOME }
    private final List<String> path = new ArrayList<>();
    private String anchor, anchorPackage, lastKey, lastPackage, requestedFrom;
    private int unplannedTaps;
    private boolean backTried, manualNavigation, settingsBack;
    private Action hint = Action.NONE, awaiting = Action.NONE;
    public static boolean isHome(String pkg) {
        return com.click.data.snapshot.ScreenEnvironmentPolicy.contains("launcher_packages",pkg);
    }
    public static boolean isHomeScreen(ScreenSnapshot screen) {
        var environment=com.click.data.snapshot.ScreenEnvironment.classify(screen,true,false,"com.click");
        return environment.kind()==com.click.data.snapshot.ScreenEnvironment.Kind.HOME
            || environment.kind()==com.click.data.snapshot.ScreenEnvironment.Kind.APP_DRAWER
            || environment.kind()==com.click.data.snapshot.ScreenEnvironment.Kind.LAUNCHER;
    }
    static String key(ScreenSnapshot screen) {
        if (screen == null) return null;
        StringBuilder value = new StringBuilder(screen.packageName).append('|').append(screen.activeWindowId)
                .append('|').append(screen.screenWidth).append('|').append(screen.screenHeight);
        screen.nodes.stream().filter(n -> n.visibleToUser).forEach(n -> value.append('|')
                .append(n.nodeId).append(':').append(n.text).append(':').append(n.contentDescription)
                .append(':').append(n.className).append(':').append(Arrays.toString(n.bounds)));
        try {
            return java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public void onPath(ScreenSnapshot screen) {
        if (screen == null) return;
        anchor = key(screen); anchorPackage = screen.packageName;
        int index = path.indexOf(anchor);
        if (index >= 0) while (path.size() > index + 1) path.remove(path.size() - 1);
        else { path.add(anchor); if (path.size() > 12) path.remove(0); }
        lastKey = anchor; lastPackage = screen.packageName;
        unplannedTaps = 0; backTried = manualNavigation = settingsBack = false; hint = awaiting = Action.NONE;
    }
    /** A different native Settings page provides a direct, local reason to offer Back.
     * No server/model target is fabricated and no navigation happens before user activation. */
    public void offPathPage(ScreenSnapshot before) {
        if(anchor==null && before!=null)onPath(before);
        hint=Action.BACK;awaiting=Action.NONE;requestedFrom=null;manualNavigation=false;
    }
    public void unplannedTap() { unplannedTaps++; }
    public Action recommend(ScreenSnapshot current) {
        hint = settingsBack && current != null && TouchAutomationPolicy.settingsPackage(current.packageName)
                || !backTried && anchor != null && unplannedTaps == 1 && current != null
                && Objects.equals(anchorPackage, current.packageName) && !Objects.equals(anchor, key(current))
                ? Action.BACK : Action.HOME;
        return hint;
    }
    /** A visible Settings subpage can go back even without a recorded graph page. */
    public Action recommendSettingsBack() {
        settingsBack = true;
        return hint = Action.BACK;
    }
    public void requested(Action action, ScreenSnapshot before) {
        manualNavigation = settingsBack && action == Action.BACK;
        hint = action; awaiting = action; requestedFrom = key(before);
        if (action == Action.BACK) backTried = true;
    }
    /** A physical Back press reports navigation; it does not promise a known graph page. */
    public void requestedByUser(Action action, ScreenSnapshot before) {
        if (awaiting == action) return; // Preserve an explicit recovery's stricter check.
        requested(action, before);
        manualNavigation = true;
    }
    public boolean finishManualWait() {
        if (!manualNavigation) return false;
        manualNavigation = settingsBack = false;
        hint = awaiting = Action.NONE;
        requestedFrom = null;
        return true;
    }
    /** NONE means either no navigation, or keep waiting; inspect hint() after calling. */
    public Action observe(ScreenSnapshot screen) {
        if (screen == null) return Action.NONE;
        String current = key(screen);
        Action observed = Action.NONE;
        if (isHomeScreen(screen) && (awaiting == Action.HOME || lastPackage != null && !isHome(lastPackage))) {
            observed = Action.HOME;
        } else if (awaiting == Action.BACK && !Objects.equals(current, requestedFrom)) {
            if (manualNavigation && screen.nodes.stream().noneMatch(n -> n.visibleToUser)) return Action.NONE;
            if (manualNavigation || Objects.equals(current, anchor) || path.contains(current)
                    && path.indexOf(current) < path.indexOf(anchor)) observed = Action.BACK;
            else { awaiting = Action.NONE; hint = Action.HOME; }
        } else if (!Objects.equals(lastKey, current) && path.contains(current)
                && (path.indexOf(current) < path.indexOf(lastKey)
                    || !path.contains(lastKey) && Objects.equals(current, anchor))) {
            observed = Action.BACK;
        }
        if (observed == Action.HOME) { path.clear(); anchor = anchorPackage = null; unplannedTaps = 0; }
        if (observed != Action.NONE) { hint = awaiting = Action.NONE; requestedFrom = null; manualNavigation = settingsBack = false; }
        lastKey = current; lastPackage = screen.packageName;
        return observed;
    }
    public Action hint() { return hint; }
    public Action awaiting() { return awaiting; }
    public boolean backAttempted() { return backTried; }
    public void forceHome() { hint = Action.HOME; awaiting = Action.NONE; manualNavigation = settingsBack = false; }
    public void clear() {
        path.clear(); anchor = anchorPackage = lastKey = lastPackage = requestedFrom = null;
        unplannedTaps = 0; backTried = manualNavigation = settingsBack = false; hint = awaiting = Action.NONE;
    }
}
