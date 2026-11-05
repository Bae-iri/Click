package com.click.designpreview;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import com.click.ui.common.ClickTextScale;

/** The same persisted text-size control in signup and display settings. */
public final class TextSizeControlView extends LinearLayout {
    private static final int BLUE = 0xFF3478F6, INK = 0xFF101A36, SECONDARY = 0xFF7182A4;
    private Runnable onSizeChanged;

    public TextSizeControlView(Context context) { this(context, null); }

    public TextSizeControlView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setPadding(dp(18), dp(18), dp(18), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1), 0xFFE3EAF6);
        setBackground(background);

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("글자 크기", 16, INK, R.font.pretendard_medium), new LayoutParams(0, -2, 1));
        TextView current = text(ClickTextScale.label(context), 13, BLUE, R.font.pretendard_medium);
        current.setId(R.id.text_size_current);
        heading.addView(current, new LayoutParams(-2, -2));
        addView(heading, new LayoutParams(-1, -2));

        LinearLayout previewRow = new LinearLayout(context);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        previewRow.setMinimumHeight(dp(48));
        previewRow.addView(text("가", 15, SECONDARY, R.font.pretendard_regular), new LayoutParams(-2, -2));
        TextView preview = text("가나다라마바사", 17, INK, R.font.pretendard_medium);
        preview.setId(R.id.text_size_preview);
        preview.setGravity(Gravity.CENTER);
        preview.setLineSpacing(dp(3), 1.1f);
        LayoutParams previewParams = new LayoutParams(0, -2, 1);
        previewParams.setMarginStart(dp(12)); previewParams.setMarginEnd(dp(12));
        previewRow.addView(preview, previewParams);
        previewRow.addView(text("가", 23, BLUE, R.font.pretendard_semibold), new LayoutParams(-2, -2));
        LayoutParams previewRowParams = new LayoutParams(-1, -2); previewRowParams.topMargin = dp(14);
        addView(previewRow, previewRowParams);

        SeekBar scale = new SeekBar(context);
        scale.setId(R.id.text_size_scale);
        scale.setMax(3);
        scale.setKeyProgressIncrement(1);
        scale.setProgress(ClickTextScale.level(context));
        scale.setContentDescription("클릭 글자 크기 조절");
        scale.setMinHeight(dp(48));
        scale.setPadding(dp(12), 0, dp(12), 0);
        scale.setProgressTintList(ColorStateList.valueOf(BLUE));
        scale.setThumbTintList(ColorStateList.valueOf(BLUE));
        GradientDrawable tick = new GradientDrawable();
        tick.setShape(GradientDrawable.OVAL);
        tick.setSize(dp(8), dp(8));
        tick.setColor(0xFF9CAECC);
        scale.setTickMark(tick);
        scale.setTickMarkTintList(ColorStateList.valueOf(0xFF9CAECC));
        scale.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                ClickTextScale.saveLevel(context, progress);
                current.setText(ClickTextScale.label(context));
                ClickTextScale.applyTree(TextSizeControlView.this);
                if (onSizeChanged != null) onSizeChanged.run();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        LayoutParams scaleParams = new LayoutParams(-1, -2); scaleParams.topMargin = dp(8);
        addView(scale, scaleParams);

        LinearLayout labels = new LinearLayout(context);
        labels.addView(text("작게", 12, SECONDARY, R.font.pretendard_regular), new LayoutParams(0, -2, 1));
        TextView max = text("아주 크게", 12, SECONDARY, R.font.pretendard_regular);
        max.setGravity(Gravity.END);
        labels.addView(max, new LayoutParams(0, -2, 1));
        addView(labels, new LayoutParams(-1, -2));
        ClickTextScale.applyTree(this);
    }

    public void setOnSizeChanged(Runnable listener) { onSizeChanged = listener; }

    private TextView text(String value, int size, int color, int font) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(getResources().getFont(font)); view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
