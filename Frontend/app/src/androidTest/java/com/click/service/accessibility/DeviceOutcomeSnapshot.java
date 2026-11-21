package com.click.service.accessibility;

import android.app.UiAutomation;
import android.content.Context;
import android.provider.Settings;
import com.click.data.snapshot.ScreenSnapshot;

/** Capture the foreground settings window through the production collector, without enabling another service. */
public final class DeviceOutcomeSnapshot {
    private DeviceOutcomeSnapshot() {}
    public static ScreenSnapshot settings(UiAutomation ui,Context context) {
        var root=ui.getRootInActiveWindow();
        if(root==null)return null;
        try {
            if(!"com.android.settings".contentEquals(String.valueOf(root.getPackageName())))return null;
            float scale=Settings.System.getFloat(context.getContentResolver(),Settings.System.FONT_SCALE,1f);
            return new AccessibilitySnapshotCollector().collect(root,"com.android.settings",context,scale);
        }finally{root.recycle();}
    }
}
