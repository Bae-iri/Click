package com.click.ui.overlay;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public final class GuideTargetGeometryTest {
    @Test public void statusBarOffsetDoesNotChangeTargetSize() {
        int[] screen = {24, 320, 1056, 408};
        assertArrayEquals(new float[]{24, 248, 1056, 336},
                GuideTargetGeometry.toLocal(screen, 0, 72, 1080, 2328), 0f);
        assertArrayEquals(new int[]{24, 320, 1056, 408}, screen);
    }

    @Test public void edgeToEdgeOriginNeedsNoCorrectionOrExtraPadding() {
        assertArrayEquals(new float[]{24, 320, 1056, 408},
                GuideTargetGeometry.toLocal(new int[]{24, 320, 1056, 408},
                        0, 0, 1080, 2400), 0f);
    }

    @Test public void horizontalAndVerticalWindowOffsetsAreBothApplied() {
        assertArrayEquals(new float[]{40, 100, 440, 180},
                GuideTargetGeometry.toLocal(new int[]{640, 200, 1040, 280},
                        600, 100, 480, 900), 0f);
    }

    @Test public void changedOriginIsUsedOnNextDraw() {
        int[] screen = {100, 200, 500, 300};
        assertArrayEquals(new float[]{100, 150, 500, 250},
                GuideTargetGeometry.toLocal(screen, 0, 50, 1080, 2350), 0f);
        assertArrayEquals(new float[]{50, 200, 450, 300},
                GuideTargetGeometry.toLocal(screen, 50, 0, 2350, 1080), 0f);
    }

    @Test public void partiallyVisibleTargetIsClippedAfterCoordinateConversion() {
        assertArrayEquals(new float[]{0, 0, 400, 100},
                GuideTargetGeometry.toLocal(new int[]{80, 20, 700, 250},
                        100, 50, 400, 100), 0f);
    }

    @Test public void invalidAndOffscreenTargetsAreNotDrawn() {
        assertNull(GuideTargetGeometry.toLocal(null, 0, 0, 100, 100));
        assertNull(GuideTargetGeometry.toLocal(new int[]{1, 2}, 0, 0, 100, 100));
        assertNull(GuideTargetGeometry.toLocal(new int[]{10, 10, 10, 40}, 0, 0, 100, 100));
        assertNull(GuideTargetGeometry.toLocal(new int[]{40, 10, 10, 40}, 0, 0, 100, 100));
        assertNull(GuideTargetGeometry.toLocal(new int[]{0, 0, 10, 10}, 0, 0, 0, 0));
        assertNull(GuideTargetGeometry.toLocal(new int[]{100, 20, 150, 40}, 0, 0, 100, 100));
        assertNull(GuideTargetGeometry.toLocal(new int[]{10, 10, 50, 30}, 0, 40, 100, 100));
    }
}
