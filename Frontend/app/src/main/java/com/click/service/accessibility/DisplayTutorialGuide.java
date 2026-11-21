package com.click.service.accessibility;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.click.R;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenIdentity;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.ui.tutorial.ClueCoachView;
import java.util.Locale;
import java.util.Set;

/** Shows a read-only example on the Display page the tutorial opened. System Back returns to CLICK. */
final class DisplayTutorialGuide {
    private static final Set<String> TITLES = Set.of("디스플레이", "화면 및 밝기", "display", "display & brightness");
    private static final Set<String> FONT = Set.of("글자 크기와 스타일", "글자 크기 및 스타일", "글자 크기",
            "글꼴 크기", "디스플레이 크기 및 텍스트", "font size", "font size and style", "display size and text");
    private final Context context;
    private final WindowManager windows;
    final String settingsPackage;
    private FrameLayout window;
    private boolean added;
    private ClueCoachView coach;

    DisplayTutorialGuide(Context context, String settingsPackage) {
        this.context = context;
        windows = context.getSystemService(WindowManager.class);
        this.settingsPackage = settingsPackage;
    }

    void update(ScreenSnapshot screen, boolean displayActivity) {
        if (screen == null || !settingsPackage.equals(screen.packageName) || !screen.privacyMaskingReady
                || "DIALOG".equals(screen.windowContext.windowKind()) || screen.windowContext.keyboardVisible()
                || !displayActivity && !isDisplayPage(screen)) {
            close();
            return;
        }
        if (window == null) {
            window = new FrameLayout(context);
            window.setId(R.id.live_tutorial_window);
            coach = ClueCoachView.displayLesson(window);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            try { windows.addView(window, params); added = true; }
            catch (WindowManager.BadTokenException | SecurityException unavailable) { close(); return; }
        }
        Rect target = target(screen);
        coach.showExternal(context.getString(target == null ? R.string.tour_display_scroll : R.string.tour_display),
                com.click.designpreview.R.drawable.clue3_tutorial, target, null, null);
    }

    private static boolean isDisplayPage(ScreenSnapshot screen) {
        String title = ScreenIdentity.pageTitle(screen);
        return title != null && TITLES.contains(title);
    }

    private static String label(AccessibilityNodeSnapshot node) {
        String text = node.text == null || node.text.isBlank() ? node.contentDescription : node.text;
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean visible(ScreenSnapshot screen, AccessibilityNodeSnapshot node) {
        return node.visibleToUser && !node.sensitiveInput && screen.packageName.equals(node.packageName)
                && (screen.activeWindowId == null || node.windowId == screen.activeWindowId);
    }

    static Rect target(ScreenSnapshot screen) {
        Rect fallback = null;
        for (AccessibilityNodeSnapshot node : screen.nodes) {
            if (!visible(screen, node) || !node.enabled || node.bounds == null || node.bounds.length != 4) continue;
            String text = label(node);
            if (text.isEmpty() || TITLES.contains(text)) continue;
            Rect box = new Rect(node.bounds[0], node.bounds[1], node.bounds[2], node.bounds[3]);
            if (!box.intersect(0, 0, screen.screenWidth, screen.screenHeight)
                    || box.height() > screen.screenHeight / 4) continue;
            if (FONT.contains(text)) return box;
            // OEM preference titles identify an actual visible menu item, including on translated phones.
            if (fallback == null && node.resourceId != null && node.resourceId.endsWith("/title")) fallback = box;
        }
        return fallback;
    }

    void close() {
        if (coach != null) coach.close();
        if (added) windows.removeViewImmediate(window);
        added = false;
        window = null;
        coach = null;
    }
}
