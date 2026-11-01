package com.click.ui.overlay;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FloatingInteractionTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    private ActivityScenario<HomeActivity> launchHost() {
        ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class);
        scenario.onActivity(activity -> activity.setContentView(new FloatingClickHost(activity,
                new FloatingClickHost.Listener() {
                    @Override public void onVoiceRequested() {}
                    @Override public void onTextSubmitted(String text) {}
                })));
        instrumentation.waitForIdleSync();
        return scenario;
    }

    @Test public void expandedPanelsKeepInputCompactAndFeedbackReadable() {
        instrumentation.runOnMainSync(() -> {
            ClickOverlayLayout control = new ClickOverlayLayout(instrumentation.getTargetContext());
            control.performClick();
            render(control, "menu");
            control.openVoice();
            control.showRecognizedSpeechResult("글자 크기를 키워줘");
            render(control, "voice");
            findText(control, "취소").performClick();
            clickAncestor(findText(control, "글로 입력하기"));
            assertEquals(ClickOverlayLayout.Mode.TEXT, control.getMode());
            control.setDraftText("글자 크기를 키워줘");
            assertNotNull(findText(control, "글자 크기를 키워줘"));
            assertNull(findText(control, "예: 글자 크기를 키워줘"));
            render(control, "text");
            control.showInputFeedback("연결을 확인한 뒤 다시 보내주세요.");
            assertNotNull(findText(control, "연결을 확인한 뒤 다시 보내주세요."));
            control.collapse();
            control.openText();
            assertNull("Previous feedback must not appear when opening input", findText(control,
                    "연결을 확인한 뒤 다시 보내주세요."));
            assertEquals("글자 크기를 키워줘", control.getDraftText());
        });
    }

    private void render(ClickOverlayLayout control, String name) {
        int width = control.getWindowWidth(control.getMode(), control.getResources().getDisplayMetrics().widthPixels);
        int height = control.getWindowHeight(control.getMode());
        control.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        control.layout(0, 0, width, height);
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        control.draw(new Canvas(image));
        try (FileOutputStream out = new FileOutputStream(new File(control.getContext().getCacheDir(),
                "floating-" + name + ".png"))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception error) { throw new AssertionError(error); }
        image.recycle();
    }

    @Test public void tapOpensMenuAndBothInputModesRemainReachable() {
        try (ActivityScenario<HomeActivity> scenario = launchHost()) {
            scenario.onActivity(activity -> {
                ClickOverlayLayout control = findControl(activity.getWindow().getDecorView());
                assertNotNull("The host must have a real interactive control", control);
                control.performClick();
                assertEquals(ClickOverlayLayout.Mode.MENU, control.getMode());
                assertNotNull(findText(control, "말로 입력하기"));
                clickAncestor(findText(control, "글로 입력하기"));
                assertEquals(ClickOverlayLayout.Mode.TEXT, control.getMode());
                assertNotNull(findText(control, "말로 입력하기"));
            });
        }
    }

    @Test public void holdingShowsTrashAndReleaseKeepsHostedControl() {
        try (ActivityScenario<HomeActivity> scenario = launchHost()) {
            final ClickOverlayLayout[] control = {null};
            final long down = SystemClock.uptimeMillis();
            scenario.onActivity(activity -> {
                control[0] = findControl(activity.getWindow().getDecorView());
                send(control[0], down, MotionEvent.ACTION_DOWN, center(control[0]), center(control[0]));
            });
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 120);
            scenario.onActivity(activity -> {
                assertTrue("Holding exposes trash without hiding the control", control[0].isShown());
                send(control[0], down, MotionEvent.ACTION_UP, center(control[0]), center(control[0]));
                assertEquals(ClickOverlayLayout.Mode.COLLAPSED, control[0].getMode());
            });
        }
    }

    @Test public void movingBeforeHoldDeadlineMovesInsteadOfOpeningOrHiding() {
        try (ActivityScenario<HomeActivity> scenario = launchHost()) {
            final ClickOverlayLayout[] control = {null};
            final long down = SystemClock.uptimeMillis();
            final float[] start = new float[2];
            scenario.onActivity(activity -> {
                control[0] = findControl(activity.getWindow().getDecorView());
                start[0] = control[0].getX(); start[1] = control[0].getY();
                float c = center(control[0]), delta = dp(control[0], 60);
                send(control[0], down, MotionEvent.ACTION_DOWN, c, c);
                send(control[0], down, MotionEvent.ACTION_MOVE, c - delta, c - delta);
                assertTrue(control[0].getX() < start[0]);
                assertTrue(control[0].getY() < start[1]);
            });
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100);
            scenario.onActivity(activity -> {
                float c = center(control[0]), delta = dp(control[0], 60);
                send(control[0], down, MotionEvent.ACTION_UP, c - delta, c - delta);
                assertTrue("Dragging must cancel hold-to-hide", control[0].isShown());
                assertEquals(ClickOverlayLayout.Mode.COLLAPSED, control[0].getMode());
            });
        }
    }

    @Test public void cancelledHoldNeverHidesOrOpens() {
        try (ActivityScenario<HomeActivity> scenario = launchHost()) {
            final ClickOverlayLayout[] control = {null};
            final long down = SystemClock.uptimeMillis();
            scenario.onActivity(activity -> {
                control[0] = findControl(activity.getWindow().getDecorView());
                float c = center(control[0]);
                send(control[0], down, MotionEvent.ACTION_DOWN, c, c);
                send(control[0], down, MotionEvent.ACTION_CANCEL, c, c);
            });
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100);
            scenario.onActivity(activity -> {
                assertTrue(control[0].isShown());
                assertEquals(ClickOverlayLayout.Mode.COLLAPSED, control[0].getMode());
            });
        }
    }

    @Test public void realTimerStartsDraggingExpandedControlOnce() {
        try (ActivityScenario<HomeActivity> scenario = launchHost()) {
            final ClickOverlayLayout[] control = {null};
            final int[] closeCount = {0};
            scenario.onActivity(activity -> {
                control[0] = new ClickOverlayLayout(activity);
                control[0].setCallback(new ClickOverlayLayout.Callback() {
                    public void onModeChanged(ClickOverlayLayout.Mode oldMode, ClickOverlayLayout.Mode newMode) {}
                    public void onDragStarted() { closeCount[0]++; }
                    public void onDragBy(float x, float y) {}
                    public void onDragEnded() {}
                    public void onTextSubmitted(String text) {}
                    public void onCloseRequested() { fail("Long press must show trash before hiding"); }
                });
                control[0].openVoice();
                FrameLayout root = new FrameLayout(activity);
                root.addView(control[0], new FrameLayout.LayoutParams(
                        control[0].getWindowWidth(ClickOverlayLayout.Mode.VOICE, activity.getResources().getDisplayMetrics().widthPixels),
                        control[0].getWindowHeight(ClickOverlayLayout.Mode.VOICE)));
                activity.setContentView(root);
            });
            instrumentation.waitForIdleSync();
            final long down = SystemClock.uptimeMillis();
            scenario.onActivity(activity -> send(control[0], down, MotionEvent.ACTION_DOWN,
                    control[0].getClickCenterX(ClickOverlayLayout.Mode.VOICE),
                    control[0].getClickCenterY(ClickOverlayLayout.Mode.VOICE)));
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100);
            scenario.onActivity(activity -> {
                send(control[0], down, MotionEvent.ACTION_UP,
                        control[0].getClickCenterX(ClickOverlayLayout.Mode.VOICE),
                        control[0].getClickCenterY(ClickOverlayLayout.Mode.VOICE));
                assertEquals(1, closeCount[0]);
                assertEquals(ClickOverlayLayout.Mode.VOICE, control[0].getMode());
            });
        }
    }

    private void send(View view, long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }
    private float center(View view) { return dp(view, 32); }
    private int dp(View view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }
    private ClickOverlayLayout findControl(View view) {
        if (view instanceof ClickOverlayLayout) return (ClickOverlayLayout) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            ClickOverlayLayout found = findControl(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }
    private TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private void clickAncestor(View view) {
        assertNotNull(view);
        while (!view.isClickable()) view = (View) view.getParent();
        view.performClick();
    }
}
