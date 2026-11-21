package com.click.ui.tutorial;

import android.app.Application;
import android.graphics.Rect;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ClueCoachPlacementTest {
    @Test public void coachAvoidsTheActualTargetPanelAndKeyboardTogether() {
        Rect panel = new Rect(20, 50, 1060, 500), target = new Rect(100, 1200, 980, 1400);
        Rect keyboard = new Rect(0, 1800, 1080, 2400);
        Rect space = ClueCoachPlacement.available(1080, 40, 2360, 20, panel, target, keyboard);
        assertTrue(space.height() > 0);
        assertFalse(Rect.intersects(space, panel)); assertFalse(Rect.intersects(space, target));
        assertFalse(Rect.intersects(space, keyboard));
        assertTrue(space.top >= 40 && space.bottom <= 2360);
    }
    @Test public void overlappingAndOffscreenTargetsNeverProduceAnInvalidGap() {
        Rect space = ClueCoachPlacement.available(1080, 40, 2360, 20,
                new Rect(0, -100, 1000, 700), new Rect(0, 600, 1080, 1500), new Rect(0, 1800, 1080, 2600));
        assertEquals(new Rect(0, 1520, 1080, 1780), space);
        Rect full = ClueCoachPlacement.available(1080, 40, 2360, 20, new Rect(0, 0, 1080, 2400));
        assertEquals(0, full.height());
    }
}
