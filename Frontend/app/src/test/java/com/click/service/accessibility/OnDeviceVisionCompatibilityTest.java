package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.os.Looper;
import com.click.data.snapshot.ScreenSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 28}, application = Application.class)
public class OnDeviceVisionCompatibilityTest {
    @Test public void unsupportedAndroidReportsOnceWithoutLoadingScreenshotApis() {
        var service = mock(AccessibilityService.class);
        var calls = new AtomicInteger();
        var screen = new ScreenSnapshot("com.android.settings", "2026-11-18T00:00:00Z", 1080, 2400, List.of(), 1);
        OnDeviceVision.capture(service, null, null, "font_size", "local", null, screen, new OnDeviceVision.Callback() {
            @Override public void onImageCaptured() { fail("No capture is possible on this Android version"); }
            @Override public void onSuccess(ScreenSnapshot ignored, JSONObject snapshot) { fail("No OCR result is possible"); }
            @Override public void onFailure(String code, String message) {
                assertEquals("unsupported", code);
                calls.incrementAndGet();
            }
        });
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11));
        assertEquals(1, calls.get());
        verifyNoInteractions(service);
    }
}
