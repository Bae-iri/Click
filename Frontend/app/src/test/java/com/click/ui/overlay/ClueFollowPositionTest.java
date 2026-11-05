package com.click.ui.overlay;

import android.graphics.Rect;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class ClueFollowPositionTest {
    @Test public void staysOutsideTargetsAtEveryScreenEdge() {
        Rect safe = new Rect(8, 32, 392, 776);
        for (Rect target : new Rect[]{new Rect(0, 40, 400, 100), new Rect(0, 680, 400, 760),
                new Rect(0, 300, 110, 360), new Rect(290, 300, 400, 360), new Rect(20, 300, 380, 370)}) {
            Rect result = ClueFollowPosition.beside(target, safe, 64, 64, 16, 320, 700);
            assertNotNull(result); assertTrue(safe.contains(result));
            Rect protectedArea = new Rect(target); protectedArea.inset(-16, -16);
            assertFalse(Rect.intersects(protectedArea, result));
        }
    }
    @Test public void followsDistantTargetAndRemainsStableForRepeatedUpdates() {
        Rect safe = new Rect(8, 32, 392, 776), target = new Rect(30, 150, 110, 210);
        Rect result = ClueFollowPosition.beside(target, safe, 64, 64, 16, 320, 700);
        assertTrue(result.top < 300); assertTrue(result.left < 200);
        assertEquals(result, ClueFollowPosition.beside(target, safe, 64, 64, 16, result.left, result.top));
    }
    @Test public void doesNotCoverTargetWhenNoSpaceOrTargetIsOffscreen() {
        Rect safe = new Rect(8, 32, 392, 776);
        assertNull(ClueFollowPosition.beside(new Rect(0, 0, 400, 800), safe, 64, 64, 16, 0, 0));
        assertNull(ClueFollowPosition.beside(new Rect(0, 900, 400, 950), safe, 64, 64, 16, 0, 0));
    }
}
