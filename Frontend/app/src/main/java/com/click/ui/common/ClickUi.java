package com.click.ui.common;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class ClickUi {
    public static final int RED = Color.rgb(255, 59, 77);
    public static final int SECONDARY_RED = Color.rgb(255, 232, 235);
    public static final int TEXT = Color.rgb(30, 37, 49);
    public static final int SUBTEXT = Color.rgb(85, 94, 108);
    public static final int BACKGROUND = Color.rgb(248, 249, 252);

    private ClickUi() {}

    public static LinearLayout page(Context context) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(context, 28), dp(context, 40), dp(context, 28), dp(context, 32));
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
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(TEXT);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        return view;
    }

    public static TextView body(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(18);
        view.setTextColor(SUBTEXT);
        view.setLineSpacing(0, 1.25f);
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
        return input;
    }

    public static Button primaryButton(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextSize(20);
        button.setTextColor(Color.WHITE);
        button.setBackgroundColor(RED);
        button.setAllCaps(false);
        return button;
    }

    public static Button secondaryButton(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextSize(19);
        button.setTextColor(TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(SECONDARY_RED));
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
}
