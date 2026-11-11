package com.click.ui.overlay;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.click.ui.common.ClickUi;
import com.click.ui.common.ClickTextScale;

/** Separate form: feedback never replaces the current task or its confirmation. */
public final class FeedbackOverlayView extends ScrollView {
    public interface Listener { void submit(String detail); void dismiss(); }
    private final EditText detail;
    private final TextView status;
    private final Button submit, later;
    private final Listener listener;
    private boolean saving;

    public FeedbackOverlayView(Context context, boolean positive, String message, String draft, Listener listener) {
        super(context);
        this.listener = listener;
        setFillViewport(false);
        setBackground(ClickUi.outlined(0xFFFFFFFF, ClickUi.BORDER, dp(22), dp(1)));
        setElevation(dp(12));
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(18), dp(20), dp(18));
        addView(body, new ScrollView.LayoutParams(-1, -2));
        TextView title = ClickUi.sectionTitle(context, positive ? "고마워요!" : "의견을 들려주세요");
        title.setTextSize(ClickTextScale.size(context, 20));
        title.setAccessibilityHeading(true);
        body.addView(title);
        TextView prompt = ClickUi.body(context, message);
        prompt.setTextSize(ClickTextScale.size(context, 16));
        body.addView(prompt, ClickUi.wrap(dp(12)));
        detail = ClickUi.input(context, positive ? "좋았던 점을 적어주세요 (선택)" : "어떤 점이 불편하셨나요?",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        detail.setTextSize(ClickTextScale.size(context, 17));
        detail.setSingleLine(false);
        detail.setMinLines(3); detail.setMaxLines(5);
        detail.setPadding(dp(12), dp(10), dp(12), dp(10));
        detail.setGravity(Gravity.TOP | Gravity.START);
        detail.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)});
        detail.setContentDescription("클릭에 전할 피드백");
        if (draft != null) detail.setText(draft);
        body.addView(detail, ClickUi.wrap(dp(14)));
        TextView purpose = ClickUi.body(context, "보내주신 의견은 클릭을 개선하기 위해 저장해요.");
        purpose.setTextSize(ClickTextScale.size(context, 13));
        body.addView(purpose, ClickUi.wrap(dp(8)));
        status = ClickUi.body(context, "");
        status.setTextSize(ClickTextScale.size(context, 14));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(GONE);
        body.addView(status, ClickUi.wrap(dp(8)));
        submit = ClickUi.primaryButton(context, "피드백 보내기");
        submit.setTextSize(ClickTextScale.size(context, 17));
        submit.setOnClickListener(v -> {
            if (saving) return;
            String text = detail.getText().toString().trim();
            if (text.isEmpty()) { showError("보내실 내용을 적어주세요. 나중에 남기셔도 괜찮아요."); return; }
            listener.submit(text);
        });
        body.addView(submit, ClickUi.fullWidth(context, 48, 12));
        later = ClickUi.secondaryButton(context, positive ? "닫기" : "나중에");
        later.setTextSize(ClickTextScale.size(context, 16));
        later.setOnClickListener(v -> { if (!saving) listener.dismiss(); });
        body.addView(later, ClickUi.fullWidth(context, 44, 8));
    }
    public void setSaving(boolean value) {
        saving = value;
        detail.setEnabled(!value); submit.setEnabled(!value); later.setEnabled(!value);
        if (value) { status.setText("피드백을 저장하고 있어요…"); status.setVisibility(VISIBLE); }
    }
    public void showError(String message) {
        setSaving(false); status.setText(message); status.setVisibility(VISIBLE);
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int max = Math.round(getResources().getDisplayMetrics().heightPixels * .72f);
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) max = Math.min(max, MeasureSpec.getSize(heightSpec));
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !saving) listener.dismiss();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
