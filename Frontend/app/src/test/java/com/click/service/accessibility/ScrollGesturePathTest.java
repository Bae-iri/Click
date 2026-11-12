package com.click.service.accessibility;

import android.graphics.Rect;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=android.app.Application.class)
public class ScrollGesturePathTest {
    private final Rect viewport = new Rect(0,300,1080,2200);
    private void safe(float[] path, List<Rect> obstacles) {
        assertNotNull(path);
        float top=Math.min(path[1],path[3]), bottom=Math.max(path[1],path[3]);
        assertEquals(270f,bottom-top,0.01f);
        assertTrue(top>=viewport.top+24 && bottom<=viewport.bottom-24);
        for(Rect r:obstacles) assertFalse("Swipe must avoid the actual control rectangle",
                path[0]>=r.left-24 && path[0]<=r.right+24 && bottom>=r.top-24 && top<=r.bottom+24);
    }
    @Test public void fullWidthBrightnessAndSwitchRowsLeaveSafeSpaceElsewhere() {
        List<Rect> controls=List.of(new Rect(0,500,1080,700),new Rect(0,800,1080,1000));
        float[] path=ScrollGesturePath.vertical(viewport,270,true,controls,24);
        safe(path,controls);assertTrue(path[1]>path[3]);
    }
    @Test public void centerSliderMovesTheSwipeIntoAnotherVerticalGap() {
        List<Rect> controls=List.of(new Rect(0,1100,1080,1450));
        safe(ScrollGesturePath.vertical(viewport,270,true,controls,24),controls);
    }
    @Test public void overlayOnOneSideDoesNotBlockTheOtherSide() {
        List<Rect> controls=List.of(new Rect(400,300,1080,2200));
        safe(ScrollGesturePath.vertical(viewport,270,true,controls,24),controls);
    }
    @Test public void upwardRevealHasTheOppositeDirectionWithTheSameProtection() {
        List<Rect> controls=List.of(new Rect(0,1100,1080,1450));
        float[] path=ScrollGesturePath.vertical(viewport,270,false,controls,24);
        safe(path,controls);assertTrue(path[1]<path[3]);
    }
    @Test public void entirelyCoveredViewportCannotDispatch() {
        assertNull(ScrollGesturePath.vertical(viewport,270,true,List.of(new Rect(viewport)),24));
    }
    @Test public void overlappingControlsAndInvalidDistancesCannotProduceUnsafePaths() {
        assertNull(ScrollGesturePath.vertical(viewport,270,true,
                List.of(new Rect(0,250,1080,1500),new Rect(0,1400,1080,2300)),24));
        assertNull(ScrollGesturePath.vertical(viewport,0,true,List.of(),24));
        assertNull(ScrollGesturePath.vertical(viewport,Integer.MAX_VALUE,true,List.of(),24));
    }
}
