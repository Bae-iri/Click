package com.click.designpreview;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

/** Shared accessible form styling for the Click account and permission screens. */
final class AccountUi {
    static final int BLUE = 0xFF3478F6, INK = 0xFF101A36;
    static int dp(Activity a, int value) { return Math.round(value * a.getResources().getDisplayMetrics().density); }
    static LinearLayout page(Activity a) {
        LinearLayout page = new LinearLayout(a); page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(a, 24), dp(a, 28), dp(a, 24), dp(a, 24)); page.setBackgroundColor(Color.WHITE);
        ScrollView scroll = new ScrollView(a); scroll.setFillViewport(true); scroll.addView(page);
        a.setContentView(scroll); return page;
    }
    static TextView text(Activity a, String value, int size) {
        TextView t = new TextView(a); t.setText(value); t.setTextSize(size); t.setTextColor(INK);
        t.setTypeface(a.getResources().getFont(R.font.pretendard_medium)); t.setLineSpacing(dp(a, 5), 1.1f);
        return t;
    }
    static void add(Activity a, LinearLayout page, View view, int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = dp(a, margin); page.addView(view, p);
    }
    static Button button(Activity a, String value, boolean primary) {
        Button b = new Button(a); b.setText(value); b.setTextSize(20); b.setAllCaps(false);
        b.setTypeface(a.getResources().getFont(R.font.pretendard_semibold));
        b.setTextColor(primary ? Color.WHITE : BLUE); b.setMinHeight(dp(a, 60));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(primary ? BLUE : 0xFFEEF4FF);
        bg.setCornerRadius(dp(a, 18)); b.setBackground(bg); b.setPadding(dp(a, 16), dp(a, 14), dp(a, 16), dp(a, 14)); return b;
    }
    static EditText input(Activity a, String label, int type) {
        EditText e = new EditText(a); e.setTextSize(20); e.setHint(label); e.setContentDescription(label);
        e.setInputType(type); e.setSingleLine(true); e.setMinHeight(dp(a, 64));
        e.setBackgroundResource(R.drawable.signup_input_background); e.setPadding(dp(a, 18), dp(a, 12), dp(a, 18), dp(a, 12));
        e.setTextColor(INK); return e;
    }
}
