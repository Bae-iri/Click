package com.click.designpreview;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import static com.click.designpreview.PermissionIconView.Kind;

/** Shared phone illustration used in the accessibility guide and the Click feature tutorial. */
public final class PermissionPhoneView extends LinearLayout {
    private static final int BLUE = 0xFF3478F6, INK = 0xFF101A36;
    private final Typeface medium, semibold;
    private LinearLayout screen;

    public PermissionPhoneView(Context context, boolean accessList, boolean muted) {
        super(context);
        setOrientation(VERTICAL);
        medium = getResources().getFont(R.font.pretendard_medium);
        semibold = getResources().getFont(R.font.pretendard_semibold);
        build(accessList, muted);
    }

    /** Extend the same screen behind the floating controls without scaling any text. */
    public void fillAvailableHeight() {
        screen.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void build(boolean accessList, boolean muted) {
        LinearLayout frame = this;
        frame.setPadding(dp(9), dp(12), dp(9), dp(12));
        GradientDrawable shell = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFFA9BEDB, 0xFF566B87, 0xFFB9CBE2});
        shell.setCornerRadius(dp(32));
        frame.setBackground(shell);
        screen = vertical();
        screen.setPadding(dp(8), dp(12), dp(8), dp(8));
        screen.setBackground(rounded(0xFFF5F7FB, 24));
        frame.addView(screen, new LinearLayout.LayoutParams(-1, -2));
        View speaker = new View(getContext());
        speaker.setBackground(rounded(0xFFD7DFEB, 4));
        LinearLayout.LayoutParams speakerParams = new LinearLayout.LayoutParams(dp(34), dp(5));
        speakerParams.gravity = Gravity.CENTER_HORIZONTAL;
        speakerParams.bottomMargin = dp(14);
        screen.addView(speaker, speakerParams);
        TextView title = text(accessList ? R.string.guide_accessibility : R.string.guide_settings,
                16, INK, semibold);
        title.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.onboarding_back, 0, 0, 0);
        title.setCompoundDrawablePadding(dp(8));
        title.setPadding(dp(6), 0, dp(6), dp(12));
        add(screen, title, 0);
        if (muted) {
            add(screen, phoneRow(R.string.guide_general, Kind.SETTINGS, 0xFF9BAABE, false, false), 2);
            add(screen, phoneRow(R.string.guide_accessibility, Kind.ACCESSIBILITY, BLUE, false, false), 6);
            add(screen, phoneRow(R.string.guide_update, Kind.DOWNLOAD, 0xFF8599B2, false, false), 6);
        } else if (accessList) {
            add(screen, phoneRow(R.string.guide_recommended, Kind.CHECK, 0xFF12A39C, false, false), 2);
            add(screen, phoneRow(R.string.guide_installed_apps, Kind.APPS, 0xFF7765DD, false, false), 6);
            add(screen, phoneRow(R.string.guide_click, Kind.CLICK, BLUE, true, true), 6);
        } else {
            add(screen, phoneRow(R.string.guide_battery, Kind.CONTROL, 0xFF18A4AD, false, false), 2);
            add(screen, phoneRow(R.string.guide_apps, Kind.APPS, 0xFF61A6EA, false, false), 6);
            add(screen, phoneRow(R.string.guide_accessibility, Kind.ACCESSIBILITY, 0xFF12A27F, true, false), 6);
        }
        // This is an illustration, not an interactive replacement for system settings.
        frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private LinearLayout phoneRow(int label, Kind icon, int color, boolean selected, boolean toggle) {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setMinimumHeight(dp(53));
        GradientDrawable background = rounded(Color.WHITE, 14);
        if (selected) background.setStroke(dp(1), BLUE);
        row.setBackground(background);
        row.addView(new PermissionIconView(getContext(), icon, color),
                new LinearLayout.LayoutParams(dp(35), dp(35)));
        TextView text = text(label, selected ? 17 : 13, INK, selected ? semibold : medium);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(8));
        row.addView(text, labelParams);
        if (toggle) {
            FrameLayout track = new FrameLayout(getContext());
            track.setBackground(rounded(BLUE, 16));
            View thumb = new View(getContext());
            thumb.setBackground(rounded(Color.WHITE, 13));
            FrameLayout.LayoutParams thumbParams = new FrameLayout.LayoutParams(dp(22), dp(22),
                    Gravity.END | Gravity.CENTER_VERTICAL);
            thumbParams.rightMargin = dp(3);
            track.addView(thumb, thumbParams);
            row.addView(track, new LinearLayout.LayoutParams(dp(47), dp(28)));
        } else if (selected) {
            ImageView chevron = new ImageView(getContext());
            chevron.setImageResource(R.drawable.onboarding_back);
            chevron.setImageTintList(ColorStateList.valueOf(BLUE));
            chevron.setRotation(180);
            row.addView(chevron, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        return row;
    }

    private TextView text(int label, int size, int color, Typeface font) {
        TextView view = new TextView(getContext());
        view.setText(label); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(font); view.setIncludeFontPadding(false); view.setLineSpacing(dp(3), 1.12f);
        return view;
    }
    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(getContext()); view.setOrientation(VERTICAL); return view;
    }
    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top); parent.addView(child, params);
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
