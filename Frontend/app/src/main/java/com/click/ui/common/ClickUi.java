package com.click.ui.common;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class ClickUi {
    public static final int BLUE = 0xFF3478F6;
    public static final int BLUE_DARK = 0xFF2469EA;
    public static final int PALE_BLUE = 0xFFF1F6FF;
    public static final int BORDER = 0xFFDCE6F5;
    public static final int RED = BLUE;
    public static final int SECONDARY_RED = PALE_BLUE;
    public static final int TEXT = 0xFF101A36;
    public static final int SUBTEXT = 0xFF6F7F9E;
    public static final int BACKGROUND = Color.WHITE;

    private ClickUi() {}

    public static LinearLayout page(Context context) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(context, 22), dp(context, 24), dp(context, 22), dp(context, 28));
        page.setBackgroundColor(BACKGROUND);
        return page;
    }

    public static ScrollView scrollPage(Context context, LinearLayout page) {
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    public static TextView title(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(30);
        view.setTypeface(font(context, com.click.designpreview.R.font.pretendard_bold));
        view.setTextColor(TEXT);
        view.setGravity(Gravity.START);
        view.setIncludeFontPadding(false);
        view.setLetterSpacing(-.02f);
        return view;
    }

    public static TextView body(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(18);
        view.setTextColor(SUBTEXT);
        view.setLineSpacing(0, 1.25f);
        view.setTypeface(font(context, com.click.designpreview.R.font.pretendard_medium));
        view.setIncludeFontPadding(false);
        return view;
    }

    public static EditText input(Context context, String hint, int inputType) {
        EditText input = new EditText(context);
        input.setHint(hint);
        input.setTextSize(19);
        input.setTextColor(TEXT);
        input.setHintTextColor(Color.rgb(135, 141, 151));
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        input.setTypeface(font(context, com.click.designpreview.R.font.pretendard_medium));
        input.setBackground(outlined(Color.WHITE, BORDER, dp(context, 16), dp(context, 1)));
        return input;
    }

    public static Button primaryButton(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextSize(20);
        button.setTextColor(Color.WHITE);
        button.setTypeface(font(context, com.click.designpreview.R.font.pretendard_semibold));
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF3979F6, 0xFF4B89FA});
        fill.setCornerRadius(dp(context, 22));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x32FFFFFF), fill,
                rounded(Color.WHITE, dp(context, 22))));
        button.setElevation(dp(context, 5));
        button.setAllCaps(false);
        return button;
    }

    public static Button secondaryButton(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextSize(19);
        button.setTextColor(BLUE_DARK);
        button.setTypeface(font(context, com.click.designpreview.R.font.pretendard_semibold));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x183478F6),
                rounded(PALE_BLUE, dp(context, 18)), rounded(Color.WHITE, dp(context, 18))));
        button.setAllCaps(false);
        return button;
    }

    public static LinearLayout.LayoutParams fullWidth(Context context, int heightDp, int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp));
        params.topMargin = dp(context, topMarginDp);
        return params;
    }

    public static LinearLayout.LayoutParams wrap(int topMarginPx) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMarginPx;
        return params;
    }

    public static int passwordType() {
        return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static LinearLayout card(Context context) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 18));
        card.setBackground(rounded(PALE_BLUE, dp(context, 24)));
        return card;
    }

    public static TextView sectionTitle(Context context, String value) {
        TextView title = body(context, value);
        title.setTextSize(20);
        title.setTextColor(TEXT);
        title.setTypeface(font(context, com.click.designpreview.R.font.pretendard_semibold));
        return title;
    }

    public static GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(radiusPx);
        return shape;
    }

    public static GradientDrawable outlined(int color, int strokeColor, int radiusPx, int strokePx) {
        GradientDrawable shape = rounded(color, radiusPx);
        shape.setStroke(strokePx, strokeColor);
        return shape;
    }

    private static Typeface font(Context context, int resource) {
        return context.getResources().getFont(resource);
    }
}
