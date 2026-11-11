package com.click.service.accessibility;

import android.app.Application;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.SettingsScreenPrivacy;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class InputHintCollectionTest {
    @Test public void nativeHintIsNotMistakenForAUserQueryOnAnySettingsPage() throws Exception {
        for (boolean hint : new boolean[]{true, false}) {
            AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
            when(node.getClassName()).thenReturn("android.widget.EditText");
            when(node.getPackageName()).thenReturn("com.android.settings");
            when(node.getViewIdResourceName()).thenReturn("android:id/search_src_text");
            when(node.getText()).thenReturn(hint ? "검색" : "PRIVATE_QUERY");
            when(node.isShowingHintText()).thenReturn(hint);
            when(node.isEditable()).thenReturn(true);
            when(node.isVisibleToUser()).thenReturn(true);
            when(node.isEnabled()).thenReturn(true);
            doAnswer(call -> { ((Rect) call.getArgument(0)).set(0, 200, 1000, 350); return null; })
                    .when(node).getBoundsInScreen(any(Rect.class));
            ScreenSnapshot snapshot = new AccessibilitySnapshotCollector().collect(node, "com.android.settings", 1080, 2400, null);
            JSONObject raw = new JSONObject(snapshot.toJson()).getJSONArray("nodes").getJSONObject(0);
            assertTrue(raw.isNull("text"));
            assertEquals(!hint, raw.getJSONObject("extras").optBoolean("input_content_present"));
            assertFalse(snapshot.toJson().contains("PRIVATE_QUERY"));
        }
    }
}
