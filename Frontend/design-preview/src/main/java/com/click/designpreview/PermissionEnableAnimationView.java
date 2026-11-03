package com.click.designpreview;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** An illustrative settings walkthrough. It never changes a system permission. */
public final class PermissionEnableAnimationView extends LinearLayout {
    private static final int BLUE = 0xFF3478F6, INK = 0xFF101A36, SECONDARY = 0xFF6F7F9E;
    private final Typeface medium, semibold;
    private final TextView instruction, switchState;
    private final View[] screens = new View[3];
    private final GradientDrawable[] highlights = new GradientDrawable[2];
    private final SwitchPreview toggle;
    private final ValueAnimator animation;
    private int phase = -1;
    private boolean playing;

    public PermissionEnableAnimationView(Context context) {
        super(context);
        medium = getResources().getFont(R.font.pretendard_medium);
        semibold = getResources().getFont(R.font.pretendard_semibold);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(16), dp(16), dp(18));
        setBackground(rounded(0xFFF0F5FF, 24));
        setContentDescription(context.getString(R.string.guide_enable_example) + ". "
                + context.getString(R.string.guide_enable_description));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);

        instruction = text(R.string.guide_enable_step_apps, 16, BLUE, true);
        instruction.setId(R.id.guide_animation_step);
        addView(instruction, new LayoutParams(-1, -2));
        FrameLayout phone = new FrameLayout(context);
        phone.setPadding(dp(14), dp(16), dp(14), dp(16));
        phone.setBackground(rounded(Color.WHITE, 22));
        phone.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LayoutParams phoneParams = new LayoutParams(-1, -2);
        phoneParams.topMargin = dp(14);
        addView(phone, phoneParams);

        LinearLayout apps = screen(R.string.guide_accessibility);
        add(apps, text(R.string.guide_recommended, 15, SECONDARY, false), 10);
        add(apps, selectedRow(R.string.guide_installed_apps, 0), 16);
        screens[0] = apps;

        LinearLayout services = screen(R.string.guide_installed_apps);
        add(services, selectedRow(R.string.guide_click, 1), 16);
        add(services, text(R.string.guide_service_off, 14, SECONDARY, false), 12);
        screens[1] = services;

        LinearLayout service = screen(R.string.guide_click);
        LinearLayout switchRow = new LinearLayout(context);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(dp(12), dp(16), dp(12), dp(16));
        switchRow.setBackground(rounded(0xFFF0F5FF, 16));
        switchState = text(R.string.guide_service_off, 18, INK, true);
        switchState.setId(R.id.guide_demo_switch_state);
        switchRow.addView(switchState, new LayoutParams(0, -2, 1));
        toggle = new SwitchPreview(context);
        LayoutParams switchParams = new LayoutParams(dp(50), dp(30));
        switchParams.setMarginStart(dp(10));
        switchRow.addView(toggle, switchParams);
        add(service, switchRow, 16);
        add(service, text(R.string.guide_service_summary, 14, SECONDARY, false), 16);
        screens[2] = service;
        for (View screen : screens) phone.addView(screen, new FrameLayout.LayoutParams(-1, -2));
        TextView example = text(R.string.guide_enable_example, 12, SECONDARY, false);
        example.setGravity(Gravity.CENTER);
        add(this, example, 12);
        // A single accessible description avoids repeated announcements as the demo loops.
        for (int i = 0; i < getChildCount(); i++)
            getChildAt(i).setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        animation = ValueAnimator.ofFloat(0f, 1f);
        animation.setDuration(8800);
        animation.setRepeatCount(ValueAnimator.INFINITE);
        animation.setInterpolator(new LinearInterpolator());
        animation.addUpdateListener(value -> render((float) value.getAnimatedValue()));
        render(0f);
    }

    public void setPlaying(boolean playing) {
        this.playing = playing;
        updateAnimation();
    }

    private void updateAnimation() {
        if (animation == null) return;
        if (!playing || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) {
            animation.cancel();
        } else if (!ValueAnimator.areAnimatorsEnabled()) {
            animation.cancel();
            render(1f);
        } else if (!animation.isStarted()) {
            animation.start();
        }
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation(); }
    @Override protected void onDetachedFromWindow() { animation.cancel(); super.onDetachedFromWindow(); }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); updateAnimation();
    }

    private void render(float progress) {
        int next = progress < .25f ? 0 : progress < .5f ? 1 : progress < .75f ? 2 : 3;
        if (next != phase) {
            phase = next;
            for (int i = 0; i < screens.length; i++)
                screens[i].setVisibility(i == Math.min(phase, 2) ? VISIBLE : INVISIBLE);
            int[] labels = {R.string.guide_enable_step_apps, R.string.guide_enable_step_click,
                    R.string.guide_enable_step_switch, R.string.guide_enable_step_done};
            instruction.setText(labels[phase]);
            switchState.setText(phase == 3 ? R.string.guide_service_on : R.string.guide_service_off);
            switchState.setTextColor(phase == 3 ? BLUE : INK);
        }
        toggle.amount = Math.max(0f, Math.min(1f, (progress - .65f) / .1f));
        toggle.invalidate();
        if (phase < 2) {
            int alpha = 120 + (int) (135 * (.5 + .5 * Math.sin(progress * Math.PI * 16)));
            highlights[phase].setStroke(dp(2), Color.argb(alpha, 52, 120, 246));
        }
    }

    private LinearLayout screen(int title) {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        column.setMinimumHeight(dp(182));
        TextView heading = text(title, 18, INK, true);
        heading.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.onboarding_back, 0, 0, 0);
        heading.setCompoundDrawablePadding(dp(6));
        add(column, heading, 0);
        return column;
    }

    private View selectedRow(int label, int index) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(14), dp(12), dp(14));
        highlights[index] = rounded(0xFFEAF2FF, 14);
        row.setBackground(highlights[index]);
        row.addView(text(label, 17, INK, true), new LayoutParams(0, -2, 1));
        View cursor = new PermissionIconView(getContext(), PermissionIconView.Kind.CLICK, BLUE);
        LayoutParams icon = new LayoutParams(dp(28), dp(28));
        icon.setMarginStart(dp(8));
        row.addView(cursor, icon);
        return row;
    }

    private TextView text(int label, int size, int color, boolean strong) {
        TextView view = new TextView(getContext());
        view.setText(label); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(strong ? semibold : medium); view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(2), 1.1f);
        return view;
    }

    private void add(LinearLayout parent, View view, int top) {
        LayoutParams params = new LayoutParams(-1, -2);
        params.topMargin = dp(top); parent.addView(view, params);
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable fill = new GradientDrawable(); fill.setColor(color);
        fill.setCornerRadius(dp(radius)); return fill;
    }

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }

    private final class SwitchPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ArgbEvaluator colors = new ArgbEvaluator();
        private float amount;
        SwitchPreview(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            float radius = getHeight() / 2f;
            paint.setColor((int) colors.evaluate(amount, 0xFFC5CEDD, BLUE));
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(radius + (getWidth() - getHeight()) * amount, radius, radius - dp(3), paint);
        }
    }
}
