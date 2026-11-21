package com.click.ui.tutorial;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.view.WindowManager;
import com.click.core.permission.PermissionStatus;
import com.click.service.accessibility.ClickAccessibilityService;
import com.click.service.overlay.ClickOverlayService;
import com.click.data.local.LiveTutorialStore;
import org.mockito.MockedStatic;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import com.click.R;
import com.click.HomeActivity;
import com.click.data.local.AuthTokenStore;
import com.click.data.local.RequestLibraryStore;
import com.click.data.local.TouchAutomationSettings;
import com.click.designpreview.TutorialNarrator;
import com.click.service.overlay.DeviceSpeechInput;
import com.click.service.overlay.SpeechInput;
import com.click.ui.common.ClickTextScale;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TutorialSpeechScriptTest {
    private MockedConstruction<TutorialNarrator> narrators;
    private MockedConstruction<DeviceSpeechInput> inputs;
    private MockedConstruction<AuthTokenStore> tokens;
    private MockedStatic<PermissionStatus> permissions;
    private MockedStatic<ClickAccessibilityService> accessibility;
    private ActivityController<HomeActivity> controller;
    private HomeActivity activity;
    private TutorialNarrator narrator;

    @Before public void setup() {
        narrators = mockConstruction(TutorialNarrator.class);
        inputs = mockConstruction(DeviceSpeechInput.class);
        tokens = mockConstruction(AuthTokenStore.class);
        permissions = mockStatic(PermissionStatus.class);
        permissions.when(() -> PermissionStatus.isAccessibilityEnabled(any())).thenReturn(true);
        accessibility = mockStatic(ClickAccessibilityService.class);
        accessibility.when(ClickAccessibilityService::accessibilityWindowManager).thenReturn(mock(WindowManager.class));
        controller = Robolectric.buildActivity(HomeActivity.class).setup();
        activity = controller.get();
        narrator = narrators.constructed().get(0);
    }

    @After public void cleanup() {
        controller.pause().stop().destroy();
        inputs.close(); narrators.close(); tokens.close(); accessibility.close(); permissions.close();
    }

    @Test public void inputStartsTheRealServiceWithTheOriginalRequestAndNoSimulatedResult() {
        boolean auto = new TouchAutomationSettings(activity).isEnabled();
        click(R.id.tutorial_title); click(R.id.home_request_input);
        EditText editor = activity.findViewById(R.id.home_request_input);
        for (String wrong : new String[]{"밝기 올려줘", "글자 크기 줄여줘", "글자 키우지 말아줘"}) {
            editor.setText(wrong); editor.onEditorAction(EditorInfo.IME_ACTION_SEND);
            assertEquals(string(R.string.tour_retry), message());
            assertNull(shadowOf(activity).getNextStartedService());
        }
        editor.setText("글씨 좀 크게 해줘"); click(R.id.home_request_send);
        Intent actual = shadowOf(activity).getNextStartedService();
        assertEquals(ClickOverlayService.class.getName(), actual.getComponent().getClassName());
        assertEquals("글씨 좀 크게 해줘", actual.getStringExtra(ClickOverlayService.EXTRA_INITIAL_GOAL));
        var course = new LiveTutorialStore(activity);
        assertTrue(course.accepts(actual.getStringExtra(ClickOverlayService.EXTRA_LIVE_TUTORIAL_ID), "글씨 좀 크게 해줘"));
        assertFalse(course.awaitingHomeTour());
        assertNull(activity.findViewById(R.id.tutorial_floating_host));
        assertEquals(auto, new TouchAutomationSettings(activity).isEnabled());
    }

    @Test public void onlyAnAcknowledgedRealTaskResumesTheSettingsAndLibraryTour() {
        activity.getOnBackPressedDispatcher().onBackPressed();
        var course = new LiveTutorialStore(activity);
        String launch = course.begin("글자 크기 키워줘");
        course.bindRequest(launch, "글자 크기 키워줘", "real-request");
        course.bindSession("real-request", "real-session");
        assertFalse(course.awaitingHomeTour());
        assertTrue(course.finish("real-request", "real-session", true));
        controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(HomeActivity.class).setup(); activity = controller.get();
        assertEquals(string(R.string.tour_settings), message());
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved).pause().stop().destroy();
        controller = Robolectric.buildActivity(HomeActivity.class).setup(saved); activity = controller.get();
        assertEquals(string(R.string.tour_settings), message());
        assertNull(shadowOf(activity).getNextStartedService());
        activity.getOnBackPressedDispatcher().onBackPressed();
        assertFalse(course.awaitingHomeTour());
    }

    @Test public void missingAccessibilityKeepsTheRequestUntilTheActualServiceCanStart() {
        permissions.when(() -> PermissionStatus.isAccessibilityEnabled(any())).thenReturn(false);
        click(R.id.tutorial_title); click(R.id.home_request_input);
        ((EditText) activity.findViewById(R.id.home_request_input)).setText("글자 크기 키워줘");
        click(R.id.home_request_send);
        assertNull(shadowOf(activity).getNextStartedService());
        assertFalse(new LiveTutorialStore(activity).running());
        assertNotNull(activity.findViewById(R.id.tutorial_floating_host));
        assertEquals("글자 크기 키워줘", ((EditText) activity.findViewById(R.id.home_request_input)).getText().toString());
    }

    @Test public void restoringAnOldPhotoPracticeRequiresARealRequestInsteadOfClaimingCompletion() {
        Bundle old = new Bundle(), saved = new Bundle();
        old.putString("step", "RESULT"); old.putString("practice_draft", "글자 크기 키워줘");
        saved.putBundle("home_clue_tutorial", old);
        controller.pause().stop().destroy();
        controller = Robolectric.buildActivity(HomeActivity.class).setup(saved); activity = controller.get();
        assertEquals(string(R.string.tour_text), message());
        assertFalse(new LiveTutorialStore(activity).awaitingHomeTour());
        assertNull(shadowOf(activity).getNextStartedService());
    }

    @Test public void reopeningALiveTutorialResumesItsOwnedRequestWithoutEnqueuingAnother() {
        var course = new LiveTutorialStore(activity);
        String launch = course.begin("글자 크기 키워줘");
        course.bindRequest(launch, "글자 크기 키워줘", "owned-request");
        course.bindSession("owned-request", "owned-session");
        controller.newIntent(new Intent(activity, HomeActivity.class).putExtra("com.click.extra.START_HOME_TUTORIAL", true));
        Intent resume = shadowOf(activity).getNextStartedService();
        assertEquals("owned-request", resume.getStringExtra(ClickOverlayService.EXTRA_RESUME_REQUEST_ID));
        assertFalse(resume.hasExtra(ClickOverlayService.EXTRA_INITIAL_GOAL));
        assertFalse(resume.hasExtra(ClickOverlayService.EXTRA_LIVE_TUTORIAL_ID));
        assertTrue(course.presents("owned-request", "owned-session"));
    }

    @Test public void coachHasRegularTypeAndNoNavigationButtons() {
        var coach = (android.view.ViewGroup) activity.findViewById(R.id.tutorial_floating_host);
        assertNull(findButton(coach));
        assertNull(activity.findViewById(R.id.tutorial_next));
        assertNull(activity.findViewById(R.id.tutorial_back));
        TextView message = activity.findViewById(R.id.tutorial_title);
        assertEquals(activity.getResources().getFont(com.click.designpreview.R.font.pretendard_regular), message.getTypeface());
        assertTrue(message.performClick());
        assertEquals(string(R.string.tour_pick), message());
    }

    @Test public void idleReminderWaitsThirtySecondsAfterNarrationEndsAndOnlyRepeatsOnce() {
        String welcome = string(R.string.tour_welcome);
        when(narrator.isSpeaking()).thenReturn(true);
        waitSeconds(45);
        verify(narrator, never()).replay(anyString());
        var listener = ArgumentCaptor.forClass(TutorialNarrator.Listener.class);
        verify(narrator).setListener(listener.capture());
        when(narrator.isSpeaking()).thenReturn(false);
        listener.getValue().changed(false, false);
        waitSeconds(29); verify(narrator, never()).replay(anyString());
        waitSeconds(1); verify(narrator).replay(welcome);
        waitSeconds(90); verify(narrator, times(1)).replay(welcome);
        click(R.id.tutorial_title); controller.pause();
        waitSeconds(40); verify(narrator, never()).replay(string(R.string.tour_pick));
        controller.resume(); waitSeconds(30); verify(narrator).replay(string(R.string.tour_pick));
    }

    @Test public void typingAndNarrationRestartTheFullIdleInterval() {
        click(R.id.tutorial_title); click(R.id.home_request_input);
        waitSeconds(25);
        ((EditText) activity.findViewById(R.id.home_request_input)).setText("글자");
        waitSeconds(29); verify(narrator, never()).replay(anyString());
        waitSeconds(1); verify(narrator).replay(string(R.string.tour_text));
    }

    @Test public void voiceStopsNarrationAndIgnoresLateSpeechAfterSystemBack() {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        click(R.id.tutorial_title); click(R.id.home_microphone); click(R.id.overlay_microphone);
        DeviceSpeechInput device = inputs.constructed().get(0);
        var callback = ArgumentCaptor.forClass(SpeechInput.Callback.class);
        verify(device).start(callback.capture());
        assertEquals(string(R.string.tour_listening), message());
        waitSeconds(40); verify(narrator, never()).replay(anyString());
        click(R.id.overlay_microphone); verify(device).finish(); callback.getValue().onRecordingFinished();
        waitSeconds(40); verify(narrator, never()).replay(anyString());
        activity.getOnBackPressedDispatcher().onBackPressed();
        callback.getValue().onSuccess("글자 크기 키워줘");
        assertEquals(string(R.string.tour_pick), message());
        assertNull(shadowOf(activity).getNextStartedService());
    }

    @Test public void speechErrorsKeepRetryAvailableAndValidSpeechStartsTheRealRequest() {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        click(R.id.tutorial_title); click(R.id.home_microphone); click(R.id.overlay_microphone);
        DeviceSpeechInput device = inputs.constructed().get(0);
        var callback = ArgumentCaptor.forClass(SpeechInput.Callback.class);
        verify(device).start(callback.capture());
        callback.getValue().onFailure(new SecurityException()); assertTrue(message().contains("마이크"));
        click(R.id.overlay_microphone); verify(device, times(2)).start(callback.capture());
        callback.getValue().onSuccess("글자 크기 키워줘");
        Intent actual = shadowOf(activity).getNextStartedService();
        assertNotNull(actual);
        assertEquals("글자 크기 키워줘", actual.getStringExtra(ClickOverlayService.EXTRA_INITIAL_GOAL));
        assertNotNull(actual.getStringExtra(ClickOverlayService.EXTRA_LIVE_TUTORIAL_ID));
        assertNull(activity.findViewById(R.id.tutorial_floating_host));
        assertNull(activity.findViewById(R.id.tutorial_next));
    }

    private View findButton(View view) {
        if (view instanceof android.widget.Button) return view;
        if (view instanceof android.view.ViewGroup) {
            var group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { var found = findButton(group.getChildAt(i)); if (found != null) return found; }
        }
        return null;
    }
    private void waitSeconds(int seconds) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds)); }
    private void click(int id) { assertTrue(activity.findViewById(id).performClick()); }
    private String string(int id) { return activity.getString(id); }
    private String message() { return ((TextView) activity.findViewById(R.id.tutorial_title)).getText().toString(); }
}
