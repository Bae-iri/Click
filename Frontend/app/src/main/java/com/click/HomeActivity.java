package com.click;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.click.core.permission.PermissionStatus;
import com.click.domain.goal.QuickHelpTask;
import com.click.service.overlay.ClickOverlayService;
import com.click.ui.common.ClickUi;
import com.click.ui.home.HomeIconView;
import com.click.ui.home.HomeStartArtworkView;

/** Main Click entry screen after accessibility onboarding. */
public final class HomeActivity extends AppCompatActivity {
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF8190AA;
    private static final int BLUE = 0xFF3478FF;
    private Typeface medium, semibold, bold;
    private HomeStartArtworkView startArtwork;
    private ScrollView homeScroll;
    private View favoritesHeading;
    private LinearLayout homeTab, favoritesTab;
    private boolean homeResumed;
    private final Rect visibleArtwork = new Rect();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        medium = getResources().getFont(com.click.designpreview.R.font.pretendard_medium);
        semibold = getResources().getFont(com.click.designpreview.R.font.pretendard_semibold);
        bold = getResources().getFont(com.click.designpreview.R.font.pretendard_bold);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFFFCFDFF);
        root.addView(new HomeBackgroundView(), new FrameLayout.LayoutParams(-1, -1));
        homeScroll = new ScrollView(this);
        homeScroll.setId(R.id.home_scroll);
        homeScroll.setFillViewport(true);
        homeScroll.setClipToPadding(true);
        homeScroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setClipChildren(false);
        int horizontal = dp(24);
        page.setPadding(horizontal, dp(16), horizontal, dp(92));
        homeScroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(homeScroll, new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels, dp(520)), -1, Gravity.CENTER_HORIZONTAL));

        page.addView(header(), new LinearLayout.LayoutParams(-1, dp(48)));
        add(page, hero(), 14);
        add(page, requestCard(), 8);
        add(page, suggestions(), 8);
        favoritesHeading = favoritesHeader();
        add(page, favoritesHeading, 14);
        addFavorites(page);

        LinearLayout navigation = bottomNavigation();
        root.addView(navigation, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            homeScroll.setPadding(0, bars.top, 0, 0);
            page.setPadding(horizontal + bars.left, dp(16),
                    horizontal + bars.right, dp(92) + bars.bottom);
            navigation.setPadding(bars.left + dp(10), dp(6), bars.right + dp(10), bars.bottom + dp(4));
            return insets;
        });
        homeScroll.setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) -> {
            if (y == 0) selectNavigation(false);
            updateArtworkAnimation();
        });
        setContentView(root);
        root.requestApplyInsets();
    }

    @Override protected void onResume() {
        super.onResume(); homeResumed = true;
        startArtwork.setAnimationActive(true);
        homeScroll.post(this::updateArtworkAnimation);
    }
    @Override protected void onPause() {
        homeResumed = false;
        if (startArtwork != null) startArtwork.setAnimationActive(false);
        super.onPause();
    }
    private void updateArtworkAnimation() {
        startArtwork.setAnimationActive(homeResumed && startArtwork.getGlobalVisibleRect(visibleArtwork));
    }

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(icon(HomeIconView.Kind.CLICK), new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout.LayoutParams mark = new LinearLayout.LayoutParams(dp(66), dp(28));
        mark.setMarginStart(dp(8));
        row.addView(icon(HomeIconView.Kind.WORDMARK), mark);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        HomeIconView settings = icon(HomeIconView.Kind.SETTINGS);
        settings.setId(R.id.home_settings);
        settings.setPadding(dp(7), dp(7), dp(7), dp(7));
        settings.setContentDescription(getString(R.string.home_settings_accessibility));
        settings.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        settings.setClickable(true); settings.setFocusable(true);
        whiteCard(settings, 24, 3);
        settings.setOnClickListener(view -> {
            startActivity(new Intent(this, MenuActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        });
        row.addView(settings, new LinearLayout.LayoutParams(dp(44), dp(44)));
        return row;
    }

    private View hero() {
        FrameLayout hero = new FrameLayout(this);
        hero.setMinimumHeight(dp(200));
        hero.setClipChildren(false);
        int contentWidth = Math.min(getResources().getDisplayMetrics().widthPixels, dp(520)) - dp(48);
        startArtwork = new HomeStartArtworkView(this);
        hero.addView(startArtwork, new FrameLayout.LayoutParams(Math.round(contentWidth * .54f),
                dp(200), Gravity.RIGHT | Gravity.BOTTOM));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(getString(R.string.home_greeting), 14, SECONDARY, semibold));
        TextView headline = text(getString(R.string.home_start_title), 26, INK, bold);
        SpannableString title = new SpannableString(getString(R.string.home_start_title));
        int start = title.toString().indexOf("클릭");
        if (start >= 0) title.setSpan(new ForegroundColorSpan(BLUE), start, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        headline.setText(title);
        headline.setLetterSpacing(-.025f);
        headline.setLineSpacing(dp(2), 1f);
        if (Build.VERSION.SDK_INT >= 28) headline.setAccessibilityHeading(true);
        add(copy, headline, 8);
        TextView description = text(getString(R.string.home_intro), 14, SECONDARY, medium);
        description.setLineSpacing(dp(3), 1f);
        add(copy, description, 12);
        hero.addView(copy, new FrameLayout.LayoutParams(Math.round(contentWidth * .63f), -2, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        return hero;
    }

    private View requestCard() {
        LinearLayout card = new LinearLayout(this);
        card.setId(R.id.home_start);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(dp(54));
        card.setPadding(dp(16), dp(12), dp(14), dp(12));
        card.setContentDescription(getString(R.string.home_start_accessibility));
        card.setClickable(true); card.setFocusable(true);
        whiteCard(card, 22, 4);
        card.addView(icon(HomeIconView.Kind.SEARCH), new LinearLayout.LayoutParams(dp(23), dp(23)));
        TextView hint = text(getString(R.string.home_request_hint), 15, SECONDARY, medium);
        LinearLayout.LayoutParams label = new LinearLayout.LayoutParams(0, -2, 1);
        label.setMarginStart(dp(14));
        card.addView(hint, label);
        card.addView(icon(HomeIconView.Kind.CHEVRON), new LinearLayout.LayoutParams(dp(18), dp(24)));
        card.setOnClickListener(view -> startOverlayWithPermission(null, null));
        return card;
    }

    private View suggestions() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        addChip(chips, "카카오톡 사진 보내기", () -> showComingSoon("카카오톡 도움"));
        addChip(chips, "글씨 크게 하기", () -> startQuickHelp(QuickHelpTask.FONT_SIZE));
        addChip(chips, "전화 걸기", () -> showComingSoon("전화 도움"));
        scroll.addView(chips, new HorizontalScrollView.LayoutParams(-2, -2));
        return scroll;
    }
    private void addChip(LinearLayout chips, String label, Runnable action) {
        TextView chip = text(label, 12, 0xFF6586BC, medium);
        chip.setGravity(Gravity.CENTER); chip.setMinimumHeight(dp(34));
        chip.setPadding(dp(13), dp(7), dp(13), dp(7));
        chip.setBackground(ripple(0xFFF0F5FD, 20));
        chip.setClickable(true); chip.setFocusable(true);
        chip.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        if (chips.getChildCount() > 0) params.setMarginStart(dp(6));
        chips.addView(chip, params);
    }

    private View favoritesHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.home_favorites), 19, INK, bold);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = text("더보기  ›", 14, BLUE, medium);
        more.setGravity(Gravity.CENTER); more.setMinimumHeight(dp(40));
        more.setPadding(dp(12), 0, 0, 0);
        more.setContentDescription("즐겨찾기 더보기");
        more.setClickable(true); more.setFocusable(true);
        more.setBackground(ripple(Color.TRANSPARENT, 14));
        more.setOnClickListener(view -> showMoreHelp());
        header.addView(more, new LinearLayout.LayoutParams(-2, -2));
        return header;
    }

    private void addFavorites(LinearLayout page) {
        View kakao = helpRow(HomeIconView.Kind.KAKAO, "카카오톡", "사진 보내는 방법 · 준비 중");
        kakao.setOnClickListener(view -> showComingSoon("카카오톡 도움")); add(page, kakao, 8);
        View font = helpRow(HomeIconView.Kind.FONT, "글씨 크기", "크게 하는 방법");
        font.setOnClickListener(view -> startQuickHelp(QuickHelpTask.FONT_SIZE)); add(page, font, 4);
        View brightness = helpRow(HomeIconView.Kind.BRIGHTNESS, "화면 밝기", "더 밝게 하는 방법");
        brightness.setOnClickListener(view -> startQuickHelp(QuickHelpTask.BRIGHTNESS)); add(page, brightness, 4);
        View volume = helpRow(HomeIconView.Kind.MEDIA, "동영상·음악 소리", "소리를 키우는 방법");
        volume.setOnClickListener(view -> startQuickHelp(QuickHelpTask.MEDIA_VOLUME)); add(page, volume, 4);
        View phone = helpRow(HomeIconView.Kind.PHONE, "전화", "걸고 받는 방법 · 준비 중");
        phone.setOnClickListener(view -> showComingSoon("전화 도움")); add(page, phone, 4);
        View tutorial = helpRow(HomeIconView.Kind.CLICK, getString(R.string.home_tutorial), "사용 방법 다시 보기");
        tutorial.setId(R.id.home_tutorial);
        tutorial.setOnClickListener(view -> startActivity(new Intent(this, TutorialActivity.class)));
        add(page, tutorial, 4);
    }

    private View helpRow(HomeIconView.Kind kind, String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(10), dp(13), dp(10));
        row.setMinimumHeight(dp(64)); row.setClickable(true); row.setFocusable(true);
        row.setContentDescription(title + ". " + subtitle);
        whiteCard(row, 22, 2);
        row.addView(icon(kind), new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, INK, bold));
        add(labels, text(subtitle, 13, SECONDARY, medium), 4);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(14)); labelParams.setMarginEnd(dp(6));
        row.addView(labels, labelParams);
        row.addView(icon(HomeIconView.Kind.STAR), new LinearLayout.LayoutParams(dp(36), dp(36)));
        return row;
    }

    private LinearLayout bottomNavigation() {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp(10), dp(6), dp(10), dp(4));
        navigation.setBackground(ClickUi.rounded(Color.WHITE, dp(24)));
        shadow(navigation, 8);
        homeTab = navigationItem(HomeIconView.Kind.HOME, "홈", R.id.home_nav_home);
        homeTab.setOnClickListener(view -> { homeScroll.smoothScrollTo(0, 0); selectNavigation(false); });
        LinearLayout help = navigationItem(HomeIconView.Kind.HELP, "도와주세요", R.id.home_nav_help);
        help.setOnClickListener(view -> startOverlayWithPermission(null, null));
        favoritesTab = navigationItem(HomeIconView.Kind.BOOKMARK, "내 즐겨찾기", R.id.home_nav_favorites);
        favoritesTab.setOnClickListener(view -> {
            homeScroll.smoothScrollTo(0, Math.max(0, favoritesHeading.getTop() - dp(12)));
            selectNavigation(true);
        });
        for (View tab : new View[]{homeTab, help, favoritesTab})
            navigation.addView(tab, new LinearLayout.LayoutParams(0, dp(58), 1));
        selectNavigation(false);
        return navigation;
    }
    private LinearLayout navigationItem(HomeIconView.Kind kind, String label, int id) {
        LinearLayout item = new LinearLayout(this);
        item.setId(id); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER);
        item.setClickable(true); item.setFocusable(true); item.setContentDescription(label);
        item.setBackground(ripple(Color.TRANSPARENT, 18));
        item.addView(icon(kind), new LinearLayout.LayoutParams(dp(25), dp(25)));
        TextView title = text(label, 10, 0xFF8993A4, semibold);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.topMargin = dp(4); item.addView(title, params);
        return item;
    }
    private void selectNavigation(boolean favorites) {
        if (homeTab == null || favoritesTab == null) return;
        setTabSelected(homeTab, !favorites); setTabSelected(favoritesTab, favorites);
    }
    private void setTabSelected(LinearLayout tab, boolean selected) {
        int color = selected ? BLUE : 0xFF8993A4;
        tab.setSelected(selected);
        ((HomeIconView) tab.getChildAt(0)).setAccentColor(color);
        ((TextView) tab.getChildAt(1)).setTextColor(color);
    }

    private void startQuickHelp(QuickHelpTask task) {
        startOverlayWithPermission(task.requestText, task.goalType);
    }
    private void showComingSoon(String feature) {
        Toast.makeText(this, feature + "은 준비 중이에요.\n글자 크기, 화면 밝기, 미디어 음량 조절을 이용해 주세요.", Toast.LENGTH_LONG).show();
    }
    private void showMoreHelp() {
        Dialog dialog = new Dialog(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(24), dp(26), dp(24), dp(20));
        panel.setBackground(ClickUi.rounded(0xFFFCFDFF, dp(28)));
        add(panel, text(getString(R.string.home_favorites), 21, INK, bold), 0);
        add(panel, text("필요한 안내를 다시 확인해 보세요.", 15, SECONDARY, medium), 12);
        View accessibility = helpRow(HomeIconView.Kind.ACCESSIBILITY, "접근성 설정 안내", "처음부터 다시 보기");
        accessibility.setOnClickListener(view -> {
            dialog.dismiss(); startActivity(new Intent(this, com.click.designpreview.PermissionGuideActivity.class));
        });
        add(panel, accessibility, 24);
        Button close = new Button(this);
        close.setText("닫기"); close.setTextSize(16); close.setTypeface(semibold);
        close.setTextColor(BLUE); close.setAllCaps(false); close.setMinHeight(dp(48));
        close.setBackground(ripple(0xFFEEF4FF, 16)); close.setOnClickListener(view -> dialog.dismiss());
        add(panel, close, 20);
        ScrollView scroll = new ScrollView(this); scroll.addView(panel); dialog.setContentView(scroll);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setGravity(Gravity.BOTTOM); dialog.show();
        dialog.getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(480)), -2);
    }
    private void openPermissionGuide() {
        startActivity(new Intent(this, com.click.designpreview.AccessibilityHelpActivity.class));
    }
    private void startOverlayWithPermission(String goal, String selectedGoalType) {
        if (!PermissionStatus.isAccessibilityEnabled(this)) { openPermissionGuide(); return; }
        if (com.click.service.accessibility.ClickAccessibilityService.accessibilityWindowManager() == null) {
            Toast.makeText(this, "접근성 연결이 끊겼어요. 설정에서 click을 껐다가 다시 켜주세요.", Toast.LENGTH_LONG).show();
            openPermissionGuide(); return;
        }
        Intent service = new Intent(this, ClickOverlayService.class);
        if (goal != null) service.putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL, goal);
        if (selectedGoalType != null) service.putExtra(ClickOverlayService.EXTRA_SELECTED_GOAL_TYPE, selectedGoalType);
        service.putExtra(ClickOverlayService.EXTRA_OPEN_MENU, goal == null && selectedGoalType == null);
        startService(service); moveTaskToBack(true);
    }
    private HomeIconView icon(HomeIconView.Kind kind) { return new HomeIconView(this, kind); }
    private TextView text(String value, float size, int color, Typeface typeface) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setTypeface(typeface);
        text.setIncludeFontPadding(false); return text;
    }
    private void add(LinearLayout parent, View child, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(topMargin); parent.addView(child, params);
    }
    private Drawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x143478F6), ClickUi.rounded(color, dp(radius)),
                ClickUi.rounded(Color.WHITE, dp(radius)));
    }
    private void whiteCard(View view, int radius, int elevation) {
        view.setBackground(ripple(Color.WHITE, radius)); shadow(view, elevation);
    }
    private void shadow(View view, int elevation) {
        view.setElevation(dp(elevation));
        if (Build.VERSION.SDK_INT >= 28) {
            view.setOutlineAmbientShadowColor(0x243D5D8F);
            view.setOutlineSpotShadowColor(0x303D5D8F);
        }
    }
    private int dp(int value) { return ClickUi.dp(this, value); }

    private final class HomeBackgroundView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        HomeBackgroundView() { super(HomeActivity.this); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            paint.setShader(new RadialGradient(width + dp(4), dp(215), dp(210),
                    new int[]{0xFFDFF1FF, 0xFFECF7FF, 0xFFFCFDFF}, new float[]{0, .67f, 1}, Shader.TileMode.CLAMP));
        }
        @Override protected void onDraw(Canvas canvas) { canvas.drawRect(0, 0, getWidth(), getHeight(), paint); }
    }
}
