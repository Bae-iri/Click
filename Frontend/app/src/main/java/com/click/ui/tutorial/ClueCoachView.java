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
import android.graphics.Outline;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.click.R;
import com.click.designpreview.ClueArtworkView;
import com.click.designpreview.TutorialNarrator;
import com.click.ui.common.ClickTextScale;
import com.click.ui.common.ClickUi;
import com.click.ui.overlay.SpeechBubbleDrawable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A floating Clue speech bubble. Only the named controls beneath its spotlight accept input. */
@SuppressLint("ViewConstructor") // Attached to an existing screen rather than inflated from XML.
public final class ClueCoachView extends FrameLayout {
    private static final long IDLE_HINT_MS = 8000;
    private final FrameLayout host;
    private final LinearLayout speaker, actions;
    private final ScrollView bubble;
    private final ClueArtworkView clue;
    private final TextView message, previous;
    private final ImageButton replay;
    private final TutorialNarrator narrator;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mask = new Path();
    private final RectF hole = new RectF();
    private final Rect panelBounds = new Rect();
    private final Rect bounds = new Rect();
    private final Point origin = new Point();
    private final List<Runnable> restoreControls = new ArrayList<>();
    private View anchor, panel;
    private View[] allowed = {};
    private Insets safe = Insets.NONE;
    private boolean below;
    private int panelTop, panelBottom;
    private boolean foreground, audioAvailable = true, emphasized, idleReached, reminded, muted, touching;
    private boolean pendingNarration;
    private View forwarded, primaryAction;
    private final int[] layerOrigin = new int[2], targetOrigin = new int[2];
    private final Runnable repeat = () -> repeatIfIdle();
    private final Runnable idle = () -> emphasize();
    private final ViewTreeObserver.OnPreDrawListener trackAnchor = this::updateHole;

    public ClueCoachView(FrameLayout host, Runnable back, Runnable close) {
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
        speaker.setGravity(Gravity.CENTER_VERTICAL);
        speaker.setClipChildren(false);
        speaker.setClipToPadding(false);
        clue = new ClueArtworkView(context);
        clue.setId(R.id.tutorial_floating_control);
        boolean narrow = getResources().getConfiguration().screenWidthDp < 360;
        speaker.addView(clue, new LinearLayout.LayoutParams(dp(narrow ? 58 : 76), dp(94)));

        bubble = new ScrollView(context);
        bubble.setFocusable(false);
        bubble.setId(R.id.tutorial_explanation_scroll);
        bubble.setBackground(new SpeechBubbleDrawable(Color.WHITE, getResources().getDisplayMetrics().density, false));
        bubble.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(dp(14), 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        bubble.setElevation(dp(5));
        bubble.setPadding(dp(27), dp(13), dp(13), dp(4));
        bubble.setClipToPadding(true);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        message = ClickUi.sectionTitle(context, "");
        message.setId(R.id.tutorial_title);
        message.setTextSize(ClickTextScale.size(context, 18));
        message.setLineSpacing(dp(2), 1.12f);
        if (Build.VERSION.SDK_INT >= 33) message.setLineBreakWordStyle(LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
        ViewCompat.setAccessibilityHeading(message, true);
        column.addView(message, new LinearLayout.LayoutParams(-1, -2));
        actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.VERTICAL);
        column.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tools = new LinearLayout(context);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        previous = link(R.string.tour_back, back);
        previous.setId(R.id.tutorial_back);
        tools.addView(previous, new LinearLayout.LayoutParams(-2, -2));
        replay = new ImageButton(context);
        replay.setId(R.id.tutorial_explanation_replay);
        replay.setImageResource(R.drawable.ic_overlay_speaker);
        replay.setBackgroundColor(Color.TRANSPARENT);
        tools.addView(replay, new LinearLayout.LayoutParams(dp(48), dp(48)));
        tools.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1));
        tools.addView(link(R.string.tour_close, close), new LinearLayout.LayoutParams(-2, -2));
        column.addView(tools, new LinearLayout.LayoutParams(-1, -2));
        bubble.addView(column);
        speaker.addView(bubble, new LinearLayout.LayoutParams(0, -2, 1));
        addView(speaker);
        narrator = new TutorialNarrator(context);
        narrator.setListener((speaking, unavailable) -> {
            replay.setContentDescription(context.getString(speaking ? R.string.tour_stop_audio : R.string.tour_replay));
            replay.setSelected(speaking);
            if (!speaking && idleReached && !reminded && foreground) {
                removeCallbacks(repeat);
                postDelayed(repeat, 900);
            }
        });
        replay.setOnClickListener(v -> {
            muted = narrator.isSpeaking();
            if (muted) narrator.stop(); else narrator.replay(message.getText().toString());
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
        clearTimers();
        emphasized = reminded = muted = false;
        primaryAction = null;
        restoreBackground();
        anchor = highlight;
        panel = highlight;
        allowed = controls;
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if (child != this) restrictToControls(child);
        }
        message.setText(words);
        clue.setImageResource(artwork);
        previous.setVisibility(canGoBack ? VISIBLE : GONE);
        actions.removeAllViews();
        bubble.scrollTo(0, 0);
        setAudioAvailable(true);
        narrate(words);
        if (anchor != null) {
            View target = anchor;
            target.post(() -> {
                if (target == anchor && target.isShown())
                    target.requestRectangleOnScreen(new Rect(0, 0, target.getWidth(), target.getHeight()), true);
            });
        }
        requestLayout();
        invalidate();
    }

    /** Keep the other parts of the highlighted control, such as its font preview, in sight. */
    public void keepPanelVisible(View view) { panel = view; requestLayout(); }

    /** Refresh listening status without reading into the microphone or stealing the editor focus. */
    public void status(String words, boolean speak) {
        message.setText(words);
        if (speak) { noteActivity(); narrate(words); }
    }

    private void narrate(String words) {
        pendingNarration = !foreground;
        if (foreground) narrator.speak(words);
    }

    public void setAudioAvailable(boolean available) {
        audioAvailable = available;
        replay.setEnabled(available);
        replay.setAlpha(available ? 1f : .45f);
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
            primaryAction.setForeground(ClickUi.outlined(Color.TRANSPARENT, 0xFF123E87, dp(22), dp(4)));
        invalidate();
        repeatIfIdle();
    }

    private void repeatIfIdle() {
        if (!idleReached || reminded || muted || !foreground || !audioAvailable || touching || narrator.isSpeaking()) return;
        reminded = true;
        narrator.replay(message.getText().toString());
    }

    public void action(int label, boolean primary, Runnable click) {
        String text = getResources().getString(label);
        Button button = primary ? ClickUi.primaryButton(getContext(), text) : ClickUi.secondaryButton(getContext(), text);
        if (primary) { button.setId(R.id.tutorial_next); primaryAction = button; }
        button.setTextSize(ClickTextScale.size(getContext(), 16));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(10), dp(9), dp(10), dp(9));
        button.setOnClickListener(v -> click.run());
        actions.addView(button, ClickUi.wrap(dp(8)));
    }

    private TextView link(int label, Runnable action) {
        TextView view = ClickUi.body(getContext(), getResources().getString(label));
        view.setTextSize(ClickTextScale.size(getContext(), 14));
        view.setTextColor(0xFF405474);
        view.setGravity(Gravity.CENTER);
        view.setMinimumWidth(dp(48));
        view.setMinimumHeight(dp(48));
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
        boolean visible = anchor != null && localBounds(anchor);
        if (visible) bounds.inset(-dp(3), -dp(3));
        else bounds.setEmpty();
        if (!moved && hole.left == bounds.left && hole.top == bounds.top
                && hole.right == bounds.right && hole.bottom == bounds.bottom) return true;
        hole.set(bounds);
        requestLayout();
        invalidate();
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int x = (int) event.getX(), y = (int) event.getY();
        if (action == MotionEvent.ACTION_DOWN) {
            forwarded = null;
            speaker.getHitRect(bounds);
            touching = bounds.contains(x, y);
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
        float radius = dp(15);
        if (!hole.isEmpty()) mask.addRoundRect(hole, radius, radius, Path.Direction.CW);
        ink.setStyle(Paint.Style.FILL);
        ink.setColor(emphasized ? 0xADFFFFFF : 0x87FFFFFF);
        canvas.drawPath(mask, ink);
        if (!hole.isEmpty()) {
            ink.setStyle(Paint.Style.STROKE);
            if (emphasized) {
                ink.setStrokeWidth(dp(9));
                ink.setColor(Color.WHITE);
                canvas.drawRoundRect(hole, radius, radius, ink);
            }
            ink.setStrokeWidth(dp(emphasized ? 6 : 3));
            ink.setColor(emphasized ? 0xFF123E87 : 0xFF2468D8);
            canvas.drawRoundRect(hole, radius, radius, ink);
        }
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        int margin = dp(12);
        int width = Math.max(1, Math.min(dp(478), w - safe.left - safe.right - margin * 2));
        int top = safe.top + margin, bottom = h - safe.bottom - margin;
        panelTop = panelBounds.top;
        panelBottom = panelBounds.bottom;
        if (Math.max(panelTop - top, bottom - panelBottom) < dp(124)) {
            panelTop = (int) hole.top;
            panelBottom = (int) hole.bottom;
        }
        int above = panelTop - margin - top, under = bottom - panelBottom - margin;
        below = under >= above;
        int room = hole.isEmpty() ? bottom - top : Math.max(above, under);
        speaker.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(1, room), MeasureSpec.AT_MOST));
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = speaker.getMeasuredWidth(), h = speaker.getMeasuredHeight(), gap = dp(12);
        int x = safe.left + (getWidth() - safe.left - safe.right - w) / 2;
        int bottom = getHeight() - safe.bottom - gap;
        int y = hole.isEmpty() ? safe.top + Math.round((bottom - safe.top - h) * .68f)
                : below ? panelBottom + gap : panelTop - gap - h;
        y = Math.max(safe.top + gap, Math.min(y, bottom - h));
        speaker.layout(x, y, x + w, y + h);
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
        if (pendingNarration) narrate(message.getText().toString());
        noteActivity();
    }
    public void close() { pause(); narrator.close(); restoreBackground(); }
    private int dp(int value) { return ClickUi.dp(getContext(), value); }
}
