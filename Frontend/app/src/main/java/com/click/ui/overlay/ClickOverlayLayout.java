package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.annotation.NonNull;

import com.click.R;

public final class ClickOverlayLayout extends ViewGroup {
    public enum Mode { COLLAPSED, VOICE, TEXT }

    public interface Callback {
        void onModeChanged(Mode mode);
        void onDragBy(int deltaX, int deltaY);
        void onCloseRequested();
    }

    private static final int CLICK_RED = Color.rgb(255, 59, 77);
    private static final int MIC_ON_GREEN = Color.rgb(143, 209, 79);
    private static final int MIC_OFF_RED = Color.rgb(225, 62, 70);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final EditText goalInput;
    private Mode mode = Mode.COLLAPSED;
    private Callback callback;
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private boolean dragging;

    public ClickOverlayLayout(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipToPadding(false);
        setContentDescription("Click 도우미");

        goalInput = new EditText(context);
        goalInput.setSingleLine(true);
        goalInput.setTextSize(14);
        goalInput.setTextColor(Color.rgb(33, 41, 54));
        goalInput.setHintTextColor(Color.rgb(138, 145, 158));
        goalInput.setHint(R.string.text_goal_hint);
        goalInput.setInputType(InputType.TYPE_CLASS_TEXT);
        goalInput.setPadding(dp(4), 0, dp(8), 0);
        goalInput.setBackgroundColor(Color.TRANSPARENT);
        goalInput.setVisibility(GONE);
        addView(goalInput);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public Mode getMode() {
        return mode;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);

        if (mode == Mode.TEXT) {
            int inputWidth = Math.max(0, width - dp(126));
            goalInput.measure(
                    MeasureSpec.makeMeasureSpec(inputWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(46), MeasureSpec.EXACTLY)
            );
        } else {
            goalInput.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY)
            );
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mode == Mode.TEXT) {
            int inputTop = (getHeight() - dp(46)) / 2;
            goalInput.layout(dp(4), inputTop, getWidth() - dp(122), inputTop + dp(46));
        } else {
            goalInput.layout(0, 0, 0, 0);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mode == Mode.COLLAPSED) {
            drawClickIcon(canvas, getWidth() / 2f, getHeight() / 2f, dp(29));
        } else if (mode == Mode.VOICE) {
            drawVoiceControls(canvas);
        } else {
            drawTextControls(canvas);
        }
    }

    private void drawVoiceControls(Canvas canvas) {
        float centerY = getHeight() - dp(39);
        drawPromptBubble(canvas, centerY - dp(98));

        paint.setColor(Color.argb(245, 255, 255, 255));
        paint.setShadowLayer(dp(8), 0, dp(3), Color.argb(55, 20, 28, 40));
        setLayerType(LAYER_TYPE_SOFTWARE, paint);
        canvas.drawRoundRect(new RectF(dp(4), centerY - dp(31), getWidth() - dp(4), centerY + dp(31)), dp(32), dp(32), paint);
        paint.clearShadowLayer();

        drawTrash(canvas, dp(45), centerY, CLICK_RED);
        drawClickIcon(canvas, getWidth() / 2f, centerY, dp(35));
        drawMic(canvas, getWidth() - dp(45), centerY, MIC_ON_GREEN);
    }

    private void drawTextControls(Canvas canvas) {
        float clickX = getWidth() - dp(88);
        float centerY = getHeight() / 2f;
        paint.setColor(Color.argb(248, 255, 255, 255));
        paint.setShadowLayer(dp(7), 0, dp(3), Color.argb(50, 20, 28, 40));
        setLayerType(LAYER_TYPE_SOFTWARE, paint);
        canvas.drawRoundRect(new RectF(dp(3), dp(6), getWidth() - dp(3), getHeight() - dp(6)), dp(28), dp(28), paint);
        paint.clearShadowLayer();
        drawClickIcon(canvas, clickX, centerY, dp(29));
        drawMicOff(canvas, getWidth() - dp(30), centerY);
    }

    private void drawPromptBubble(Canvas canvas, float top) {
        float left = dp(18);
        float right = getWidth() - dp(8);
        float bottom = top + dp(65);
        paint.setColor(Color.argb(250, 255, 255, 255));
        paint.setShadowLayer(dp(7), 0, dp(3), Color.argb(52, 20, 28, 40));
        setLayerType(LAYER_TYPE_SOFTWARE, paint);
        canvas.drawRoundRect(new RectF(left, top, right, bottom), dp(14), dp(14), paint);
        path.reset();
        path.moveTo(getWidth() / 2f - dp(10), bottom - dp(1));
        path.lineTo(getWidth() / 2f, bottom + dp(11));
        path.lineTo(getWidth() / 2f + dp(10), bottom - dp(1));
        path.close();
        canvas.drawPath(path, paint);
        paint.clearShadowLayer();

        paint.setColor(Color.rgb(43, 48, 59));
        paint.setTextSize(dp(14));
        paint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
        canvas.drawText("무엇을 도와드릴까요?", left + dp(18), top + dp(27), paint);
        canvas.drawText("말씀해주세요.", left + dp(18), top + dp(49), paint);
    }

    private void drawClickIcon(Canvas canvas, float cx, float cy, float radius) {
        paint.setColor(CLICK_RED);
        paint.setShadowLayer(dp(6), 0, dp(3), Color.argb(65, 30, 35, 50));
        setLayerType(LAYER_TYPE_SOFTWARE, paint);
        canvas.drawCircle(cx, cy - radius * 0.06f, radius, paint);

        path.reset();
        path.moveTo(cx - radius * 0.56f, cy + radius * 0.62f);
        path.lineTo(cx - radius * 0.76f, cy + radius * 1.02f);
        path.lineTo(cx - radius * 0.18f, cy + radius * 0.78f);
        path.close();
        canvas.drawPath(path, paint);
        paint.clearShadowLayer();

        paint.setColor(Color.WHITE);
        path.reset();
        path.moveTo(cx - radius * 0.30f, cy - radius * 0.50f);
        path.lineTo(cx + radius * 0.42f, cy - radius * 0.02f);
        path.lineTo(cx + radius * 0.08f, cy + radius * 0.08f);
        path.lineTo(cx + radius * 0.30f, cy + radius * 0.50f);
        path.lineTo(cx + radius * 0.06f, cy + radius * 0.62f);
        path.lineTo(cx - radius * 0.14f, cy + radius * 0.18f);
        path.lineTo(cx - radius * 0.42f, cy + radius * 0.42f);
        path.close();
        canvas.drawPath(path, paint);

        paint.setStrokeWidth(dp(2));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx + radius * 0.25f, cy - radius * 0.63f, cx + radius * 0.36f, cy - radius * 0.88f, paint);
        canvas.drawLine(cx + radius * 0.47f, cy - radius * 0.48f, cx + radius * 0.68f, cy - radius * 0.65f, paint);
        canvas.drawLine(cx + radius * 0.55f, cy - radius * 0.20f, cx + radius * 0.80f, cy - radius * 0.22f, paint);
    }

    private void drawMic(Canvas canvas, float cx, float cy, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawRoundRect(new RectF(cx - dp(7), cy - dp(14), cx + dp(7), cy + dp(7)), dp(7), dp(7), paint);
        canvas.drawArc(new RectF(cx - dp(13), cy - dp(4), cx + dp(13), cy + dp(13)), 0, 180, false, paint);
        canvas.drawLine(cx, cy + dp(13), cx, cy + dp(20), paint);
        canvas.drawLine(cx - dp(7), cy + dp(20), cx + dp(7), cy + dp(20), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawMicOff(Canvas canvas, float cx, float cy) {
        drawMic(canvas, cx, cy, MIC_OFF_RED);
        paint.setColor(MIC_OFF_RED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(cx - dp(14), cy - dp(17), cx + dp(14), cy + dp(19), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawTrash(Canvas canvas, float cx, float cy, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawRoundRect(new RectF(cx - dp(9), cy - dp(9), cx + dp(9), cy + dp(14)), dp(2), dp(2), paint);
        canvas.drawLine(cx - dp(13), cy - dp(14), cx + dp(13), cy - dp(14), paint);
        canvas.drawLine(cx - dp(4), cy - dp(19), cx + dp(4), cy - dp(19), paint);
        canvas.drawLine(cx - dp(4), cy - dp(5), cx - dp(4), cy + dp(9), paint);
        canvas.drawLine(cx + dp(4), cy - dp(5), cx + dp(4), cy + dp(9), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downRawX = lastRawX = rawX;
            downRawY = lastRawY = rawY;
            dragging = false;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && mode == Mode.COLLAPSED) {
            int dx = Math.round(rawX - lastRawX);
            int dy = Math.round(rawY - lastRawY);
            if (Math.hypot(rawX - downRawX, rawY - downRawY) > dp(6)) {
                dragging = true;
                if (callback != null) {
                    callback.onDragBy(dx, dy);
                }
            }
            lastRawX = rawX;
            lastRawY = rawY;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP && !dragging) {
            handleTap(event.getX(), event.getY());
            return true;
        }
        return true;
    }

    private void handleTap(float x, float y) {
        if (mode == Mode.COLLAPSED) {
            changeMode(Mode.VOICE);
            return;
        }
        if (mode == Mode.VOICE) {
            if (y < getHeight() - dp(78)) {
                return;
            }
            if (x < dp(76)) {
                if (callback != null) {
                    callback.onCloseRequested();
                }
            } else if (x > getWidth() - dp(76)) {
                changeMode(Mode.TEXT);
            } else {
                changeMode(Mode.COLLAPSED);
            }
            return;
        }

        if (x >= getWidth() - dp(118) && x < getWidth() - dp(58)) {
            hideKeyboard();
            changeMode(Mode.COLLAPSED);
        } else if (x >= getWidth() - dp(58)) {
            hideKeyboard();
            changeMode(Mode.VOICE);
        }
    }

    private void changeMode(Mode newMode) {
        mode = newMode;
        goalInput.setVisibility(mode == Mode.TEXT ? VISIBLE : GONE);
        setContentDescription(mode == Mode.COLLAPSED ? "Click 도우미 열기" : "Click 도우미 컨트롤");
        if (callback != null) {
            callback.onModeChanged(mode);
        }
        requestLayout();
        invalidate();
        if (mode == Mode.TEXT) {
            goalInput.requestFocus();
            postDelayed(() -> {
                InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
                keyboard.showSoftInput(goalInput, InputMethodManager.SHOW_IMPLICIT);
            }, 120);
        }
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        keyboard.hideSoftInputFromWindow(goalInput.getWindowToken(), 0);
        goalInput.clearFocus();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
