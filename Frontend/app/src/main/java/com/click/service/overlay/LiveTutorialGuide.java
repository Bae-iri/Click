package com.click.service.overlay;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import com.click.R;
import com.click.data.local.LiveTutorialStore;
import com.click.ui.overlay.TaskFlowOverlayView;
import com.click.ui.tutorial.ClueCoachView;

/** A visual coach for the owned, real task. All actions and narration remain in the normal task flow. */
public final class LiveTutorialGuide {
    public interface Host {
        int windowType();
        void add(View view, WindowManager.LayoutParams params);
        void visibility(View view, int visibility);
        void remove(View view);
    }
    private final Context context;
    private final Host host;
    private final LiveTutorialStore store;
    private String pendingLaunch, requestId, sessionId;
    private FrameLayout window;
    private ClueCoachView coach;
    private TaskFlowOverlayView panel;
    private View input;
    private Rect nativeTarget, keyboard;
    private float coachFontScale;
    private final ViewTreeObserver.OnPreDrawListener trackPanel = () -> { render(); return true; };

    public LiveTutorialGuide(Context context, Host host) { this(context, host, new LiveTutorialStore(context)); }
    public LiveTutorialGuide(Context context, Host host, LiveTutorialStore store) {
        this.context = context; this.host = host; this.store = store;
    }
    public boolean accept(String launch, String request) {
        if (!store.accepts(launch, request)) return false;
        pendingLaunch = launch; nativeTarget = null;
        return true;
    }
    public void requestCreated(String id, String request) {
        if (pendingLaunch != null) store.bindRequest(pendingLaunch, request, id);
        pendingLaunch = null;
    }
    public void sessionStarted(String id, String session) {
        if (store.bindSession(id, session)) nativeTarget = null;
    }
    public boolean finish(String id, String session, boolean successful) {
        if (!store.finish(id, session, successful)) return false;
        close(); return true;
    }
    public void cancel() { if (store.running()) store.clear(); close(); }
    public boolean hasWindow(String id, String session) {
        return window != null && window.isAttachedToWindow() && store.presents(id, session);
    }
    public void target(int[] bounds) {
        nativeTarget = bounds == null || bounds.length != 4 ? null : new Rect(bounds[0], bounds[1], bounds[2], bounds[3]);
        render();
    }
    public void update(String id, String session, TaskFlowOverlayView panel, View input, Rect keyboard, String goalType) {
        requestId = id; sessionId = session; this.input = input; this.keyboard = keyboard == null ? null : new Rect(keyboard);
        if (store.presents(id, session) && goalType != null && !"settings.font_size.increase".equals(goalType)) {
            cancel(); return;
        }
        if (this.panel != panel) {
            if (this.panel != null && this.panel.getViewTreeObserver().isAlive())
                this.panel.getViewTreeObserver().removeOnPreDrawListener(trackPanel);
            this.panel = panel;
            if (panel != null) panel.getViewTreeObserver().addOnPreDrawListener(trackPanel);
        }
        render();
    }

    private void render() {
        if (!store.presents(requestId, sessionId) || panel == null) {
            if (window != null) host.visibility(window, View.GONE);
            return;
        }
        if (!ensureWindow()) return;
        View begin = panel.findViewById(R.id.task_flow_begin);
        View result = panel.findViewById(R.id.task_flow_result_satisfied);
        View finish = panel.findViewWithTag("task_completion_end");
        View answer = panel.findViewById(R.id.task_flow_answer);
        Rect target = null;
        String words;
        int art = com.click.designpreview.R.drawable.clue3_tutorial;
        if (finish != null) {
            target = actionBounds(finish);
            words = context.getString(panel.isMinimizedProgress() ? R.string.tour_live_expand : R.string.tour_live_finished);
            art = com.click.designpreview.R.drawable.clue4_complete;
        } else if (result != null) {
            target = actionBounds(result);
            words = context.getString(panel.isMinimizedProgress() ? R.string.tour_live_expand : R.string.tour_live_result);
        } else if (begin != null && begin.getVisibility() == View.VISIBLE) {
            target = actionBounds(begin);
            words = context.getString(panel.isMinimizedProgress() ? R.string.tour_live_expand : R.string.tour_review);
        } else if (answer != null && answer.isShown() && answer.isEnabled()) {
            target = visibleBounds(answer);
            words = context.getString(R.string.tour_live_answer, ((TextView) answer).getText());
        } else if (visibleChoices(panel) != null) {
            target = visibleBounds(visibleChoices(panel));
            words = panel.guidanceMessage();
        } else if (visibleEditor(input) != null || visibleEditor(panel) != null) {
            View editor = visibleEditor(input); if (editor == null) editor = visibleEditor(panel);
            target = visibleBounds(editor);
            words = context.getString(R.string.tour_live_edit);
        } else if (nativeTarget != null) {
            target = nativeTarget;
            words = panel.guidanceMessage();
            if (words == null || words.isBlank()) words = context.getString(R.string.tour_live_follow);
        } else {
            words = panel.guidanceMessage();
            if (words == null || words.isBlank()) words = context.getString(R.string.tour_live_wait);
        }
        Rect occupied = visibleBounds(panel);
        occupied.union(visibleBounds(input));
        coach.showExternal(words, art, target, occupied, keyboard);
        host.visibility(window, View.VISIBLE);
    }
    private Rect actionBounds(View action) {
        return panel.isMinimizedProgress() ? visibleBounds(panel) : visibleBounds(action);
    }
    private boolean ensureWindow() {
        float scale = context.getResources().getConfiguration().fontScale;
        if (window != null && Float.compare(coachFontScale, scale) == 0) return true;
        if (window != null) removeWindow();
        coachFontScale = scale;
        window = new FrameLayout(context);
        window.setId(R.id.live_tutorial_window);
        coach = ClueCoachView.liveOverlay(window);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1, host.windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        try { host.add(window, params); return true; }
        catch (RuntimeException unavailable) { coach.close(); window = null; coach = null; return false; }
    }
    private static View visibleEditor(View view) {
        if (view == null || !view.isShown()) return null;
        if (view instanceof EditText) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View editor = visibleEditor(((ViewGroup) view).getChildAt(i));
            if (editor != null) return editor;
        }
        return null;
    }
    private static View visibleChoices(View view) {
        if (view == null || !view.isShown()) return null;
        if (view instanceof RadioGroup) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View choices = visibleChoices(((ViewGroup) view).getChildAt(i));
            if (choices != null) return choices;
        }
        return null;
    }
    private static Rect visibleBounds(View view) {
        Rect bounds = new Rect();
        if (view == null || !view.isShown() || !view.getLocalVisibleRect(bounds)) { bounds.setEmpty(); return bounds; }
        int[] origin = new int[2]; view.getLocationOnScreen(origin);
        bounds.offset(origin[0] - view.getScrollX(), origin[1] - view.getScrollY());
        return bounds;
    }
    /** Detaches visuals only; a reconnect or process restart still owns the same real request. */
    public void close() {
        if (panel != null && panel.getViewTreeObserver().isAlive()) panel.getViewTreeObserver().removeOnPreDrawListener(trackPanel);
        panel = null;
        removeWindow();
    }
    private void removeWindow() {
        if (coach != null) coach.close();
        if (window != null) host.remove(window);
        coach = null; window = null;
    }
}
