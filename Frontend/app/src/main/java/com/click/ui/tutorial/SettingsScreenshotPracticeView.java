package com.click.ui.tutorial;

import android.content.Context;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import com.click.R;

/** Untouched Galaxy Note20 Settings captures with a matching, accessible practice hit area. */
public final class SettingsScreenshotPracticeView extends FrameLayout {
    private final ImageView screenshot;
    private final View target;
    private boolean fontSize;

    public SettingsScreenshotPracticeView(Context context, Runnable selected) {
        super(context);
        setId(R.id.tutorial_settings_photo);
        setBackgroundColor(0xFFF7F7F7);
        screenshot = new ImageView(context);
        screenshot.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screenshot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(screenshot, new LayoutParams(-1, -1, Gravity.CENTER));
        target = new View(context);
        target.setId(R.id.tutorial_settings_photo_target);
        target.setFocusable(true);
        target.setOnClickListener(view -> selected.run());
        addView(target, new LayoutParams(0, 0));
        showFontSize(false);
    }

    public View target() { return target; }

    public void showFontSize(boolean enabled) {
        fontSize = enabled;
        screenshot.setImageResource(enabled ? R.drawable.tutorial_settings_font_size : R.drawable.tutorial_settings_display);
        target.setContentDescription(enabled ? "글자 크기 조절 막대, 실제 설정 화면에서 연습" : "글자 크기와 스타일, 실제 설정 화면에서 연습");
        requestLayout();
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        var drawable = screenshot.getDrawable();
        if (drawable == null) return;
        float scale = Math.min(getWidth() / (float) drawable.getIntrinsicWidth(), getHeight() / (float) drawable.getIntrinsicHeight());
        float dx = (getWidth() - drawable.getIntrinsicWidth() * scale) / 2f;
        float dy = (getHeight() - drawable.getIntrinsicHeight() * scale) / 2f;
        RectF source = fontSize ? new RectF(135, 1980, 950, 2130) : new RectF(50, 2000, 1030, 2160);
        int x = Math.round(dx + source.left * scale), y = Math.round(dy + source.top * scale);
        int width = Math.round(source.width() * scale), height = Math.round(source.height() * scale);
        target.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        target.layout(x, y, x + width, y + height);
    }
}
