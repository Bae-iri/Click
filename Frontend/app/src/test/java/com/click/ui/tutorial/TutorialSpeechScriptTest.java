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
import com.click.TutorialActivity;
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
    private ActivityController<TutorialActivity> controller;
    private TutorialActivity activity;
    private TutorialNarrator narrator;

    @Before public void setup() {
        narrators = mockConstruction(TutorialNarrator.class);
        inputs = mockConstruction(DeviceSpeechInput.class);
        tokens = mockConstruction(AuthTokenStore.class);
        controller = Robolectric.buildActivity(TutorialActivity.class).setup();
        activity = controller.get();
        narrator = narrators.constructed().get(0);
    }

    @After public void cleanup() {
        controller.pause().stop().destroy();
        inputs.close(); narrators.close(); tokens.close();
    }

    @Test public void textAndFontPracticeRequireTheRequestedActionAndKeepRealSettingsAndHistory() {
        int original = ClickTextScale.level(activity);
        boolean auto = new TouchAutomationSettings(activity).isEnabled();
        click(R.id.tutorial_next);
        click(R.id.home_request_input);
        EditText editor = activity.findViewById(R.id.home_request_input);
        for (String wrong : new String[]{"밝기 올려줘", "글자 크기 줄여줘", "글자 키우지 말아줘"}) {
            editor.setText(wrong);
            editor.onEditorAction(EditorInfo.IME_ACTION_SEND);
            assertEquals(string(R.string.tour_retry), message());
        }
        editor.setText("글씨 좀 크게 해줘");
        click(R.id.home_request_send);
        assertTrue(message().contains("글씨 좀 크게 해줘"));
        click(R.id.tutorial_next);
        TextView sample = activity.findViewById(R.id.tutorial_font_preview);
        float before = sample.getTextSize();
        assertEquals(string(R.string.tour_adjust), message());
        click(R.id.tutorial_increase);
        assertTrue(sample.getTextSize() > before);
        assertEquals(string(R.string.tour_result), message());
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved).pause().stop().destroy();
        controller = Robolectric.buildActivity(TutorialActivity.class).setup(saved);
        activity = controller.get();
        assertEquals(string(R.string.tour_result), message());
        assertTrue(((TextView) activity.findViewById(R.id.tutorial_font_preview)).getTextSize() > before);
        click(R.id.tutorial_next);
        assertEquals(string(R.string.tour_settings), message());
        assertEquals(original, ClickTextScale.level(activity));
        assertEquals(auto, new TouchAutomationSettings(activity).isEnabled());
        assertTrue(new RequestLibraryStore(activity).entries(false).isEmpty());
        assertNull(shadowOf(activity).getNextStartedService());
    }

    @Test public void idleEmphasizesAndRepeatsExactlyOncePerStepAndNeverWhilePaused() {
        String welcome = string(R.string.tour_welcome);
        View start = activity.findViewById(R.id.tutorial_next);
        waitSeconds(7);
        verify(narrator, never()).replay(anyString());
        assertNull(start.getForeground());
        waitSeconds(1);
        assertNotNull(start.getForeground());
        waitSeconds(30);
        verify(narrator, times(1)).replay(welcome);
        click(R.id.tutorial_next);
        controller.pause();
        waitSeconds(20);
        verify(narrator, never()).replay(string(R.string.tour_pick));
        controller.resume();
        waitSeconds(8);
        verify(narrator).replay(string(R.string.tour_pick));
    }

    @Test public void typingPostponesReminderAndNewStepCancelsTheOldTimer() {
        click(R.id.tutorial_next);
        waitSeconds(6);
        click(R.id.home_request_input);
        waitSeconds(6);
        ((EditText) activity.findViewById(R.id.home_request_input)).setText("글자");
        waitSeconds(6);
        verify(narrator, never()).replay(anyString());
        waitSeconds(2);
        verify(narrator).replay(string(R.string.tour_text));
    }

    @Test public void narrationFinishingDuringTypingCannotTriggerAnEarlyReminder() {
        click(R.id.tutorial_next);
        click(R.id.home_request_input);
        when(narrator.isSpeaking()).thenReturn(true);
        waitSeconds(8);
        verify(narrator, never()).replay(anyString());
        ((EditText) activity.findViewById(R.id.home_request_input)).setText("글");
        ArgumentCaptor<TutorialNarrator.Listener> listener = ArgumentCaptor.forClass(TutorialNarrator.Listener.class);
        verify(narrator).setListener(listener.capture());
        when(narrator.isSpeaking()).thenReturn(false);
        listener.getValue().changed(false, false);
        waitSeconds(7);
        verify(narrator, never()).replay(anyString());
        waitSeconds(1);
        verify(narrator).replay(string(R.string.tour_text));
    }

    @Test public void voiceStopsNarrationDuringCaptureAndRejectsLateResultsAfterBack() {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        click(R.id.tutorial_next);
        click(R.id.home_microphone);
        assertEquals(string(R.string.tour_voice), message());
        assertTrue(inputs.constructed().isEmpty());
        click(R.id.overlay_microphone);
        DeviceSpeechInput device = inputs.constructed().get(0);
        ArgumentCaptor<SpeechInput.Callback> callback = ArgumentCaptor.forClass(SpeechInput.Callback.class);
        verify(device).start(callback.capture());
        assertEquals(string(R.string.tour_listening), message());
        waitSeconds(12);
        verify(narrator, never()).replay(anyString());
        click(R.id.overlay_microphone);
        verify(device).finish();
        callback.getValue().onRecordingFinished();
        waitSeconds(9);
        verify(narrator, never()).replay(anyString());
        click(R.id.tutorial_back);
        callback.getValue().onSuccess("글자 크기 키워줘");
        assertEquals(string(R.string.tour_pick), message());
        assertNull(shadowOf(activity).getNextStartedService());
    }

    @Test public void recognitionErrorOffersRetryAndTextAndValidSpeechGoesToReview() {
        shadowOf(activity.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
        click(R.id.tutorial_next);
        click(R.id.home_microphone);
        click(R.id.overlay_microphone);
        DeviceSpeechInput device = inputs.constructed().get(0);
        ArgumentCaptor<SpeechInput.Callback> callback = ArgumentCaptor.forClass(SpeechInput.Callback.class);
        verify(device).start(callback.capture());
        callback.getValue().onFailure(new SecurityException());
        assertTrue(message().contains("마이크"));
        click(R.id.overlay_microphone);
        verify(device, times(2)).start(callback.capture());
        callback.getValue().onSuccess("글자 크기 키워줘");
        assertEquals(activity.getString(R.string.tour_review, "글자 크기 키워줘"), message());
    }

    private void waitSeconds(int seconds) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds)); }
    private void click(int id) { assertTrue(activity.findViewById(id).performClick()); }
    private String string(int id) { return activity.getString(id); }
    private String message() { return ((TextView) activity.findViewById(R.id.tutorial_title)).getText().toString(); }
}
