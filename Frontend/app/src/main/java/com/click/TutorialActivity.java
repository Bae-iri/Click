package com.click;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.click.designpreview.TutorialNarrator;
import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUi;
import com.click.ui.tutorial.TutorialExplanationView;

/** Reversible explanation cards, following the accessibility guide's read-and-next flow. */
public final class TutorialActivity extends AppCompatActivity {
    public static final String EXTRA_FROM_SIGN_UP = "from_sign_up";
    private static final String PAGE_STATE = "tutorial_explanation_page_v1";
    private static final int[] TITLES = {
        R.string.tutorial_explain_open_title, R.string.tutorial_explain_input_title,
        R.string.tutorial_explain_review_title, R.string.tutorial_explain_guidance_title,
        R.string.tutorial_explain_result_title
    };
    private static final int[] DESCRIPTIONS = {
        R.string.tutorial_explain_open_body, R.string.tutorial_explain_input_body,
        R.string.tutorial_explain_review_body, R.string.tutorial_explain_guidance_body,
        R.string.tutorial_explain_result_body
    };
    private int page;
    private TutorialNarrator narrator;
    private String spokenPage;
    private TextView title, description, caption, count, previous, replay;
    private Button next;
    private View[] progressBars;
    private FrameLayout example;
    private ScrollView scroll;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        narrator = new TutorialNarrator(this);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        FrameLayout canvas = new FrameLayout(this);
        canvas.setBackgroundColor(Color.WHITE);
        LinearLayout column = vertical();
        canvas.addView(column, new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels, dp(480)),
                -1, Gravity.CENTER_HORIZONTAL));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        previous = link(getString(R.string.tutorial_explain_previous));
        previous.setId(R.id.tutorial_back);
        previous.setOnClickListener(v -> showPage(page - 1));
        header.addView(previous, new LinearLayout.LayoutParams(-2, -2));
        TextView name = text(getString(R.string.tutorial_explain_name), 17, ClickUi.BLUE, true);
        name.setGravity(Gravity.CENTER);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView close = link(getString(R.string.tutorial_explain_close));
        close.setTag("tutorial_skip");
        close.setOnClickListener(v -> finishToHome());
        header.addView(close, new LinearLayout.LayoutParams(-2, -2));
        column.addView(header, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout progress = new LinearLayout(this);
        progress.setId(R.id.tutorial_explanation_progress);
        progress.setGravity(Gravity.CENTER);
        progress.setPadding(0, dp(12), 0, dp(8));
        progressBars = new View[TITLES.length];
        for (int i = 0; i < progressBars.length; i++) {
            View bar = new View(this);
            bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(5), 1);
            params.setMargins(dp(3), 0, dp(3), 0);
            progress.addView(bar, params);
            progressBars[i] = bar;
        }
        column.addView(progress, new LinearLayout.LayoutParams(-1, -2));
        count = text("", 15, ClickUi.SUBTEXT, false);
        count.setGravity(Gravity.END);
        column.addView(count, new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this);
        scroll.setId(R.id.tutorial_explanation_scroll);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = vertical();
        body.setPadding(dp(2), dp(20), dp(2), dp(16));
        title = text("", 24, ClickUi.TEXT, true);
        title.setId(R.id.tutorial_title);
        title.setLetterSpacing(-.025f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));
        description = text("", 18, 0xFF53657F, false);
        description.setId(R.id.tutorial_explanation_description);
        body.addView(description, spaced(12));
        TextView exampleLabel = text(getString(R.string.tutorial_explain_example), 15, ClickUi.SUBTEXT, false);
        body.addView(exampleLabel, spaced(24));
        example = new FrameLayout(this);
        example.setId(R.id.tutorial_phone_stage);
        body.addView(example, spaced(8));
        caption = text("", 16, 0xFF53657F, false);
        caption.setId(R.id.tutorial_explanation_caption);
        body.addView(caption, spaced(12));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = vertical();
        footer.setPadding(0, dp(6), 0, dp(12));
        replay = link(getString(R.string.tutorial_explain_replay));
        replay.setId(R.id.tutorial_explanation_replay);
        replay.setGravity(Gravity.CENTER);
        replay.setOnClickListener(v -> {
            if (narrator.isSpeaking()) narrator.stop();
            else narrator.replay(spokenPage);
        });
        narrator.setListener((speaking, unavailable) -> replay.setText(speaking
                ? R.string.tutorial_explain_stop_speech : R.string.tutorial_explain_replay));
        footer.addView(replay, new LinearLayout.LayoutParams(-1, -2));
        next = ClickUi.primaryButton(this, "");
        next.setId(R.id.tutorial_next);
        next.setTextSize(ClickTextScale.size(this, 20));
        next.setMinHeight(dp(60));
        next.setPadding(dp(18), dp(16), dp(18), dp(16));
        next.setOnClickListener(v -> {
            if (page == TITLES.length - 1) finishToHome();
            else showPage(page + 1);
        });
        footer.addView(next, new LinearLayout.LayoutParams(-1, -2));
        column.addView(footer, new LinearLayout.LayoutParams(-1, -2));

        setContentView(canvas);
        ViewCompat.setOnApplyWindowInsetsListener(canvas, (view, insets) -> {
            Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            column.setPadding(dp(22) + system.left, dp(8) + system.top,
                    dp(22) + system.right, system.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(canvas);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (page > 0) showPage(page - 1);
                else finishToHome();
            }
        });
        showPage(state == null ? 0 : state.getInt(PAGE_STATE, 0));
    }

    private void showPage(int requestedPage) {
        page = Math.max(0, Math.min(TITLES.length - 1, requestedPage));
        narrator.stop();
        title.setText(TITLES[page]);
        description.setText(DESCRIPTIONS[page]);
        count.setText(getString(R.string.click_tutorial_progress, page + 1, TITLES.length));
        findViewById(R.id.tutorial_explanation_progress).setContentDescription(
                getString(R.string.tutorial_explain_progress_description, page + 1, TITLES.length));
        previous.setEnabled(page > 0);
        previous.setAlpha(page > 0 ? 1f : .4f);
        for (int i = 0; i < progressBars.length; i++) {
            progressBars[i].setBackground(ClickUi.rounded(i <= page ? ClickUi.BLUE : ClickUi.BORDER, dp(3)));
        }
        example.removeAllViews();
        example.addView(new TutorialExplanationView(this, page), new FrameLayout.LayoutParams(-1, -2));
        next.setText(page == TITLES.length - 1 ? R.string.tutorial_explain_finish : R.string.tutorial_explain_next);
        caption.setText(page == TITLES.length - 1
                ? R.string.tutorial_explain_finish_caption : R.string.tutorial_explain_caption);
        // Every page is an explanation. No tap, recording, input or success gate is required.
        next.setEnabled(true);
        spokenPage = getString(TITLES[page]) + " " + getString(DESCRIPTIONS[page]);
        if (page == 0 || page == TITLES.length - 1) spokenPage += " " + caption.getText();
        scroll.post(() -> scroll.scrollTo(0, 0));
        narrator.speak(spokenPage);
    }

    private void finishToHome() {
        narrator.stop();
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
    @Override protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putInt(PAGE_STATE, page);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() { super.onResume(); narrator.resume(); }
    @Override protected void onPause() { narrator.pause(); super.onPause(); }
    @Override protected void onDestroy() { narrator.close(); super.onDestroy(); }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private TextView text(String value, int size, int color, boolean strong) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(ClickTextScale.size(this, size));
        view.setTextColor(color);
        view.setTypeface(getResources().getFont(strong
                ? com.click.designpreview.R.font.pretendard_semibold
                : com.click.designpreview.R.font.pretendard_regular));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(3), 1.12f);
        return view;
    }
    private TextView link(String value) {
        TextView view = text(value, 16, ClickUi.BLUE_DARK, false);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(48));
        view.setPadding(dp(8), dp(12), dp(8), dp(12));
        view.setFocusable(true);
        view.setBackground(null);
        return view;
    }
    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        return params;
    }
    private int dp(int value) { return ClickUi.dp(this, value); }
}
