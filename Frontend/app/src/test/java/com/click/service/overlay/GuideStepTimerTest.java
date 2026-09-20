package com.click.service.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class GuideStepTimerTest {
    @Test public void measuresOnlyFirstTapInGuidedPackage() {
        GuideStepTimer timer = new GuideStepTimer();
        timer.start("session-1", "S1_SETTINGS_HOME", 1,
                "com.android.launcher3", 1000);
        assertNull(timer.firstTap("com.other.app", 1100));
        GuideStepTimer.Observation observed = timer.firstTap("com.android.launcher3", 4500);
        assertNotNull(observed);
        assertEquals("session-1", observed.sessionId);
        assertEquals("S1_SETTINGS_HOME", observed.stateId);
        assertEquals(1, observed.step);
        assertEquals(3500L, observed.durationMs);
        assertNull(timer.firstTap("com.android.launcher3", 5000));
    }

    @Test public void ignoresStaleEventsAndResetsForNextGuide() {
        GuideStepTimer timer = new GuideStepTimer();
        timer.start("session-1", "S1_SETTINGS_HOME", 1, "launcher", 2000);
        assertNull(timer.firstTap("launcher", 1999));
        timer.start("session-1", "S2_DISPLAY_MENU", 2, "settings", 3000);
        assertNull(timer.firstTap("launcher", 4000));
        assertEquals(700L, timer.firstTap("settings", 3700).durationMs);
        timer.clear();
        assertNull(timer.firstTap("settings", 3800));
    }

    @Test public void recordsHomeScreenAsStepZero() {
        GuideStepTimer timer = new GuideStepTimer();
        timer.start("session-1", "S0_UNKNOWN", 0,
                "com.android.launcher3", 1000);
        assertEquals(2000L, timer.firstTap("com.android.launcher3", 3000).durationMs);
    }
}
