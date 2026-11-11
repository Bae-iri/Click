package com.click.service.accessibility;

import android.app.Application;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class WifiSwitchCollectionTest {
    @Test public void collectorReadsCheckedRatherThanSelected() throws Exception {
        for (boolean checked : new boolean[]{false, true}) {
            AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
            when(node.getClassName()).thenReturn("android.widget.Switch");
            when(node.getPackageName()).thenReturn("com.android.settings");
            when(node.getText()).thenReturn("Wi-Fi");
            when(node.getViewIdResourceName()).thenReturn("com.android.settings:id/switch_widget");
            when(node.isVisibleToUser()).thenReturn(true);
            when(node.isEnabled()).thenReturn(true);
            when(node.isClickable()).thenReturn(true);
            when(node.isCheckable()).thenReturn(true);
            when(node.isChecked()).thenReturn(checked);
            when(node.isSelected()).thenReturn(false);
            when(node.getWindowId()).thenReturn(42);
            doAnswer(call -> { ((Rect) call.getArgument(0)).set(0, 200, 1000, 350); return null; })
                    .when(node).getBoundsInScreen(any(Rect.class));
            ScreenSnapshot snapshot = new AccessibilitySnapshotCollector().collect(node, "com.android.settings", 1080, 2400, null);
            JSONObject raw = new JSONObject(snapshot.toJson()).getJSONArray("nodes").getJSONObject(0);
            assertEquals(checked, raw.getJSONObject("extras").getBoolean("checked"));
            assertFalse(raw.getBoolean("selected"));
        }
    }
}
