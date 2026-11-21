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

/** Uses real windows to verify that new highlights leave the user's icon position unchanged. */
@RunWith(AndroidJUnit4.class)
public class StationaryOverlayWindowTest {
    @Test public void collapsedIconAndCaptionStayPutWhenHighlightsChange() throws Exception {
        ClickOverlayService service = new ClickOverlayService();
        ClickOverlayLayout[] control = {null};
        GuideOverlayView[] guide = {null};
        TaskFlowOverlayView[] bubble = {null};
        WindowManager[] manager = {null};
        int[][] targets = new int[2][];
        int[] initialPosition = new int[2];
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
                        bubble[0] = new TaskFlowOverlayView(activity, new TaskFlowOverlayView.Listener() {
                            public void chooseGoal(org.json.JSONObject goal) {} public void clarify(String value) {}
                            public void rephrase(String value) {} public void result(String value) {}
                            public void adjust(String value) {} public void control(String value) {}
                        });
                        bubble[0].progress("글자 크기", null, "디스플레이를 눌러주세요.");
                        bubble[0].setMinimizedProgress(true);
                        WindowManager.LayoutParams caption = new WindowManager.LayoutParams(
                                bubble[0].getWindowWidth(width), WindowManager.LayoutParams.WRAP_CONTENT,
                                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, params.flags,
                                android.graphics.PixelFormat.TRANSLUCENT);
                        caption.gravity = Gravity.TOP | Gravity.LEFT; caption.token = params.token;
                        manager[0].addView(bubble[0], caption);
                        set(service, "taskFlowView", bubble[0]); set(service, "taskFlowParams", caption);
                        invoke(service, "placeProgressBubble");
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> control[0].getLocationOnScreen(initialPosition));
                for (int index = 0; index < targets.length; index++) {
                    int[] target = targets[index];
                    scenario.onActivity(activity -> {
                        try {
                            set(service, "guideOverlayView", guide[0]);
                            set(service, "lastTargetBounds", target);
                            Method show = ClickOverlayService.class.getDeclaredMethod("showGuideOverlay", int[].class, String.class, int.class);
                            show.setAccessible(true); show.invoke(service, target, "tap", 0);
                            invoke(service, "placeProgressBubble");
                        } catch (Exception error) { throw new AssertionError(error); }
                    });
                    SystemClock.sleep(500);
                    final Rect[] placed = {null};
                    scenario.onActivity(activity -> {
                        int[] position = new int[2]; control[0].getLocationOnScreen(position);
                        placed[0] = new Rect(position[0], position[1], position[0] + control[0].getWidth(), position[1] + control[0].getHeight());
                        assertEquals(initialPosition[0], placed[0].left);
                        assertEquals(initialPosition[1], placed[0].top);
                        int[] captionPosition = new int[2]; bubble[0].getLocationOnScreen(captionPosition);
                        Rect caption = new Rect(captionPosition[0], captionPosition[1],
                                captionPosition[0] + bubble[0].getWidth(), captionPosition[1] + bubble[0].getHeight());
                        int gap = caption.bottom <= placed[0].top ? placed[0].top - caption.bottom
                                : caption.top - placed[0].bottom;
                        assertEquals(10 * activity.getResources().getDisplayMetrics().density, gap, 2f);
                        assertTrue(caption.left <= placed[0].centerX() && caption.right >= placed[0].centerX());
                        assertTrue(caption.top >= 0 && caption.bottom <= activity.getResources().getDisplayMetrics().heightPixels);
                        assertTrue(placed[0].top >= 0);
                        assertTrue(placed[0].bottom <= activity.getResources().getDisplayMetrics().heightPixels);
                    });
                    Bitmap shot = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                            android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
                    assertNotNull(shot);
                    try (FileOutputStream out = new FileOutputStream(new File(
                            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "stationary-overlay-" + index + ".png"))) {
                        shot.compress(Bitmap.CompressFormat.PNG, 100, out);
                    }
                    shot.recycle();
                }
                scenario.onActivity(activity -> {
                    try {
                        WindowManager.LayoutParams icon = (WindowManager.LayoutParams) control[0].getLayoutParams();
                        int dockX = icon.x, dockY = icon.y;
                        control[0].setCallback(service);
                        control[0].openMenu();
                        control[0].openText();
                        control[0].collapse();
                        assertEquals("An edge-clamped input panel must return to the original icon", dockX, icon.x);
                        assertEquals(dockY, icon.y);

                        set(service, "taskFlowCollapsed", false);
                        bubble[0].setMinimizedProgress(false);
                        Method place = ClickOverlayService.class.getDeclaredMethod("placeTaskFlow", int[].class, boolean.class);
                        place.setAccessible(true);
                        int[] topControl = {0, 0, icon.width, icon.height};
                        place.invoke(service, topControl, true);
                        WindowManager.LayoutParams card = (WindowManager.LayoutParams) bubble[0].getLayoutParams();
                        assertEquals(Gravity.BOTTOM, card.gravity & Gravity.VERTICAL_GRAVITY_MASK);
                        place.invoke(service, null, true);
                        assertEquals("A status refresh has no reason to move the request card",
                                Gravity.BOTTOM, card.gravity & Gravity.VERTICAL_GRAVITY_MASK);
                        int screen = activity.getResources().getDisplayMetrics().heightPixels;
                        place.invoke(service, new int[]{0, screen - icon.height, icon.width, screen}, true);
                        assertEquals("A new target under the card must remain reachable",
                                Gravity.TOP, card.gravity & Gravity.VERTICAL_GRAVITY_MASK);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
            } finally {
                scenario.onActivity(activity -> {
                    if (manager[0] != null && bubble[0] != null && bubble[0].isAttachedToWindow())
                        manager[0].removeViewImmediate(bubble[0]);
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
