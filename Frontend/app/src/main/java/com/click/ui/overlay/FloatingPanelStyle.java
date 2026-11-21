package com.click.ui.overlay;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.InsetDrawable;
import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;
import com.click.ui.common.ClickTextScale;

/** Shared surfaces for the input, review and guidance panels. */
final class FloatingPanelStyle {
    // [UI 수정] 입력·요청 확인·진행 안내창에 공통으로 쓰는 간격.
    static final int HORIZONTAL_PADDING_DP = 16;
    static final int TOP_PADDING_DP = 8;
    static final int BOTTOM_PADDING_DP = 12;
    static final int PANEL_WIDTH_DP = 340;
    static final int HEADER_DIVIDER_GAP_DP = 5;
    static final int TITLE_SIZE_SP = 22;
    static final int BODY_SIZE_SP = 18;
    static final int INPUT_SIZE_SP = 20;
    static final int ACTION_SIZE_SP = 18;
    static final int HEADER_ACTION_SIZE_SP = 15;
    private FloatingPanelStyle() {}

    static void styleTitle(TextView title) {
        Context context = title.getContext();
        title.setTextSize(ClickTextScale.size(context, TITLE_SIZE_SP));
        title.setTypeface(context.getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        title.setIncludeFontPadding(false);
        title.setPadding(0, 0, 0, 0);
        title.setLineSpacing(2 * context.getResources().getDisplayMetrics().density, 1.05f);
        title.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED);
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
    }

    /** Compact visual controls retain a 48dp touch target and grow with system text. */
    static Button compactButton(Context context, String label, Runnable action) {
        float density = context.getResources().getDisplayMetrics().density;
        Button button = new Button(context);
        button.setText(label); button.setTextSize(ClickTextScale.size(context, 16)); button.setAllCaps(false);
        button.setTextColor(0xFF536885);
        button.setTypeface(context.getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        button.setIncludeFontPadding(false); button.setGravity(Gravity.CENTER);
        button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(Math.round(48 * density)); button.setMinimumHeight(Math.round(48 * density));
        GradientDrawable fill = gradient(density, 10, 0xFFF0F5FD, 0xFFF0F5FD);
        fill.setStroke(Math.max(1, Math.round(density)), 0xFFE1E9F5);
        Drawable ripple = new RippleDrawable(ColorStateList.valueOf(0x183478F6), fill,
                gradient(density, 10, Color.WHITE, Color.WHITE));
        button.setBackground(new InsetDrawable(ripple, 0, Math.round(4 * density), 0, Math.round(4 * density)));
        button.setPadding(Math.round(10 * density), Math.round(6 * density),
                Math.round(10 * density), Math.round(6 * density));
        button.setStateListAnimator(null);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    static GradientDrawable surface(float density) {
        GradientDrawable surface = gradient(density, 22, 0xF2FFFFFF, 0xF2F8FAFF);
        // 오버레이 창 경계에서 잘리는 elevation 그림자 대신 얇은 테두리로 구분한다.
        surface.setStroke(Math.max(1, Math.round(density)), 0xFFDCE6F5);
        return surface;
    }

    static GradientDrawable summary(float density) {
        return gradient(density, 18, 0xFFEAF1FF, 0xFFDCE7FF);
    }

    static Drawable primary(float density) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                gradient(density, 16, 0xFF3987FF, 0xFF1954F5),
                gradient(density, 16, Color.WHITE, Color.WHITE));
    }

    private static GradientDrawable gradient(float density, int radius, int start, int end) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{start, end});
        drawable.setCornerRadius(radius * density);
        return drawable;
    }
}
