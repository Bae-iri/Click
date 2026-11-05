package com.click.ui.overlay;

import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import com.click.service.overlay.ClickOverlayService;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses real application windows and the service's animation without starting a server session. */
@RunWith(AndroidJUnit4.class)
public class ClueFollowWindowTest {
    @Test public void companionFollowsTwoHighlightsWithoutCoveringEither() throws Exception {
        ClickOverlayService service = new ClickOverlayService();
        ClickOverlayLayout[] control = {null};
        GuideOverlayView[] guide = {null};
        WindowManager[] manager = {null};
        int[][] targets = new int[2][];
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    try {
                        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class);
                        attach.setAccessible(true); attach.invoke(service, activity);
                        float density = activity.getResources().getDisplayMetrics().density;
                        int width = activity.getResources().getDisplayMetrics().widthPixels;
                        int height = activity.getResources().getDisplayMetrics().heightPixels;
                        int size = Math.round(64 * density), margin = Math.round(20 * density);
                        targets[0] = new int[]{margin, height / 3, width - margin, height / 3 + Math.round(52 * density)};
                        targets[1] = new int[]{margin, height * 2 / 3, width - margin, height * 2 / 3 + Math.round(52 * density)};
                        guide[0] = new GuideOverlayView(activity); guide[0].setBackgroundColor(0xFFF5F8FF);
                        activity.setContentView(guide[0]);
                        control[0] = new ClickOverlayLayout(activity);
                        WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size,
                                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                                android.graphics.PixelFormat.TRANSLUCENT);
                        params.gravity = Gravity.TOP | Gravity.LEFT;
                        params.token = activity.getWindow().getDecorView().getWindowToken();
                        params.x = width - size - margin; params.y = height - size - margin * 3;
                        manager[0] = activity.getWindowManager(); manager[0].addView(control[0], params);
                        set(service, "overlayView", control[0]); set(service, "windowManager", manager[0]);
                        set(service, "windowParams", params); set(service, "taskFlowCollapsed", true);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                int previousTop = -1;
                for (int index = 0; index < targets.length; index++) {
                    int[] target = targets[index];
                    scenario.onActivity(activity -> {
                        try {
                            guide[0].showTarget(target, "tap", 0);
                            set(service, "clueFollowBounds", target);
                            set(service, "clueFollowAction", "tap");
                            assertEquals(true, invoke(service, "followGuideWithClue"));
                        } catch (Exception error) { throw new AssertionError(error); }
                    });
                    SystemClock.sleep(500);
                    final Rect[] placed = {null};
                    scenario.onActivity(activity -> {
                        int[] position = new int[2]; control[0].getLocationOnScreen(position);
                        placed[0] = new Rect(position[0], position[1], position[0] + control[0].getWidth(), position[1] + control[0].getHeight());
                        assertFalse(Rect.intersects(placed[0], new Rect(target[0], target[1], target[2], target[3])));
                        assertTrue(placed[0].top >= 0);
                        assertTrue(placed[0].bottom <= activity.getResources().getDisplayMetrics().heightPixels);
                    });
                    if (previousTop >= 0) assertNotEquals(previousTop, placed[0].top);
                    previousTop = placed[0].top;
                    Bitmap shot = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
                    assertNotNull(shot);
                    try (FileOutputStream out = new FileOutputStream(new File(
                            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "clue-follow-" + index + ".png"))) {
                        shot.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    shot.recycle();
                }
            } finally {
                scenario.onActivity(activity -> {
                    try { invoke(service, "cancelClueFollow"); }
                    catch (Exception error) { throw new AssertionError(error); }
                    if (manager[0] != null && control[0] != null && control[0].isAttachedToWindow())
                        manager[0].removeViewImmediate(control[0]);
                });
            }
        }
    }
    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
    private static Object invoke(Object instance, String name) throws Exception {
        Method method = instance.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(instance);
    }
}
