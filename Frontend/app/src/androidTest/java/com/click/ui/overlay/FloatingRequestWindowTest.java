package com.click.ui.overlay;

import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import com.click.domain.goal.RequirementReview;
import com.click.service.overlay.ClickOverlayService;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real-window input/confirmation rendering; no server, recording or phone settings changes. */
@RunWith(AndroidJUnit4.class)
public class FloatingRequestWindowTest {
    @Test public void sendingTextKeepsTheAsyncConfirmationVisibleUntilTheUserDecides() throws Exception {
        checkHandoff(1f);
    }

    @Test public void largeTextConfirmationKeepsItsControlsVisibleInRealWindows() throws Exception {
        checkHandoff(2f);
    }

    private void checkHandoff(float fontScale) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        var context = instrumentation.getTargetContext();
        new com.click.data.local.GuidancePreferences(context).markHomeTutorialSeen();
        ClickOverlayService service = new ClickOverlayService();
        ClickOverlayLayout[] input = {null};
        TaskFlowOverlayView[] panel = {null};
        WindowManager[] windows = {null};
        String[] submitted = {null};
        boolean[] approved = {false};
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            try {
                scenario.onActivity(activity -> {
                    try {
                        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class);
                        attach.setAccessible(true); attach.invoke(service, activity);
                        View background = new View(activity); background.setBackgroundColor(0xFFF5F8FF);
                        activity.setContentView(background);
                        windows[0] = activity.getWindowManager();
                        var config = new android.content.res.Configuration(activity.getResources().getConfiguration());
                        config.fontScale = fontScale;
                        var panelContext = activity.createConfigurationContext(config);
                        input[0] = new ClickOverlayLayout(panelContext);
                        panel[0] = new TaskFlowOverlayView(panelContext, new TaskFlowOverlayView.Listener() {
                            public void chooseGoal(org.json.JSONObject goal) {} public void clarify(String value) {}
                            public void rephrase(String value) {} public void result(String value) {}
                            public void adjust(String value) {} public void control(String value) {}
                            public void confirmRequirements() { approved[0] = true; }
                        });
                        WindowManager.LayoutParams icon = params(activity, dp(activity, 64), dp(activity, 64));
                        icon.gravity = Gravity.TOP | Gravity.LEFT;
                        icon.x = activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 80);
                        icon.y = activity.getResources().getDisplayMetrics().heightPixels - dp(activity, 220);
                        windows[0].addView(input[0], icon);
                        WindowManager.LayoutParams review = params(activity,
                                panel[0].getWindowWidth(activity.getResources().getDisplayMetrics().widthPixels), -2);
                        windows[0].addView(panel[0], review); panel[0].setVisibility(View.GONE);
                        set(service, "windowManager", windows[0]); set(service, "windowParams", icon);
                        set(service, "overlayView", input[0]); set(service, "taskFlowView", panel[0]);
                        set(service, "taskFlowParams", review);
                        input[0].setCallback(new ClickOverlayLayout.Callback() {
                            public void onModeChanged(ClickOverlayLayout.Mode from, ClickOverlayLayout.Mode to) { service.onModeChanged(from, to); }
                            public void onDragStarted() {} public void onDragBy(float x, float y) {} public void onDragEnded() {}
                            public void onCloseRequested() {}
                            public void onTextSubmitted(String text) {
                                submitted[0] = text;
                                input[0].collapse();
                                panel[0].setVisibility(View.GONE);
                                panel[0].post(() -> {
                                    RequirementReview request = new RequirementReview(text);
                                    request.choose("phone");
                                    panel[0].requirements(request, true);
                                    try {
                                        Method place = ClickOverlayService.class.getDeclaredMethod("placeTaskFlow", int[].class, boolean.class);
                                        place.setAccessible(true); place.invoke(service, null, true);
                                    } catch (Exception error) { throw new AssertionError(error); }
                                });
                            }
                        });
                        input[0].openText(); input[0].setDraftText("휴대폰 글자 크기를 키워줘");
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                instrumentation.waitForIdleSync();
                scenario.onActivity(activity -> editor(input[0]).onEditorAction(EditorInfo.IME_ACTION_SEND));
                instrumentation.waitForIdleSync();
                android.os.SystemClock.sleep(400);
                instrumentation.waitForIdleSync();
                scenario.onActivity(activity -> {
                    assertEquals("휴대폰 글자 크기를 키워줘", submitted[0]);
                    assertTrue(panel[0].isShown()); assertTrue(panel[0].getHeight() > 0);
                    assertEquals(View.GONE, input[0].getVisibility());
                    assertNotNull(panel[0].findViewWithTag("review_summary_text"));
                    assertFalse(approved[0]);
                    FloatingPanelHeader header = panel[0].findViewWithTag("floating_header");
                    for (var action : new android.widget.TextView[]{header.edit, header.collapse}) {
                        assertNotNull(action.getLayout());
                        assertTrue(action.getLayout().getHeight() <= action.getHeight()
                                - action.getPaddingTop() - action.getPaddingBottom());
                    }
                });
                Bitmap screenshot = instrumentation.getUiAutomation(
                        android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
                assertNotNull(screenshot);
                try (var out = new FileOutputStream(new File(context.getCacheDir(), "input-confirmation-handoff-" + fontScale + ".png"))) {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                screenshot.recycle();
            } finally {
                scenario.onActivity(activity -> {
                    for (View view : new View[]{input[0], panel[0]})
                        if (view != null && view.isAttachedToWindow()) windows[0].removeViewImmediate(view);
                });
            }
        }
    }

    private static WindowManager.LayoutParams params(android.app.Activity activity, int width, int height) {
        var params = new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.token = activity.getWindow().getDecorView().getWindowToken();
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; params.y = dp(activity, 24);
        return params;
    }

    private static EditText editor(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            EditText found = editor(((ViewGroup) view).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static int dp(android.content.Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
