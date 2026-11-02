package com.click;

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
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.click.core.permission.PermissionStatus;
import com.click.data.local.HomeFavoritesStore;
import com.click.ui.home.HomeHelpItem;
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
    private Typeface regular, medium, semibold, bold;
    private HomeStartArtworkView startArtwork;
    private ScrollView homeScroll;
    private ScrollView favoritesScroll;
    private LinearLayout homeFavorites, favoritesPage;
    private HomeFavoritesStore favorites;
    private EditText requestInput;
    private View sendRequest;
    private boolean favoritesVisible, addingFavorites, requestLaunching;
    private OnBackPressedCallback pageBack;
    private LinearLayout homeTab, favoritesTab;
    private boolean homeResumed;
    private final Rect visibleArtwork = new Rect();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        favorites = new HomeFavoritesStore(this);
        regular = getResources().getFont(com.click.designpreview.R.font.pretendard_regular);
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
        homeScroll.setFocusableInTouchMode(true);
        homeScroll.setDefaultFocusHighlightEnabled(false);
        homeScroll.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
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
        add(page, favoritesHeader(), 20);
        homeFavorites = new LinearLayout(this);
        homeFavorites.setOrientation(LinearLayout.VERTICAL);
        add(page, homeFavorites, 8);
        favoritesScroll = new ScrollView(this);
        favoritesScroll.setId(R.id.home_favorites_scroll);
        favoritesScroll.setFillViewport(true);
        favoritesScroll.setVerticalScrollBarEnabled(false);
        favoritesPage = new LinearLayout(this);
        favoritesPage.setOrientation(LinearLayout.VERTICAL);
        favoritesPage.setClipChildren(false);
        favoritesScroll.addView(favoritesPage, new ScrollView.LayoutParams(-1, -2));
        root.addView(favoritesScroll, new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels, dp(520)), -1, Gravity.CENTER_HORIZONTAL));
        favoritesScroll.setVisibility(View.GONE);
        pageBack = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() {
                if (addingFavorites) { addingFavorites = false; renderFavoritesPage(); }
                else showFavorites(false);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, pageBack);
        refreshFavorites();

        LinearLayout navigation = bottomNavigation();
        root.addView(navigation, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            boolean keyboard = insets.isVisible(WindowInsetsCompat.Type.ime());
            int keyboardBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            homeScroll.setPadding(0, bars.top, 0, keyboard ? keyboardBottom : 0);
            favoritesScroll.setPadding(0, bars.top, 0, 0);
            page.setPadding(horizontal + bars.left, dp(16), horizontal + bars.right,
                    keyboard ? dp(24) : dp(92) + bars.bottom);
            favoritesPage.setPadding(horizontal + bars.left, dp(24), horizontal + bars.right, dp(92) + bars.bottom);
            navigation.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            navigation.setPadding(bars.left + dp(10), dp(6), bars.right + dp(10), bars.bottom + dp(4));
            if (keyboard && requestInput.hasFocus()) requestInput.post(() -> requestInput.requestRectangleOnScreen(
                    new Rect(0, 0, requestInput.getWidth(), requestInput.getHeight()), false));
            return insets;
        });
        homeScroll.setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) -> {
            updateArtworkAnimation();
        });
        setContentView(root);
        homeScroll.requestFocus();
        root.requestApplyInsets();
        if (savedInstanceState != null) {
            requestInput.setText(savedInstanceState.getString("request_draft", ""));
            addingFavorites = savedInstanceState.getBoolean("adding_favorites");
            showFavorites(savedInstanceState.getBoolean("favorites_visible"));
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("request_draft", requestInput.getText().toString());
        state.putBoolean("favorites_visible", favoritesVisible);
        state.putBoolean("adding_favorites", addingFavorites);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume(); homeResumed = true; requestLaunching = false;
        refreshFavorites();
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
        hero.setMinimumHeight(dp(180));
        hero.setClipChildren(false);
        int contentWidth = Math.min(getResources().getDisplayMetrics().widthPixels, dp(520)) - dp(48);
        startArtwork = new HomeStartArtworkView(this);
        hero.addView(startArtwork, new FrameLayout.LayoutParams(Math.round(contentWidth * .54f),
                dp(180), Gravity.RIGHT | Gravity.BOTTOM));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(getString(R.string.home_greeting), 14, SECONDARY, medium));
        TextView headline = text(getString(R.string.home_start_title), 26, INK, bold);
        SpannableString title = new SpannableString(getString(R.string.home_start_title));
        int start = title.toString().indexOf("클릭");
        if (start >= 0) title.setSpan(new ForegroundColorSpan(BLUE), start, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        headline.setText(title);
        headline.setLetterSpacing(-.025f);
        headline.setLineSpacing(dp(2), 1f);
        if (Build.VERSION.SDK_INT >= 28) headline.setAccessibilityHeading(true);
        add(copy, headline, 8);
        hero.addView(copy, new FrameLayout.LayoutParams(Math.round(contentWidth * .63f), -2, Gravity.LEFT | Gravity.CENTER_VERTICAL));
        return hero;
    }

    private View requestCard() {
        LinearLayout card = new LinearLayout(this);
        card.setId(R.id.home_start);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(dp(60));
        card.setPadding(dp(16), dp(6), dp(8), dp(6));
        whiteCard(card, 22, 4);
        requestInput = new EditText(this);
        requestInput.setId(R.id.home_request_input);
        requestInput.setHint(R.string.home_request_hint);
        requestInput.setContentDescription("궁금한 기능 입력");
        requestInput.setTextSize(15); requestInput.setTypeface(regular);
        requestInput.setTextColor(INK); requestInput.setHintTextColor(SECONDARY);
        requestInput.setSingleLine(true); requestInput.setInputType(InputType.TYPE_CLASS_TEXT);
        requestInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        requestInput.setBackgroundColor(Color.TRANSPARENT);
        requestInput.setPadding(0, dp(10), dp(6), dp(10));
        requestInput.setMinimumHeight(dp(48));
        requestInput.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId != EditorInfo.IME_ACTION_SEND && !enter) return false;
            if (!enter || event.getAction() == KeyEvent.ACTION_DOWN) submitHomeRequest();
            return true;
        });
        card.addView(requestInput, new LinearLayout.LayoutParams(0, -2, 1));
        View microphone = iconButton(HomeIconView.Kind.MICROPHONE, "말로 입력", R.id.home_microphone);
        microphone.setOnClickListener(view -> startOverlayWithPermission(null, null, true));
        card.addView(microphone, new LinearLayout.LayoutParams(dp(44), dp(44)));
        sendRequest = iconButton(HomeIconView.Kind.SEND, "요청 보내기", R.id.home_request_send);
        sendRequest.setVisibility(View.GONE);
        sendRequest.setOnClickListener(view -> submitHomeRequest());
        card.addView(sendRequest, new LinearLayout.LayoutParams(dp(44), dp(44)));
        requestInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                sendRequest.setVisibility(text.toString().trim().isEmpty() ? View.GONE : View.VISIBLE);
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        return card;
    }

    private void submitHomeRequest() {
        String request = requestInput.getText().toString().trim();
        if (request.isEmpty()) return;
        if (startOverlayWithPermission(request, null, false)) requestInput.setText("");
    }

    private HomeIconView iconButton(HomeIconView.Kind kind, String label, int id) {
        HomeIconView button = icon(kind);
        button.setId(id); button.setContentDescription(label);
        button.setClickable(true); button.setFocusable(true);
        button.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        button.setBackground(ripple(Color.TRANSPARENT, 22));
        return button;
    }

    private View favoritesHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.home_favorites), 19, INK, semibold);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = text("더보기  ›", 14, BLUE, regular);
        more.setGravity(Gravity.CENTER); more.setMinimumHeight(dp(40));
        more.setPadding(dp(12), 0, 0, 0);
        more.setContentDescription("즐겨찾기 더보기");
        more.setClickable(true); more.setFocusable(true);
        more.setBackground(ripple(Color.TRANSPARENT, 14));
        more.setOnClickListener(view -> showFavorites(true));
        header.addView(more, new LinearLayout.LayoutParams(-2, -2));
        return header;
    }

    private void refreshFavorites() {
        homeFavorites.removeAllViews();
        int count = 0;
        for (HomeHelpItem item : HomeHelpItem.values()) if (favorites.contains(item.id)) {
            add(homeFavorites, helpRow(item, false), count++ == 0 ? 0 : 6);
        }
        if (count == 0) add(homeFavorites, emptyFavorites(), 0);
        renderFavoritesPage();
    }

    private void renderFavoritesPage() {
        favoritesPage.removeAllViews();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(addingFavorites ? "즐겨찾기 추가" : "내 즐겨찾기", 23, INK, semibold);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView action = text(addingFavorites ? "완료" : "+ 추가", 15, BLUE, medium);
        action.setId(addingFavorites ? R.id.home_favorites_done : R.id.home_favorites_add);
        action.setContentDescription(addingFavorites ? "즐겨찾기 선택 완료" : "즐겨찾기 추가");
        action.setGravity(Gravity.CENTER); action.setMinimumHeight(dp(48));
        action.setPadding(dp(12), 0, dp(12), 0);
        action.setBackground(ripple(0xFFEEF4FF, 16));
        action.setOnClickListener(view -> {
            addingFavorites = !addingFavorites; renderFavoritesPage(); favoritesScroll.scrollTo(0, 0);
        });
        heading.addView(action, new LinearLayout.LayoutParams(-2, -2));
        add(favoritesPage, heading, 0);
        int count = 0;
        for (HomeHelpItem item : HomeHelpItem.values()) if (favorites.contains(item.id)) count++;
        TextView subtitle = text(addingFavorites ? "자주 쓰는 기능을 별로 표시해 보세요!"
                : "기능 " + count + "개를 모아뒀어요!\n별을 다시 누르면 목록에서 뺄 수 있어요.", 14, SECONDARY, regular);
        add(favoritesPage, subtitle, 14);
        int row = 0;
        for (HomeHelpItem item : HomeHelpItem.values()) if (addingFavorites || favorites.contains(item.id))
            add(favoritesPage, helpRow(item, addingFavorites), row++ == 0 ? 24 : 6);
        if (row == 0) add(favoritesPage, emptyFavorites(), 24);
    }

    private View emptyFavorites() {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL); empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(30), dp(20), dp(30));
        whiteCard(empty, 22, 2);
        add(empty, text("자주 쓰는 기능을 모아볼까요?", 17, INK, medium), 0);
        add(empty, text("별을 눌러 즐겨찾기에 담아보세요!", 14, SECONDARY, regular), 10);
        TextView button = text("+ 즐겨찾기 추가", 15, BLUE, medium);
        button.setGravity(Gravity.CENTER); button.setMinimumHeight(dp(48));
        button.setBackground(ripple(0xFFEEF4FF, 16));
        button.setOnClickListener(view -> { addingFavorites = true; showFavorites(true); });
        add(empty, button, 20);
        return empty;
    }

    private View helpRow(HomeHelpItem item, boolean selecting) {
        LinearLayout row = new LinearLayout(this);
        row.setTag("help_" + item.id);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(10), dp(9), dp(10));
        row.setMinimumHeight(dp(64));
        whiteCard(row, 22, 2);
        LinearLayout launch = new LinearLayout(this);
        launch.setGravity(Gravity.CENTER_VERTICAL);
        launch.setMinimumHeight(dp(48)); launch.setClickable(true); launch.setFocusable(true);
        launch.setContentDescription(item.title + ". " + item.subtitle);
        if (item == HomeHelpItem.TUTORIAL && !selecting) launch.setId(R.id.home_tutorial);
        launch.addView(icon(item.icon), new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(item.title, 16, INK, semibold));
        add(labels, text(item.subtitle, 13, SECONDARY, regular), 4);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(dp(14)); labelParams.setMarginEnd(dp(6));
        launch.addView(labels, labelParams);
        launch.setOnClickListener(view -> { if (selecting) toggleFavorite(item); else openHelp(item); });
        row.addView(launch, new LinearLayout.LayoutParams(0, -2, 1));
        HomeIconView star = icon(HomeIconView.Kind.STAR);
        star.setTag("favorite_" + item.id);
        star.setFavoriteSelected(favorites.contains(item.id));
        star.setSelected(favorites.contains(item.id));
        star.setContentDescription(item.title + (favorites.contains(item.id) ? " 즐겨찾기 해제" : " 즐겨찾기 추가"));
        star.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        star.setClickable(true); star.setFocusable(true);
        star.setBackground(ripple(Color.TRANSPARENT, 24));
        star.setOnClickListener(view -> toggleFavorite(item));
        row.addView(star, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    private void toggleFavorite(HomeHelpItem item) {
        boolean selected = !favorites.contains(item.id);
        favorites.setSelected(item.id, selected);
        refreshFavorites();
        (favoritesVisible ? favoritesScroll : homeScroll).announceForAccessibility(
                item.title + (selected ? " 기능을 즐겨찾기에 담았어요!" : " 기능을 즐겨찾기에서 뺐어요."));
    }

    private void openHelp(HomeHelpItem item) {
        if (item.task != null) startQuickHelp(item.task);
        else if (item == HomeHelpItem.TUTORIAL) startActivity(new Intent(this, TutorialActivity.class));
        else if (item == HomeHelpItem.ACCESSIBILITY)
            startActivity(new Intent(this, com.click.designpreview.PermissionGuideActivity.class));
        else showComingSoon(item.title + " 도움");
    }

    private void showFavorites(boolean visible) {
        hideKeyboard();
        favoritesVisible = visible;
        homeScroll.setVisibility(visible ? View.GONE : View.VISIBLE);
        favoritesScroll.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) homeScroll.requestFocus();
        pageBack.setEnabled(visible);
        if (visible) renderFavoritesPage();
        else addingFavorites = false;
        selectNavigation(visible);
        updateArtworkAnimation();
    }

    private LinearLayout bottomNavigation() {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp(10), dp(6), dp(10), dp(4));
        navigation.setBackground(ClickUi.rounded(Color.WHITE, dp(24)));
        shadow(navigation, 8);
        homeTab = navigationItem(HomeIconView.Kind.HOME, "홈", R.id.home_nav_home);
        homeTab.setOnClickListener(view -> { showFavorites(false); homeScroll.smoothScrollTo(0, 0); });
        favoritesTab = navigationItem(HomeIconView.Kind.BOOKMARK, "내 즐겨찾기", R.id.home_nav_favorites);
        favoritesTab.setOnClickListener(view -> { addingFavorites = false; showFavorites(true); });
        for (View tab : new View[]{homeTab, favoritesTab})
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
        TextView title = text(label, 10, 0xFF8993A4, medium);
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
        startOverlayWithPermission(task.requestText, task.goalType, false);
    }
    private void showComingSoon(String feature) {
        Toast.makeText(this, feature + "을 준비하고 있어요.\n지금은 글씨 크기·화면 밝기·소리 조절을 도와드릴게요!", Toast.LENGTH_LONG).show();
    }
    private void openPermissionGuide() {
        startActivity(new Intent(this, com.click.designpreview.AccessibilityHelpActivity.class));
    }
    private void hideKeyboard() {
        if (requestInput == null) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(requestInput.getWindowToken(), 0);
        requestInput.clearFocus();
        if (homeScroll.isShown()) homeScroll.requestFocus();
    }
    private boolean startOverlayWithPermission(String goal, String selectedGoalType, boolean voice) {
        if (requestLaunching) return false;
        if (!PermissionStatus.isAccessibilityEnabled(this)) { openPermissionGuide(); return false; }
        if (com.click.service.accessibility.ClickAccessibilityService.accessibilityWindowManager() == null) {
            Toast.makeText(this, "클릭을 다시 연결해 주세요.\n설정에서 클릭을 껐다 켜면 이어서 도와드릴게요.", Toast.LENGTH_LONG).show();
            openPermissionGuide(); return false;
        }
        Intent service = new Intent(this, ClickOverlayService.class);
        if (goal != null) service.putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL, goal);
        if (selectedGoalType != null) service.putExtra(ClickOverlayService.EXTRA_SELECTED_GOAL_TYPE, selectedGoalType);
        service.putExtra(ClickOverlayService.EXTRA_OPEN_VOICE, voice);
        requestLaunching = true;
        hideKeyboard();
        startService(service); moveTaskToBack(true);
        return true;
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
