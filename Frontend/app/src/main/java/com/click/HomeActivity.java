package com.click;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.click.core.permission.PermissionStatus;
import com.click.service.overlay.ClickOverlayService;
import com.click.service.overlay.OverlayActions;
import com.click.ui.common.ClickUi;
import com.click.ui.home.HomeArtworkView;
import com.click.ui.overlay.FloatingClickHost;

/** A quiet entry screen with one start action and the tutorial favorite. */
public final class HomeActivity extends AppCompatActivity {
    private static final int BACKGROUND = 0xFFF3F3F3;
    private Typeface semibold;
    private FloatingClickHost floatingControl;
    private boolean inputOpened;
    private OnBackPressedCallback closeInput;
    private final BroadcastReceiver overlayVisibilityReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { syncFloatingControl(); }
    };
    private final ActivityResultLauncher<String> microphonePermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startOverlayWithPermission(null, true);
                else Toast.makeText(this, "음성으로 요청하려면 마이크 권한이 필요해요.", Toast.LENGTH_LONG).show();
            });

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        semibold = getResources().getFont(com.click.designpreview.R.font.pretendard_semibold);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BACKGROUND);
        FrameLayout sheet = new FrameLayout(this);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.WHITE);
        float radius = dp(34);
        surface.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        sheet.setBackground(surface);
        sheet.setClipToOutline(true);
        sheet.setElevation(dp(4));
        FrameLayout.LayoutParams sheetParams = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(16), dp(520)),
                -1, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        root.addView(sheet, sheetParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(32), dp(38), dp(24), dp(48));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        sheet.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        View handle = new View(this);
        handle.setBackground(ClickUi.rounded(0xFFCDCDCD, dp(3)));
        handle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams handleParams = new FrameLayout.LayoutParams(dp(32), dp(4), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        handleParams.topMargin = dp(5);
        sheet.addView(handle, handleParams);

        page.addView(header(), new LinearLayout.LayoutParams(-1, dp(48)));
        TextView title = text(R.string.home_start_title, 28);
        title.setLetterSpacing(-.025f);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        add(page, title, 16);
        HomeArtworkView start = new HomeArtworkView(this, HomeArtworkView.Kind.START);
        start.setId(R.id.home_start);
        start.setContentDescription(getString(R.string.home_start_accessibility));
        start.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        start.setClickable(true);
        start.setFocusable(true);
        start.setBackground(ripple(Color.TRANSPARENT, 28));
        start.setOnClickListener(view -> {
            inputOpened = true;
            closeInput.setEnabled(true);
            floatingControl.showMenuFrom(start);
        });
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(-1, dp(210));
        startParams.topMargin = dp(20);
        page.addView(start, startParams);
        TextView favorites = text(R.string.home_favorites, 23);
        if (Build.VERSION.SDK_INT >= 28) favorites.setAccessibilityHeading(true);
        add(page, favorites, 12);
        add(page, tutorialCard(), 20);

        floatingControl = new FloatingClickHost(this, new FloatingClickHost.Listener() {
            @Override public void onVoiceRequested() { startVoiceRequest(); }
            @Override public void onTextSubmitted(String text) { startSupportedGoal(text); }
            @Override public void onHidden() { inputOpened = false; closeInput.setEnabled(false); }
        });
        // Keep the home clear until the central start action is selected.
        floatingControl.setVisibility(View.INVISIBLE);
        root.addView(floatingControl, new FrameLayout.LayoutParams(-1, -1));
        closeInput = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                inputOpened = false;
                floatingControl.setVisibility(View.INVISIBLE);
                setEnabled(false);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, closeInput);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            sheetParams.setMargins(dp(8) + bars.left, dp(8) + bars.top, dp(8) + bars.right, dp(8) + bars.bottom);
            sheetParams.width = Math.min(getResources().getDisplayMetrics().widthPixels
                    - bars.left - bars.right - dp(16), dp(520));
            sheet.setLayoutParams(sheetParams);
            FrameLayout.LayoutParams floatingParams = (FrameLayout.LayoutParams) floatingControl.getLayoutParams();
            floatingParams.setMargins(bars.left, bars.top, bars.right, bars.bottom);
            floatingControl.setLayoutParams(floatingParams);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
    }

    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, overlayVisibilityReceiver,
                new IntentFilter(OverlayActions.ACTION_VISIBILITY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
    }
    @Override protected void onStop() { unregisterReceiver(overlayVisibilityReceiver); super.onStop(); }
    @Override protected void onResume() { super.onResume(); syncFloatingControl(); }
    private void syncFloatingControl() {
        if (floatingControl == null) return;
        if (!inputOpened) floatingControl.setVisibility(View.INVISIBLE);
        else floatingControl.setSystemOverlayVisible(ClickOverlayService.isRunning());
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        HomeArtworkView wordmark = new HomeArtworkView(this, HomeArtworkView.Kind.WORDMARK);
        wordmark.setContentDescription("click");
        wordmark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        row.addView(wordmark, new LinearLayout.LayoutParams(dp(78), dp(30)));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        HomeArtworkView settings = new HomeArtworkView(this, HomeArtworkView.Kind.SETTINGS);
        settings.setId(R.id.home_settings);
        settings.setContentDescription(getString(R.string.home_settings_accessibility));
        settings.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        settings.setClickable(true);
        settings.setFocusable(true);
        settings.setBackground(ripple(Color.TRANSPARENT, 24));
        settings.setOnClickListener(view -> {
            startActivity(new Intent(this, MenuActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });
        row.addView(settings, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    private View tutorialCard() {
        LinearLayout card = new LinearLayout(this);
        card.setId(R.id.home_tutorial);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(18), dp(12), dp(12), dp(12));
        card.setMinimumHeight(dp(68));
        card.setBackground(ripple(0xFFD9ECFF, 19));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(getString(R.string.home_tutorial_accessibility));
        card.setOnClickListener(view -> startActivity(new Intent(this, TutorialActivity.class)));
        card.addView(new HomeArtworkView(this, HomeArtworkView.Kind.CURSOR), new LinearLayout.LayoutParams(dp(43), dp(43)));
        TextView label = text(R.string.home_tutorial, 20);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMargins(dp(16), 0, dp(8), 0);
        card.addView(label, labelParams);
        card.addView(new HomeArtworkView(this, HomeArtworkView.Kind.STAR), new LinearLayout.LayoutParams(dp(34), dp(34)));
        return card;
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
    private void openPermissionGuide() {
        startActivity(new Intent(this, com.click.designpreview.AccessibilityHelpActivity.class));
    }
    private void startOverlayWithPermission(String goal, boolean openVoice) {
        if (!PermissionStatus.isAccessibilityEnabled(this)
                || com.click.service.accessibility.ClickAccessibilityService.accessibilityWindowManager() == null) {
            Toast.makeText(this, "접근성 연결이 끊겼어요. 설정에서 click을 껐다가 다시 켜주세요.", Toast.LENGTH_LONG).show();
            openPermissionGuide();
            return;
        }
        inputOpened = false;
        closeInput.setEnabled(false);
        floatingControl.setVisibility(View.INVISIBLE);
        Intent service = new Intent(this, ClickOverlayService.class);
        if (goal != null) service.putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL, goal);
        service.putExtra(ClickOverlayService.EXTRA_OPEN_VOICE, openVoice);
        startService(service);
        moveTaskToBack(true);
    }
    private TextView text(int resource, int size) {
        TextView text = new TextView(this);
        text.setText(resource);
        text.setTextSize(size);
        text.setTextColor(0xFF080808);
        text.setTypeface(semibold);
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
                ClickUi.rounded(color, dp(radius)), ClickUi.rounded(Color.WHITE, dp(radius)));
    }
    private int dp(int value) { return ClickUi.dp(this, value); }
}
