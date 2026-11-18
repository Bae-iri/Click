package com.click;

import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.click.ui.common.ClickUi;
import com.click.ui.common.ClickTextScale;

/** Welcomes the user after accessibility is enabled, before starting the hands-on tour. */
public final class TutorialReadyActivity extends AppCompatActivity {
    private com.click.designpreview.TutorialNarrator narrator;
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        narrator = new com.click.designpreview.TutorialNarrator(this);
        narrator.speak("이제 클릭이 도와드릴 수 있어요! 시작하기를 눌러 클릭 사용법을 알아보세요.");
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new com.click.designpreview.ClickBackgroundDrawable(getResources()));
        LinearLayout column = vertical();
        root.addView(column, new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels, dp(480)), -1,
                Gravity.CENTER_HORIZONTAL));

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cursor = new ImageView(this);
        cursor.setImageResource(com.click.designpreview.R.drawable.icon_floating_cursor);
        cursor.setContentDescription("클릭");
        brand.addView(cursor, new LinearLayout.LayoutParams(dp(44), dp(44)));
        column.addView(brand, new LinearLayout.LayoutParams(-1, dp(60)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = vertical();
        content.setGravity(Gravity.CENTER);
        content.setPadding(0, dp(16), 0, dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        ImageView clue = new com.click.designpreview.ClueArtworkView(this);
        clue.setImageResource(com.click.designpreview.R.drawable.clue4_complete);
        content.addView(clue, new LinearLayout.LayoutParams(dp(210), dp(210)));
        TextView title = ClickUi.title(this, getString(R.string.tutorial_ready_title));
        title.setId(R.id.tutorial_ready_title);
        title.setTextSize(ClickTextScale.size(this, 24));
        title.setGravity(Gravity.CENTER);
        title.setLineSpacing(dp(4), 1.1f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        content.addView(title, ClickUi.wrap(dp(28)));
        TextView description = ClickUi.body(this, getString(R.string.tutorial_ready_description));
        description.setTextSize(ClickTextScale.size(this, 16));
        description.setGravity(Gravity.CENTER);
        content.addView(description, ClickUi.wrap(dp(18)));

        Button start = ClickUi.primaryButton(this, getString(R.string.tutorial_ready_start));
        start.setId(R.id.tutorial_ready_start);
        start.setMinHeight(dp(64));
        start.setTextSize(ClickTextScale.size(this, 20));
        start.setPadding(dp(16), dp(14), dp(16), dp(14));
        start.setOnClickListener(view -> {
            start.setEnabled(false);
            startActivity(new Intent(this, TutorialActivity.class)
                    .putExtra(TutorialActivity.EXTRA_FROM_SIGN_UP, true));
            finish();
        });
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(-1, -2);
        startParams.setMargins(0, dp(12), 0, dp(20));
        column.addView(start, startParams);

        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            column.setPadding(dp(24) + system.left, system.top,
                    dp(24) + system.right, system.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                startActivity(new Intent(TutorialReadyActivity.this, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        new com.click.data.local.AccessibilityOnboardingState(this).markTutorialLaunched();
        narrator.resume();
    }
    @Override protected void onPause() { narrator.pause(); super.onPause(); }
    @Override protected void onDestroy() { narrator.close(); super.onDestroy(); }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private int dp(int value) { return ClickUi.dp(this, value); }
}
