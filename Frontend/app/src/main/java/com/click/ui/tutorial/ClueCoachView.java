package com.click.ui.tutorial;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.graphics.text.LineBreakConfig;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.click.R;
import com.click.designpreview.TutorialNarrator;
import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.SpeechBubbleDrawable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Clue explains the real controls; informational steps continue by tapping its speech bubble. */
@SuppressLint("ViewConstructor")
public final class ClueCoachView extends FrameLayout {
    private static final long IDLE_HINT_MS = 30000;
    private final FrameLayout host;
    private final LinearLayout speaker;
    private final ScrollView bubble;
    private final ImageView clue;
    private final TextView message, continuation;
    private final TutorialNarrator narrator;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mask = new Path(), corner = new Path();
    private final RectF hole = new RectF();
    private final Rect panelBounds = new Rect(), bounds = new Rect();
    private final Point globalOrigin = new Point();
    private final List<Runnable> restoreControls = new ArrayList<>();
    private final int[] layerOrigin = new int[2], targetOrigin = new int[2];
    private View anchor, panel, forwarded;
    private View[] allowed = {};
    private Insets safe = Insets.NONE;
    private int speakerBottom;
    private boolean foreground, audioAvailable = true, emphasized, reminded, touching, pendingNarration;
    private String spokenWords;
    private ValueAnimator motion;
    private final Runnable idle = this::repeatIfIdle;
    private final ViewTreeObserver.OnPreDrawListener trackAnchor = this::updateHole;

    public ClueCoachView(FrameLayout host) {
        super(host.getContext());
        this.host = host;
        setId(R.id.tutorial_floating_host);
        setWillNotDraw(false);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setElevation(dp(16));
        setMotionEventSplittingEnabled(false);
        speaker = new LinearLayout(getContext());
        speaker.setGravity(Gravity.BOTTOM);
        speaker.setClipChildren(false);
        speaker.setClipToPadding(false);
        clue = new ImageView(getContext());
        clue.setScaleType(ImageView.ScaleType.FIT_CENTER);
        clue.setId(R.id.tutorial_floating_control);
        boolean narrow = getResources().getConfiguration().screenWidthDp < 360;
        speaker.addView(clue, new LinearLayout.LayoutParams(dp(narrow ? 62 : 88), dp(narrow ? 76 : 104)));
        bubble = new ScrollView(getContext());
        bubble.setId(R.id.tutorial_explanation_scroll);
        bubble.setBackground(new SpeechBubbleDrawable(Color.WHITE,
                getResources().getDisplayMetrics().density, false, 0xFFB9C6D8));
        bubble.setVerticalScrollBarEnabled(false);
        bubble.setPadding(dp(24), dp(14), dp(16), dp(14));
        LinearLayout copy = new LinearLayout(getContext()); copy.setOrientation(LinearLayout.VERTICAL);
        message = ClickUi.body(getContext(), "");
        message.setId(R.id.tutorial_title);
        message.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_regular));
        message.setTextColor(0xFF1D2942);
        message.setIncludeFontPadding(false);
        message.setTextSize(ClickTextScale.size(getContext(), 18));
        message.setTextLocale(Locale.KOREAN);
        message.setLineSpacing(dp(2), 1.12f);
        if (Build.VERSION.SDK_INT >= 33)
            message.setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        copy.addView(message, new LinearLayout.LayoutParams(-1, -2));
        continuation = ClickUi.body(getContext(), getResources().getString(R.string.tour_continue_hint));
        continuation.setTextSize(ClickTextScale.size(getContext(), 14));
        continuation.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_regular));
        continuation.setPadding(0, dp(10), 0, 0);
        continuation.setVisibility(GONE);
        copy.addView(continuation, new LinearLayout.LayoutParams(-1, -2));
        bubble.addView(copy, new ScrollView.LayoutParams(-1, -2));
        speaker.addView(bubble, new LinearLayout.LayoutParams(0, -2, 1));
        addView(speaker);
        narrator = new TutorialNarrator(getContext());
        narrator.setListener((speaking, unavailable) -> {
            clearTimers();
            if (!speaking) noteActivity();
        });
        ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
            Insets next = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            if (!next.equals(safe)) { safe = next; requestLayout(); }
            return insets;
        });
        host.addView(this, new FrameLayout.LayoutParams(-1, -1));
        ViewCompat.requestApplyInsets(this);
    }

    public void show(String words, int artwork, View highlight, View... controls) {
        clearTimers(); emphasized = reminded = false;
        restoreBackground();
        anchor = panel = highlight;
        allowed = controls;
        for (int i = 0; i < host.getChildCount(); i++)
            if (host.getChildAt(i) != this) restrictToControls(host.getChildAt(i));
        message.setText(words);
        clue.setImageResource(artwork);
        onContinue(null);
        bubble.scrollTo(0, 0);
        audioAvailable = true;
        narrate(words);
        if (anchor != null) {
            View target = anchor;
            target.post(() -> {
                Rect visible = new Rect();
                if (target == anchor && target.isShown() && (!target.getLocalVisibleRect(visible)
                        || visible.height() < target.getHeight() || visible.width() < target.getWidth()))
                    target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), true);
            });
        }
        requestLayout(); invalidate();
    }

    /** Uses the bubble and character, without adding another navigation button. */
    public void onContinue(Runnable next) {
        View.OnClickListener listener = next == null ? null : view -> next.run();
        for (View view : new View[]{bubble, message, continuation, clue}) {
            view.setOnClickListener(listener);
            view.setClickable(next != null);
        }
        clue.setImportantForAccessibility(next == null ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_YES);
        clue.setContentDescription(next == null ? null : getResources().getString(R.string.tour_continue_hint));
        continuation.setVisibility(next == null ? GONE : VISIBLE);
        requestLayout();
    }

    public void keepPanelVisible(View view) { panel = view; requestLayout(); }
    public void status(String words, boolean speak) {
        message.setText(words);
        if (speak) { noteActivity(); narrate(words); }
        requestLayout();
    }
    private void narrate(String words) {
        spokenWords = words;
        pendingNarration = !foreground;
        if (foreground) narrator.speak(words);
    }
    public void setAudioAvailable(boolean available) {
        audioAvailable = available;
        if (!available) narrator.stop();
        noteActivity();
    }
    public void onTextEdited() { narrator.stop(); noteActivity(); }
    private void noteActivity() {
        clearTimers();
        if (foreground && audioAvailable && !reminded && !touching && !narrator.isSpeaking())
            postDelayed(idle, IDLE_HINT_MS);
    }
    private void clearTimers() { removeCallbacks(idle); }
    private void repeatIfIdle() {
        if (!foreground || !audioAvailable || reminded || touching || narrator.isSpeaking()) return;
        emphasized = reminded = true;
        invalidate();
        narrator.replay(spokenWords);
    }

    private void restrictToControls(View view) {
        if (Arrays.asList(allowed).contains(view)) return;
        boolean hasControl = false;
        for (View target : allowed) {
            for (View parent = target; parent != null && parent != host;
                    parent = parent.getParent() instanceof View ? (View) parent.getParent() : null) {
                if (parent == view) { hasControl = true; break; }
            }
            if (hasControl) break;
        }
        int accessibility = view.getImportantForAccessibility(), focus = view.getFocusable();
        boolean touchFocus = view.isFocusableInTouchMode();
        restoreControls.add(() -> {
            view.setImportantForAccessibility(accessibility);
            view.setFocusableInTouchMode(touchFocus); view.setFocusable(focus);
        });
        view.setImportantForAccessibility(hasControl ? IMPORTANT_FOR_ACCESSIBILITY_NO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.setFocusable(false);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        if (hasControl) for (int i = 0; i < group.getChildCount(); i++) restrictToControls(group.getChildAt(i));
        else {
            int old = group.getDescendantFocusability();
            restoreControls.add(() -> group.setDescendantFocusability(old));
            group.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        }
    }
    private void restoreBackground() { for (Runnable restore : restoreControls) restore.run(); restoreControls.clear(); }

    private boolean localBounds(View view) {
        // Both rectangles must use the view root's coordinates. Screen coordinates
        // include a window/status-bar offset that getGlobalVisibleRect does not.
        if (!view.isShown() || !getGlobalVisibleRect(bounds, globalOrigin)
                || !view.getGlobalVisibleRect(bounds)) return false;
        bounds.offset(-globalOrigin.x, -globalOrigin.y);
        return true;
    }
    private boolean navigationTarget() {
        return anchor != null && (anchor.getId() == R.id.home_nav_favorites || anchor.getId() == R.id.home_nav_home);
    }
    private boolean updateHole() {
        if (panel == null || !localBounds(panel)) bounds.setEmpty();
        boolean moved = !panelBounds.equals(bounds);
        panelBounds.set(bounds);
        if (anchor != null && localBounds(anchor)) {
            if (navigationTarget() && anchor instanceof ViewGroup) {
                Rect content = new Rect(), child = new Rect();
                ViewGroup navigation = (ViewGroup) anchor;
                for (int i = 0; i < navigation.getChildCount(); i++) {
                    if (navigation.getChildAt(i).isShown() && navigation.getChildAt(i).getGlobalVisibleRect(child))
                        content.union(child);
                }
                if (!content.isEmpty()) { content.offset(-globalOrigin.x, -globalOrigin.y); bounds.set(content); }
            }
            bounds.inset(-dp(5), -dp(5));
        } else bounds.setEmpty();
        if (!moved && hole.equals(new RectF(bounds))) return true;
        hole.set(bounds);
        requestLayout(); invalidate();
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked(), x = (int) event.getX(), y = (int) event.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            forwarded = null;
            speaker.getHitRect(bounds); touching = bounds.contains(x, y);
            if (!touching) for (View target : allowed)
                if (target.isEnabled() && localBounds(target) && bounds.contains(x, y)) { forwarded = target; touching = true; break; }
            if (touching) clearTimers();
        }
        if (forwarded != null) {
            View target = forwarded;
            MotionEvent copy = MotionEvent.obtain(event);
            if (action == MotionEvent.ACTION_POINTER_DOWN || !target.isShown()) copy.setAction(MotionEvent.ACTION_CANCEL);
            getLocationOnScreen(layerOrigin); target.getLocationOnScreen(targetOrigin);
            copy.offsetLocation(layerOrigin[0] - targetOrigin[0], layerOrigin[1] - targetOrigin[1]);
            if (copy.getActionMasked() == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) forwarded = null;
            target.dispatchTouchEvent(copy); copy.recycle();
        } else super.dispatchTouchEvent(event);
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && touching) { touching = false; noteActivity(); }
        return true;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        mask.rewind(); mask.setFillType(Path.FillType.EVEN_ODD);
        mask.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        float radius = Math.min(Math.min(hole.width(), hole.height()) / 2f, dp(18));
        if (!hole.isEmpty()) mask.addRoundRect(hole, radius, radius, Path.Direction.CW);
        ink.setStyle(Paint.Style.FILL); ink.setColor(0x80FFFFFF); canvas.drawPath(mask, ink);
        if (hole.isEmpty() || allowed.length == 0) return;
        ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(dp(emphasized ? 4 : 2));
        ink.setStrokeCap(Paint.Cap.ROUND); ink.setColor(emphasized ? 0xFF174DAA : 0xFF316BC5);
        float leg = Math.min(dp(emphasized ? 32 : 24), Math.min(hole.width(), hole.height()) / 2f);
        float bend = Math.min(radius, leg * .65f);
        corner.rewind(); corner.moveTo(0, leg); corner.lineTo(0, bend); corner.quadTo(0, 0, bend, 0); corner.lineTo(leg, 0);
        drawCorner(canvas, hole.left, hole.top, 1, 1); drawCorner(canvas, hole.right, hole.top, -1, 1);
        drawCorner(canvas, hole.left, hole.bottom, 1, -1); drawCorner(canvas, hole.right, hole.bottom, -1, -1);
    }
    private void drawCorner(Canvas canvas, float x, float y, int sx, int sy) {
        canvas.save(); canvas.translate(x, y); canvas.scale(sx, sy); canvas.drawPath(corner, ink); canvas.restore();
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        int gap = dp(16), top = safe.top + gap;
        int width = Math.max(1, Math.min(dp(440), w - safe.left - safe.right - gap * 2));
        speakerBottom = h - safe.bottom - gap;
        if (!hole.isEmpty() && hole.top > h * .65f) speakerBottom = Math.min(speakerBottom, (int) hole.top - gap);
        int available = Math.max(1, speakerBottom - top);
        int room = Math.round(available * .55f);
        if (!panelBounds.isEmpty() && panelBounds.bottom < speakerBottom && !navigationTarget())
            room = Math.min(room, Math.max(dp(100), speakerBottom - panelBounds.bottom - gap));
        speaker.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, room), MeasureSpec.AT_MOST));
        setMeasuredDimension(w, h);
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = speaker.getMeasuredWidth(), h = speaker.getMeasuredHeight();
        int x = safe.left + (getWidth() - safe.left - safe.right - w) / 2, y = speakerBottom - h;
        speaker.layout(x, y, x + w, y + h);
    }
    private void startMotion() {
        if (!foreground || !isAttachedToWindow() || motion != null || !ValueAnimator.areAnimatorsEnabled()) return;
        motion = ValueAnimator.ofFloat(0f, 1f); motion.setDuration(3200); motion.setRepeatCount(ValueAnimator.INFINITE);
        motion.setInterpolator(new android.view.animation.LinearInterpolator());
        motion.addUpdateListener(frame -> {
            double phase = ((float) frame.getAnimatedValue()) * Math.PI * 2;
            clue.setTranslationY((float) Math.sin(phase) * dp(4));
            clue.setRotation((float) Math.sin(phase) * 2f);
            float scale = 1f + (float) Math.sin(phase) * .025f;
            clue.setScaleX(scale); clue.setScaleY(scale);
        });
        motion.start();
    }
    private void stopMotion() {
        if (motion != null) { motion.cancel(); motion = null; }
        clue.setTranslationY(0); clue.setRotation(0); clue.setScaleX(1); clue.setScaleY(1);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); getViewTreeObserver().addOnPreDrawListener(trackAnchor); startMotion(); }
    @Override protected void onDetachedFromWindow() { getViewTreeObserver().removeOnPreDrawListener(trackAnchor); stopMotion(); super.onDetachedFromWindow(); }
    public void pause() { foreground = false; touching = false; clearTimers(); narrator.pause(); stopMotion(); }
    public void resume() { foreground = true; narrator.resume(); startMotion(); if (pendingNarration) narrate(spokenWords); noteActivity(); }
    public void close() { pause(); narrator.close(); restoreBackground(); }
    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
