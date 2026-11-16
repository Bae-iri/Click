package com.click;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import com.click.designpreview.AccessibilityHelpActivity;
import com.click.designpreview.ClickHost;
import com.click.designpreview.DesignPreviewActivity;
import com.click.designpreview.TextSizeSetupActivity;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class StartupOrderTest {
    @Test public void newUserChoosesTextSizeBeforeOnboarding() {
        assertRoute(false, false, TextSizeSetupActivity.class);
    }
    @Test public void signedInUserStillGoesDirectlyHome() {
        assertRoute(true, true, null);
    }
    @Test public void signedInUserWithoutAccessibilityKeepsItsExistingRecoveryRoute() {
        assertRoute(true, false, AccessibilityHelpActivity.class);
    }
    private void assertRoute(boolean signedIn, boolean accessibility, Class<?> target) {
        ClickHost host = mock(ClickHost.class);
        when(host.signedIn()).thenReturn(signedIn);
        when(host.accessibilityEnabled()).thenReturn(accessibility);
        try (var bridge = mockStatic(ClickHost.class);
             var controller = Robolectric.buildActivity(DesignPreviewActivity.class)) {
            DesignPreviewActivity activity = controller.get();
            bridge.when(() -> ClickHost.from(activity)).thenReturn(host);
            controller.setup();
            View root = activity.getWindow().getDecorView();
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 360, 800);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));
            Intent next = shadowOf(activity).getNextStartedActivity();
            if (target == null) {
                assertNull(next); verify(host).openHome(activity);
            } else {
                assertNotNull(next);
                assertEquals(target.getName(), next.getComponent().getClassName());
            }
            assertTrue(activity.isFinishing());
        }
    }
}
