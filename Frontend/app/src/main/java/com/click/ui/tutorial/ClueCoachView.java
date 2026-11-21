package com.click.ui.tutorial;

import android.content.Context;
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
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
import com.click.designpreview.TutorialNarrator;
import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.SpeechBubbleDrawable;
import com.click.ui.overlay.RequestSummaryCard;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** A floating Clue speech bubble. Only the named controls beneath its spotlight accept input. */
@SuppressLint("ViewConstructor") // Attached to an existing screen rather than inflated from XML.
public final class ClueCoachView extends FrameLayout {
    private static final long IDLE_HINT_MS = 8000;
    private final FrameLayout host;
    private final LinearLayout speaker, footer, actions;
    private final ScrollView bubble;
    private final ImageView clue;
    private final TextView message, previous;
    private final ScrollView requestPreview;
    private final TutorialNarrator narrator;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mask = new Path();
    private final RectF hole = new RectF();
    private final Path corner = new Path();
    private final Rect panelBounds = new Rect();
    private final Rect bounds = new Rect();
    private final Point origin = new Point();
    private final List<Runnable> restoreControls = new ArrayList<>();
    private View anchor, panel;
    private ScrollView targetScroll;
    private View[] allowed = {};
    private Insets safe = Insets.NONE;
    private int footerTop, speakerBottom;
    private boolean foreground, audioAvailable = true, emphasized, idleReached, reminded, touching;
    private boolean pendingNarration;
    private String spokenWords;
    private View forwarded, primaryAction;
    private final int[] layerOrigin = new int[2], targetOrigin = new int[2];
    private final Runnable repeat = () -> repeatIfIdle();
    private final Runnable idle = () -> emphasize();
    private final ViewTreeObserver.OnPreDrawListener trackAnchor = this::updateHole;

    public ClueCoachView(FrameLayout host, Runnable back) {
        super(host.getContext());
        this.host = host;
        Context context = getContext();
        setId(R.id.tutorial_floating_host);
        setWillNotDraw(false);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        setOutlineProvider(null);
        setElevation(dp(16));
        setMotionEventSplittingEnabled(false);

        speaker = new LinearLayout(context);
        speaker.setGravity(Gravity.BOTTOM);
        speaker.setClipChildren(false);
        speaker.setClipToPadding(false);
        clue = new ImageView(context);
        clue.setScaleType(ImageView.ScaleType.FIT_CENTER);
        clue.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        clue.setId(R.id.tutorial_floating_control);
        boolean narrow = getResources().getConfiguration().screenWidthDp < 360;
        speaker.addView(clue, new LinearLayout.LayoutParams(dp(narrow ? 62 : 88), dp(narrow ? 76 : 104)));

        bubble = new ScrollView(context);
        bubble.setFocusable(false);
        bubble.setId(R.id.tutorial_explanation_scroll);
        float density = getResources().getDisplayMetrics().density;
        bubble.setBackground(new SpeechBubbleDrawable(Color.WHITE, density, false, 0xFFB9C6D8));
        bubble.setOutlineProvider(null);
        bubble.setVerticalScrollBarEnabled(false);
        bubble.setPadding(dp(24), dp(14), dp(16), dp(14));
        message = ClickUi.body(context, "");
        message.setId(R.id.tutorial_title);
        message.setTypeface(getResources().getFont(com.click.designpreview.R.font.pretendard_medium));
        message.setTextColor(0xFF1D2942);
        message.setIncludeFontPadding(false);
        message.setTextSize(ClickTextScale.size(context, 18));
        message.setTextLocale(Locale.KOREAN);
        message.setLineSpacing(dp(2), 1.12f);
        if (Build.VERSION.SDK_INT >= 33) message.setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        ViewCompat.setAccessibilityHeading(message, true);
        bubble.addView(message, new ScrollView.LayoutParams(-1, -2));
        speaker.addView(bubble, new LinearLayout.LayoutParams(0, -2, 1));
        addView(speaker);
        requestPreview = new ScrollView(context);
        requestPreview.setVisibility(GONE);
        requestPreview.setFocusable(false);
        addView(requestPreview);

        footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        previous = link(R.string.tour_back, back);
        previous.setId(R.id.tutorial_back);
        previous.setBackground(new RippleDrawable(ColorStateList.valueOf(0x143478F6),
                ClickUi.rounded(Color.WHITE, dp(14)), null));
        footer.addView(previous, new LinearLayout.LayoutParams(-2, -2));
        actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        footer.addView(actions, new LinearLayout.LayoutParams(0, -2, 1));
        addView(footer);

        narrator = new TutorialNarrator(context);
        narrator.setListener((speaking, unavailable) -> {
            if (!speaking && idleReached && !reminded && foreground) {
                removeCallbacks(repeat);
                postDelayed(repeat, 900);
            }
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

    public void show(String words, int artwork, boolean canGoBack, View highlight, View... controls) {
        show(words, words, artwork, canGoBack, highlight, controls);
    }

    private void show(String words, String narration, int artwork, boolean canGoBack, View highlight, View... controls) {
        clearTimers();
        emphasized = reminded = false;
        primaryAction = null;
        restoreBackground();
        anchor = highlight;
        panel = highlight;
        targetScroll = null;
        for (View v = highlight; v != null && v.getParent() instanceof View; v = (View) v.getParent()) {
            if (v.getParent() instanceof ScrollView) {
                targetScroll = (ScrollView) v.getParent();
                break;
            }
        }
        allowed = controls;
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if (child != this) restrictToControls(child);
        }
        message.setText(words);
        requestPreview.setVisibility(GONE);
        requestPreview.removeAllViews();
        clue.setImageResource(artwork);
        previous.setText(R.string.tour_back);
        previous.setVisibility(canGoBack ? VISIBLE : GONE);
        actions.setPadding(canGoBack ? dp(10) : 0, 0, 0, 0);
        actions.removeAllViews();
        bubble.scrollTo(0, 0);
        setAudioAvailable(true);
        narrate(narration);
        if (anchor != null) {
            View target = anchor;
            target.post(() -> {
                Rect visible = new Rect();
                if (target == anchor && target.isShown() && (!target.getLocalVisibleRect(visible)
                        || visible.height() < target.getHeight() || visible.width() < target.getWidth()))
                    target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), true);
            });
        }
        requestLayout();
        invalidate();
    }

    /** Keep the other parts of the highlighted control, such as its font preview, in sight. */
    public void keepPanelVisible(View view) { panel = view; targetScroll = null; requestLayout(); }

    public void setBackLabel(int label) { previous.setText(label); }

    public void showReview(String request, int artwork) {
        String words = getResources().getString(R.string.tour_review);
        show(words, request + ". " + words, artwork, true, null);
        setBackLabel(R.string.tour_edit);
        requestPreview.addView(new RequestSummaryCard(getContext(), request, false));
        requestPreview.setVisibility(VISIBLE);
    }

    /** Refresh listening status without reading into the microphone or stealing the editor focus. */
    public void status(String words, boolean speak) {
        message.setText(words);
        if (speak) { noteActivity(); narrate(words); }
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

    /** A fresh idle interval starts after each edit. Listening and background time never count. */
    public void onTextEdited() {
        narrator.stop();
        noteActivity();
    }

    private void noteActivity() {
        clearTimers();
        if (foreground && audioAvailable && !reminded && !touching) postDelayed(idle, IDLE_HINT_MS);
    }

    private void clearTimers() { idleReached = false; removeCallbacks(idle); removeCallbacks(repeat); }

    private void emphasize() {
        if (!foreground || !audioAvailable || touching) return;
        emphasized = idleReached = true;
        if (primaryAction != null && allowed.length == 0)
            primaryAction.setBackgroundTintList(ColorStateList.valueOf(0xFF245ED6));
        invalidate();
        repeatIfIdle();
    }

    private void repeatIfIdle() {
        if (!idleReached || reminded || !foreground || !audioAvailable || touching || narrator.isSpeaking()) return;
        reminded = true;
        narrator.replay(spokenWords);
    }

    public void action(int label, boolean primary, Runnable click) {
        String text = getResources().getString(label);
        Button button = primary ? ClickUi.primaryButton(getContext(), text) : ClickUi.secondaryButton(getContext(), text);
        if (primary) { button.setId(R.id.tutorial_next); primaryAction = button; }
        button.setTextSize(ClickTextScale.size(getContext(), primary ? 18 : 16));
        button.setStateListAnimator(null);
        button.setElevation(0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x32FFFFFF : 0x193478F6),
                ClickUi.rounded(primary ? 0xFF316CE4 : 0xFFEDF3FF, dp(16)), null));
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setPadding(dp(10), dp(9), dp(10), dp(9));
        button.setOnClickListener(v -> click.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, primary ? 1.2f : 1f);
        if (actions.getChildCount() > 0) params.setMarginEnd(dp(8));
        actions.addView(button, 0, params);
    }

    private TextView link(int label, Runnable action) {
        TextView view = ClickUi.body(getContext(), getResources().getString(label));
        view.setTextSize(ClickTextScale.size(getContext(), 16));
        view.setTextColor(0xFF405474);
        view.setGravity(Gravity.CENTER);
        view.setMinimumWidth(dp(48));
        view.setMinimumHeight(dp(48));
        view.setPadding(dp(8), 0, dp(8), 0);
        view.setFocusable(true);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    // Hide entire inactive branches from TalkBack and keyboard focus until the next step.
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
            view.setFocusableInTouchMode(touchFocus);
            view.setFocusable(focus);
        });
        view.setImportantForAccessibility(hasControl ? IMPORTANT_FOR_ACCESSIBILITY_NO
                : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.setFocusable(false);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        if (hasControl) {
            for (int i = 0; i < group.getChildCount(); i++) restrictToControls(group.getChildAt(i));
        } else {
            int old = group.getDescendantFocusability();
            restoreControls.add(() -> group.setDescendantFocusability(old));
            group.setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        }
    }

    private void restoreBackground() {
        for (Runnable restore : restoreControls) restore.run();
        restoreControls.clear();
    }

    private boolean localBounds(View view) {
        if (!view.isShown() || !getGlobalVisibleRect(bounds, origin) || !view.getGlobalVisibleRect(bounds)) return false;
        bounds.offset(-origin.x, -origin.y);
        return true;
    }

    private boolean updateHole() {
        if (panel == null || !localBounds(panel)) bounds.setEmpty();
        boolean moved = !panelBounds.equals(bounds);
        panelBounds.set(bounds);
        if (targetScroll != null && speaker.isLaidOut() && !panelBounds.isEmpty()) {
            int shift = Math.min(panelBounds.bottom + dp(10) - speaker.getTop(), panelBounds.top - safe.top - dp(10));
            if (shift > 0 && targetScroll.canScrollVertically(1)) {
                // Bring the highlighted control above Clue without moving Clue or animating the page.
                targetScroll.scrollBy(0, shift);
                invalidate();
                return false;
            }
        }
        boolean visible = anchor != null && localBounds(anchor);
        if (visible) {
            if (anchor.getId() == R.id.home_nav_favorites) {
                int half = Math.min(bounds.width() / 2, dp(38));
                int center = bounds.centerX();
                bounds.left = center - half;
                bounds.right = center + half;
            }
            bounds.inset(-dp(3), -dp(3));
        } else bounds.setEmpty();
        if (!moved && hole.left == bounds.left && hole.top == bounds.top
                && hole.right == bounds.right && hole.bottom == bounds.bottom) return true;
        hole.set(bounds);
        requestLayout();
        invalidate();
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int x = (int) event.getX(), y = (int) event.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            forwarded = null;
            speaker.getHitRect(bounds);
            touching = bounds.contains(x, y)
                    || (localBounds(requestPreview) && bounds.contains(x, y))
                    || (localBounds(previous) && bounds.contains(x, y))
                    || (actions.getChildCount() > 0 && localBounds(actions) && bounds.contains(x, y));
            if (!touching) {
                for (View target : allowed)
                    if (target.isEnabled() && localBounds(target) && bounds.contains(x, y)) {
                        forwarded = target;
                        touching = true;
                        break;
                    }
            }
            if (touching) clearTimers();
        }
        // Own the whole gesture. A missed tap, drag or second finger cannot reach another home action.
        if (forwarded != null) {
            View target = forwarded;
            MotionEvent copy = MotionEvent.obtain(event);
            if (action == MotionEvent.ACTION_POINTER_DOWN || !target.isShown()) copy.setAction(MotionEvent.ACTION_CANCEL);
            getLocationOnScreen(layerOrigin);
            target.getLocationOnScreen(targetOrigin);
            copy.offsetLocation(layerOrigin[0] - targetOrigin[0], layerOrigin[1] - targetOrigin[1]);
            if (copy.getActionMasked() == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) forwarded = null;
            target.dispatchTouchEvent(copy);
            copy.recycle();
        } else super.dispatchTouchEvent(event);
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && touching) {
            touching = false;
            noteActivity();
        }
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        mask.reset();
        mask.setFillType(Path.FillType.EVEN_ODD);
        mask.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        boolean roundIcon = anchor != null && anchor.getId() == R.id.home_settings;
        float radius = Math.min(Math.min(hole.width(), hole.height()) / 2f, dp(roundIcon ? 32 : 18));
        if (!hole.isEmpty()) mask.addRoundRect(hole, radius, radius, Path.Direction.CW);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(0x80FFFFFF);
        canvas.drawPath(mask, ink);
        if (!hole.isEmpty() && allowed.length > 0) {
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeWidth(dp(emphasized ? 4 : 2));
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setColor(emphasized ? 0xFF174DAA : 0xFF316BC5);
            float leg = Math.min(dp(emphasized ? 32 : 24), Math.min(hole.width(), hole.height()) / 2f);
            float bend = Math.min(radius, leg * .65f);
            corner.rewind();
            corner.moveTo(0, leg);
            corner.lineTo(0, bend);
            corner.quadTo(0, 0, bend, 0);
            corner.lineTo(leg, 0);
            drawCorner(canvas, hole.left, hole.top, 1, 1);
            drawCorner(canvas, hole.right, hole.top, -1, 1);
            drawCorner(canvas, hole.left, hole.bottom, 1, -1);
            drawCorner(canvas, hole.right, hole.bottom, -1, -1);
        }
        // The lower strip covers the inactive navigation. Leave the library tab exposed on its step.
        if (anchor == null || anchor.getId() != R.id.home_nav_favorites) {
            ink.setStyle(Paint.Style.FILL);
            ink.setColor(Color.WHITE);
            canvas.drawRect(0, footerTop - dp(8), getWidth(), getHeight(), ink);
        }
    }

    private void drawCorner(Canvas canvas, float x, float y, int sx, int sy) {
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(sx, sy);
        canvas.drawPath(corner, ink);
        canvas.restore();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        int gap = dp(12), margin = dp(16);
        int width = Math.max(1, Math.min(dp(440), w - safe.left - safe.right - margin * 2));
        int top = safe.top + gap;
        int footerBottom = h - safe.bottom - dp(6);
        footer.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, footerBottom - top), MeasureSpec.AT_MOST));
        footerTop = footerBottom - Math.max(dp(52), footer.getMeasuredHeight());
        speakerBottom = footerTop - dp(18);
        int speakerWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int available = Math.max(1, speakerBottom - top);
        int room = Math.round(available * .52f);
        if (!panelBounds.isEmpty() && panelBounds.top < speakerBottom
                && anchor != null && anchor.getId() != R.id.home_nav_favorites) {
            int panelEnd = targetScroll != null && targetScroll.canScrollVertically(1)
                    ? top + panelBounds.height() : panelBounds.bottom;
            room = Math.min(room, speakerBottom - panelEnd - gap);
        }
        speaker.measure(speakerWidth, MeasureSpec.makeMeasureSpec(Math.max(1, room), MeasureSpec.AT_MOST));
        if (requestPreview.getVisibility() == VISIBLE)
            requestPreview.measure(speakerWidth, MeasureSpec.makeMeasureSpec(
                    Math.max(1, available - speaker.getMeasuredHeight() - gap), MeasureSpec.AT_MOST));
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = speaker.getMeasuredWidth(), h = speaker.getMeasuredHeight();
        int x = safe.left + (getWidth() - safe.left - safe.right - w) / 2;
        int y = speakerBottom - h;
        speaker.layout(x, y, x + w, y + h);
        if (requestPreview.getVisibility() == VISIBLE) {
            int top = safe.top + dp(24);
            requestPreview.layout(x, top, x + w, top + requestPreview.getMeasuredHeight());
        }
        footer.layout(x, footerTop, x + footer.getMeasuredWidth(), footerTop + footer.getMeasuredHeight());
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(trackAnchor);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(trackAnchor);
        super.onDetachedFromWindow();
    }

    public void pause() { foreground = false; touching = false; clearTimers(); narrator.pause(); }
    public void resume() {
        foreground = true;
        narrator.resume();
        if (pendingNarration) narrate(spokenWords);
        noteActivity();
    }
    public void close() { pause(); narrator.close(); restoreBackground(); }
    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
