package com.click;

import android.Manifest;
import android.app.Dialog;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.click.core.permission.PermissionStatus;
import com.click.domain.goal.QuickHelpTask;
import com.click.service.overlay.ClickOverlayService;
import com.click.service.overlay.OverlayActions;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.FloatingClickHost;

/** Main Click entry screen after accessibility onboarding. */
public final class HomeActivity extends AppCompatActivity {
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF7182A4;
    private Typeface medium;
    private Typeface semibold;
    private Typeface bold;
    private FloatingClickHost floatingControl;
    private final BroadcastReceiver overlayVisibilityReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (floatingControl != null) floatingControl.setSystemOverlayVisible(ClickOverlayService.isRunning());
        }
    };

    private final ActivityResultLauncher<String> microphonePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startOverlayWithPermission(null, true);
                else Toast.makeText(this, "음성으로 요청하려면 마이크 권한이 필요해요.",
                        Toast.LENGTH_LONG).show();
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        medium = getResources().getFont(com.click.designpreview.R.font.pretendard_medium);
        semibold = getResources().getFont(com.click.designpreview.R.font.pretendard_semibold);
        bold = getResources().getFont(com.click.designpreview.R.font.pretendard_bold);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        root.addView(new HomeBackgroundView(), new FrameLayout.LayoutParams(-1, -1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(22);
        page.setPadding(horizontal, dp(16), horizontal, dp(96));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels, dp(520)), -1,
                Gravity.CENTER_HORIZONTAL));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            page.setPadding(horizontal, dp(16) + bars.top, horizontal, dp(96) + bars.bottom);
            if (floatingControl != null) {
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) floatingControl.getLayoutParams();
                int left = bars.left, top = bars.top;
                int right = bars.right, bottom = bars.bottom;
                if (params.leftMargin != left || params.topMargin != top
                        || params.rightMargin != right || params.bottomMargin != bottom) {
                    params.setMargins(left, top, right, bottom);
                    floatingControl.setLayoutParams(params);
                }
            }
            return insets;
        });

        page.addView(header(), new LinearLayout.LayoutParams(-1, dp(58)));
        TextView title = text("무엇을\n도와드릴까요?", 34, INK, bold);
        title.setLetterSpacing(-.025f);
        title.setLineSpacing(dp(1), 1.03f);
        add(page, title, 30);
        TextView description = text("말하거나, 아래에서 선택해보세요.", 17, SECONDARY, medium);
        add(page, description, 12);
        add(page, voiceCard(), 22);

        LinearLayout sectionHeader = new LinearLayout(this);
        sectionHeader.setGravity(Gravity.CENTER_VERTICAL);
        sectionHeader.addView(text("자주 묻는 도움", 22, INK, bold),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = text("더보기  ›", 16, SECONDARY, semibold);
        more.setGravity(Gravity.CENTER);
        more.setPadding(dp(12), dp(10), 0, dp(10));
        more.setClickable(true);
        more.setFocusable(true);
        more.setContentDescription("자주 묻는 도움 더보기");
        more.setBackground(ripple(Color.TRANSPARENT, dp(14)));
        more.setOnClickListener(view -> showMoreHelp());
        sectionHeader.addView(more, new LinearLayout.LayoutParams(-2, -2));
        add(page, sectionHeader, 22);

        LinearLayout kakao = helpRow(HomeIconView.Kind.KAKAO, "카카오톡",
                "사진 보내는 방법 · 준비 중", false);
        kakao.setOnClickListener(view -> showComingSoon("카카오톡 도움"));
        add(page, kakao, 10);

        LinearLayout font = helpRow(HomeIconView.Kind.SETTINGS_TILE, "글씨 크기",
                "크게 하는 방법", true);
        font.setOnClickListener(view -> startQuickHelp(QuickHelpTask.FONT_SIZE));
        add(page, font, 8);

        LinearLayout phone = helpRow(HomeIconView.Kind.PHONE, "전화",
                "걸고 받는 방법 · 준비 중", false);
        phone.setOnClickListener(view -> showComingSoon("전화 도움"));
        add(page, phone, 8);

        floatingControl = new FloatingClickHost(this, new FloatingClickHost.Listener() {
            @Override public void onVoiceRequested() { startVoiceRequest(); }
            @Override public void onTextSubmitted(String text) { startSupportedGoal(text); }
        });
        root.addView(floatingControl, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        root.requestApplyInsets();
    }

    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, overlayVisibilityReceiver,
                new IntentFilter(OverlayActions.ACTION_VISIBILITY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override protected void onStop() {
        unregisterReceiver(overlayVisibilityReceiver);
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (floatingControl != null) floatingControl.setSystemOverlayVisible(ClickOverlayService.isRunning());
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this);
        mark.setImageResource(com.click.designpreview.R.drawable.ic_click_cursor);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView wordmark = text("click", 30, 0xFF07183F, bold);
        wordmark.setLetterSpacing(-.04f);
        LinearLayout.LayoutParams wordmarkParams = new LinearLayout.LayoutParams(0, -2, 1);
        wordmarkParams.setMarginStart(dp(4));
        row.addView(wordmark, wordmarkParams);

        HomeIconView settings = new HomeIconView(HomeIconView.Kind.SETTINGS);
        settings.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        settings.setContentDescription("설정 열기");
        settings.setClickable(true);
        settings.setFocusable(true);
        settings.setBackground(ripple(Color.TRANSPARENT, dp(14)));
        settings.setOnClickListener(view -> {
            startActivity(new Intent(this, MenuActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });
        row.addView(settings, new LinearLayout.LayoutParams(dp(52), dp(52)));
        return row;
    }

    private LinearLayout voiceCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFF1F7FF, 0xFFEAF3FF, 0xFFF5F8FD});
        fill.setCornerRadius(dp(26));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x183478F6), fill,
                ClickUi.rounded(Color.WHITE, dp(26))));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(getString(R.string.start_click_question_accessibility));
        card.setOnClickListener(view -> startVoiceRequest());

        LinearLayout audio = new LinearLayout(this);
        audio.setGravity(Gravity.CENTER);
        audio.addView(new HomeWaveView(), new LinearLayout.LayoutParams(dp(68), dp(48)));
        HomeIconView mic = new HomeIconView(HomeIconView.Kind.MIC);
        LinearLayout.LayoutParams micParams = new LinearLayout.LayoutParams(dp(86), dp(86));
        micParams.setMarginStart(dp(14));
        micParams.setMarginEnd(dp(14));
        audio.addView(mic, micParams);
        HomeWaveView right = new HomeWaveView();
        right.setScaleX(-1f);
        audio.addView(right, new LinearLayout.LayoutParams(dp(68), dp(48)));
        card.addView(audio, new LinearLayout.LayoutParams(-1, dp(90)));

        TextView label = text(getString(R.string.start_click_question), 20, INK, bold);
        label.setGravity(Gravity.CENTER);
        add(card, label, 6);
        TextView example = text("예) 글자 크기를 크게 해줘", 15, SECONDARY, medium);
        example.setGravity(Gravity.CENTER);
        add(card, example, 7);
        return card;
    }

    private LinearLayout helpRow(HomeIconView.Kind kind, String title, String subtitle,
                                 boolean supported) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(13), dp(9), dp(12), dp(9));
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFFF6FAFF, 0xFFEEF5FD, 0xFFF5F8FC});
        fill.setCornerRadius(dp(22));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x163478F6), fill,
                ClickUi.rounded(Color.WHITE, dp(22))));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(title + ". " + subtitle);
        row.addView(new HomeIconView(kind), new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -1, 1);
        labelsParams.setMarginStart(dp(16));
        labels.addView(text(title, 18, INK, semibold), new LinearLayout.LayoutParams(-1, -2));
        TextView secondary = text(subtitle, 14, supported ? SECONDARY : 0xFF8997B1, medium);
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(-1, -2);
        secondaryParams.topMargin = dp(4);
        labels.addView(secondary, secondaryParams);
        row.addView(labels, labelsParams);

        HomeIconView chevron = new HomeIconView(HomeIconView.Kind.CHEVRON);
        chevron.setAlpha(supported ? 1f : .58f);
        row.addView(chevron, new LinearLayout.LayoutParams(dp(28), dp(42)));
        return row;
    }

    private void startVoiceRequest() {
        if (!PermissionStatus.isAccessibilityEnabled(this)) { openPermissionGuide(); return; }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startOverlayWithPermission(null, true);
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO);
    }

    private void startSupportedGoal(String goal) {
        if (!PermissionStatus.isAccessibilityEnabled(this)) { openPermissionGuide(); return; }
        startOverlayWithPermission(goal, false);
    }

    private void startQuickHelp(QuickHelpTask task) {
        if (!PermissionStatus.isAccessibilityEnabled(this)) { openPermissionGuide(); return; }
        startOverlayWithPermission(task.requestText, false, task.goalType);
    }

    private void showComingSoon(String feature) {
        Toast.makeText(this, feature + "은 준비 중이에요.\n지금은 글자 크기 조절을 도와드릴 수 있어요.",
                Toast.LENGTH_LONG).show();
    }

    private void showMoreHelp() {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(28), dp(24), dp(20));
        panel.setBackground(ClickUi.rounded(Color.WHITE, dp(28)));
        TextView heading = text("자주 묻는 도움", 25, INK, bold);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        add(panel, heading, 0);
        add(panel, text("필요한 안내를 다시 확인해 보세요.", 17, SECONDARY, medium), 12);
        LinearLayout accessibility = helpRow(HomeIconView.Kind.ACCESSIBILITY,
                "접근성 설정 안내", "처음부터 다시 보기", true);
        accessibility.setOnClickListener(view -> {
            dialog.dismiss();
            startActivity(new Intent(this, com.click.designpreview.PermissionGuideActivity.class));
        });
        add(panel, accessibility, 24);
        Button close = new Button(this);
        close.setText("닫기");
        close.setTextSize(18);
        close.setTypeface(semibold);
        close.setTextColor(ClickUi.BLUE);
        close.setAllCaps(false);
        close.setMinHeight(dp(56));
        close.setBackground(ripple(0xFFEEF4FF, dp(16)));
        close.setOnClickListener(view -> dialog.dismiss());
        add(panel, close, 20);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        dialog.setContentView(scroll);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setGravity(Gravity.BOTTOM);
        dialog.show();
        dialog.getWindow().setLayout(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(480)), -2);
    }

    private void openPermissionGuide() {
        startActivity(new Intent(this, com.click.designpreview.AccessibilityHelpActivity.class));
    }

    private void startOverlayWithPermission(String goal, boolean openVoice) {
        startOverlayWithPermission(goal, openVoice, null);
    }

    private void startOverlayWithPermission(String goal, boolean openVoice, String selectedGoalType) {
        if (!PermissionStatus.isAccessibilityEnabled(this)
                || com.click.service.accessibility.ClickAccessibilityService.accessibilityWindowManager() == null) {
            Toast.makeText(this, "접근성 연결이 끊겼어요. 설정에서 click을 껐다가 다시 켜주세요.",
                    Toast.LENGTH_LONG).show();
            openPermissionGuide();
            return;
        }
        Intent service = new Intent(this, ClickOverlayService.class);
        if (goal != null) service.putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL, goal);
        if (selectedGoalType != null) service.putExtra(ClickOverlayService.EXTRA_SELECTED_GOAL_TYPE, selectedGoalType);
        service.putExtra(ClickOverlayService.EXTRA_OPEN_VOICE, openVoice);
        startService(service);
        moveTaskToBack(true);
    }

    private TextView text(String value, int size, int color, Typeface typeface) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setTypeface(typeface);
        text.setIncludeFontPadding(false);
        return text;
    }

    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin);
        parent.addView(child, params);
    }

    private Drawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x173478F6),
                ClickUi.rounded(color, radius), ClickUi.rounded(Color.WHITE, radius));
    }

    private int dp(int value) { return ClickUi.dp(this, value); }

    private final class HomeBackgroundView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        HomeBackgroundView() {
            super(HomeActivity.this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }
        @Override protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFF4F8FF); canvas.drawCircle(w + dp(24), dp(118), dp(108), paint);
            paint.setColor(0xFFEAF3FF); canvas.drawCircle(w + dp(38), dp(215), dp(104), paint);
            paint.setColor(0xFFEAF3FF); canvas.drawCircle(dp(42), h + dp(40), dp(150), paint);
            paint.setColor(0xFFF3F7FD); canvas.drawCircle(w * .48f, h + dp(65), dp(178), paint);
            paint.setColor(0xFFE4EEFD); canvas.drawCircle(w * .78f, h + dp(55), dp(132), paint);
            paint.setColor(0xFFDCEAFF); canvas.drawCircle(w + dp(20), h - dp(58), dp(92), paint);
        }
    }

    private final class HomeWaveView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        HomeWaveView() { super(HomeActivity.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(@NonNull Canvas canvas) {
            paint.setColor(0xFFAACBFF); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(dp(6));
            float cy = getHeight() / 2f;
            canvas.drawLine(dp(12), cy - dp(7), dp(12), cy + dp(7), paint);
            canvas.drawLine(dp(30), cy - dp(16), dp(30), cy + dp(16), paint);
            canvas.drawLine(dp(48), cy - dp(10), dp(48), cy + dp(10), paint);
        }
    }

    private final class HomeIconView extends View {
        enum Kind { SETTINGS, MIC, KAKAO, SETTINGS_TILE, PHONE, ACCESSIBILITY, CHEVRON }
        private final Kind kind;
        private final Drawable referenceIcon;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        HomeIconView(Kind kind) {
            super(HomeActivity.this); this.kind = kind;
            referenceIcon = kind == Kind.SETTINGS || kind == Kind.SETTINGS_TILE
                    ? ContextCompat.getDrawable(HomeActivity.this, R.drawable.ic_home_settings)
                    : kind == Kind.PHONE
                    ? ContextCompat.getDrawable(HomeActivity.this, R.drawable.ic_home_phone) : null;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }
        @Override protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            if (referenceIcon != null) {
                referenceIcon.setBounds(0, 0, getWidth(), getHeight());
                referenceIcon.draw(canvas);
                return;
            }
            if (kind == Kind.MIC) {
                paint.setShader(new LinearGradient(0, 0, w, h, 0xFF3478F6, 0xFF5A9AFF,
                        Shader.TileMode.CLAMP));
                paint.setStyle(Paint.Style.FILL); paint.setShadowLayer(dp(12), 0, dp(6), 0x353478F6);
                canvas.drawCircle(cx, cy, Math.min(w, h) * .46f, paint);
                paint.clearShadowLayer(); paint.setShader(null);
                drawMic(canvas, cx, cy, Color.WHITE, Math.min(w, h) / 98f);
            } else if (kind == Kind.KAKAO) {
                tile(canvas, 0xFFFFE735);
                paint.setColor(0xFF3A2133); paint.setStyle(Paint.Style.FILL);
                canvas.drawOval(new RectF(w * .19f, h * .22f, w * .81f, h * .70f), paint);
                path.reset(); path.moveTo(w * .38f, h * .65f); path.lineTo(w * .30f, h * .82f);
                path.lineTo(w * .51f, h * .68f); path.close(); canvas.drawPath(path, paint);
                paint.setTextAlign(Paint.Align.CENTER); paint.setColor(0xFFFFE735);
                paint.setTypeface(bold); paint.setTextSize(w * .22f);
                canvas.drawText("TALK", cx, h * .53f, paint);
            } else if (kind == Kind.ACCESSIBILITY) {
                tile(canvas, 0xFFE5EFFF);
                canvas.save(); canvas.translate(cx, cy);
                float scale = Math.min(w, h) / 56f;
                canvas.scale(scale, scale);
                paint.setColor(0xFF3478F6); paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(0, -13, 4, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3.5f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                canvas.drawLine(-14, -5, 14, -5, paint);
                canvas.drawLine(0, -5, 0, 5, paint);
                canvas.drawLine(0, 5, -8, 18, paint);
                canvas.drawLine(0, 5, 8, 18, paint);
                canvas.restore();
            } else if (kind == Kind.CHEVRON) {
                paint.setColor(0xFF7083A8); paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2)); paint.setStrokeCap(Paint.Cap.ROUND);
                path.reset(); path.moveTo(w * .38f, h * .32f); path.lineTo(w * .62f, h * .5f);
                path.lineTo(w * .38f, h * .68f); canvas.drawPath(path, paint);
            }
            paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.LEFT);
        }
        private void tile(Canvas canvas, int color) {
            paint.setColor(color); paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), dp(15), dp(15), paint);
        }
        private void drawMic(Canvas canvas, float cx, float cy, int color, float scale) {
            paint.setShader(null); paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeWidth(5f * scale);
            canvas.drawRoundRect(new RectF(cx - 11f * scale, cy - 22f * scale,
                    cx + 11f * scale, cy + 7f * scale), 11f * scale, 11f * scale, paint);
            canvas.drawArc(new RectF(cx - 20f * scale, cy - 4f * scale,
                    cx + 20f * scale, cy + 16f * scale), 0, 180, false, paint);
            canvas.drawLine(cx, cy + 16f * scale, cx, cy + 28f * scale, paint);
        }
        private void drawGear(Canvas canvas, float cx, float cy, int color, float scale) {
            paint.setShader(null); paint.setColor(color); paint.setStyle(Paint.Style.FILL);
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            for (int i = 0; i < 48; i++) {
                double angle = Math.PI * 2 * i / 48d;
                float radius = (i % 6 == 2 || i % 6 == 3 ? 21f : 16.5f) * scale;
                float x = cx + (float) Math.cos(angle) * radius;
                float y = cy + (float) Math.sin(angle) * radius;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            path.close();
            path.addCircle(cx, cy, 7 * scale, Path.Direction.CW);
            canvas.drawPath(path, paint);
            path.setFillType(Path.FillType.WINDING);
        }
    }
}
