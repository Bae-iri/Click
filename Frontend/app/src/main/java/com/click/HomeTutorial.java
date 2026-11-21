package com.click;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import androidx.activity.OnBackPressedCallback;
import com.click.core.permission.PermissionStatus;
import com.click.data.local.LiveTutorialStore;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.ui.tutorial.ClueCoachView;

/** The Display lesson illustrates guidance on the phone without creating or executing a user request. */
final class HomeTutorial {
    static final String SAVED_STATE = "home_clue_tutorial";
    private enum Step { DISPLAY, SETTINGS, LIBRARY, SAVED, RECENT }

    private final HomeActivity home;
    private final LiveTutorialStore course;
    private final ClueCoachView coach;
    private final OnBackPressedCallback backCallback;
    private Step step;
    private boolean displayOpened, needsPermission;

    HomeTutorial(HomeActivity home, Bundle state) {
        this.home = home;
        course = new LiveTutorialStore(home);
        FrameLayout root = home.findViewById(R.id.home_root);
        coach = new ClueCoachView(root);
        home.showTutorialHome();
        step = Step.DISPLAY;
        if (state != null) {
            try { step = Step.valueOf(state.getString("step", Step.DISPLAY.name())); }
            catch (IllegalArgumentException oldInputLesson) { /* Start the new example. */ }
            displayOpened = state.getBoolean("display_opened");
        }
        backCallback = new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                switch (step) {
                    case SAVED: show(Step.LIBRARY); break;
                    case RECENT: show(Step.SAVED); break;
                    case LIBRARY: show(Step.SETTINGS); break;
                    default: home.closeTutorial();
                }
            }
        };
        home.getOnBackPressedDispatcher().addCallback(home, backCallback);
        if (step == Step.DISPLAY) coach.setVisibility(View.INVISIBLE);
        else show(step);
    }

    private void startDisplay() {
        if (!PermissionStatus.isAccessibilityEnabled(home)) {
            if (!needsPermission) {
                needsPermission = true;
                coach.setVisibility(View.VISIBLE);
                coach.show(home.getString(R.string.tour_display_permission),
                        com.click.designpreview.R.drawable.clue3_tutorial, null);
                coach.onContinue(home::openPermissionGuide);
            }
            return;
        }
        Intent intent = new Intent(Settings.ACTION_DISPLAY_SETTINGS);
        ComponentName page = intent.resolveActivity(home.getPackageManager());
        if (page != null) {
            displayOpened = true;
            coach.setVisibility(View.INVISIBLE);
            course.beginDisplay(page.getPackageName());
            ClickAccessibilityService.refreshDisplayTutorial();
            try {
                home.openTourDisplay(intent);
                return;
            } catch (ActivityNotFoundException | SecurityException unavailable) { endDisplay(); }
        }
        coach.setVisibility(View.VISIBLE);
        coach.show(home.getString(R.string.tour_display_unavailable),
                com.click.designpreview.R.drawable.clue3_tutorial, null);
        coach.onContinue(() -> show(Step.SETTINGS));
    }

    private void endDisplay() {
        displayOpened = false;
        course.endDisplay();
        ClickAccessibilityService.refreshDisplayTutorial();
    }

    void onDisplayResult() {
        if (step != Step.DISPLAY || !displayOpened) return;
        endDisplay();
        show(Step.SETTINGS);
    }

    private void show(Step next) {
        step = next;
        coach.setVisibility(View.VISIBLE);
        if (next == Step.SAVED || next == Step.RECENT) home.openLibraryTab(next == Step.RECENT);
        else home.showTutorialHome();
        int words, target;
        switch (next) {
            case SETTINGS: words = R.string.tour_settings; target = R.id.home_settings; break;
            case LIBRARY: words = R.string.tour_library; target = R.id.home_nav_favorites; break;
            case SAVED: words = R.string.tour_saved; target = R.id.home_library_recent; break;
            case RECENT: words = R.string.tour_recent; target = R.id.home_nav_home; break;
            default: throw new IllegalStateException("Display opens in system Settings");
        }
        View control = home.findViewById(target);
        coach.show(home.getString(words), next == Step.RECENT
                ? com.click.designpreview.R.drawable.clue4_complete : com.click.designpreview.R.drawable.clue3_tutorial,
                control, control);
        coach.bringToFront();
    }

    void openSettings() { if (step == Step.SETTINGS) home.openTourSettings(); }
    void onSettingsResult(boolean continued) { if (step == Step.SETTINGS) show(continued ? Step.LIBRARY : Step.SETTINGS); }
    void openSaved() { if (step == Step.LIBRARY) show(Step.SAVED); }
    void openRecent() { if (step == Step.SAVED) show(Step.RECENT); }
    boolean finishFromHome() {
        if (step != Step.RECENT) return false;
        home.closeTutorial();
        return true;
    }

    static Bundle afterDisplay() {
        Bundle state = new Bundle();
        state.putString("step", Step.SETTINGS.name());
        return state;
    }

    Bundle save() {
        Bundle state = new Bundle();
        state.putString("step", step.name());
        state.putBoolean("display_opened", displayOpened);
        return state;
    }
    void resume() {
        if (step == Step.DISPLAY && !displayOpened) startDisplay();
        if (coach.getVisibility() == View.VISIBLE) coach.resume();
    }
    void pause() { coach.pause(); }
    void close(boolean ending) {
        if (ending && displayOpened) endDisplay();
        backCallback.remove();
        coach.close();
        ((FrameLayout) coach.getParent()).removeView(coach);
    }
}
