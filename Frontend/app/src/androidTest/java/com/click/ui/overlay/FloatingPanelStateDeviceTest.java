package com.click.ui.overlay;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import com.click.R;
import com.click.data.local.GuidancePreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.util.function.Predicate;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FloatingPanelStateDeviceTest {
    @Before public void keepComponentTestsOutOfTheFirstRunTutorial() {
        new GuidancePreferences(InstrumentationRegistry.getInstrumentation().getTargetContext()).markHomeTutorialSeen();
    }
    @Test public void manualMinimizeSurvivesRenderedStepsAndOpeningRestoresTheLatestActions() throws Exception {
        for (float scale : new float[]{1f, 2f}) {
            try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
                Fixture fixture = new Fixture();
                scenario.onActivity(activity -> fixture.attach(activity, scale));
                idle();
                tap(scenario, fixture, v -> v.getId() == R.id.task_flow_collapse);
                int[] width = {0};
                scenario.onActivity(activity -> width[0] = fixture.panel.getWidth());
                for (int stage = 0; stage < 5; stage++) {
                    final int current = stage;
                    scenario.onActivity(activity -> {
                        fixture.render(current);
                        assertTrue("Rendering must retain the manual choice", fixture.panel.isMinimizedProgress());
                    });
                    idle();
                    scenario.onActivity(activity -> {
                        assertTrue(fixture.panel.isMinimizedProgress());
                        assertFalse(fixture.panel.findViewById(R.id.task_flow_collapse).isShown());
                        assertEquals(width[0], fixture.panel.getWidth());
                        assertFalse("No expanded frame may flash between steps", fixture.expandedFrameWhileMinimized);
                        assertTrue(fixture.panel.getHeight() > 0);
                    });
                }
                scenario.onActivity(activity -> fixture.save("panel-choice-minimized-" + scale));
                tap(scenario, fixture, v -> v.getContentDescription() != null
                        && v.getContentDescription().toString().endsWith("눌러서 안내 펼치기"));
                scenario.onActivity(activity -> {
                    assertFalse(fixture.panel.isMinimizedProgress());
                    assertTrue(fixture.panel.findViewById(R.id.task_flow_result_satisfied).isShown());
                    assertFalse(fixture.panel.findViewById(R.id.task_flow_begin).isShown());
                    assertEquals(1, fixture.minimizations); assertEquals(1, fixture.expansions);
                    fixture.save("panel-choice-expanded-" + scale);
                });
                tap(scenario, fixture, v -> v.getId() == R.id.task_flow_result_satisfied);
                scenario.onActivity(activity -> assertEquals("satisfied", fixture.answer));
            }
        }
    }

    @Test public void expandedPanelAndTargetCardNeverMinimizeDuringStepChanges() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            Fixture fixture = new Fixture();
            scenario.onActivity(activity -> fixture.attach(activity, 1f));
            idle();
            scenario.onActivity(activity -> fixture.render(0)); idle();
            tap(scenario, fixture, v -> "디스플레이".contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription()));
            for (int stage = 1; stage < 5; stage++) {
                final int current = stage;
                scenario.onActivity(activity -> fixture.render(current)); idle();
                scenario.onActivity(activity -> {
                    assertFalse(fixture.panel.isMinimizedProgress());
                    assertTrue(fixture.panel.findViewById(R.id.task_flow_collapse).isShown());
                    assertEquals(0, fixture.minimizations); assertEquals(0, fixture.expansions);
                });
            }
        }
    }

    private static void idle() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }

    private static void tap(ActivityScenario<HomeActivity> scenario, Fixture fixture, Predicate<View> match) {
        scenario.onActivity(activity -> {
            View target = find(fixture.panel, match);
            assertNotNull("Visible control must exist", target);
            int[] control = new int[2], root = new int[2];
            target.getLocationOnScreen(control); fixture.host.getLocationOnScreen(root);
            float x = control[0] - root[0] + target.getWidth() / 2f;
            float y = control[1] - root[1] + target.getHeight() / 2f;
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, x, y, 0);
            fixture.host.dispatchTouchEvent(down); fixture.host.dispatchTouchEvent(up);
            down.recycle(); up.recycle();
        });
        idle();
    }

    private static View find(View view, Predicate<View> match) {
        if (view.isShown() && match.test(view)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), match);
            if (found != null) return found;
        }
        return null;
    }

    private static final class Fixture implements TaskFlowOverlayView.Listener {
        FrameLayout host;
        TaskFlowOverlayView panel;
        int minimizations, expansions;
        boolean expandedFrameWhileMinimized;
        String answer;

        void attach(HomeActivity activity, float scale) {
            Configuration config = new Configuration(activity.getResources().getConfiguration());
            config.fontScale = scale;
            panel = new TaskFlowOverlayView(activity.createConfigurationContext(config), this);
            host = new FrameLayout(activity); host.setBackgroundColor(0xFFEAF0FA);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(panel.getWindowWidth(activity.getResources().getDisplayMetrics().widthPixels), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            params.topMargin = Math.round(24 * activity.getResources().getDisplayMetrics().density);
            host.addView(panel, params); activity.setContentView(host);
            panel.requestConfirmation("글자 크기 키워줘", "다시 입력하기", () -> {});
            panel.getViewTreeObserver().addOnDrawListener(() -> {
                if (panel.isMinimizedProgress() && panel.findViewById(R.id.task_flow_collapse).isShown())
                    expandedFrameWhileMinimized = true;
            });
        }
        void render(int stage) {
            try {
                if (stage == 0) {
                    panel.progress(null, null, "디스플레이를 눌러주세요.", false);
                    panel.setGuidanceTarget("디스플레이");
                } else if (stage == 1) {
                    panel.waitingProgress("다음 항목을 찾고 있어요."); panel.setAutomaticProgress(true);
                } else if (stage == 2) panel.observationRecovery("화면을 확인하지 못했어요.", () -> {});
                else if (stage == 3) panel.paused();
                else panel.result(new JSONObject(), true);
            } catch (Exception error) { throw new AssertionError(error); }
        }
        private void resize() {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
            params.width = panel.getWindowWidth(host.getWidth()); panel.setLayoutParams(params);
        }
        public void collapse() { minimizations++; panel.setMinimizedProgress(true); resize(); }
        public void expand() { expansions++; panel.setMinimizedProgress(false); resize(); }
        public void chooseGoal(JSONObject goal) {} public void clarify(String choice) {}
        public void rephrase(String text) {} public void result(String value) { answer = value; }
        public void adjust(String reason) {} public void control(String command) {}

        void save(String name) {
            Bitmap bitmap = Bitmap.createBitmap(panel.getWidth(), panel.getHeight(), Bitmap.Config.ARGB_8888);
            panel.draw(new Canvas(bitmap));
            try (FileOutputStream out = new FileOutputStream(new File(panel.getContext().getCacheDir(), name + ".png"))) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception error) { throw new AssertionError(error); }
            bitmap.recycle();
        }
    }
}
