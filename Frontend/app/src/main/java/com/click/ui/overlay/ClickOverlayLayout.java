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
        void onModeChanged(Mode oldMode, Mode newMode);
        void onDragStarted();
        void onDragBy(float deltaX, float deltaY);
        void onDragEnded();
        void onTextSubmitted(String text);
        void onCloseRequested();
    }

    private static final int CLICK_RED = Color.rgb(255, 59, 77);
    private static final int MIC_ON_GREEN = Color.rgb(143, 209, 79);
    private static final int MIC_OFF_RED = Color.rgb(225, 62, 70);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final EditText goalInput;
    private Mode mode = Mode.COLLAPSED;
    private Mode lastExpandedMode = Mode.VOICE;
    private Callback callback;
    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private boolean dragging;
    private String recognizedSpeech = "듣고 있어요…";

    public ClickOverlayLayout(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipToPadding(false);
        setContentDescription("Click 도우미 열기");

        goalInput = new EditText(context);
        goalInput.setSingleLine(true);
        goalInput.setTextSize(14);
        goalInput.setTextColor(Color.rgb(33, 41, 54));
        goalInput.setHintTextColor(Color.rgb(138, 145, 158));
        goalInput.setHint(R.string.text_goal_hint);
        goalInput.setInputType(InputType.TYPE_CLASS_TEXT);
        goalInput.setPadding(dp(14), 0, dp(8), 0);
        goalInput.setBackgroundColor(Color.TRANSPARENT);
        goalInput.setVisibility(GONE);
        goalInput.setFocusableInTouchMode(true);
        goalInput.setOnTouchListener((view, event) -> handleInputTouch(event));
        addView(goalInput);

    }

    public void setCallback(Callback callback) { this.callback = callback; }
    public Mode getMode() { return mode; }

    public void setRecognizedSpeech(String text) {
        recognizedSpeech = text == null || text.trim().isEmpty() ? "듣고 있어요…" : text.trim();
        invalidate();
    }

    public void collapse() {
        if (mode == Mode.COLLAPSED) return;
        hideKeyboard();
        changeMode(Mode.COLLAPSED);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus && mode == Mode.TEXT) post(this::collapse);
    }

    public int getWindowWidth(Mode targetMode, int screenWidth) {
        if (targetMode == Mode.COLLAPSED) return dp(64);
        return Math.min(screenWidth - dp(72), dp(320));
    }

    public int getWindowHeight(Mode targetMode) {
        if (targetMode == Mode.COLLAPSED) return dp(64);
        return dp(68);
    }

    public float getClickCenterX(Mode targetMode) {
        if (targetMode == Mode.COLLAPSED) return dp(32);
        return getWindowWidth(targetMode, getResources().getDisplayMetrics().widthPixels) - dp(88);
    }

    public float getClickCenterY(Mode targetMode) {
        if (targetMode == Mode.COLLAPSED) return dp(32);
        return dp(34);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(width, height);
        if (mode == Mode.TEXT) {
            goalInput.measure(
                    MeasureSpec.makeMeasureSpec(Math.max(0, width - dp(176)), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(46), MeasureSpec.EXACTLY));
        } else {
            goalInput.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mode == Mode.TEXT) {
            int inputTop = (getHeight() - dp(46)) / 2;
            goalInput.layout(dp(4), inputTop, getWidth() - dp(172), inputTop + dp(46));
        } else goalInput.layout(0, 0, 0, 0);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        if (mode == Mode.COLLAPSED) drawClickIcon(canvas, getWidth() / 2f, getHeight() / 2f, dp(29));
        else if (mode == Mode.VOICE) drawVoiceControls(canvas);
        else drawTextControls(canvas);
    }

    private void drawVoiceControls(Canvas canvas) {
        float centerY = getHeight() / 2f;
        paint.setColor(Color.argb(245, 255, 255, 255));
        paint.setShadowLayer(dp(7), 0, dp(3), Color.argb(50, 20, 28, 40));
        setLayerType(LAYER_TYPE_SOFTWARE, paint);
        canvas.drawRoundRect(new RectF(dp(3), dp(6), getWidth() - dp(3), getHeight() - dp(6)), dp(28), dp(28), paint);
        paint.clearShadowLayer();
        drawListeningChip(canvas, centerY);
        drawClickIcon(canvas, getWidth() - dp(88), centerY, dp(29));
        drawMic(canvas, getWidth() - dp(30), centerY, MIC_ON_GREEN);
    }

    private void drawListeningChip(Canvas canvas, float centerY) {
        RectF bubble = new RectF(dp(4), centerY - dp(23), getWidth() - dp(122), centerY + dp(23));
        paint.setColor(Color.rgb(245, 247, 250));
        canvas.drawRoundRect(bubble, dp(18), dp(18), paint);
        paint.setColor(Color.rgb(38, 44, 55));
        paint.setTextSize(dp(12));
        float available = bubble.width() - dp(24);
        int firstLength = paint.breakText(recognizedSpeech, true, available, null);
        String first = recognizedSpeech.substring(0, firstLength);
        String second = recognizedSpeech.substring(firstLength);
        if (paint.measureText(second) > available) {
            int secondLength = Math.max(0, paint.breakText(second, true, available - paint.measureText("…"), null));
            second = second.substring(0, secondLength) + "…";
        }
        float firstY = second.isEmpty() ? centerY + dp(4) : centerY - dp(4);
        canvas.drawText(first, bubble.left + dp(12), firstY, paint);
        if (!second.isEmpty()) canvas.drawText(second, bubble.left + dp(12), centerY + dp(12), paint);
    }

    private void drawTextControls(Canvas canvas) {
        float centerY = getHeight() / 2f;
        paint.setColor(Color.argb(248, 255, 255, 255));
        paint.setShadowLayer(dp(7), 0, dp(3), Color.argb(50, 20, 28, 40));
        setLayerType(LAYER_TYPE_SOFTWARE, paint);
        canvas.drawRoundRect(new RectF(dp(3), dp(6), getWidth() - dp(3), getHeight() - dp(6)), dp(28), dp(28), paint);
        paint.clearShadowLayer();
        drawSend(canvas, getWidth() - dp(146), centerY);
        drawClickIcon(canvas, getWidth() - dp(88), centerY, dp(29));
        drawMicOff(canvas, getWidth() - dp(30), centerY);
    }

    private void drawSend(Canvas canvas, float cx, float cy) {
        paint.setColor(Color.rgb(80, 92, 112));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        canvas.drawCircle(cx, cy, dp(19), paint);
        paint.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        path.moveTo(cx - dp(7), cy - dp(6));
        path.lineTo(cx + dp(8), cy);
        path.lineTo(cx - dp(7), cy + dp(6));
        path.close();
        canvas.drawPath(path, paint);
        canvas.drawLine(cx - dp(6), cy, cx + dp(6), cy, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawClickIcon(Canvas canvas, float cx, float cy, float radius) {
        ClickIconRenderer.draw(this, canvas, paint, path, cx, cy, radius, 0f, false);
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
        return handleDragOrTap(event, false);
    }

    private boolean handleInputTouch(MotionEvent event) {
        boolean handled = handleDragOrTap(event, true);
        if (event.getActionMasked() == MotionEvent.ACTION_UP && !dragging) {
            goalInput.requestFocus();
            InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
            keyboard.showSoftInput(goalInput, InputMethodManager.SHOW_IMPLICIT);
        }
        return handled;
    }

    private boolean handleDragOrTap(MotionEvent event, boolean fromInput) {
        float rawX = event.getRawX();
        float rawY = event.getRawY();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downRawX = lastRawX = rawX;
            downRawY = lastRawY = rawY;
            dragging = false;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (Math.hypot(rawX - downRawX, rawY - downRawY) > dp(5)) {
                if (!dragging && callback != null) callback.onDragStarted();
                dragging = true;
            }
            if (dragging && callback != null) callback.onDragBy(rawX - lastRawX, rawY - lastRawY);
            lastRawX = rawX;
            lastRawY = rawY;
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (dragging) {
                if (callback != null) callback.onDragEnded();
            } else if (!fromInput) handleTap(event.getX(), event.getY());
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL && dragging && callback != null) callback.onDragEnded();
        return true;
    }

    private void handleTap(float x, float y) {
        if (mode == Mode.COLLAPSED) {
            changeMode(lastExpandedMode);
            return;
        }
        if (mode == Mode.VOICE) {
            if (y < getHeight() - dp(78)) return;
            if (x > getWidth() - dp(76)) changeMode(Mode.TEXT);
            else changeMode(Mode.COLLAPSED);
            return;
        }
        if (x >= getWidth() - dp(170) && x < getWidth() - dp(122)) {
            submitText();
        } else if (x >= getWidth() - dp(118) && x < getWidth() - dp(58)) {
            hideKeyboard();
            changeMode(Mode.COLLAPSED);
        } else if (x >= getWidth() - dp(58)) {
            hideKeyboard();
            changeMode(Mode.VOICE);
        }
    }

    private void changeMode(Mode newMode) {
        Mode oldMode = mode;
        if (newMode != Mode.COLLAPSED) lastExpandedMode = newMode;
        mode = newMode;
        goalInput.setVisibility(mode == Mode.TEXT ? VISIBLE : GONE);
        if (mode == Mode.TEXT) goalInput.clearFocus();
        setContentDescription(mode == Mode.COLLAPSED ? "Click 도우미 열기" : "Click 도우미 컨트롤");
        if (callback != null) callback.onModeChanged(oldMode, newMode);
        requestLayout();
        invalidate();
    }

    private void submitText() {
        String text = goalInput.getText().toString().trim();
        if (text.isEmpty()) return;
        hideKeyboard();
        if (callback != null) callback.onTextSubmitted(text);
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
