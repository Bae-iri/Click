package com.click.ui.tutorial;

import android.Manifest;
import android.app.Application;
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
    private ActivityController<HomeActivity> controller;
    private HomeActivity activity;
    private TutorialNarrator narrator;

    @Before public void setup() {
        narrators = mockConstruction(TutorialNarrator.class);
        inputs = mockConstruction(DeviceSpeechInput.class);
        tokens = mockConstruction(AuthTokenStore.class);
        controller = Robolectric.buildActivity(HomeActivity.class).setup();
        activity = controller.get();
        narrator = narrators.constructed().get(0);
    }

    @After public void cleanup() {
        controller.pause().stop().destroy();
        inputs.close(); narrators.close(); tokens.close();
    }

    @Test public void inputUsesRealFloatingActionsAndActualSettingsPhotosWithoutChangingThePhone() {
        int original = ClickTextScale.level(activity);
        boolean auto = new TouchAutomationSettings(activity).isEnabled();
        click(R.id.tutorial_title);
        click(R.id.home_request_input);
        EditText editor = activity.findViewById(R.id.home_request_input);
        for (String wrong : new String[]{"밝기 올려줘", "글자 크기 줄여줘", "글자 키우지 말아줘"}) {
            editor.setText(wrong); editor.onEditorAction(EditorInfo.IME_ACTION_SEND);
            assertEquals(string(R.string.tour_retry), message());
        }
        editor.setText("글씨 좀 크게 해줘"); click(R.id.home_request_send);
        assertEquals(string(R.string.tour_review), message());
        assertEquals("글씨 좀 크게 해줘", reviewText());
        assertTrue(activity.findViewById(R.id.tutorial_practice_panel) instanceof com.click.ui.overlay.TaskFlowOverlayView);
        assertEquals("다시 입력하기", ((TextView) activity.findViewById(R.id.task_flow_edit)).getText().toString());
        click(R.id.task_flow_edit);
        assertEquals(string(R.string.tour_text), message());
        assertEquals("글씨 좀 크게 해줘", editor.getText().toString());
        editor.onEditorAction(EditorInfo.IME_ACTION_SEND);
        click(R.id.task_flow_begin);
        assertEquals(string(R.string.tour_floating), message());
        assertNotNull(activity.findViewById(R.id.task_flow_instruction));
        click(R.id.tutorial_title);
        assertEquals(string(R.string.tour_actual_screen), message());
        assertTrue(activity.findViewById(R.id.tutorial_settings_photo) instanceof com.click.ui.tutorial.SettingsScreenshotPracticeView);
        click(R.id.tutorial_settings_photo_target);
        assertEquals(string(R.string.tour_font_photo), message());
        click(R.id.tutorial_settings_photo_target);
        assertEquals(string(R.string.tour_result), message());
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved).pause().stop().destroy();
        controller = Robolectric.buildActivity(HomeActivity.class).setup(saved); activity = controller.get();
        assertEquals(string(R.string.tour_result), message());
        click(R.id.task_flow_result_satisfied);
        assertEquals(string(R.string.tour_settings), message());
        assertEquals(original, ClickTextScale.level(activity));
        assertEquals(auto, new TouchAutomationSettings(activity).isEnabled());
        assertTrue(new RequestLibraryStore(activity).entries(false).isEmpty());
        assertNull(shadowOf(activity).getNextStartedService());
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

    @Test public void speechErrorsKeepRetryAvailableAndValidSpeechUsesTheRealReview() {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        click(R.id.tutorial_title); click(R.id.home_microphone); click(R.id.overlay_microphone);
        DeviceSpeechInput device = inputs.constructed().get(0);
        var callback = ArgumentCaptor.forClass(SpeechInput.Callback.class);
        verify(device).start(callback.capture());
        callback.getValue().onFailure(new SecurityException()); assertTrue(message().contains("마이크"));
        click(R.id.overlay_microphone); verify(device, times(2)).start(callback.capture());
        callback.getValue().onSuccess("글자 크기 키워줘");
        assertEquals(string(R.string.tour_review), message());
        assertEquals("글자 크기 키워줘", reviewText());
        assertTrue(activity.findViewById(R.id.task_flow_begin).isShown());
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
    private String reviewText() {
        TextView summary = activity.getWindow().getDecorView().findViewWithTag("review_summary_text");
        return summary.getText().toString();
    }
}
