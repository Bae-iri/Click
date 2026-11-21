package com.click.ui.tutorial;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.click.R;
import com.click.data.local.LiveTutorialStore;
import com.click.designpreview.TutorialNarrator;
import com.click.service.overlay.LiveTutorialGuide;
import com.click.ui.overlay.TaskFlowOverlayView;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, qualifiers = "w411dp-h891dp")
@LooperMode(LooperMode.Mode.PAUSED)
public class LiveTutorialGuideTest {
    private ActivityController<Activity> lifecycle;
    private Activity activity;
    private FrameLayout root, liveWindow;
    private TaskFlowOverlayView panel;
    private TaskFlowOverlayView.Listener actions;
    private LiveTutorialStore store;
    private LiveTutorialGuide guide;
    private WindowManager.LayoutParams flags;
    private MockedConstruction<TutorialNarrator> narrators;

    @Before public void setup() {
        narrators = mockConstruction(TutorialNarrator.class);
        lifecycle = Robolectric.buildActivity(Activity.class).setup(); activity = lifecycle.get();
        root = new FrameLayout(activity); activity.setContentView(root);
        actions = mock(TaskFlowOverlayView.Listener.class);
        panel = new TaskFlowOverlayView(activity, actions);
        root.addView(panel, new FrameLayout.LayoutParams(360, -2));
        store = new LiveTutorialStore(activity.getSharedPreferences("live-guide-test", 0));
        guide = new LiveTutorialGuide(activity, new LiveTutorialGuide.Host() {
            public int windowType() { return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY; }
            public void add(View view, WindowManager.LayoutParams params) {
                flags = params; liveWindow = (FrameLayout) view;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
            }
            public void visibility(View view, int visibility) { view.setVisibility(visibility); }
            public void remove(View view) { root.removeView(view); }
        }, store);
        String launch = store.begin("글자 크기 키워줘");
        assertTrue(guide.accept(launch, "글자 크기 키워줘"));
        guide.requestCreated("request", "글자 크기 키워줘");
        guide.sessionStarted("request", "session");
    }
    @After public void cleanup() {
        guide.close(); lifecycle.pause().stop().destroy(); narrators.close();
    }
    private void update() {
        guide.update("request", "session", panel, null, null, "settings.font_size.increase");
        layout(); root.getViewTreeObserver().dispatchOnPreDraw(); layout();
    }
    private void layout() {
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 1080, 2400);
    }
    private String words() { return ((TextView) liveWindow.findViewById(R.id.tutorial_title)).getText().toString(); }
    private RectF hole() throws Exception {
        View coach = liveWindow.findViewById(R.id.tutorial_floating_host);
        var field = ClueCoachView.class.getDeclaredField("hole"); field.setAccessible(true);
        return new RectF((RectF) field.get(coach));
    }

    @Test public void coachUsesActualControlsAndNeverInterceptsTheirActionsOrAddsNarration() throws Exception {
        panel.requestConfirmation("글자 크기 키워줘", "내용 수정", () -> {}); update();
        assertEquals(activity.getString(R.string.tour_review), words());
        assertTrue((flags.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        assertTrue((flags.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags.type);
        assertTrue(narrators.constructed().isEmpty());
        TextView copy = liveWindow.findViewById(R.id.tutorial_title);
        assertEquals(activity.getResources().getFont(com.click.designpreview.R.font.pretendard_regular), copy.getTypeface());
        assertFalse(hasButton(liveWindow));
        View begin = panel.findViewById(R.id.task_flow_begin);
        int[] location = new int[2]; begin.getLocationOnScreen(location);
        float x = location[0] + begin.getWidth() / 2f, y = location[1] + begin.getHeight() / 2f;
        int[] coachOrigin = new int[2], rootOrigin = new int[2];
        liveWindow.findViewById(R.id.tutorial_floating_host).getLocationOnScreen(coachOrigin);
        assertTrue(hole().contains(x - coachOrigin[0], y - coachOrigin[1]));
        root.getLocationOnScreen(rootOrigin); x -= rootOrigin[0]; y -= rootOrigin[1];
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0);
        root.dispatchTouchEvent(down); root.dispatchTouchEvent(up); down.recycle(); up.recycle();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        verify(actions).confirmRequirements();
        assertFalse(store.awaitingHomeTour());
    }
    @Test public void nativeTargetsAndResultFollowTheActualTaskWithoutOpeningTheMinimizedPanel() throws Exception {
        panel.progress(null, null, "‘글자 크기와 스타일’을 눌러주세요.", false); update();
        guide.target(new int[]{30, 1100, 1050, 1250}); layout();
        assertEquals(panel.guidanceMessage(), words());
        assertTrue(hole().contains(500, 1150));
        guide.target(null); layout(); assertTrue(hole().isEmpty());
        panel.result(new JSONObject(), true); panel.setMinimizedProgress(true); update();
        assertTrue(panel.isMinimizedProgress());
        assertEquals(activity.getString(R.string.tour_live_expand), words());
        panel.setMinimizedProgress(false); update();
        assertEquals(activity.getString(R.string.tour_live_result), words());
        panel.findViewById(R.id.task_flow_result_satisfied).performClick(); verify(actions).result("satisfied");
        assertFalse(store.awaitingHomeTour());
        assertFalse(guide.finish("request", "old-session", true));
        assertTrue(guide.finish("request", "session", true)); assertTrue(store.awaitingHomeTour());
    }
    @Test public void otherRequestsAndPrivateGoalsNeverReceiveThisTutorial() {
        panel.progress(null, null, "현재 안내", false);
        guide.update("other", "other-session", panel, null, null, "settings.brightness.increase");
        assertNull(liveWindow); assertTrue(store.running());
        update(); assertEquals(View.VISIBLE, liveWindow.getVisibility());
        guide.update("request", "old-session", panel, null, null, "settings.font_size.increase");
        assertEquals(View.GONE, liveWindow.getVisibility());
        guide.update("request", "session", panel, null, null, "settings.private_dns.change");
        assertFalse(store.running()); assertFalse(store.awaitingHomeTour());
        assertNull(liveWindow.getParent());
    }
    @Test public void clarificationHighlightsTheRealChoiceAndThenItsEnabledSubmitAction() throws Exception {
        JSONObject question = new JSONObject().put("kind", "choice").put("id", "scope")
                .put("message", "어디의 글자를 키울까요?").put("choices", new org.json.JSONArray()
                        .put(new JSONObject().put("label", "휴대폰 전체").put("value", "system"))
                        .put(new JSONObject().put("label", "특정 앱 안에서만").put("value", "app")));
        panel.preparation(new com.click.domain.goal.GoalPreparationState(new JSONObject()
                .put("version", "goal-inputs.v1").put("id", "clarification").put("status", "needs_input")
                .put("source_text", "글자 크기 키워줘").put("question", question).put("values", new JSONObject()),
                null, "어디의 글자를 키울까요?"));
        update(); assertFalse(hole().isEmpty());
        java.util.ArrayList<View> options = new java.util.ArrayList<>();
        panel.findViewsWithText(options, "휴대폰 전체", View.FIND_VIEWS_WITH_TEXT);
        assertEquals(1, options.size()); options.get(0).performClick(); update();
        View submit = panel.findViewById(R.id.task_flow_answer);
        assertTrue(submit.isEnabled());
        assertEquals(activity.getString(R.string.tour_live_answer, ((TextView) submit).getText()), words());
        submit.performClick(); verify(actions).answerGoalInput("system");
        assertFalse(store.awaitingHomeTour());
    }
    private boolean hasButton(View view) {
        if (view instanceof android.widget.Button) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (hasButton(((ViewGroup) view).getChildAt(i))) return true;
        return false;
    }
}
