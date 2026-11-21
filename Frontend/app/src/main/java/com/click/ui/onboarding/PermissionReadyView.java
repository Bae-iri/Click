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

/** The illustrated welcome shown once Click is ready. The continue button stays below the scroll area. */
public final class PermissionReadyView extends FrameLayout {
    private final TextView title, description;
    private final Button start;

    public PermissionReadyView(Context context) {
        super(context);
        setBackground(new ClickBackgroundDrawable(getResources()));
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(Math.min(getResources().getDisplayMetrics().widthPixels, dp(480)),
                LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL));

        LinearLayout brand = new LinearLayout(context);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView cursor = new ImageView(context);
        cursor.setImageResource(com.click.designpreview.R.drawable.icon_floating_cursor);
        cursor.setContentDescription("클릭");
        brand.addView(cursor, new LinearLayout.LayoutParams(dp(44), dp(44)));
        column.addView(brand, new LinearLayout.LayoutParams(-1, dp(60)));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(0, dp(16), 0, dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        ClueArtworkView clue = new ClueArtworkView(context);
        clue.setId(R.id.permission_ready_clue);
        clue.setImageResource(com.click.designpreview.R.drawable.clue4_complete);
        content.addView(clue, new LinearLayout.LayoutParams(dp(210), dp(210)));
        title = ClickUi.title(context, "");
        title.setId(R.id.tutorial_ready_title);
        title.setTextSize(ClickTextScale.size(context, 24));
        title.setGravity(Gravity.CENTER);
        title.setLineSpacing(dp(4), 1.1f);
        title.setTextLocale(Locale.KOREAN);
        title.setBreakStrategy(LineBreaker.BREAK_STRATEGY_BALANCED);
        if (Build.VERSION.SDK_INT >= 33) title.setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        content.addView(title, ClickUi.wrap(dp(28)));
        description = ClickUi.body(context, "");
        description.setTextSize(ClickTextScale.size(context, 16));
        description.setGravity(Gravity.CENTER);
        content.addView(description, ClickUi.wrap(dp(18)));

        start = ClickUi.primaryButton(context, "");
        start.setId(R.id.tutorial_ready_start);
        start.setMinHeight(dp(64));
        start.setTextSize(ClickTextScale.size(context, 20));
        start.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(-1, -2);
        startParams.setMargins(0, dp(12), 0, dp(20));
        column.addView(start, startParams);

        ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
            Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            column.setPadding(dp(24) + system.left, system.top, dp(24) + system.right, system.bottom);
            return insets;
        });
    }

    public void bind(String heading, String body, String label, OnClickListener onStart) {
        title.setText(heading);
        description.setText(body);
        start.setText(label);
        start.setOnClickListener(onStart);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewCompat.requestApplyInsets(this);
    }

    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
