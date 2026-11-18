package com.click.ui.tutorial;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.click.domain.goal.RequirementReview;
import com.click.ui.common.ClickUi;
import com.click.ui.common.ClickUiSpeed;
import com.click.ui.overlay.ClickOverlayLayout;
import com.click.ui.overlay.TaskFlowOverlayView;
import org.json.JSONObject;

/** Read-only examples using the same controls as the app; never starts a request or a microphone. */
public final class TutorialExplanationView extends FrameLayout {
    private ClickOverlayLayout menuPreview;
    private static final String REQUEST = "휴대폰 전체 글자 크기 키워줘";
    private static final TaskFlowOverlayView.Listener NO_ACTION = new TaskFlowOverlayView.Listener() {
        public void chooseGoal(JSONObject goal) {} public void clarify(String choice) {}
        public void rephrase(String text) {} public void result(String answer) {}
        public void adjust(String reason) {} public void control(String command) {}
    };

    public TutorialExplanationView(Context context, int page) {
        super(context);
        setTag("tutorial_readonly_example");
        setPadding(dp(10), dp(14), dp(10), dp(14));
        setBackground(ClickUi.outlined(0xFFF6F8FC, ClickUi.BORDER, dp(20), dp(1)));
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        if (page == 0 || page == 3) addSettings(page == 3);
        else if (page == 1) {
            ClickOverlayLayout control = new ClickOverlayLayout(context);
            control.openMenu();
            menuPreview = control;
            addView(control, new LayoutParams(-1, -2));
        } else {
            TaskFlowOverlayView panel = new TaskFlowOverlayView(context, NO_ACTION);
            panel.setAvailableHeight(dp(620));
            if (page == 2) panel.requirements(new RequirementReview(REQUEST), true, "");
            else {
                JSONObject prompt = new JSONObject();
                try { prompt.put("message", "글씨가 잘 보이나요?"); }
                catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
                panel.result(prompt, true);
            }
            addView(panel, new LayoutParams(-1, -2));
        }
        post(() -> finishAnimations(this));
    }

    private void addSettings(boolean highlighted) {
        FrameLayout phone = new FrameLayout(getContext());
        phone.setPadding(dp(14), dp(20), dp(14), dp(20));
        phone.setBackground(ClickUi.outlined(Color.WHITE, 0xFFC2D2E6, dp(24), dp(4)));
        float scale = Math.max(1f, getResources().getConfiguration().fontScale);
        addView(phone, new LayoutParams(-1, Math.round(dp(highlighted ? 300 : 280) * scale)));
        LinearLayout rows = new LinearLayout(getContext());
        rows.setOrientation(LinearLayout.VERTICAL);
        phone.addView(rows, new LayoutParams(-1, -2));
        TextView heading = label("설정", true);
        heading.setMinHeight(dp(48));
        rows.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        TextView display = label("디스플레이", false);
        display.setBackground(highlighted
                ? ClickUi.outlined(ClickUi.PALE_BLUE, ClickUi.BLUE, dp(12), dp(3))
                : ClickUi.rounded(0xFFF6F8FC, dp(12)));
        display.setMinHeight(dp(60));
        rows.addView(display, new LinearLayout.LayoutParams(-1, -2));
        if (highlighted) {
            TextView instruction = label("‘디스플레이’를 눌러주세요.", false);
            instruction.setTextColor(ClickUi.BLUE_DARK);
            instruction.setBackground(ClickUi.rounded(ClickUi.PALE_BLUE, dp(18)));
            LayoutParams params = new LayoutParams(-1, -2, Gravity.BOTTOM);
            params.setMargins(dp(14), 0, dp(14), dp(20));
            phone.addView(instruction, params);
        } else {
            TextView sound = label("소리 및 진동", false);
            sound.setMinHeight(dp(60));
            rows.addView(sound, new LinearLayout.LayoutParams(-1, -2));
            ClickOverlayLayout control = new ClickOverlayLayout(getContext());
            control.collapse();
            LayoutParams params = new LayoutParams(dp(72), dp(72), Gravity.BOTTOM | Gravity.END);
            params.setMargins(0, 0, dp(12), dp(12));
            phone.addView(control, params);
            TextView callout = label("클릭 버튼 →", false);
            callout.setTextColor(ClickUi.BLUE_DARK);
            LayoutParams labelParams = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            labelParams.setMargins(0, 0, dp(88), dp(24));
            phone.addView(callout, labelParams);
        }
    }

    private TextView label(String value, boolean title) {
        TextView view = ClickUi.body(getContext(), value);
        view.setTextColor(ClickUi.TEXT);
        view.setTextSize(title ? 22 : 18);
        view.setPadding(dp(14), dp(14), dp(14), dp(14));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private void finishAnimations(View view) {
        ClickUiSpeed.finishReveal(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) finishAnimations(group.getChildAt(i));
        }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (menuPreview != null) {
            int width = Math.max(1, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
            menuPreview.getLayoutParams().height = menuPreview.getWindowHeight(
                    ClickOverlayLayout.Mode.MENU, width, dp(620));
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
    @Override public boolean onTouchEvent(MotionEvent event) { return true; }
    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
