package com.click;

import android.view.View;
import com.click.service.overlay.OverlayTapGeometry;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class OverlayTapGeometryTest {
    private View panel() {
        View view=mock(View.class);
        when(view.isShown()).thenReturn(true);when(view.getAlpha()).thenReturn(1f);
        when(view.getWidth()).thenReturn(956);when(view.getHeight()).thenReturn(636);
        doAnswer(i->{int[] p=i.getArgument(0);p[0]=62;p[1]=160;return null;}).when(view).getLocationOnScreen(any(int[].class));
        return view;
    }
    @Test public void reproducedWifiTitleIsCoveredButBottomControlIsNot(){
        View p=panel();
        assertTrue(OverlayTapGeometry.covers(p,new int[]{68,356,232,439}));
        assertFalse(OverlayTapGeometry.covers(p,new int[]{0,1675,1080,1901}));
    }
    @Test public void hiddenOrTransparentPanelsNeverBlock(){
        View p=panel();when(p.isShown()).thenReturn(false);
        assertFalse(OverlayTapGeometry.covers(p,new int[]{68,356,232,439}));
        when(p.isShown()).thenReturn(true);when(p.getAlpha()).thenReturn(0f);
        assertFalse(OverlayTapGeometry.covers(p,new int[]{68,356,232,439}));
    }
    @Test public void invalidTargetCannotAuthorizeATap(){
        View p=panel();assertFalse(OverlayTapGeometry.covers(p,null));
        assertFalse(OverlayTapGeometry.covers(p,new int[]{0,0}));
        assertFalse(OverlayTapGeometry.covers(p,new int[]{68,356,68,439}));
    }
}
