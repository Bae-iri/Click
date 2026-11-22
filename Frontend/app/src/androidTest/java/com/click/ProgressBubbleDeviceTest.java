package com.click;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inspector.WindowInspector;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.core.permission.PermissionStatus;
import com.click.data.local.GuidancePreferences;
import com.click.service.overlay.ClickOverlayService;
import com.click.ui.overlay.ClickOverlayLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProgressBubbleDeviceTest {
    private final Instrumentation device = InstrumentationRegistry.getInstrumentation();

    @Test public void attachedBubbleUpdatesNextToTheIconAndOpensOnTap() throws Exception {
        var app = device.getTargetContext();
        var ui = device.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        org.junit.Assume.assumeTrue(PermissionStatus.isAccessibilityEnabled(app));
        TutorialReadyFlowTest.reconnectIfNeeded(app, ui);
        new GuidancePreferences(app).markHomeTutorialSeen();
        HomeActivity home = (HomeActivity) device.startActivitySync(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Intent serviceIntent = new Intent(app, ClickOverlayService.class);
        try {
            device.runOnMainSync(() -> {
                home.startService(serviceIntent);
                home.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.getPackageName())));
            });
            ClickOverlayLayout icon = (ClickOverlayLayout) await(() -> findWindow(ClickOverlayLayout.class, View.NO_ID));
            ClickOverlayService service = (ClickOverlayService) icon.getContext();
            Method ensure = ClickOverlayService.class.getDeclaredMethod("ensureTaskFlow");
            Method waiting = ClickOverlayService.class.getDeclaredMethod("showTaskWait", String.class);
            ensure.setAccessible(true); waiting.setAccessible(true);
            // Supply two queue states to the running service without enqueuing a remote task.
            device.runOnMainSync(() -> call(ensure, service));
            String first = "부탁하신 내용을 확인하고 있어요.";
            device.runOnMainSync(() -> call(waiting, service, first));
            View bubble = caption(first);
            String next = "요청하신 결과가 나왔는지 확인하고 있어요.";
            device.runOnMainSync(() -> call(waiting, service, next));
            assertSame(bubble, caption(next));
            Rect caption = bounds(bubble), logo = bounds(icon);
            assertFalse(Rect.intersects(caption, logo));
            assertTrue("The caption should stay beside the logo", Math.abs(logo.centerX() - caption.centerX()) < caption.width());
            device.waitForIdleSync();
            Bitmap screen = ui.takeScreenshot();
            assertNotNull(screen);
            try {
                Rect crop = new Rect(caption);
                crop.union(logo);
                crop.inset(-Math.round(16 * app.getResources().getDisplayMetrics().density),
                        -Math.round(16 * app.getResources().getDisplayMetrics().density));
                assertTrue(crop.intersect(0, 0, screen.getWidth(), screen.getHeight()));
                Bitmap visible = Bitmap.createBitmap(screen, crop.left, crop.top, crop.width(), crop.height());
                try (var output = new FileOutputStream(new File(app.getCacheDir(), "progress-bubble.png"))) {
                    assertTrue(visible.compress(Bitmap.CompressFormat.PNG, 100, output));
                } finally { visible.recycle(); }
            } finally { screen.recycle(); }
            tap(ui, caption);
            View minimize = await(() -> findWindow(null, R.id.task_flow_collapse));
            tap(ui, bounds(minimize));
            assertSame(bubble, caption(next));
        } finally {
            app.stopService(serviceIntent);
            ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            device.runOnMainSync(home::finish);
        }
    }

    private View caption(String words) {
        return await(() -> {
            TextView view = (TextView) findWindow(null, R.id.task_flow_compact_message);
            return view != null && words.contentEquals(view.getText()) ? view : null;
        });
    }

    private View findWindow(Class<?> type, int id) {
        for (View root : WindowInspector.getGlobalWindowViews()) {
            View found = type == null ? root.findViewById(id) : findType(root, type);
            if (found != null && found.isShown() && found.getWidth() > 0 && found.getHeight() > 0) return found;
        }
        return null;
    }

    private View findType(View view, Class<?> type) {
        if (type.isInstance(view)) return view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            View found = findType(group.getChildAt(i), type);
            if (found != null) return found;
        }
        return null;
    }

    private View await(Supplier<View> read) {
        long until = SystemClock.uptimeMillis() + 8000;
        View[] result = {null};
        do {
            device.waitForIdleSync();
            device.runOnMainSync(() -> result[0] = read.get());
            if (result[0] != null) return result[0];
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("The expected overlay control did not appear");
    }

    private Rect bounds(View view) {
        Rect result = new Rect();
        device.runOnMainSync(() -> {
            int[] at = new int[2];
            view.getLocationOnScreen(at);
            result.set(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
        });
        return result;
    }

    private void tap(UiAutomation ui, Rect target) {
        long down = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            var touch = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, target.centerX(), target.centerY(), 0);
            touch.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { assertTrue(ui.injectInputEvent(touch, true)); } finally { touch.recycle(); }
        }
    }

    private static void call(Method method, ClickOverlayService service, Object... args) {
        try { method.invoke(service, args); } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
