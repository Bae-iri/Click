package com.click.ui.tutorial;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.click.HomeActivity;
import com.click.R;
import com.click.core.permission.PermissionStatus;
import com.click.data.local.LiveTutorialStore;
import com.click.designpreview.TutorialNarrator;
import com.click.service.overlay.DeviceSpeechInput;
import com.click.service.overlay.SpeechInput;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
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
    private final Application app = RuntimeEnvironment.getApplication();
    private MockedConstruction<TutorialNarrator> voices;
    private MockedConstruction<com.click.data.local.AuthTokenStore> tokens;
    private MockedStatic<PermissionStatus> permissions;
    private MockedStatic<com.click.service.accessibility.ClickAccessibilityService> accessibility;
    private ActivityController<HomeActivity> controller;

    @Before public void setup() {
        tokens = mockConstruction(com.click.data.local.AuthTokenStore.class);
        voices = mockConstruction(TutorialNarrator.class);
        permissions = mockStatic(PermissionStatus.class);
        accessibility = mockStatic(com.click.service.accessibility.ClickAccessibilityService.class);
        accessibility.when(com.click.service.accessibility.ClickAccessibilityService::accessibilityWindowManager)
                .thenReturn(mock(android.view.WindowManager.class));
        permissions.when(() -> PermissionStatus.isAccessibilityEnabled(any())).thenReturn(true);
        ResolveInfo display = new ResolveInfo();
        display.activityInfo = new ActivityInfo();
        display.activityInfo.packageName = "com.android.settings";
        display.activityInfo.name = "com.android.settings.Settings$DisplaySettingsActivity";
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(Settings.ACTION_DISPLAY_SETTINGS), display);
    }

    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
        accessibility.close();
        permissions.close();
        voices.close();
        tokens.close();
    }

    private HomeActivity start(Bundle saved) {
        controller = Robolectric.buildActivity(HomeActivity.class);
        if (saved == null) controller.setup(); else controller.setup(saved);
        return controller.get();
    }

    @Test public void firstEntryNeedsHomeInputAndSendBeforeDisplayAndBackContinuesTheLesson() {
        HomeActivity home = start(null);
        TextView guide = home.findViewById(R.id.tutorial_title);
        EditText editor = home.findViewById(R.id.home_request_input);
        assertEquals(home.getString(R.string.tour_input), guide.getText().toString());
        assertNull(shadowOf(home).getNextStartedActivityForResult());
        editor.setText("글자");
        assertEquals(home.getString(R.string.tour_input), guide.getText().toString());
        editor.setText("밝기 줄여줘");
        home.findViewById(R.id.home_request_send).performClick();
        assertEquals(home.getString(R.string.tour_input_retry), guide.getText().toString());
        assertNull(shadowOf(home).getNextStartedActivityForResult());
        editor.setText("");
        editor.setText("글자 크기 키워줘");
        assertEquals(home.getString(R.string.tour_send), guide.getText().toString());
        assertNull(shadowOf(home).getNextStartedActivityForResult());
        editor.onEditorAction(EditorInfo.IME_ACTION_SEND);
        var launch = shadowOf(home).getNextStartedActivityForResult();
        assertEquals(Settings.ACTION_DISPLAY_SETTINGS, launch.intent.getAction());
        var course = new LiveTutorialStore(home);
        assertEquals("com.android.settings", course.displayPackage());
        assertEquals(course.displayPackage(), LiveTutorialStore.activeDisplayPackage(home));
        assertFalse(course.running());
        assertNull(shadowOf(home).getNextStartedService());
        assertTrue(home.getActivityResultRegistry().dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null));
        assertEquals(home.getString(R.string.tour_settings),
                ((TextView) home.findViewById(R.id.tutorial_title)).getText().toString());
        controller.pause().resume();
        verify(voices.constructed().get(0)).speak(home.getString(R.string.tour_settings));
        assertNull(course.displayPackage());
        assertNull(LiveTutorialStore.activeDisplayPackage(home));
        assertNull(shadowOf(home).getNextStartedActivityForResult());
    }

    @Test public void restoringDisplayWaitsForOriginalResultAndPreservesHomeDraft() {
        Bundle state = new Bundle(), legacy = new Bundle();
        state.putString("request_draft", "글자 크기 키워줘");
        legacy.putString("step", "TEXT");
        legacy.putString("home_draft", "나중에 보낼 요청");
        legacy.putInt("home_cursor", 3);
        legacy.putString("practice_draft", "글자 크기 키워줘");
        state.putBundle("home_clue_tutorial", legacy);
        HomeActivity home = start(state);
        EditText practice = home.findViewById(R.id.home_request_input);
        assertEquals("글자 크기 키워줘", practice.getText().toString());
        assertNull(shadowOf(home).getNextStartedActivityForResult());
        home.findViewById(R.id.home_request_send).performClick();
        var launch = shadowOf(home).getNextStartedActivityForResult();
        assertEquals(Settings.ACTION_DISPLAY_SETTINGS, launch.intent.getAction());
        Bundle left = new Bundle();
        controller.saveInstanceState(left).pause().stop().destroy();
        HomeActivity restored = start(left);
        assertNull(shadowOf(restored).getNextStartedActivityForResult());
        assertTrue(restored.getActivityResultRegistry().dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null));
        EditText editor = restored.findViewById(R.id.home_request_input);
        assertEquals("나중에 보낼 요청", editor.getText().toString());
        assertEquals(3, editor.getSelectionEnd());
        assertTrue(new LiveTutorialStore(restored).awaitingHomeTour());
    }

    @Test public void unfinishedPracticeAndTheOriginalDraftSurviveRecreationAndBack() {
        new com.click.data.local.GuidancePreferences(app).markHomeTutorialSeen();
        HomeActivity home = start(null);
        EditText editor = home.findViewById(R.id.home_request_input);
        editor.setText("원래 요청"); editor.setSelection(2);
        controller.newIntent(new Intent(home, HomeActivity.class).putExtra("com.click.extra.START_HOME_TUTORIAL", true));
        assertEquals("", editor.getText().toString());
        editor.setText("글자 크기"); editor.setSelection(2);
        Bundle state = new Bundle();
        controller.saveInstanceState(state).pause().stop().destroy();
        HomeActivity restored = start(state);
        EditText practice = restored.findViewById(R.id.home_request_input);
        assertEquals("글자 크기", practice.getText().toString());
        assertEquals(2, practice.getSelectionEnd());
        restored.getOnBackPressedDispatcher().onBackPressed();
        assertEquals("원래 요청", practice.getText().toString());
        assertEquals(2, practice.getSelectionEnd());
        assertNull(shadowOf(restored).getNextStartedActivityForResult());
    }

    @Test public void voiceTranscriptCanBeEditedAndCancelledSpeechCannotSubmit() {
        try (var microphones = mockConstruction(DeviceSpeechInput.class)) {
            shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO);
            HomeActivity home = start(null);
            home.findViewById(R.id.home_microphone).performClick();
            home.findViewById(R.id.overlay_microphone).performClick();
            var first = org.mockito.ArgumentCaptor.forClass(SpeechInput.Callback.class);
            verify(microphones.constructed().get(0)).start(first.capture());
            home.getOnBackPressedDispatcher().onBackPressed();
            first.getValue().onSuccess("글자 크기 키워줘");
            EditText editor = home.findViewById(R.id.home_request_input);
            assertEquals("", editor.getText().toString());
            home.findViewById(R.id.home_microphone).performClick();
            home.findViewById(R.id.overlay_microphone).performClick();
            var second = org.mockito.ArgumentCaptor.forClass(SpeechInput.Callback.class);
            verify(microphones.constructed().get(1)).start(second.capture());
            second.getValue().onRecordingFinished();
            second.getValue().onSuccess("글자 크기");
            assertEquals("글자 크기", editor.getText().toString());
            assertNull(shadowOf(home).getNextStartedActivityForResult());
            editor.append(" 키워줘");
            home.findViewById(R.id.home_request_send).performClick();
            assertEquals(Settings.ACTION_DISPLAY_SETTINGS, shadowOf(home).getNextStartedActivity().getAction());
        }
    }

    @Test public void missingPermissionExplainsHowToSeeTheBoxAndResumesAtDisplay() {
        permissions.when(() -> PermissionStatus.isAccessibilityEnabled(any())).thenReturn(false);
        HomeActivity home = start(null);
        TextView message = home.findViewById(R.id.tutorial_title);
        assertEquals(home.getString(R.string.tour_input), message.getText().toString());
        ((EditText) home.findViewById(R.id.home_request_input)).setText("글자 크기 키워줘");
        home.findViewById(R.id.home_request_send).performClick();
        assertEquals(home.getString(R.string.tour_display_permission), message.getText().toString());
        assertTrue(message.performClick());
        assertNotNull(shadowOf(home).getNextStartedActivity().getComponent());
        controller.pause();
        permissions.when(() -> PermissionStatus.isAccessibilityEnabled(any())).thenReturn(true);
        controller.resume();
        assertEquals(Settings.ACTION_DISPLAY_SETTINGS, shadowOf(home).getNextStartedActivity().getAction());
    }

    @Test public void selectedButDisconnectedServiceKeepsTheLessonAtHomeUntilTheGuideCanAppear() {
        var host = mock(com.click.designpreview.ClickHost.class);
        when(host.accessibilityEnabled()).thenReturn(true);
        accessibility.when(com.click.service.accessibility.ClickAccessibilityService::accessibilityWindowManager)
                .thenReturn(null);
        try (var hosts = mockStatic(com.click.designpreview.ClickHost.class)) {
            hosts.when(() -> com.click.designpreview.ClickHost.from(any())).thenReturn(host);
            HomeActivity home = start(null);
            ((EditText) home.findViewById(R.id.home_request_input)).setText("글자 크기 키워줘");
            home.findViewById(R.id.home_request_send).performClick();
            TextView message = home.findViewById(R.id.tutorial_title);
            assertEquals(home.getString(R.string.tour_display_connecting), message.getText().toString());
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(9));
            assertEquals(home.getString(R.string.tour_display_reconnect), message.getText().toString());
            assertNull(shadowOf(home).getNextStartedActivityForResult());
            assertTrue(message.performClick());
            assertTrue(shadowOf(home).getNextStartedActivity().getBooleanExtra(
                    com.click.designpreview.PermissionGuideActivity.EXTRA_RECONNECT, false));
            controller.pause();
            when(host.accessibilityConnected()).thenReturn(true);
            controller.resume();
            assertEquals(Settings.ACTION_DISPLAY_SETTINGS, shadowOf(home).getNextStartedActivity().getAction());
        }
    }

    @Test public void displayPenguinSpeaksTheVisibleExplanationOnlyOnceAcrossFrameUpdates() {
        FrameLayout frame = new FrameLayout(app);
        ClueCoachView coach = ClueCoachView.displayLesson(frame);
        String words = app.getString(R.string.tour_display);
        for (int y : new int[]{100, 145})
            coach.showExternal(words, com.click.designpreview.R.drawable.clue3_tutorial, new Rect(30, y, 600, y + 70), null, null);
        assertEquals(words, ((TextView) coach.findViewById(R.id.tutorial_title)).getText().toString());
        verify(voices.constructed().get(0), times(1)).speak(words);
        coach.close();
    }
}
