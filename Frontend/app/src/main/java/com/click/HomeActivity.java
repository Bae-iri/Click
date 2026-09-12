package com.click;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.click.service.overlay.ClickOverlayService;
import com.click.core.permission.PermissionStatus;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.ClickHeroView;

public final class HomeActivity extends AppCompatActivity {
    private static final int PURPLE = Color.rgb(151, 92, 242);
    private final ActivityResultLauncher<String> microphonePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startOverlayWithPermission();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ClickUi.dp(this, 20), ClickUi.dp(this, 16),
                ClickUi.dp(this, 20), ClickUi.dp(this, 20));
        page.setBackgroundColor(Color.WHITE);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView homeLabel = new TextView(this);
        homeLabel.setText("❖  홈 화면");
        homeLabel.setTextSize(18);
        homeLabel.setText("");
        homeLabel.setTextColor(PURPLE);
        homeLabel.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        topBar.addView(homeLabel, new LinearLayout.LayoutParams(0, ClickUi.dp(this, 52), 1f));

        TextView menu = new TextView(this);
        menu.setText("메뉴");
        menu.setTextSize(17);
        menu.setTextColor(ClickUi.TEXT);
        menu.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        menu.setGravity(Gravity.CENTER);
        menu.setBackground(roundBackground(ClickUi.SECONDARY_RED, 28));
        menu.setOnClickListener(view -> {
            startActivity(new Intent(this, MenuActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });
        topBar.addView(menu, new LinearLayout.LayoutParams(ClickUi.dp(this, 76), ClickUi.dp(this, 42)));
        page.addView(topBar, new LinearLayout.LayoutParams(-1, ClickUi.dp(this, 54)));

        addSpace(page, 34);
        ClickHeroView hero = new ClickHeroView(this);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, ClickUi.dp(this, 112));
        page.addView(hero, heroParams);

        TextView logo = new TextView(this);
        logo.setText("Click AI");
        logo.setGravity(Gravity.CENTER);
        logo.setTextSize(34);
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setTextColor(ClickUi.RED);
        logo.setShadowLayer(ClickUi.dp(this, 3), 0, ClickUi.dp(this, 2), Color.rgb(255, 194, 203));
        page.addView(logo, new LinearLayout.LayoutParams(-1, ClickUi.dp(this, 58)));

        addSpace(page, 34);
        TextView description = new TextView(this);
        description.setText("음성 대화를 통해 필요한 화면을\n함께 찾아드려요");
        description.setGravity(Gravity.CENTER);
        description.setTextSize(19);
        description.setTextColor(Color.rgb(25, 25, 28));
        description.setLineSpacing(0, 1.35f);
        page.addView(description, new LinearLayout.LayoutParams(-1, ClickUi.dp(this, 74)));

        addSpace(page, 42);
        android.widget.Button start = ClickUi.primaryButton(this, "Click AI 시작");
        start.setTextSize(21);
        start.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        start.setBackground(roundBackground(ClickUi.RED, 16));
        start.setOnClickListener(view -> startOverlay());
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(-1, ClickUi.dp(this, 64));
        startParams.leftMargin = ClickUi.dp(this, 28);
        startParams.rightMargin = ClickUi.dp(this, 28);
        page.addView(start, startParams);

        TextView note = new TextView(this);
        note.setText("시작 버튼을 누르면 Click 아이콘이\n화면에 나타납니다.");
        note.setGravity(Gravity.CENTER);
        note.setTextSize(14);
        note.setTextColor(Color.rgb(55, 55, 60));
        note.setLineSpacing(0, 1.2f);
        page.addView(note, new LinearLayout.LayoutParams(-1, ClickUi.dp(this, 70)));

        setContentView(page);
    }

    private GradientDrawable roundBackground(int color, int radiusDp) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(ClickUi.dp(this, radiusDp));
        return background;
    }

    private void addSpace(LinearLayout page, int heightDp) {
        View space = new View(this);
        page.addView(space, new LinearLayout.LayoutParams(1, ClickUi.dp(this, heightDp)));
    }

    private void startOverlay() {
        if (!PermissionStatus.isAccessibilityEnabled(this)) {
            startActivity(new Intent(this, AccessibilityPermissionTutorialActivity.class));
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }
        startOverlayWithPermission();
    }

    private void startOverlayWithPermission() {
        startService(new Intent(this, ClickOverlayService.class));
        moveTaskToBack(true);
    }
}
