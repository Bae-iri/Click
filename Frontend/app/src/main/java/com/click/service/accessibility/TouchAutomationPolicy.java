package com.click.service.accessibility;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.NavigationRecovery;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Resolve a server-selected Settings control using its title and real AX ancestry. */
public final class TouchAutomationPolicy {
    private TouchAutomationPolicy() {}

    private static boolean settingValueControl(ScreenSnapshot screen,AccessibilityNodeSnapshot node) {
        Set<String> seen=new HashSet<>();
        for(AccessibilityNodeSnapshot current=node;current!=null && seen.add(current.nodeId);
                current=find(screen,current.parentId)) {
            String role=current.role==null?"":current.role;
            String type=current.className==null?"":current.className.toLowerCase(Locale.ROOT);
            if(com.click.data.snapshot.SettingsCapabilities.privateIdentity(current.resourceId)
                    || current.sensitiveInput || current.checkable || current.progress!=null
                    || Set.of("slider","toggle","switch","radio","radio_button","checkbox","text_field","text_input").contains(role)
                    || type.contains("seekbar") || type.contains("switch") || type.contains("radio")
                    || type.contains("checkbox") || type.contains("edittext")
                    || com.click.data.snapshot.SettingsControlResources.soundModeChoice(current.resourceId))return true;
        }
        return false;
    }

    public static boolean settingsPackage(String pkg) {
        return com.click.data.snapshot.SettingsCapabilities.settingsPackage(pkg);
    }

    private static boolean usable(ScreenSnapshot screen, AccessibilityNodeSnapshot node) {
        return node != null && node.visibleToUser && node.enabled && !node.sensitiveInput
                && !settingValueControl(screen,node) && Objects.equals(screen.packageName, node.packageName)
                && screen.activeWindowId != null && node.windowId == screen.activeWindowId
                && node.bounds != null && node.bounds.length == 4 && node.bounds[0] >= 0 && node.bounds[1] >= 0
                && node.bounds[2] <= screen.screenWidth && node.bounds[3] <= screen.screenHeight
                && node.bounds[2] > node.bounds[0] && node.bounds[3] > node.bounds[1];
    }

    private static String label(AccessibilityNodeSnapshot node) {
        String text = node.text != null && !node.text.trim().isEmpty() ? node.text : node.contentDescription;
        return text == null ? "" : text.trim();
    }

    private static String canonical(String text) { return com.click.data.snapshot.SettingsCapabilities.canonical(text); }
    public static boolean isSettingsAppLabel(String text) {
        if (text == null) return false;
        String name = text.trim().toLowerCase(Locale.ROOT);
        return name.equals("설정") || name.equals("settings")
                || name.startsWith("설정,") || name.startsWith("settings,");
    }

    private static String titleIdentity(ScreenSnapshot screen, String text) {
        return NavigationRecovery.isHome(screen.packageName) && isSettingsAppLabel(text)
                ? "설정" : canonical(text);
    }

    private static boolean allowedTitle(ScreenSnapshot screen, String text) {
        return NavigationRecovery.isHome(screen.packageName)
                ? isSettingsAppLabel(text) : com.click.data.snapshot.SettingsCapabilities.navigationLabel(text);
    }

    private static boolean risky(AccessibilityNodeSnapshot node) {
        String text = ((node.text == null ? "" : node.text) + " "
                + (node.contentDescription == null ? "" : node.contentDescription)).toLowerCase(Locale.ROOT);
        return com.click.data.snapshot.SettingsCapabilities.risky(text);
    }

    private static boolean descendant(ScreenSnapshot screen, AccessibilityNodeSnapshot child, String ancestor) {
        String parent = child.parentId;
        for (int depth = 0; parent != null && depth < 32; depth++) {
            if (parent.equals(ancestor)) return true;
            AccessibilityNodeSnapshot next = find(screen, parent);
            if (next == null || Objects.equals(parent, next.parentId)) return false;
            parent = next.parentId;
        }
        return false;
    }

    private static boolean launcherDecoration(AccessibilityNodeSnapshot node) {
        String type=node.className==null?"":node.className.toLowerCase(Locale.ROOT);
        return label(node).isEmpty() && type.contains("image") && !node.checkable
                && !node.sensitiveInput && node.progress==null;
    }

    /** Subtitles and row descriptions are context, not the menu's identity. */
    private static boolean approvedRow(ScreenSnapshot screen, AccessibilityNodeSnapshot row) {
        if (!usable(screen, row) || row.scrollable || risky(row)) return false;
        Set<String> titles = new HashSet<>(), otherLabels = new HashSet<>();
        for (AccessibilityNodeSnapshot child : screen.nodes) {
            if (!child.visibleToUser || !descendant(screen, child, row.nodeId)) continue;
            if (com.click.data.snapshot.SettingsCapabilities.privateIdentity(child.resourceId)
                    || child.sensitiveInput || child.checkable || child.progress != null || risky(child)
                    || child.clickable && !allowedTitle(screen, label(child))
                        && !(NavigationRecovery.isHome(screen.packageName) && launcherDecoration(child))) return false;
            String resource = child.resourceId == null ? "" : child.resourceId.substring(child.resourceId.lastIndexOf('/') + 1);
            if (Set.of("summary", "subtitle", "description", "secondary_text").contains(resource)) continue;
            if (allowedTitle(screen, label(child))) {
                otherLabels.add(titleIdentity(screen, label(child)));
                if (child.resourceId != null && child.resourceId.endsWith(":id/title")) titles.add(titleIdentity(screen, label(child)));
            }
        }
        if (otherLabels.isEmpty() && allowedTitle(screen, label(row))) otherLabels.add(titleIdentity(screen, label(row)));
        // Multiple independently named menu controls in a parent are ambiguous, not one row.
        return otherLabels.size() == 1 && titles.size() <= 1;
    }

    public static AccessibilityNodeSnapshot target(ScreenSnapshot screen, String id, int[] bounds) {
        if (screen == null || !(settingsPackage(screen.packageName) || NavigationRecovery.isHome(screen.packageName))
                || !screen.privacyMaskingReady || id == null || bounds == null || bounds.length != 4) return null;
        AccessibilityNodeSnapshot selected = find(screen, id);
        return usable(screen, selected) && Arrays.equals(selected.bounds, bounds) && approvedRow(screen, selected)
                ? selected : null;
    }

    public static AccessibilityNodeSnapshot clickableTarget(ScreenSnapshot screen, String id, int[] bounds) {
        AccessibilityNodeSnapshot selected = target(screen, id, bounds);
        if (selected == null) return null;
        if (selected.clickable) return selected;
        AccessibilityNodeSnapshot fallback = selected;
        AccessibilityNodeSnapshot current = selected;
        for (int depth = 0; depth < 6; depth++) {
            AccessibilityNodeSnapshot parent = find(screen, current.parentId);
            if (!usable(screen, parent) || parent.scrollable
                    || parent.bounds[3] - parent.bounds[1] > screen.screenHeight / 2
                    || parent.bounds[0] > selected.bounds[0] || parent.bounds[1] > selected.bounds[1]
                    || parent.bounds[2] < selected.bounds[2] || parent.bounds[3] < selected.bounds[3]) break;
            if (parent.clickable) return approvedRow(screen, parent) ? parent : null;
            if (approvedRow(screen, parent)) fallback = parent;
            current = parent;
        }
        return fallback;
    }

    /** A globally reviewed label must also belong to this accepted goal and step. */
    public static AccessibilityNodeSnapshot clickableTarget(ScreenSnapshot screen,String id,int[] bounds,String goal,String state) {
        AccessibilityNodeSnapshot target=clickableTarget(screen,id,bounds);
        if(target==null)return splitSwitchNavigationTitle(screen,id,bounds,goal,state);
        for(AccessibilityNodeSnapshot node:screen.nodes) {
            if(!node.visibleToUser || !node.nodeId.equals(target.nodeId) && !descendant(screen,node,target.nodeId))continue;
            String resource=node.resourceId==null?"":node.resourceId.substring(node.resourceId.lastIndexOf('/')+1);
            if(Set.of("summary","subtitle","description","secondary_text").contains(resource))continue;
            String name=NavigationRecovery.isHome(screen.packageName) && isSettingsAppLabel(label(node))?"설정":label(node);
            if(com.click.data.snapshot.SettingsCapabilities.navigationForGoal(goal,state,name))return target;
        }
        return null;
    }

    /** Samsung menu rows can contain a separate switch. Only the disjoint title is a safe hit region. */
    private static AccessibilityNodeSnapshot splitSwitchNavigationTitle(ScreenSnapshot screen,String id,int[] bounds,String goal,String state) {
        if(screen==null || !settingsPackage(screen.packageName))return null;
        AccessibilityNodeSnapshot row=find(screen,id);
        if(!usable(screen,row) || !Arrays.equals(row.bounds,bounds) || row.scrollable || risky(row))return null;
        boolean page=false;
        for(AccessibilityNodeSnapshot n:screen.nodes) {
            if(!usable(screen,n) || n.clickable || n.scrollable || !"android.widget.TextView".equals(n.className)
                    || !com.click.data.snapshot.SettingsCapabilities.pageTitle(goal,state,label(n)))continue;
            Set<String> seen=new HashSet<>();String parent=n.parentId;boolean independent=true;
            while(parent!=null && seen.add(parent)){
                AccessibilityNodeSnapshot a=find(screen,parent);
                if(a==null || a.clickable || a.scrollable || "list".equals(a.role) || a.sensitiveInput
                        || com.click.data.snapshot.SettingsCapabilities.privateIdentity(a.resourceId)){independent=false;break;}
                parent=a.parentId;
            }
            if(parent!=null)independent=false;
            page|=independent;
        }
        if(!page)return null;
        AccessibilityNodeSnapshot title=null;java.util.List<AccessibilityNodeSnapshot> switches=new java.util.ArrayList<>();
        for(AccessibilityNodeSnapshot child:screen.nodes) {
            if(!child.visibleToUser || !descendant(screen,child,row.nodeId))continue;
            if(child.sensitiveInput || risky(child) || com.click.data.snapshot.SettingsCapabilities.privateIdentity(child.resourceId))return null;
            boolean value=child.checkable || Set.of("switch","toggle","slider","radio","radio_button","checkbox").contains(child.role==null?"":child.role)
                    || child.progress!=null;
            if(value){
                if(!child.checkable || !com.click.data.snapshot.SettingsCapabilities.splitSwitch(child.resourceId)
                        || child.className==null || !child.className.toLowerCase(Locale.ROOT).contains("switch"))return null;
                switches.add(child);continue;
            }
            if(com.click.data.snapshot.SettingsCapabilities.splitTitle(child.resourceId)){
                if(title!=null || !usable(screen,child) || !"android.widget.TextView".equals(child.className)
                        || !com.click.data.snapshot.SettingsCapabilities.navigationForGoal(goal,state,label(child)))return null;
                title=child;
            }else if(child.clickable)return null;
        }
        if(title==null || switches.size()!=1)return null;
        for(AccessibilityNodeSnapshot sw:switches) {
            if(sw.bounds==null || sw.bounds.length!=4 || sw.bounds[0]>=sw.bounds[2] || sw.bounds[1]>=sw.bounds[3]
                    || title.bounds[0]<sw.bounds[2] && title.bounds[2]>sw.bounds[0] && title.bounds[1]<sw.bounds[3] && title.bounds[3]>sw.bounds[1])return null;
        }
        return title;
    }

    /** Ignore unrelated clocks/revision events, but compare the selected control and all its children. */
    public static boolean sameTarget(ScreenSnapshot before, ScreenSnapshot after, String id, int[] bounds) {
        if (before == null || after == null || !after.privacyMaskingReady || id == null || bounds == null
                || !Objects.equals(before.packageName, after.packageName)
                || !Objects.equals(before.activeWindowId, after.activeWindowId)
                || before.screenWidth != after.screenWidth || before.screenHeight != after.screenHeight) return false;
        AccessibilityNodeSnapshot old = find(before, id), now = find(after, id);
        if (old == null || now == null || !now.visibleToUser || !now.enabled || now.sensitiveInput
                || !Arrays.equals(old.bounds, bounds) || !Arrays.equals(now.bounds, bounds)) return false;
        return shape(before, id).equals(shape(after, id));
    }
    private static String shape(ScreenSnapshot screen, String id) {
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (AccessibilityNodeSnapshot n : screen.nodes) {
            if (!n.nodeId.equals(id) && !descendant(screen, n, id)) continue;
            StringBuilder result = new StringBuilder();
            result.append(n.nodeId).append('|').append(n.parentId).append('|').append(n.packageName)
                    .append('|').append(n.windowId).append('|').append(n.text).append('|').append(n.contentDescription)
                    .append('|').append(n.resourceId).append('|').append(n.className).append('|').append(n.visibleToUser)
                    .append('|').append(n.enabled).append('|').append(n.clickable).append('|').append(n.checkable)
                    .append('|').append(n.sensitiveInput).append('|').append(Arrays.toString(n.bounds))
                    .append('|').append(n.progress).append('|').append(n.rangeMin).append('|').append(n.rangeMax).append(';');
            rows.add(result.toString());
        }
        java.util.Collections.sort(rows);
        return String.join("", rows);
    }

    private static AccessibilityNodeSnapshot find(ScreenSnapshot screen, String id) {
        if (id == null) return null;
        for (AccessibilityNodeSnapshot node : screen.nodes) if (id.equals(node.nodeId)) return node;
        return null;
    }
}
