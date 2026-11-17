package com.click.designpreview;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUiSpeed;

/** Shared, immediately saved presentation-speed control with an actual animation preview. */
public final class UiSpeedControlView extends LinearLayout {
    private static final int BLUE = 0xFF3478F6, INK = 0xFF101A36, SECONDARY = 0xFF7182A4;
    private final TextView preview, title, current;
    private final LinearLayout heading;

    public UiSpeedControlView(Context context) { this(context, null); }

    public UiSpeedControlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setPadding(dp(18), dp(18), dp(18), dp(16));
        setBackground(surface(Color.WHITE, 24, 0xFFE3EAF6));
        heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        title = text(R.string.ui_speed_title, 16, INK);
        title.setTypeface(getResources().getFont(R.font.pretendard_medium));
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        heading.addView(title, new LayoutParams(0, -2, 1));
        current = text(0, 13, BLUE);
        current.setTypeface(getResources().getFont(R.font.pretendard_medium));
        current.setId(R.id.ui_speed_current);
        current.setText(ClickUiSpeed.label(context));
        LayoutParams currentParams = new LayoutParams(-2, -2);
        currentParams.setMarginStart(dp(10));
        heading.addView(current, currentParams);
        addView(heading, new LayoutParams(-1, -2));
        add(text(R.string.ui_speed_description, 13, SECONDARY), 10);

        preview = text(R.string.ui_speed_preview_message, 16, INK);
        preview.setId(R.id.ui_speed_preview);
        preview.setGravity(Gravity.CENTER);
        preview.setMinHeight(dp(60));
        preview.setPadding(dp(12), dp(14), dp(12), dp(14));
        preview.setBackground(surface(0xFFF0F5FF, 16, 0));
        add(preview, 16);

        SeekBar speed = new SeekBar(context);
        speed.setId(R.id.ui_speed_scale);
        speed.setMax(2);
        speed.setKeyProgressIncrement(1);
        speed.setProgress(ClickUiSpeed.level(context));
        speed.setContentDescription(getResources().getString(R.string.ui_speed_adjust, ClickUiSpeed.label(context)));
        speed.setMinHeight(dp(48));
        speed.setPadding(dp(12), 0, dp(12), 0);
        speed.setProgressTintList(ColorStateList.valueOf(BLUE));
        speed.setThumbTintList(ColorStateList.valueOf(BLUE));
        GradientDrawable tick = new GradientDrawable();
        tick.setShape(GradientDrawable.OVAL);
        tick.setSize(dp(8), dp(8));
        tick.setColor(0xFF9CAECC);
        speed.setTickMark(tick);
        speed.setTickMarkTintList(ColorStateList.valueOf(0xFF9CAECC));
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                ClickUiSpeed.saveLevel(context, progress);
                current.setText(ClickUiSpeed.label(context));
                speed.setContentDescription(getResources().getString(R.string.ui_speed_adjust, ClickUiSpeed.label(context)));
                ClickUiSpeed.reveal(preview);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        add(speed, 8);
        LinearLayout labels = new LinearLayout(context);
        int[] values = {R.string.ui_speed_slow, R.string.ui_speed_normal, R.string.ui_speed_fast};
        for (int i = 0; i < values.length; i++) {
            TextView label = text(values[i], 12, SECONDARY);
            label.setGravity(i == 0 ? Gravity.START : i == 2 ? Gravity.END : Gravity.CENTER);
            labels.addView(label, new LayoutParams(0, -2, 1));
        }
        addView(labels, new LayoutParams(-1, -2));
        Button replay = new Button(context);
        replay.setId(R.id.ui_speed_replay);
        replay.setText(R.string.ui_speed_replay);
        replay.setTextSize(14);
        replay.setTextColor(BLUE);
        replay.setAllCaps(false);
        replay.setIncludeFontPadding(false);
        replay.setTypeface(getResources().getFont(R.font.pretendard_medium));
        replay.setMinHeight(dp(48));
        replay.setMinimumHeight(dp(48));
        replay.setStateListAnimator(null);
        replay.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x1A3478F6),
                surface(0xFFF0F5FF, 14, 0), null));
        replay.setOnClickListener(view -> ClickUiSpeed.reveal(preview));
        add(replay, 14);
        ClickTextScale.applyTree(this);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        float needed = title.getPaint().measureText(title.getText().toString())
                + current.getPaint().measureText(current.getText().toString()) + dp(10);
        boolean stacked = MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && needed > available;
        int orientation = stacked ? VERTICAL : HORIZONTAL;
        if (heading.getOrientation() != orientation) {
            heading.setOrientation(orientation);
            title.setLayoutParams(stacked ? new LayoutParams(-1, -2) : new LayoutParams(0, -2, 1));
            LayoutParams currentParams = new LayoutParams(stacked ? -1 : -2, -2);
            currentParams.topMargin = stacked ? dp(4) : 0;
            currentParams.setMarginStart(stacked ? 0 : dp(10));
            current.setGravity(stacked ? Gravity.END : Gravity.START);
            current.setLayoutParams(currentParams);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    @Override protected void onDetachedFromWindow() {
        ClickUiSpeed.finishReveal(preview);
        super.onDetachedFromWindow();
    }

    private TextView text(int resource, int size, int color) {
        TextView view = new TextView(getContext());
        if (resource != 0) view.setText(resource);
        view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(false);
        view.setTypeface(getResources().getFont(R.font.pretendard_regular));
        view.setLineSpacing(dp(2), 1.1f);
        return view;
    }
    private void add(android.view.View child, int margin) {
        LayoutParams params = new LayoutParams(-1, -2); params.topMargin = dp(margin); addView(child, params);
    }
    private GradientDrawable surface(int color, int radius, int border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
