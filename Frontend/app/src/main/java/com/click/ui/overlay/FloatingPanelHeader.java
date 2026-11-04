package com.click.ui.overlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import com.click.R;
import com.click.ui.common.ClickArtworkView;
import com.click.ui.common.ClickTextScale;

/** 요청 확인 기준의 공통 헤더. 입력·진행·결과 화면도 이 규격을 그대로 사용한다. */
final class FloatingPanelHeader extends LinearLayout {
    final Button back, replay, collapse;
    final View divider;
    private final LinearLayout row, identity, actions;

    FloatingPanelHeader(Context context, Runnable onBack, Runnable onReplay, Runnable onCollapse) {
        super(context);
        setOrientation(VERTICAL);
        setTag("floating_header");
        row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        identity = new LinearLayout(context);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setMinimumHeight(dp(48));
        back = button("", onBack, false);
        back.setContentDescription("이전 단계");
        back.setBackgroundColor(Color.TRANSPARENT);
        Drawable arrow = context.getDrawable(R.drawable.ic_flow_back);
        arrow.setBounds(0, 0, dp(22), dp(22));
        back.setCompoundDrawables(arrow, null, null, null);
        back.setPadding(dp(4), dp(10), dp(10), dp(10));
        back.setVisibility(INVISIBLE);
        identity.addView(back, new LayoutParams(dp(48), -1));
        ClickArtworkView brand = new ClickArtworkView(context, ClickArtworkView.Kind.WORDMARK);
        brand.setContentDescription("CLICK");
        identity.addView(brand, new LayoutParams(dp(66), dp(24)));
        row.addView(identity, new LayoutParams(0, -2, 1));
        actions = new LinearLayout(context);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        replay = button("다시 듣기", onReplay, true);
        replay.setContentDescription("클릭 음성 안내 다시 듣기");
        Drawable speaker = context.getDrawable(R.drawable.ic_overlay_speaker);
        speaker.setBounds(0, 0, dp(14), dp(14));
        replay.setCompoundDrawablesRelative(speaker, null, null, null);
        replay.setCompoundDrawablePadding(dp(4));
        actions.addView(replay, new LayoutParams(-2, -2));
        collapse = button("줄이기", onCollapse, false);
        collapse.setContentDescription("안내 창 줄이기");
        LayoutParams gap = new LayoutParams(-2, -2); gap.leftMargin = dp(4);
        actions.addView(collapse, gap);
        row.addView(actions, new LayoutParams(-2, -2));
        addView(row, new LayoutParams(-1, -2));
        divider = new View(context);
        divider.setTag("floating_header_divider");
        divider.setBackgroundColor(0xFFE1E9F5);
        divider.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams line = new LayoutParams(-1, dp(1));
        line.topMargin = line.bottomMargin = dp(FloatingPanelStyle.HEADER_DIVIDER_GAP_DP);
        addView(divider, line);
    }

    private Button button(String label, Runnable action, boolean primary) {
        Button button = new Button(getContext());
        button.setText(label); button.setAllCaps(false); button.setSingleLine(true);
        button.setTextSize(ClickTextScale.size(getContext(), 11));
        button.setTextColor(0xFF3478F6);
        button.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        button.setIncludeFontPadding(false); button.setGravity(Gravity.CENTER);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(primary ? 0xFFEAF2FF : Color.WHITE); fill.setCornerRadius(dp(10));
        fill.setStroke(dp(1), 0xFFCEDDF4);
        GradientDrawable mask = new GradientDrawable(); mask.setColor(Color.WHITE); mask.setCornerRadius(dp(10));
        button.setBackground(new InsetDrawable(new RippleDrawable(ColorStateList.valueOf(0x223478F6),
                fill, mask), 0, dp(8), 0, dp(8)));
        button.setPadding(dp(primary ? 14 : 8), dp(8), dp(primary ? 14 : 8), dp(8));
        button.setStateListAnimator(null);
        button.setOnClickListener(v -> action.run());
        return button;
    }

    @Override protected void onMeasure(int width, int height) {
        int natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        replay.measure(natural, natural); collapse.measure(natural, natural);
        int actionHeight = Math.max(dp(48), Math.max(replay.getMeasuredHeight(), collapse.getMeasuredHeight()));
        replay.getLayoutParams().height = collapse.getLayoutParams().height = actionHeight;
        // Reserve back/replay slots even where unavailable. Screen changes cannot
        // shift the logo/divider or unexpectedly switch between one and two rows.
        boolean stacked = dp(48 + 66 + 8 + 4) + replay.getMeasuredWidth() + collapse.getMeasuredWidth()
                > MeasureSpec.getSize(width);
        int orientation = stacked ? VERTICAL : HORIZONTAL;
        if (row.getOrientation() != orientation) {
            row.setOrientation(orientation);
            identity.setLayoutParams(new LayoutParams(stacked ? -1 : 0, -2, stacked ? 0 : 1));
            actions.setLayoutParams(new LayoutParams(stacked ? -1 : -2, -2));
        }
        super.onMeasure(width, height);
    }

    Rect collapseBounds(int width) {
        if (getWidth() != width || getHeight() == 0 || isLayoutRequested()) {
            measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            // Anchor queries must not move an attached header out of its padding.
            layout(getLeft(), getTop(), getLeft() + getMeasuredWidth(), getTop() + getMeasuredHeight());
        }
        Rect bounds = new Rect(0, 0, collapse.getWidth(), collapse.getHeight());
        offsetDescendantRectToMyCoords(collapse, bounds);
        return bounds;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
