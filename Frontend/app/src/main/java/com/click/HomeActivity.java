package com.click;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
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
import com.click.data.local.RequestLibraryStore;
import com.click.ui.home.HomeHelpItem;
import com.click.domain.goal.QuickHelpTask;
import com.click.service.overlay.ClickOverlayService;
import com.click.ui.common.ClickUi;
import com.click.ui.common.ClickTextScale;
import com.click.ui.home.HomeIconView;

/** Main Click entry screen after accessibility onboarding. */
public final class HomeActivity extends AppCompatActivity {
    public static final String EXTRA_OPEN_LIBRARY = "com.click.extra.OPEN_LIBRARY";
    private static final int INK = 0xFF101A36;
    private static final int SECONDARY = 0xFF8190AA;
    private static final int BLUE = 0xFF3478FF;
    private Typeface regular, medium, semibold;
    private ScrollView homeScroll;
    private ScrollView favoritesScroll;
    private LinearLayout homeFavorites, favoritesPage;
    private HomeFavoritesStore favorites;
    private RequestLibraryStore library;
    private EditText requestInput;
    private View sendRequest;
    private boolean favoritesVisible, recentVisible, requestLaunching, reordering;
    private java.util.List<String> displayedLibraryIds = new java.util.ArrayList<>();
    private OnBackPressedCallback pageBack;
    private LinearLayout homeTab, favoritesTab;
    private float renderedTextScale;
    private com.click.designpreview.AccessibilityConnectionWatcher connectionWatcher;
    private com.click.data.local.GuidancePreferences guidanceChoices;
    private com.click.data.remote.VoiceQueueGateway recentQueue;
    private LinearLayout firstSuggestion, resumeRow;
    private TextView resumeLabel;
    private String resumeRequestId;
    private int homeReadGeneration;
    private final android.os.Handler helpTiming=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable offerFirstHelp=()->{
        if(!isFinishing()&&hasWindowFocus()&&!requestLaunching&&!favoritesVisible
                &&requestInput!=null&&!requestInput.hasFocus()&&requestInput.length()==0
                &&guidanceChoices.maySuggest()&&resumeRequestId==null&&library.entries(false).isEmpty()
                &&firstSuggestion.getVisibility()!=View.VISIBLE) {
            firstSuggestion.setVisibility(View.VISIBLE);guidanceChoices.suggestionShown();
            firstSuggestion.announceForAccessibility("처음이라면 글씨 크기 도움부터 살펴보세요.");
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectionWatcher = new com.click.designpreview.AccessibilityConnectionWatcher(this);
        renderedTextScale = ClickTextScale.scale(this);
        favorites = new HomeFavoritesStore(this);
        library = new RequestLibraryStore(this);
        guidanceChoices=new com.click.data.local.GuidancePreferences(this);
        recentQueue=new com.click.data.remote.VoiceQueueGateway(this);
        if(savedInstanceState==null)guidanceChoices.visitHome();
        regular = getResources().getFont(com.click.designpreview.R.font.pretendard_regular);
        medium = getResources().getFont(com.click.designpreview.R.font.pretendard_medium);
        semibold = getResources().getFont(com.click.designpreview.R.font.pretendard_semibold);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        FrameLayout root = new FrameLayout(this);
        root.setBackground(new com.click.designpreview.ClickBackgroundDrawable(getResources()));
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
        addAssistanceEntry(page);
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
                if (reordering) { reordering = false; renderFavoritesPage(); }
                else if (recentVisible) { recentVisible = false; renderFavoritesPage(); }
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
            favoritesPage.setPadding(dp(12) + bars.left, dp(24), dp(12) + bars.right, dp(92) + bars.bottom);
            navigation.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            navigation.setPadding(bars.left + dp(10), dp(6), bars.right + dp(10), bars.bottom + dp(4));
            if (keyboard && requestInput.hasFocus()) requestInput.post(() -> requestInput.requestRectangleOnScreen(
                    new Rect(0, 0, requestInput.getWidth(), requestInput.getHeight()), false));
            return insets;
        });
        setContentView(root);
        homeScroll.requestFocus();
        root.requestApplyInsets();
        if (savedInstanceState != null) {
            requestInput.setText(savedInstanceState.getString("request_draft", ""));
            recentVisible = savedInstanceState.getBoolean("recent_visible");
            reordering = savedInstanceState.getBoolean("library_reordering");
            showFavorites(savedInstanceState.getBoolean("favorites_visible"));
        }
        openLibraryFromIntent(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openLibraryFromIntent(intent);
    }

    private void openLibraryFromIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_LIBRARY, false)) return;
        intent.removeExtra(EXTRA_OPEN_LIBRARY);
        recentVisible = false;
        reordering = false;
        showFavorites(true);
        favoritesScroll.scrollTo(0, 0);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("request_draft", requestInput.getText().toString());
        state.putBoolean("favorites_visible", favoritesVisible);
        state.putBoolean("recent_visible", recentVisible);
        state.putBoolean("library_reordering", reordering);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume(); requestLaunching = false;
        com.click.data.local.AccessibilityOnboardingState onboarding =
                new com.click.data.local.AccessibilityOnboardingState(this);
        if (onboarding.isPending() && onboarding.returnsToHome()
                && com.click.service.accessibility.ClickAccessibilityService.accessibilityWindowManager() != null) {
            onboarding.markHomeReturned();
        }
        if (Float.compare(renderedTextScale, ClickTextScale.scale(this)) != 0) {
            recreate();
            return;
        }
        refreshFavorites();
        scheduleFirstHelp();readRecentTask();
    }

    @Override protected void onPause() {
        homeReadGeneration++;helpTiming.removeCallbacks(offerFirstHelp);
        connectionWatcher.stop();
        super.onPause();
    }
    @Override public void onUserInteraction(){super.onUserInteraction();scheduleFirstHelp();}
    private void scheduleFirstHelp(){helpTiming.removeCallbacks(offerFirstHelp);if(guidanceChoices!=null&&guidanceChoices.maySuggest())helpTiming.postDelayed(offerFirstHelp,12000);}
    @Override protected void onDestroy(){helpTiming.removeCallbacksAndMessages(null);if(recentQueue!=null)recentQueue.close();super.onDestroy();}
    private void readRecentTask(){
        final int owner=++homeReadGeneration;
        recentQueue.read(new com.click.data.remote.VoiceQueueGateway.Callback(){
            public void onSuccess(com.click.data.remote.VoiceQueueGateway.Response response){
                if(owner!=homeReadGeneration||isFinishing())return;
                var current=response.running;
                if(current!=null&&(current.agentSessionId==null||current.rawGoal==null||current.rawGoal.isBlank()))current=null;
                resumeRequestId=current==null?null:current.id;
                resumeRow.setVisibility(resumeRequestId==null?View.GONE:View.VISIBLE);
                if(current!=null){String request=current.rawGoal==null?"진행하던 안내":current.rawGoal;resumeLabel.setText("진행하던 작업\n"+request.substring(0,Math.min(80,request.length())));firstSuggestion.setVisibility(View.GONE);}
            }
            public void onFailure(Throwable error){/* Optional history never interrupts home input. */}
        });
    }
    private void addAssistanceEntry(LinearLayout page){
        TextView scope=text("어떤 도움을 받을 수 있나요?",13,SECONDARY,medium);scope.setMinHeight(dp(44));scope.setGravity(Gravity.CENTER_VERTICAL);
        scope.setOnClickListener(view->com.click.ui.common.GuidanceDialogs.capabilities(this));add(page,scope,0);
        resumeRow=new LinearLayout(this);resumeRow.setGravity(Gravity.CENTER_VERTICAL);resumeRow.setPadding(dp(14),dp(8),dp(10),dp(8));whiteCard(resumeRow,16,0);resumeRow.setVisibility(View.GONE);resumeRow.setTag("resume_request");
        resumeLabel=text("",14,INK,medium);resumeRow.addView(resumeLabel,new LinearLayout.LayoutParams(0,-2,1));
        android.widget.Button resume=com.click.ui.common.ClickUi.secondaryButton(this,"이어서 하기");resume.setOnClickListener(view->{if(resumeRequestId!=null)startOverlayWithPermission(null,null,false,resumeRequestId);});resumeRow.addView(resume);add(page,resumeRow,4);
        firstSuggestion=new LinearLayout(this);firstSuggestion.setGravity(Gravity.CENTER_VERTICAL);firstSuggestion.setTag("first_help_suggestion");firstSuggestion.setVisibility(View.GONE);
        TextView suggestion=text("처음이라면 글씨 크기 도움부터 살펴볼까요?",14,INK,medium);suggestion.setPadding(0,dp(8),dp(8),dp(8));suggestion.setMinHeight(dp(48));
        suggestion.setOnClickListener(view->{firstSuggestion.setVisibility(View.GONE);startQuickHelp(com.click.domain.goal.QuickHelpTask.FONT_SIZE);});firstSuggestion.addView(suggestion,new LinearLayout.LayoutParams(0,-2,1));
        android.widget.Button dismiss=com.click.ui.common.ClickUi.secondaryButton(this,"그만 보기");dismiss.setContentDescription("처음 도움 추천 그만 보기");dismiss.setOnClickListener(view->{guidanceChoices.dismissSuggestions();firstSuggestion.setVisibility(View.GONE);});firstSuggestion.addView(dismiss);add(page,firstSuggestion,4);
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
        settings.setBackground(null);
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
        View startArtwork = new com.click.ui.home.ClueHomeArtworkView(this);
        FrameLayout.LayoutParams artworkParams = new FrameLayout.LayoutParams(Math.round(contentWidth * .58f),
                dp(200), Gravity.RIGHT | Gravity.BOTTOM);
        artworkParams.rightMargin = dp(8);
        hero.addView(startArtwork, artworkParams);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(text(getString(R.string.home_greeting), 14, SECONDARY, medium));
        TextView headline = text(getString(R.string.home_start_title), 24, INK, semibold);
        SpannableString title = new SpannableString(getString(R.string.home_start_title));
        int start = title.toString().indexOf("클릭");
        if (start >= 0) title.setSpan(new ForegroundColorSpan(BLUE), start, start + 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        headline.setText(title);
        headline.setLetterSpacing(-.025f);
        headline.setLineSpacing(dp(2), 1f);
        if (Build.VERSION.SDK_INT >= 28) headline.setAccessibilityHeading(true);
        add(copy, headline, 8);
        hero.addView(copy, new FrameLayout.LayoutParams(Math.round(contentWidth * .58f), -2, Gravity.LEFT | Gravity.CENTER_VERTICAL));
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
        requestInput.setTextSize(ClickTextScale.size(this, 15)); requestInput.setTypeface(regular);
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
        more.setContentDescription("보관함 더보기");
        more.setClickable(true); more.setFocusable(true);
        more.setBackground(ripple(Color.TRANSPARENT, 14));
        more.setOnClickListener(view -> showFavorites(true));
        header.addView(more, new LinearLayout.LayoutParams(-2, -2));
        return header;
    }

    private java.util.LinkedHashMap<String, View> libraryRows(boolean recent, boolean rearranging) {
        java.util.LinkedHashMap<String, View> rows = new java.util.LinkedHashMap<>();
        if (!recent) for (HomeHelpItem item : HomeHelpItem.values()) if (favorites.contains(item.id)) {
            String id = "help:" + item.id;
            LinearLayout row = (LinearLayout) helpRow(item, false);
            if (rearranging) prepareReorderRow(row, id, item.title);
            row.setTag("library_" + id);
            rows.put(id, row);
        }
        for (RequestLibraryStore.Entry entry : library.entries(!recent)) {
            String id = "request:" + entry.id;
            LinearLayout row = (LinearLayout) requestRow(entry, recent);
            if (rearranging) prepareReorderRow(row, id, entry.request);
            row.setTag("library_" + id);
            if (recent && !rearranging) {
                com.click.ui.home.SwipeDeleteRow swipe = new com.click.ui.home.SwipeDeleteRow(this, row, entry.request, () -> {
                    library.removeRecent(entry.id);
                    refreshFavorites();
                    favoritesScroll.announceForAccessibility("최근 질문에서 지웠어요.");
                });
                row.setTag(null);
                swipe.setTag("library_" + id);
                rows.put(id, swipe);
            } else rows.put(id, row);
        }
        return rows;
    }

    private void prepareReorderRow(LinearLayout row, String id, String label) {
        View launch = row.getChildAt(0);
        launch.setOnClickListener(null); launch.setClickable(false); launch.setFocusable(false);
        row.removeViewAt(row.getChildCount() - 1);
        TextView move = text("≡", 24, BLUE, semibold);
        move.setGravity(Gravity.CENTER);
        move.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(move, new LinearLayout.LayoutParams(dp(48), dp(48)));
        launch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        row.setOnClickListener(null);
        row.setFocusable(true); row.setContentDescription(label + ", 길게 눌러 위아래로 이동");
        ViewCompat.replaceAccessibilityAction(row,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_BACKWARD,
                "위로 옮기기", (view, arguments) -> { moveLibraryItem(id, displayedLibraryIds.indexOf(id) - 1); return true; });
        ViewCompat.replaceAccessibilityAction(row,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_SCROLL_FORWARD,
                "아래로 옮기기", (view, arguments) -> { moveLibraryItem(id, displayedLibraryIds.indexOf(id) + 1); return true; });
    }

    private void moveLibraryItem(String id, int position) {
        int scrollY = favoritesScroll.getScrollY();
        library.moveToPosition(id, position, displayedLibraryIds, recentVisible);
        refreshFavorites();
        favoritesScroll.post(() -> favoritesScroll.scrollTo(0, scrollY));
        favoritesScroll.announceForAccessibility("순서를 바꿨어요.");
    }

    private void refreshFavorites() {
        homeFavorites.removeAllViews();
        java.util.LinkedHashMap<String, View> rows = libraryRows(false, false);
        int count = 0;
        for (String id : library.orderedIds(new java.util.ArrayList<>(rows.keySet()), false))
            add(homeFavorites, rows.get(id), count++ == 0 ? 0 : 6);
        if (count == 0) add(homeFavorites, emptyFavorites(), 0);
        renderFavoritesPage();
    }

    private void renderFavoritesPage() {
        favoritesPage.removeAllViews();
        TextView title = text("보관함", 25, INK, semibold);
        LinearLayout identity = new LinearLayout(this);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.addView(title, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout heading = new LinearLayout(this) {
            @Override protected void onMeasure(int width, int height) {
                int natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                identity.measure(natural, natural); actions.measure(natural, natural);
                boolean stacked = identity.getMeasuredWidth() + actions.getMeasuredWidth() + dp(8)
                        > MeasureSpec.getSize(width);
                int orientation = stacked ? VERTICAL : HORIZONTAL;
                if (getOrientation() != orientation) {
                    setOrientation(orientation);
                    identity.setLayoutParams(new LinearLayout.LayoutParams(stacked ? -1 : 0, -2, stacked ? 0 : 1));
                    actions.setLayoutParams(new LinearLayout.LayoutParams(stacked ? -1 : -2, -2));
                }
                super.onMeasure(width, height);
            }
        };
        heading.setGravity(Gravity.CENTER_VERTICAL);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        heading.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        TextView reorder = text(reordering ? "완료" : "순서 바꾸기", 14, Color.WHITE, semibold);
        if (!reordering) {
            android.graphics.drawable.Drawable arrows = getDrawable(R.drawable.ic_library_reorder);
            arrows.setBounds(0, 0, dp(17), dp(17));
            reorder.setCompoundDrawablesRelative(arrows, null, null, null);
            reorder.setCompoundDrawablePadding(dp(6));
        }
        reorder.setTag("library_reorder"); reorder.setGravity(Gravity.CENTER);
        reorder.setMinimumHeight(dp(44)); reorder.setPadding(dp(14), dp(6), dp(14), dp(6));
        reorder.setBackground(ripple(reordering ? 0xFF245BC4 : BLUE, 16));
        reorder.setFocusable(true); reorder.setSelected(reordering);
        reorder.setOnClickListener(view -> { reordering = !reordering; renderFavoritesPage(); });
        actions.addView(reorder, new LinearLayout.LayoutParams(-2, -2));
        android.widget.ImageButton help = new android.widget.ImageButton(this);
        help.setTag("library_help"); help.setContentDescription("보관함 사용 방법");
        help.setImageResource(R.drawable.ic_library_info);
        help.setBackground(ripple(Color.TRANSPARENT, 16));
        help.setPadding(dp(12), dp(12), dp(12), dp(12));
        help.setOnClickListener(view -> showLibraryHelp());
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        helpParams.leftMargin = dp(6);
        identity.addView(help, helpParams);
        heading.addView(actions, new LinearLayout.LayoutParams(-2, -2));
        add(favoritesPage, heading, 0);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(36), 0, dp(8), 0);
        String[] names = {"즐겨찾기", "최근 질문"};
        for (int i = 0; i < names.length; i++) {
            final boolean recent = i == 1;
            boolean selected = recentVisible == recent;
            TextView tab = text(names[i], 13, selected ? BLUE : SECONDARY, selected ? semibold : medium);
            tab.setGravity(Gravity.CENTER); tab.setMinimumHeight(dp(48));
            tab.setSelected(selected); tab.setFocusable(true);
            tab.setContentDescription(names[i] + (selected ? ", 선택됨" : ""));
            android.graphics.drawable.GradientDrawable index = new android.graphics.drawable.GradientDrawable();
            index.setColor(selected ? 0xFFFFFEFC : 0xFFDCE8F8);
            float radius = dp(14);
            index.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
            index.setStroke(dp(1), 0xFFDCE6F3);
            tab.setBackground(new android.graphics.drawable.InsetDrawable(
                    new RippleDrawable(ColorStateList.valueOf(0x183478FF), index, null), 0, dp(8), 0, 0));
            tab.setPadding(dp(14), dp(12), dp(14), dp(4));
            tab.setOnClickListener(view -> {
                recentVisible = recent; reordering = false;
                renderFavoritesPage(); favoritesScroll.scrollTo(0, 0);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) params.leftMargin = dp(4);
            tabs.addView(tab, params);
        }
        add(favoritesPage, tabs, 8);
        LinearLayout notebook = new LinearLayout(this);
        notebook.setTag("library_notebook"); notebook.setOrientation(LinearLayout.VERTICAL);
        notebook.setMinimumHeight(dp(260)); notebook.setPadding(dp(32), dp(12), dp(4), dp(16));
        notebook.setBackground(new com.click.ui.home.LibraryNotebookBackground(getResources().getDisplayMetrics().density));
        java.util.LinkedHashMap<String, View> rows = libraryRows(recentVisible, reordering);
        displayedLibraryIds = library.orderedIds(new java.util.ArrayList<>(rows.keySet()), recentVisible);
        com.click.ui.home.LibraryDragController drag = reordering
                ? new com.click.ui.home.LibraryDragController(notebook, favoritesScroll, this::moveLibraryItem) : null;
        int count = 0;
        for (String id : displayedLibraryIds) {
            View row = rows.get(id);
            if (drag != null) drag.prepare(row, id);
            row.setElevation(0);
            row.setBackground(ripple(Color.TRANSPARENT, 12));
            if (count++ > 0) {
                View line = new View(this); line.setBackgroundColor(0xFFE7EDF6);
                notebook.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
            }
            add(notebook, row, 4);
        }
        if (rows.isEmpty()) {
            TextView empty = text(recentVisible ? "아직 질문한 내용이 없어요.\n클릭에게 물어보면 여기에 모아드려요."
                    : "아직 보관한 즐겨찾기가 없어요.\n최근 질문의 별을 눌러 보관해 보세요.", 15, SECONDARY, regular);
            empty.setGravity(Gravity.CENTER); empty.setPadding(dp(8), dp(40), dp(8), dp(32));
            add(notebook, empty, 0);
        }
        reorder.setEnabled(!rows.isEmpty()); reorder.setAlpha(rows.isEmpty() ? .45f : 1f);
        favoritesPage.addView(notebook, new LinearLayout.LayoutParams(-1, -2, 1));
    }

    private void showLibraryHelp() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        String[] sections = {
                "즐겨찾기에 보관하기", "최근 질문에서 별을 누르면 즐겨찾기에 담겨요. 별을 다시 누르면 보관을 해제해요.",
                "순서 바꾸기", "‘순서 바꾸기’를 누르고 항목을 꾹 누른 채 위아래로 옮겨주세요. 다 옮겼으면 ‘완료’를 눌러주세요.",
                "최근 질문 지우기", "최근 질문의 항목을 왼쪽으로 밀면 ‘삭제’ 버튼이 보여요. 이 버튼을 누르면 기록이 지워져요. 즐겨찾기에 담아 둔 항목은 그대로 남아요."
        };
        for (int index = 0; index < sections.length; index += 2) {
            TextView label = text(sections[index], 16, INK, semibold);
            if (Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
            add(content, label, index == 0 ? 0 : 22);
            add(content, text(sections[index + 1], 15, SECONDARY, regular), 8);
        }
        com.click.ui.common.ClickDialog.show(this, "보관함 사용 방법", content, "알겠어요", null, null);
    }

    private View emptyFavorites() {
        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL); empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(20), dp(20), dp(20));
        whiteCard(empty, 22, 2);
        add(empty, text("아직 보관한 즐겨찾기가 없어요", 17, INK, medium), 0);
        add(empty, text("최근 질문의 별을 눌러 보관해 보세요.", 14, SECONDARY, regular), 10);
        return empty;
    }

    private View requestRow(RequestLibraryStore.Entry entry, boolean recent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(16), dp(6), dp(8), dp(6));
        row.setMinimumHeight(dp(60));
        whiteCard(row, 18, 2);
        LinearLayout copy = new LinearLayout(this); copy.setOrientation(LinearLayout.VERTICAL);
        copy.setMinimumHeight(dp(48)); copy.setGravity(Gravity.CENTER_VERTICAL);
        copy.setFocusable(true); copy.setClickable(true);
        copy.setContentDescription(entry.request + ", 다시 요청하기");
        copy.addView(text(entry.request, 16, INK, medium));
        if (recent && !entry.favorite) {
            String date = android.text.format.DateFormat.format("M월 d일 · HH:mm", entry.timestamp).toString();
            add(copy, text(date, 12, SECONDARY, regular), 3);
        }
        copy.setOnClickListener(view -> startOverlayWithPermission(entry.request,
                entry.goalType.isEmpty() ? null : entry.goalType, false));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        HomeIconView star = icon(HomeIconView.Kind.STAR);
        star.setTag("saved_" + entry.id);
        star.setFavoriteSelected(entry.favorite); star.setSelected(entry.favorite);
        star.setContentDescription(entry.request + (entry.favorite ? " 즐겨찾기 해제" : " 즐겨찾기 추가"));
        star.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        star.setClickable(true); star.setFocusable(true); star.setBackground(ripple(Color.TRANSPARENT, 24));
        star.setOnClickListener(view -> {
            library.setFavorite(entry.id, !entry.favorite); refreshFavorites();
            favoritesScroll.announceForAccessibility(entry.favorite ? "즐겨찾기에서 뺐어요." : "즐겨찾기에 보관했어요.");
        });
        row.addView(star, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return row;
    }

    private View helpRow(HomeHelpItem item, boolean selecting) {
        LinearLayout row = new LinearLayout(this);
        row.setTag("help_" + item.id);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(6), dp(8), dp(6));
        row.setMinimumHeight(dp(60));
        whiteCard(row, 18, 2);
        LinearLayout launch = new LinearLayout(this);
        launch.setGravity(Gravity.CENTER_VERTICAL);
        launch.setMinimumHeight(dp(48)); launch.setClickable(true); launch.setFocusable(true);
        launch.setContentDescription(item.title + ". " + item.subtitle);
        if (item == HomeHelpItem.TUTORIAL && !selecting) launch.setId(R.id.home_tutorial);
        launch.addView(icon(item.icon), new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(item.title, 16, INK, medium));
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
        if (!visible) reordering = false;
        homeScroll.setVisibility(visible ? View.GONE : View.VISIBLE);
        favoritesScroll.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) homeScroll.requestFocus();
        pageBack.setEnabled(visible);
        if (visible) renderFavoritesPage();
        else recentVisible = false;
        selectNavigation(visible);
    }

    private LinearLayout bottomNavigation() {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp(10), dp(6), dp(10), dp(4));
        navigation.setBackground(ClickUi.rounded(Color.WHITE, dp(24)));
        shadow(navigation, 8);
        homeTab = navigationItem(HomeIconView.Kind.HOME, "홈", R.id.home_nav_home);
        homeTab.setOnClickListener(view -> { showFavorites(false); homeScroll.smoothScrollTo(0, 0); });
        favoritesTab = navigationItem(HomeIconView.Kind.BOX, "보관함", R.id.home_nav_favorites);
        favoritesTab.setOnClickListener(view -> { recentVisible = false; showFavorites(true); });
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
        startActivity(new Intent(this, com.click.designpreview.PermissionGuideActivity.class)
                .putExtra(com.click.designpreview.PermissionGuideActivity.EXTRA_RECONNECT,
                        PermissionStatus.isAccessibilityEnabled(this)));
    }
    private void hideKeyboard() {
        if (requestInput == null) return;
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(requestInput.getWindowToken(), 0);
        requestInput.clearFocus();
        if (homeScroll.isShown()) homeScroll.requestFocus();
    }
    private boolean startOverlayWithPermission(String goal, String selectedGoalType, boolean voice) {
        return startOverlayWithPermission(goal,selectedGoalType,voice,null);
    }
    private boolean startOverlayWithPermission(String goal, String selectedGoalType, boolean voice,String resumeId) {
        if (requestLaunching) return false;
        if (!PermissionStatus.isAccessibilityEnabled(this)) { openPermissionGuide(); return false; }
        if (com.click.service.accessibility.ClickAccessibilityService.accessibilityWindowManager() == null) {
            requestLaunching = true;
            Toast.makeText(this, "클릭을 준비하고 있어요.", Toast.LENGTH_SHORT).show();
            connectionWatcher.start(() -> {
                if (goal != null && goal.equals(requestInput.getText().toString().trim())) requestInput.setText("");
                launchOverlay(goal, selectedGoalType, voice,resumeId);
            }, () -> { requestLaunching = false; openPermissionGuide(); });
            return false;
        }
        launchOverlay(goal, selectedGoalType, voice,resumeId);
        return true;
    }
    private void launchOverlay(String goal, String selectedGoalType, boolean voice,String resumeId) {
        Intent service = new Intent(this, ClickOverlayService.class);
        if (goal != null) service.putExtra(ClickOverlayService.EXTRA_INITIAL_GOAL, goal);
        if (selectedGoalType != null) service.putExtra(ClickOverlayService.EXTRA_SELECTED_GOAL_TYPE, selectedGoalType);
        service.putExtra(ClickOverlayService.EXTRA_OPEN_VOICE, voice);
        if(resumeId!=null)service.putExtra(ClickOverlayService.EXTRA_RESUME_REQUEST_ID,resumeId);
        requestLaunching = true;
        hideKeyboard();
        startService(service); moveTaskToBack(true);
    }
    private HomeIconView icon(HomeIconView.Kind kind) { return new HomeIconView(this, kind); }
    private TextView text(String value, float size, int color, Typeface typeface) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextSize(ClickTextScale.size(this, size)); text.setTextColor(color); text.setTypeface(typeface);
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

}
