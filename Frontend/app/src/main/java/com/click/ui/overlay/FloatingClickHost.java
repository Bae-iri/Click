package com.click.ui.overlay;

import android.content.Context;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** The same interactive floating control, hosted inside the app before opening another app. */
public final class FloatingClickHost extends FrameLayout implements ClickOverlayLayout.Callback {
    public interface Listener {
        void onVoiceRequested();
        void onTextSubmitted(String text);
        default void onHidden() {}
        default void onMoved() {}
    }
    private final ClickOverlayLayout control;
    private final TrashDropView trash;
    private final Listener listener;
    private boolean positioned;
    private boolean hidden;
    private boolean keyboardVisible;
    private int textRestingY;
    private final ViewTreeObserver.OnGlobalLayoutListener keyboardLayout = this::fitTextAboveKeyboard;

    public FloatingClickHost(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        setClipChildren(false);
        trash = new TrashDropView(context);
        trash.setContentDescription("여기에 놓으면 Click 숨기기");
        trash.setVisibility(GONE);
        LayoutParams trashParams = new LayoutParams(dp(112), dp(112), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        trashParams.bottomMargin = dp(18);
        addView(trash, trashParams);
        control = new ClickOverlayLayout(context);
        control.setCallback(this);
        addView(control, new LayoutParams(dp(64), dp(64), Gravity.TOP | Gravity.LEFT));
        ViewCompat.setOnApplyWindowInsetsListener(this, (view, insets) -> {
            keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            post(this::fitTextAboveKeyboard);
            return insets;
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalLayoutListener(keyboardLayout);
        ViewCompat.requestApplyInsets(this);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalLayoutListener(keyboardLayout);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        if (!positioned) {
            control.setX(Math.max(0, w - dp(80)));
            control.setY(Math.max(0, h - dp(96)));
            positioned = true;
        } else if (control.getMode() == ClickOverlayLayout.Mode.TEXT) {
            fitTextAboveKeyboard();
        } else constrain(control.getX(), control.getY());
    }

    @Override public void onModeChanged(ClickOverlayLayout.Mode oldMode, ClickOverlayLayout.Mode newMode) {
        LayoutParams params = (LayoutParams) control.getLayoutParams();
        float anchorX = control.getX() + control.getClickCenterX(oldMode, params.width);
        float anchorY = control.getY() + control.getClickCenterY(oldMode);
        params.width = Math.min(getWidth(), control.getWindowWidth(newMode, getResources().getDisplayMetrics().widthPixels));
        params.height = Math.min(getHeight(), control.getWindowHeight(newMode));
        control.setLayoutParams(params);
        constrain(anchorX - control.getClickCenterX(newMode, params.width), anchorY - control.getClickCenterY(newMode));
        if (newMode == ClickOverlayLayout.Mode.TEXT) {
            textRestingY = Math.round(control.getY());
            fitTextAboveKeyboard();
        }
        if (newMode == ClickOverlayLayout.Mode.VOICE) {
            control.collapse();
            listener.onVoiceRequested();
        }
    }

    private void fitTextAboveKeyboard() {
        if (getHeight() == 0 || control.getMode() != ClickOverlayLayout.Mode.TEXT) return;
        int availableBottom = getHeight();
        if (keyboardVisible) {
            Rect visible = new Rect();
            getWindowVisibleDisplayFrame(visible);
            int[] location = new int[2];
            getLocationOnScreen(location);
            availableBottom = Math.min(availableBottom, visible.bottom - location[1]) - dp(12);
        }
        FloatingPanelPosition placement = FloatingPanelPosition.fit(textRestingY,
                control.getWindowHeight(ClickOverlayLayout.Mode.TEXT), 0, availableBottom);
        LayoutParams params = (LayoutParams) control.getLayoutParams();
        if (params.height != placement.height) {
            params.height = placement.height;
            control.setLayoutParams(params);
        }
        control.setY(placement.y);
    }

    @Override public void onDragStarted() {
        control.animate().cancel();
        control.collapse();
        trash.setActive(false);
        trash.setVisibility(VISIBLE);
    }
    @Override public void onDragBy(float x, float y) {
        constrain(control.getX() + x, control.getY() + y);
        trash.setActive(overTrash());
    }
    @Override public void onDragEnded() {
        boolean remove = overTrash();
        trash.setVisibility(GONE);
        if (remove) onCloseRequested();
        else {
            snap();
            listener.onMoved();
        }
    }
    @Override public void onDragCancelled() {
        trash.setVisibility(GONE);
        snap();
    }
    @Override public void onCloseRequested() {
        hidden = true;
        trash.setVisibility(GONE);
        setVisibility(GONE);
        announceForAccessibility("Click 버튼을 숨겼어요");
        listener.onHidden();
    }
    @Override public void onTextSubmitted(String text) {
        control.collapse();
        listener.onTextSubmitted(text);
    }
    public void setSystemOverlayVisible(boolean visible) {
        setVisibility(hidden || visible ? GONE : VISIBLE);
    }
    public void showAgain() {
        hidden = false;
        control.collapse();
        setVisibility(VISIBLE);
    }
    /** Opens the existing voice/text menu at an in-app start button. */
    public void showMenuFrom(View anchor) {
        showAgain();
        post(() -> {
            if (!isAttachedToWindow() || !anchor.isAttachedToWindow()) return;
            int[] anchorLocation = new int[2], hostLocation = new int[2];
            anchor.getLocationOnScreen(anchorLocation);
            getLocationOnScreen(hostLocation);
            float centerX = anchorLocation[0] - hostLocation[0] + anchor.getWidth() / 2f;
            float centerY = anchorLocation[1] - hostLocation[1] + anchor.getHeight() / 3f;
            constrain(centerX - control.getClickCenterX(ClickOverlayLayout.Mode.COLLAPSED),
                    centerY - control.getClickCenterY(ClickOverlayLayout.Mode.COLLAPSED));
            positioned = true;
            control.openMenu();
        });
    }
    private boolean overTrash() {
        if (trash.getVisibility() != VISIBLE) return false;
        float cx = control.getX() + control.getClickCenterX(control.getMode(), control.getLayoutParams().width);
        float cy = control.getY() + control.getClickCenterY(control.getMode());
        return Math.hypot(cx - (trash.getX() + trash.getWidth() / 2f),
                cy - (trash.getY() + trash.getHeight() / 2f)) <= dp(68);
    }
    private void constrain(float x, float y) {
        control.setX(Math.max(0, Math.min(x, getWidth() - control.getLayoutParams().width)));
        control.setY(Math.max(0, Math.min(y, getHeight() - control.getLayoutParams().height)));
    }
    private void snap() {
        float maxX = Math.max(0, getWidth() - control.getLayoutParams().width);
        control.animate().x(control.getX() < maxX / 2f ? 0 : maxX).setDuration(180).start();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
