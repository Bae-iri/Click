package com.click.ui.onboarding;

import android.content.Context;
import android.graphics.text.LineBreakConfig;
import android.graphics.text.LineBreaker;
import android.os.Build;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.click.R;
import com.click.designpreview.ClickBackgroundDrawable;
import com.click.designpreview.ClueArtworkView;
import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUi;
import java.util.Locale;

/** The three permission steps and their completion share one illustrated layout. */
public final class RequiredPermissionView extends FrameLayout {
    private final TextView progress, title, description;
    private final ClueArtworkView clue;
    private final Button start;

    public RequiredPermissionView(Context context, OnClickListener onContinue) {
        super(context);
        setBackground(new ClickBackgroundDrawable(getResources()));
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(24), 0, dp(24), 0);
        addView(column, new LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels, dp(480)),
                LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));

        LinearLayout brand = new LinearLayout(context);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cursor = new ImageView(context);
        cursor.setImageResource(com.click.designpreview.R.drawable.icon_floating_cursor);
        cursor.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(cursor, new LinearLayout.LayoutParams(dp(44), dp(44)));
        android.widget.Space spacer = new android.widget.Space(context);
        brand.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        progress = ClickUi.body(context, "");
        progress.setTextSize(13);
        progress.setTextColor(0xFF285AA8);
        progress.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_semibold));
        progress.setBackground(ClickUi.rounded(0xFFEAF2FF, dp(16)));
        progress.setPadding(dp(12), dp(6), dp(12), dp(6));
        brand.addView(progress);
        brand.setMinimumHeight(dp(60));
        column.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(0, dp(16), 0, dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        clue = new ClueArtworkView(context);
        content.addView(clue, new LinearLayout.LayoutParams(dp(160), dp(160)));
        title = ClickUi.title(context, "");
        title.setId(R.id.tutorial_ready_title);
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setLineSpacing(dp(4), 1.1f);
        title.setTextLocale(Locale.KOREAN);
        title.setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED);
        if (Build.VERSION.SDK_INT >= 33) title.setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        content.addView(title, ClickUi.wrap(dp(18)));
        description = ClickUi.body(context, "");
        description.setTextSize(15);
        description.setTextColor(0xFF526681);
        description.setLineSpacing(dp(2), 1.13f);
        description.setTextLocale(Locale.KOREAN);
        description.setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED);
        if (Build.VERSION.SDK_INT >= 33) description.setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        description.setGravity(Gravity.CENTER);
        content.addView(description, ClickUi.wrap(dp(12)));

        start = ClickUi.primaryButton(context, "");
        start.setId(R.id.tutorial_ready_start);
        start.setMinHeight(dp(56));
        start.setTextSize(17);
        start.setStateListAnimator(null);
        start.setElevation(0);
        start.setPadding(dp(16), dp(14), dp(16), dp(14));
        start.setOnClickListener(onContinue);
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(-1, -2);
        startParams.setMargins(0, dp(12), 0, dp(20));
        column.addView(start, startParams);

        ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
            Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            column.setPadding(dp(24) + system.left, system.top, dp(24) + system.right, system.bottom);
            return insets;
        });
    }

    public void bind(String step, String heading, String body, String label) {
        boolean complete = step == null;
        progress.setVisibility(complete ? GONE : VISIBLE);
        progress.setText(step);
        clue.setId(complete ? R.id.permission_ready_clue : R.id.permission_setup_clue);
        clue.setImageResource(complete ? com.click.designpreview.R.drawable.clue4_complete
                : com.click.designpreview.R.drawable.clue3_tutorial);
        title.setText(heading);
        description.setText(body);
        start.setText(label);
        ClickTextScale.applyTree(this);
    }

    public void showSettingsError() {
        description.setText("설정 화면을 열지 못했어요. 휴대폰 설정에서 ‘클릭’의 앱 권한을 확인해 주세요.");
        description.announceForAccessibility(description.getText());
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewCompat.requestApplyInsets(this);
    }

    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
