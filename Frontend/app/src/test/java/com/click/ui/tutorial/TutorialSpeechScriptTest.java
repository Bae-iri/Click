package com.click.ui.tutorial;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.click.HomeActivity;
import com.click.R;
import com.click.core.permission.PermissionStatus;
import com.click.data.local.LiveTutorialStore;
import com.click.designpreview.TutorialNarrator;
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
    private ActivityController<HomeActivity> controller;

    @Before public void setup() {
        tokens = mockConstruction(com.click.data.local.AuthTokenStore.class);
        voices = mockConstruction(TutorialNarrator.class);
        permissions = mockStatic(PermissionStatus.class);
        permissions.when(() -> PermissionStatus.isAccessibilityEnabled(any())).thenReturn(true);
        ResolveInfo display = new ResolveInfo();
        display.activityInfo = new ActivityInfo();
        display.activityInfo.packageName = "com.android.settings";
        display.activityInfo.name = "com.android.settings.Settings$DisplaySettingsActivity";
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(Settings.ACTION_DISPLAY_SETTINGS), display);
    }

    @After public void cleanup() {
        if (controller != null) controller.pause().stop().destroy();
        permissions.close();
        voices.close();
        tokens.close();
    }

    private HomeActivity start(Bundle saved) {
        controller = Robolectric.buildActivity(HomeActivity.class);
        if (saved == null) controller.setup(); else controller.setup(saved);
        return controller.get();
    }

    @Test public void firstEntryStartsAtDisplayAndBackContinuesWithoutSendingARequest() {
        HomeActivity home = start(null);
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
        state.putBundle("home_clue_tutorial", legacy);
        HomeActivity home = start(state);
        var launch = shadowOf(home).getNextStartedActivityForResult();
        assertEquals(Settings.ACTION_DISPLAY_SETTINGS, launch.intent.getAction());
        Bundle left = new Bundle();
        controller.saveInstanceState(left).pause().stop().destroy();
        HomeActivity restored = start(left);
        assertNull(shadowOf(restored).getNextStartedActivityForResult());
        assertTrue(restored.getActivityResultRegistry().dispatchResult(launch.requestCode, Activity.RESULT_CANCELED, null));
        EditText editor = restored.findViewById(R.id.home_request_input);
        assertEquals("나중에 보낼 요청", editor.getText().toString());
        assertTrue(new LiveTutorialStore(restored).awaitingHomeTour());
    }

    @Test public void missingPermissionExplainsHowToSeeTheBoxAndResumesAtDisplay() {
        permissions.when(() -> PermissionStatus.isAccessibilityEnabled(any())).thenReturn(false);
        HomeActivity home = start(null);
        TextView message = home.findViewById(R.id.tutorial_title);
        assertEquals(home.getString(R.string.tour_display_permission), message.getText().toString());
        assertTrue(message.performClick());
        assertNotNull(shadowOf(home).getNextStartedActivity().getComponent());
        controller.pause();
        permissions.when(() -> PermissionStatus.isAccessibilityEnabled(any())).thenReturn(true);
        controller.resume();
        assertEquals(Settings.ACTION_DISPLAY_SETTINGS, shadowOf(home).getNextStartedActivity().getAction());
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
