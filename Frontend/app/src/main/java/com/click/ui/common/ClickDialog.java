package com.click.ui.common;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Click's rounded dialog; the entire sheet remains reachable at large font sizes. */
public final class ClickDialog {
    private ClickDialog() {}

    public static TextView body(Activity activity, String message) {
        TextView text = ClickUi.body(activity, message);
        text.setTextSize(16);
        text.setLineSpacing(ClickUi.dp(activity, 3), 1.12f);
        text.setPadding(ClickUi.dp(activity, 16), ClickUi.dp(activity, 14),
                ClickUi.dp(activity, 16), ClickUi.dp(activity, 14));
        text.setBackground(ClickUi.rounded(ClickUi.PALE_BLUE, ClickUi.dp(activity, 18)));
        return text;
    }

    public static Dialog show(Activity activity, String title, View body,
                              String positive, Runnable action, String negative) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        int padding = ClickUi.dp(activity, 22);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(padding, padding, padding, padding);
        card.setBackground(ClickUi.rounded(Color.WHITE, ClickUi.dp(activity, 26)));

        TextView brand = ClickUi.body(activity, "click");
        brand.setTextSize(16); brand.setTextColor(ClickUi.BLUE);
        card.addView(brand);
        TextView heading = ClickUi.title(activity, title);
        heading.setTextSize(23);
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.setMargins(0, ClickUi.dp(activity, 10), 0, ClickUi.dp(activity, 18));
        card.addView(heading, headingParams);
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));

        Button confirm = ClickUi.primaryButton(activity, positive);
        confirm.setId(android.R.id.button1);
        confirm.setTextSize(17); confirm.setMinHeight(ClickUi.dp(activity, 50));
        confirm.setPadding(padding, ClickUi.dp(activity, 12), padding, ClickUi.dp(activity, 12));
        confirm.setOnClickListener(view -> { dialog.dismiss(); if (action != null) action.run(); });
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(-1, -2);
        confirmParams.topMargin = ClickUi.dp(activity, 20);
        card.addView(confirm, confirmParams);
        if (negative != null) {
            Button cancel = ClickUi.secondaryButton(activity, negative);
            cancel.setId(android.R.id.button2);
            cancel.setTextSize(16);
            cancel.setPadding(padding, ClickUi.dp(activity, 12), padding, ClickUi.dp(activity, 12));
            cancel.setOnClickListener(view -> dialog.dismiss());
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-1, -2);
            cancelParams.topMargin = ClickUi.dp(activity, 8);
            card.addView(cancel, cancelParams);
        }
        ScrollView scroll = new ScrollView(activity) {
            @Override protected void onMeasure(int width, int height) {
                int limit = Math.round(getResources().getDisplayMetrics().heightPixels * .82f);
                if (MeasureSpec.getMode(height) != MeasureSpec.UNSPECIFIED)
                    limit = Math.min(limit, MeasureSpec.getSize(height));
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        scroll.setBackground(ClickUi.rounded(Color.WHITE, ClickUi.dp(activity, 26)));
        scroll.setClipToOutline(true);
        scroll.setFillViewport(false);
        scroll.addView(card, new ScrollView.LayoutParams(-1, -2));
        dialog.setContentView(scroll);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(.38f);
            window.setGravity(Gravity.CENTER);
            window.setLayout(Math.min(activity.getResources().getDisplayMetrics().widthPixels
                    - ClickUi.dp(activity, 32), ClickUi.dp(activity, 400)), WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }
}
