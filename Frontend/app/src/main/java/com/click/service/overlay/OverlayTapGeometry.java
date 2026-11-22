package com.click.service.overlay;

import android.view.View;

/** Only actual interactive view bounds can intercept a physical tap. */
public final class OverlayTapGeometry {
    private OverlayTapGeometry() {}
    public static boolean covers(View view,int[] target) {
        if(target==null || target.length!=4 || target[2]<=target[0] || target[3]<=target[1]
                || view==null || !view.isShown() || view.getAlpha()<=0)return false;
        int[] xy=new int[2];view.getLocationOnScreen(xy);
        long x=((long)target[0]+target[2])/2,y=((long)target[1]+target[3])/2;
        return x>=xy[0] && x<(long)xy[0]+view.getWidth()
            && y>=xy[1] && y<(long)xy[1]+view.getHeight();
    }
}
