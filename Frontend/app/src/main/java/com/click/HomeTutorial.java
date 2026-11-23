package com.click;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.EditText;
import androidx.activity.OnBackPressedCallback;
import com.click.core.permission.PermissionStatus;
import com.click.data.local.LiveTutorialStore;
import com.click.designpreview.AccessibilityConnectionWatcher;
import com.click.domain.goal.GoalCommandInterpreter;
import com.click.domain.goal.RequirementReview;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.ui.tutorial.ClueCoachView;

/** Practice a request on Home before following the guide on the phone's Display screen. */
final class HomeTutorial {
    static final String SAVED_STATE = "home_clue_tutorial";
    private enum Step { INPUT, VOICE, DISPLAY, SETTINGS, LIBRARY, SAVED, RECENT }

    private final HomeActivity home;
    private final LiveTutorialStore course;
    private final AccessibilityConnectionWatcher displayConnection;
    private final ClueCoachView coach;
    private final OnBackPressedCallback backCallback;
    private final EditText input;
    private final String originalDraft;
    private final int originalCursor;
    private Step step;
    private HomeTutorialVoice voice;
    private boolean showingSend;
    private boolean displayOpened, needsPermission;

    HomeTutorial(HomeActivity home, Bundle state) {
        this.home = home;
        course = new LiveTutorialStore(home);
        displayConnection = new AccessibilityConnectionWatcher(home);
        FrameLayout root = home.findViewById(R.id.home_root);
        coach = new ClueCoachView(root);
        input = home.findViewById(R.id.home_request_input);
        originalDraft = state == null ? input.getText().toString() : state.getString("home_draft", input.getText().toString());
        originalCursor = state == null ? input.getSelectionEnd() : state.getInt("home_cursor", input.getSelectionEnd());
        home.showTutorialHome();
        step = Step.INPUT;
        if (state != null) {
            try { step = Step.valueOf(state.getString("step", Step.INPUT.name())); }
            catch (IllegalArgumentException oldStep) { /* WELCOME and TEXT both resume at the home editor. */ }
            displayOpened = state.getBoolean("display_opened");
        }
        if (state != null) restoreEditor(state);
        else setEditor("", 0);
        backCallback = new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                switch (step) {
                    case VOICE: show(Step.INPUT); break;
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

    private void setEditor(String text, int cursor) {
        if (!text.contentEquals(input.getText())) input.setText(text);
        input.setSelection(Math.max(0, Math.min(cursor, input.length())));
    }

    void restoreEditor(Bundle state) {
        if (step == Step.INPUT || step == Step.VOICE) {
            String draft = state.getString("practice_draft", "");
            setEditor(draft, state.getInt("practice_cursor", draft.length()));
        } else if (state.containsKey("home_draft")) {
            setEditor(originalDraft, originalCursor);
        }
    }

    private void showInput(boolean canSend) {
        showingSend = canSend;
        View send = home.findViewById(R.id.home_request_send);
        View mic = home.findViewById(R.id.home_microphone);
        View card = home.findViewById(R.id.home_start);
        coach.show(home.getString(showingSend ? R.string.tour_send : R.string.tour_input),
                com.click.designpreview.R.drawable.clue3_tutorial, showingSend ? send : card, input, mic, send);
        coach.keepPanelVisible(card);
    }

    void onInputChanged() {
        if (step != Step.INPUT) return;
        boolean canSend = matchesExample();
        if (showingSend != canSend) showInput(canSend);
    }

    void chooseVoice() { if (step == Step.INPUT) show(Step.VOICE); }
    void onMicrophonePermission(boolean granted) { if (voice != null) voice.onPermission(granted); }

    private boolean matchesExample() {
        String words = input.getText().toString().trim();
        RequirementReview example = new RequirementReview(words);
        return example.requestsLargerText() && !example.appOnly() && !GoalCommandInterpreter.requiresContextualInterpretation(words);
    }

    void submitInput() {
        if (step != Step.INPUT) return;
        if (!matchesExample()) {
            coach.status(home.getString(R.string.tour_input_retry), true);
            return;
        }
        step = Step.DISPLAY;
        home.hideKeyboard();
        setEditor(originalDraft, originalCursor);
        startDisplay();
    }

    private void startDisplay() {
        if (!PermissionStatus.isAccessibilityEnabled(home)) {
            if (!needsPermission) showDisplayHelp(R.string.tour_display_permission);
            return;
        }
        if (ClickAccessibilityService.accessibilityWindowManager() != null) { openDisplay(); return; }
        coach.setVisibility(View.VISIBLE);
        coach.show(home.getString(R.string.tour_display_connecting),
                com.click.designpreview.R.drawable.clue3_tutorial, null);
        displayConnection.start(this::openDisplay, () -> showDisplayHelp(R.string.tour_display_reconnect));
    }

    private void showDisplayHelp(int message) {
        needsPermission = true;
        coach.setVisibility(View.VISIBLE);
        coach.show(home.getString(message), com.click.designpreview.R.drawable.clue3_tutorial, null);
        coach.onContinue(home::openPermissionGuide);
    }

    private void openDisplay() {
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
        if (voice != null) { voice.close(); voice = null; }
        step = next;
        coach.setVisibility(View.VISIBLE);
        if (next == Step.INPUT) { showInput(matchesExample()); return; }
        if (next == Step.VOICE) {
            home.hideKeyboard();
            voice = new HomeTutorialVoice(home, coach, () -> show(Step.INPUT), words -> {
                setEditor(words, words.length());
                show(Step.INPUT);
            });
            return;
        }
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
        state.putString("home_draft", originalDraft);
        state.putInt("home_cursor", originalCursor);
        if (step == Step.INPUT || step == Step.VOICE) {
            state.putString("practice_draft", input.getText().toString());
            state.putInt("practice_cursor", input.getSelectionEnd());
        }
        return state;
    }
    void resume() {
        if (step == Step.DISPLAY && !displayOpened) startDisplay();
        if (coach.getVisibility() == View.VISIBLE) coach.resume();
    }
    void pause() {
        displayConnection.stop();
        if (voice != null) voice.pause();
        coach.pause();
    }
    void close(boolean ending) {
        displayConnection.stop();
        if (ending && displayOpened) endDisplay();
        if (voice != null) voice.close();
        if (step == Step.INPUT || step == Step.VOICE) setEditor(originalDraft, originalCursor);
        backCallback.remove();
        coach.close();
        ((FrameLayout) coach.getParent()).removeView(coach);
    }
}
