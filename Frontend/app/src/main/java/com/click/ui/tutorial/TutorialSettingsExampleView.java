package com.click.ui.tutorial;

import android.content.Context;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import com.click.designpreview.PermissionScreenshotView;

/** Practice directly on the highlighted photo from accessibility guide page one. */
public final class TutorialSettingsExampleView extends FrameLayout {
    private final PermissionScreenshotView screenshot;
    private final View target;

    public TutorialSettingsExampleView(Context context) {
        super(context);
        setTag("tutorial_settings_example");
        screenshot = PermissionScreenshotView.accessibilityFirstPage(context);
        screenshot.setHighlightFilled(true);
        screenshot.setTag("tutorial_settings_screenshot");
        screenshot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams photo = new LayoutParams(-1, -2, Gravity.TOP);
        photo.setMargins(dp(8), dp(8), dp(8), 0);
        addView(screenshot, photo);
        target = new View(context);
        target.setTag("tutorial_highlight_target");
        target.setContentDescription("설치된 앱. 클릭이 표시한 항목");
        target.setFocusable(true);
        target.setOnClickListener(view -> {
            target.setSelected(true);
            target.setContentDescription("설치된 앱, 선택했어요");
        });
        addView(target, new LayoutParams(0, 0));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        RectF bounds = screenshot.getTargetBounds();
        int x = screenshot.getLeft() + Math.round(bounds.left);
        int y = screenshot.getTop() + Math.round(bounds.top);
        int width = Math.round(bounds.width()), height = Math.round(bounds.height());
        target.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        target.layout(x, y, x + width, y + height);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
