package com.click.data.snapshot;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisionImageRegionTest {
    @Test public void restoresCropOffsetAndUpscaleToPhysicalScreenPixels() {
        VisionImageRegion crop = new VisionImageRegion(20, 100, 180, 300);
        assertArrayEquals(new int[]{30, 120, 50, 140},
                crop.toScreen(new int[]{15, 30, 45, 60}, 240, 300));
    }
    @Test public void emptyOrInvalidFocusUsesOverlappingTilesCoveringEntireScreen() {
        for (int[] focus : new int[][]{null, {-1, 0, 100, 100}, {0, 0, 0, 100}}) {
            VisionImageRegion[] regions = VisionImageRegion.regions(1080, 2400, focus);
            assertEquals(2, regions.length);
            assertEquals(0, regions[0].top);
            assertEquals(2400, regions[1].bottom);
            assertTrue(regions[0].bottom > regions[1].top);
        }
    }
    @Test public void validMenuContainerIsUsedWithoutChangingItsBounds() {
        VisionImageRegion[] regions = VisionImageRegion.regions(1080, 2400, new int[]{20, 100, 1060, 2200});
        assertEquals(1, regions.length);
        assertArrayEquals(new int[]{20, 100, 1060, 2200}, regions[0].toScreen(new int[]{0, 0, 1560, 3150}, 1560, 3150));
    }
}
