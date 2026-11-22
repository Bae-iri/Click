package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.LauncherAppSearch;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in One UI regression. Reads launch surfaces locally and never taps an app icon. */
@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
public class LauncherFeedRecoveryDeviceTest {
    private static final String LAUNCHER = "com.sec.android.app.launcher";
    private static final String NEWS = "com.samsung.android.app.spage";
    private static final Set<String> ABSENT_APP = Set.of("CLICK launcher regression unused label");
    private UiAutomation automation;

    @Before public void startAtHome() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("launcherFeedRecovery")));
        automation = InstrumentationRegistry.getInstrumentation().getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        var info = automation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        automation.setServiceInfo(info);
        reconnectSelectedService();
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
        awaitLauncher(this::workspace);
        automation.waitForIdle(250, 3000);
    }

    // Instrumenting the main APK can disconnect its already-enabled accessibility service.
    private void reconnectSelectedService() {
        if (ClickAccessibilityService.accessibilityWindowManager()!=null) return;
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        var resolver=context.getContentResolver();
        var target=new ComponentName(context,ClickAccessibilityService.class);
        String key=Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES;
        String original=Settings.Secure.getString(resolver,key);
        var services=new java.util.ArrayList<>(List.of(original==null ? new String[0] : original.split(":")));
        assertTrue("Enable CLICK accessibility before running the device test",
                services.removeIf(value -> target.equals(ComponentName.unflattenFromString(value))));
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS);
        try {
            try {
                Settings.Secure.putString(resolver,key,String.join(":",services));
                SystemClock.sleep(350);
            } finally { Settings.Secure.putString(resolver,key,original); }
        } finally { automation.dropShellPermissionIdentity(); }
    }

    @After public void returnHome() {
        if (automation != null) automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    }

    @Test public void onlyTheDrawerPagerCanMoveAndAReturnToHomeCancelsIt() throws Exception {
        var search = new LauncherAppSearch(ABSENT_APP);
        var home = awaitLauncher(this::workspace);
        var open = search.next(home);
        assertEquals(LauncherAppSearch.Kind.OPEN_DRAWER, open.kind());
        assertTrue("upward drawer entry", ClickAccessibilityService.performLauncherSearch(home, open, ABSENT_APP));
        awaitLauncher(LauncherAppSearch::drawerScrollable);
        automation.waitForIdle(250, 3000);
        var drawer = awaitLauncher(LauncherAppSearch::drawerScrollable);
        var scroll = search.next(drawer);
        assertEquals(LauncherAppSearch.Kind.SCROLL, scroll.kind());
        assertTrue("scroll inside the confirmed drawer", ClickAccessibilityService.performLauncherSearch(drawer, scroll, ABSENT_APP));
        Predicate<ScreenSnapshot> differentPage = s -> LauncherAppSearch.drawerScrollable(s)
                && !drawer.navigation.fingerprint.equals(s.navigation.fingerprint);
        var moved = settledDrawer(differentPage);
        var restore = new LauncherAppSearch.Step(LauncherAppSearch.Kind.SCROLL, null, !scroll.forward(), "restore_drawer_page");
        assertTrue(ClickAccessibilityService.performLauncherSearch(moved, restore, ABSENT_APP));
        awaitLauncher(s -> LauncherAppSearch.drawerScrollable(s) && drawer.navigation.fingerprint.equals(s.navigation.fingerprint));
        assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME));
        var returned = awaitLauncher(this::workspace);
        search.observeLocation(returned);
        assertFalse(ClickAccessibilityService.performLauncherSearch(drawer, scroll, ABSENT_APP));
        assertFalse(ClickAccessibilityService.performLauncherSearch(returned, scroll, ABSENT_APP));
        assertEquals(LauncherAppSearch.Kind.FINISHED, search.next(awaitLauncher(this::workspace)).kind());
    }

    @Test public void visitingSamsungNewsAndReturningDoesNotRestartHomeSearch() throws Exception {
        var search = new LauncherAppSearch(ABSENT_APP);
        var home = awaitLauncher(this::workspace);
        assertEquals(LauncherAppSearch.Kind.OPEN_DRAWER, search.next(home).kind());
        manualSwipe(home, true);
        long end = SystemClock.uptimeMillis() + 5000;
        boolean observedNews = false;
        do {
            var root = automation.getRootInActiveWindow();
            if (root != null) try {
                if (NEWS.contentEquals(String.valueOf(root.getPackageName()))) {
                    search.observeLocation(new ScreenSnapshot(NEWS, java.time.Instant.now().toString(), home.screenWidth, home.screenHeight, List.of(), root.getWindowId()));
                    observedNews = true;
                }
            } finally { root.recycle(); }
            if (!observedNews) SystemClock.sleep(60);
        } while (!observedNews && SystemClock.uptimeMillis() < end);
        assertTrue("Samsung News reached the foreground", observedNews);
        manualSwipe(home, false);
        for (int i = 0; i < 3; i++) {
            var returned=awaitLauncher(s -> LauncherAppSearch.safe(s) && !LauncherAppSearch.isDrawer(s));
            assertEquals(LauncherAppSearch.Kind.FINISHED, search.next(returned).kind());
            SystemClock.sleep(250);
        }
    }

    // Gesture dispatch is asynchronous. Its animation can start after UiAutomation already reports idle.
    private ScreenSnapshot settledDrawer(Predicate<ScreenSnapshot> matches) {
        long deadline=SystemClock.uptimeMillis()+5500, since=0;
        String previous=null;
        do {
            var screen=ClickAccessibilityService.freshSnapshot();
            if(screen!=null && LAUNCHER.equals(screen.packageName) && matches.test(screen)) {
                String page=LauncherAppSearch.fingerprint(screen);
                if (!page.equals(previous)) { previous=page; since=SystemClock.uptimeMillis(); }
                else if(SystemClock.uptimeMillis()-since>=500)return screen;
            } else previous=null;
            SystemClock.sleep(60);
        } while(SystemClock.uptimeMillis()<deadline);
        throw new AssertionError("Drawer page did not settle");
    }

    private boolean workspace(ScreenSnapshot screen) {
        return !LauncherAppSearch.isDrawer(screen) && screen.nodes.stream().anyMatch(n ->
                n.visibleToUser && n.resourceId != null && n.resourceId.endsWith(":id/workspace"));
    }

    private void manualSwipe(ScreenSnapshot home, boolean enterNews) throws Exception {
        int left = home.screenWidth * 16 / 100, right = home.screenWidth * 85 / 100;
        int y = home.screenHeight * 55 / 100;
        String command = "input swipe "+(enterNews ? left : right)+" "+y+" "+(enterNews ? right : left)+" "+y+" 320";
        try (var output = new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))) {
            byte[] buffer = new byte[256];
            while (output.read(buffer) != -1) { }
        }
    }

    private ScreenSnapshot awaitLauncher(Predicate<ScreenSnapshot> matches) {
        long end = SystemClock.uptimeMillis() + 5500;
        do {
            var screen = ClickAccessibilityService.freshSnapshot();
            if (screen != null && LAUNCHER.equals(screen.packageName) && matches.test(screen)) return screen;
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Expected One UI surface was not observed");
    }
}
