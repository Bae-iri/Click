package com.click;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.click.designpreview.ClueArtworkView;
import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUi;

/** A friendly introduction to Click's mascot, separate from account and display settings. */
public final class AboutClueActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new com.click.designpreview.ClickBackgroundDrawable(getResources()));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(14), dp(22), dp(32));
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels, dp(520)), -1, Gravity.CENTER_HORIZONTAL));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            page.setPadding(dp(22) + insets.getSystemWindowInsetLeft(), dp(14) + insets.getSystemWindowInsetTop(),
                    dp(22) + insets.getSystemWindowInsetRight(), dp(32) + insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setContentDescription("설정으로 돌아가기");
        back.setImageResource(com.click.designpreview.R.drawable.onboarding_back);
        back.setBackground(ClickUi.rounded(Color.WHITE, dp(24)));
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setOnClickListener(view -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = label("클루에 대해서", 24, true);
        heading.setPadding(dp(12), 0, 0, 0);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ClueArtworkView clue = new ClueArtworkView(this);
        clue.setImageResource(com.click.designpreview.R.drawable.clue2_signup);
        LinearLayout.LayoutParams artwork = new LinearLayout.LayoutParams(-1, dp(250));
        artwork.topMargin = dp(28); artwork.bottomMargin = dp(20);
        page.addView(clue, artwork);

        LinearLayout introduction = new LinearLayout(this);
        introduction.setOrientation(LinearLayout.VERTICAL);
        introduction.setPadding(dp(24), dp(24), dp(24), dp(26));
        introduction.setBackground(ClickUi.rounded(Color.WHITE, dp(24)));
        TextView name = label("안녕하세요, 클루예요!", 23, true);
        name.setTextColor(ClickUi.BLUE);
        introduction.addView(name);
        TextView age = label("3세 · 호기심 많은 펭귄", 15, false);
        age.setTextColor(ClickUi.SUBTEXT); age.setPadding(0, dp(10), 0, dp(20));
        introduction.addView(age);
        TextView story = label("어려운 휴대폰 사용도 혼자 해낼 수 있도록 곁에서 도와주는 클릭의 친구예요.\n\n"
                + "좋아하는 건 새로운 것을 배우기, 그리고 함께 해냈을 때 활짝 웃기!\n\n"
                + "아직 배우는 중이지만, 오늘도 한 걸음씩 함께할게요.", 16, false);
        story.setLineSpacing(dp(3), 1.15f);
        introduction.addView(story);
        page.addView(introduction, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        root.requestApplyInsets();
    }

    private TextView label(String value, int size, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value); text.setTextColor(ClickUi.TEXT);
        text.setTextSize(ClickTextScale.size(this, size));
        text.setIncludeFontPadding(false);
        text.setTypeface(getResources().getFont(bold ? com.click.designpreview.R.font.pretendard_semibold
                : com.click.designpreview.R.font.pretendard_regular));
        return text;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
