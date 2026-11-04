package com.click.ui.tutorial;

import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.click.R;
import com.click.TutorialActivity;
import com.click.designpreview.TutorialNarrator;
import com.click.service.overlay.DeviceSpeechInput;
import com.click.ui.overlay.ClickOverlayLayout;
import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
    private ActivityController<TutorialActivity> controller;
    private TutorialActivity activity;
    private TutorialNarrator narrator;

    @Before public void setup() {
        narrators = mockConstruction(TutorialNarrator.class);
        inputs = mockConstruction(DeviceSpeechInput.class);
        controller = Robolectric.buildActivity(TutorialActivity.class).setup();
        activity = controller.get();
        narrator = narrators.constructed().get(0);
    }

    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
        if (inputs != null) inputs.close();
        if (narrators != null) narrators.close();
    }

    @Test public void introductionAndReplayReadTheAuthoredScriptInsteadOfTheScreenCopy() {
        String script = activity.getString(R.string.tutorial_speech_intro);
        verify(narrator).speak(script);
        TextView title = activity.findViewById(R.id.tutorial_title);
        assertEquals("클릭을 함께 연습해요", title.getText().toString());
        assertFalse(script.contains("click의 기능은 다음과 같아요"));
        title.setText("화면 제목만 수정한 경우");
        find("안내 다시 듣기").performClick();
        verify(narrator, times(1)).speak(script);
        verify(narrator).replay(script);
        verify(narrator, never()).speak("화면 제목만 수정한 경우");
    }

    @Test public void requestReviewAndBothReplayButtonsUseTheTutorialScript() {
        clearInvocations(narrator);
        activity.onTextSubmitted("이 화면은 어떻게 사용해요?");
        String ready = activity.getString(R.string.tutorial_speech_review_ready);
        verify(narrator).speak(ready);
        find("다시 듣기").performClick();
        find("안내 다시 듣기").performClick();
        verify(narrator, times(1)).speak(ready);
        verify(narrator, times(2)).replay(ready);
        find("안내 시작하기").performClick();
        verify(narrator).speak(activity.getString(R.string.tutorial_speech_running));
    }

    @Test public void keyboardDefersTheScriptUntilTheUserCanHearTheNextInstruction() throws Exception {
        clearInvocations(narrator);
        set("keyboardVisible", true);
        activity.onTextSubmitted("이 화면은 어떻게 사용해요?");
        verify(narrator, never()).speak(anyString());
        set("keyboardVisible", false);
        updateCopy();
        updateCopy();
        verify(narrator).speak(activity.getString(R.string.tutorial_speech_review_ready));
    }

    @Test public void voiceInstructionsTeachHoldingAndReleasingWithoutAddingAStep() throws Exception {
        clearInvocations(narrator);
        set("step", 0);
        ((ClickOverlayLayout) activity.findViewById(R.id.tutorial_floating_control)).openVoice();
        updateCopy();
        String script = activity.getString(R.string.tutorial_speech_voice_record);
        verify(narrator).speak(script);
        find("안내 다시 듣기").performClick();
        verify(narrator).replay(script);
        activity.onTextSubmitted("휴대폰 전체 글자 크기 키워줘");
        verify(narrator).speak(activity.getString(R.string.tutorial_speech_review_ready));
    }

    @Test public void releasingMicrophoneAdvancesWithoutMinimumHoldTimeOrRecording() {
        activity.findViewById(R.id.tutorial_next).performClick();
        ClickOverlayLayout control = activity.findViewById(R.id.tutorial_floating_control);
        control.openVoice();
        activity.onMicrophonePressed();
        assertTrue(control.isVoiceInputActive());
        assertEquals("마이크를 꾹 눌러보세요", ((TextView) activity.findViewById(R.id.tutorial_title)).getText());
        activity.onMicrophoneReleased();
        assertFalse(control.isVoiceInputActive());
        assertNotNull(find("안내 시작하기"));
        assertTrue("Practice must not create a microphone or STT client", inputs.constructed().isEmpty());
    }

    @Test public void cancelledGestureAndBackgroundNeverSubmitARequest() {
        activity.findViewById(R.id.tutorial_next).performClick();
        ClickOverlayLayout control = activity.findViewById(R.id.tutorial_floating_control);
        control.openVoice();
        activity.onMicrophonePressed();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500));
        activity.onMicrophoneCancelled();
        activity.onMicrophoneReleased();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        assertEquals("마이크를 꾹 눌러보세요", ((TextView) activity.findViewById(R.id.tutorial_title)).getText());
        activity.onMicrophonePressed();
        controller.pause().stop();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
        controller.start().resume();
        activity.onMicrophoneReleased();
        assertFalse(control.isVoiceInputActive());
        assertEquals("마이크를 꾹 눌러보세요", ((TextView) activity.findViewById(R.id.tutorial_title)).getText());
    }

    private void updateCopy() throws Exception {
        java.lang.reflect.Method method = TutorialActivity.class.getDeclaredMethod("updateCopy");
        method.setAccessible(true); method.invoke(activity);
    }

    private void set(String name, Object value) throws Exception {
        Field field = TutorialActivity.class.getDeclaredField(name);
        field.setAccessible(true); field.set(activity, value);
    }

    private TextView find(String label) {
        TextView result = find(activity.getWindow().getDecorView(), label);
        assertNotNull(label, result);
        return result;
    }

    private TextView find(View view, String label) {
        if (view instanceof TextView && (label.contentEquals(((TextView) view).getText())
                || "다시 듣기".equals(label) && "클릭 음성 안내 다시 듣기".contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription()))) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView result = find(((ViewGroup) view).getChildAt(i), label);
            if (result != null) return result;
        }
        return null;
    }
}
