package com.click.data.snapshot;

import java.util.Locale;

/** Foreground identity is observed before task graph matching and before any HTTP. */
public record ScreenEnvironment(Kind kind,String reason) {
    public enum Kind { HOME, APP_DRAWER, LAUNCHER, RECENTS, SETTINGS_HOME, SETTINGS_SUBPAGE, SETTINGS_UNKNOWN,
        CONTROL_SURFACE, OTHER_APP, SELF, KEYBOARD, DIALOG, SYSTEM_PANEL, SYSTEM_DIALOG, LOCKED, DISCONNECTED, SENSITIVE, UNOBSERVABLE }
    public static ScreenEnvironment classify(ScreenSnapshot screen,boolean connected,boolean locked,String ownPackage) {
        if(locked)return new ScreenEnvironment(Kind.LOCKED,"DEVICE_LOCKED");
        if(!connected)return new ScreenEnvironment(Kind.DISCONNECTED,"ACCESSIBILITY_DISCONNECTED");
        if(screen==null || screen.packageName==null)return new ScreenEnvironment(Kind.UNOBSERVABLE,"OBSERVATION_EMPTY");
        String pkg=screen.packageName;
        if(pkg.equals(ownPackage))return new ScreenEnvironment(Kind.SELF,"SELF_OVERLAY");
        boolean launcher=ScreenEnvironmentPolicy.contains("launcher_packages",pkg),settings=ScreenEnvironmentPolicy.contains("settings_packages",pkg);
        if(ScreenEnvironmentPolicy.contains("system_packages",pkg) && !"com.android.systemui".equals(pkg))return new ScreenEnvironment(Kind.SYSTEM_DIALOG,"SYSTEM_DIALOG_VISIBLE");
        if(!launcher && !settings && !"com.android.systemui".equals(pkg))return new ScreenEnvironment(Kind.OTHER_APP,"OTHER_APP");
        if(settings)for(var node:screen.nodes)if(node.visibleToUser && node.sensitiveInput && !node.emptySearchInput())return new ScreenEnvironment(Kind.SENSITIVE,"SENSITIVE_INPUT");
        if(screen.windowContext.keyboardVisible() || "INPUT_METHOD".equals(screen.windowContext.windowKind()))return new ScreenEnvironment(Kind.KEYBOARD,"KEYBOARD_VISIBLE");
        if("com.android.systemui".equals(pkg) && NativeControlSurface.identify(screen)!=null)return new ScreenEnvironment(Kind.CONTROL_SURFACE,"NATIVE_CONTROL_VISIBLE");
        if("com.android.systemui".equals(pkg))return new ScreenEnvironment(Kind.SYSTEM_PANEL,"SYSTEM_PANEL_VISIBLE");
        if(launcher){
            if(has(screen,"recents_tokens"))return new ScreenEnvironment(Kind.RECENTS,"RECENTS_VISIBLE");
            for(var node:screen.nodes)if(screen.activeWindowId!=null && node.windowId==screen.activeWindowId
                    && pkg.equals(node.packageName) && drawerContainer(node))return new ScreenEnvironment(Kind.APP_DRAWER,"APP_DRAWER_VISIBLE");
            if(has(screen,"home_tokens"))return new ScreenEnvironment(Kind.HOME,"HOME_VISIBLE");
            return new ScreenEnvironment(Kind.LAUNCHER,"LAUNCHER_VISIBLE");
        }
        // A protected heading outranks a generic OEM dialog classification.
        String title=ScreenIdentity.pageTitle(screen);
        if(ScreenEnvironmentPolicy.contains("blocked_titles",title))return new ScreenEnvironment(Kind.SENSITIVE,"SENSITIVE_SCREEN");
        // Foreign app text is never inspected; sensitive Settings input cannot resume a task.
        if("DIALOG".equals(screen.windowContext.windowKind()))return new ScreenEnvironment(Kind.DIALOG,"DIALOG_VISIBLE");
        for(var node:screen.nodes)if(node.visibleToUser && ScreenEnvironmentPolicy.contains("dialog_classes",node.className))return new ScreenEnvironment(Kind.DIALOG,"DIALOG_VISIBLE");
        if("SUBPAGE".equals(screen.windowContext.settingsActivity()))return new ScreenEnvironment(Kind.SETTINGS_SUBPAGE,"SETTINGS_SUBPAGE_VISIBLE");
        if(title!=null && !ScreenEnvironmentPolicy.contains("settings_home_titles",title))return new ScreenEnvironment(Kind.SETTINGS_SUBPAGE,"SETTINGS_SUBPAGE_VISIBLE");
        if("HOME".equals(screen.windowContext.settingsActivity()) || title!=null && has(screen,"settings_home_tokens"))return new ScreenEnvironment(Kind.SETTINGS_HOME,"SETTINGS_HOME_VISIBLE");
        return new ScreenEnvironment(Kind.SETTINGS_UNKNOWN,"SETTINGS_PAGE_UNIDENTIFIED");
    }
    public static boolean drawerContainer(AccessibilityNodeSnapshot node){
        if(!node.visibleToUser || node.sensitiveInput || node.className==null)return false;
        String type=node.className.toLowerCase(Locale.ROOT);
        String resource=node.resourceId==null?"":node.resourceId.toLowerCase(Locale.ROOT);
        resource=resource.substring(resource.lastIndexOf('/')+1);
        if(resource.contains("button") || resource.contains("icon") || resource.contains("handle")
                || resource.contains("search") || resource.contains("indicator"))return false;
        boolean container=node.scrollable || type.equals("android.view.view") || type.endsWith("layout") || type.endsWith("viewgroup")
                || type.endsWith("containerview") || type.endsWith("recyclerview") || type.endsWith("pagedview") || type.endsWith("viewpager");
        return container && ScreenEnvironmentPolicy.token("drawer_tokens",resource+" "+type)
                && (node.scrollable || !node.clickable);
    }
    private static boolean has(ScreenSnapshot screen,String tokens){
        for(var node:screen.nodes)if(node.visibleToUser && ScreenEnvironmentPolicy.token(tokens,(node.resourceId==null?"":node.resourceId)+" "+(node.className==null?"":node.className)))return true;
        return false;
    }
    public boolean navigable(){return kind==Kind.CONTROL_SURFACE || kind==Kind.HOME || kind==Kind.APP_DRAWER || kind==Kind.LAUNCHER || kind==Kind.SETTINGS_HOME || kind==Kind.SETTINGS_SUBPAGE || kind==Kind.SETTINGS_UNKNOWN;}
}
