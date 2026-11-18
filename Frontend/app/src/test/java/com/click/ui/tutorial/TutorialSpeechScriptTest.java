package com.click.ui.tutorial;

import android.app.Application;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.click.R;
import com.click.TutorialActivity;
import com.click.designpreview.TutorialNarrator;
import com.click.service.overlay.DeviceSpeechInput;
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

    @Test public void everyExplanationAdvancesWithoutInputOrCompletingAnExercise() {
        int[] titles = {R.string.tutorial_explain_open_title, R.string.tutorial_explain_input_title,
            R.string.tutorial_explain_review_title, R.string.tutorial_explain_guidance_title,
            R.string.tutorial_explain_result_title};
        for (int page = 0; page < titles.length; page++) {
            assertEquals(activity.getString(titles[page]), title());
            View next = activity.findViewById(R.id.tutorial_next);
            assertTrue(next.isEnabled());
            assertEquals(View.VISIBLE, next.getVisibility());
            assertNotNull(activity.findViewById(R.id.tutorial_phone_stage)
                .findViewWithTag("tutorial_readonly_example"));
            assertFalse("Explanations must never open a keyboard",
                activity.getWindow().getDecorView().findFocus() instanceof android.widget.EditText);
            if (page < titles.length - 1) next.performClick();
        }
        assertEquals(activity.getString(R.string.tutorial_explain_finish),
            ((TextView) activity.findViewById(R.id.tutorial_next)).getText().toString());
        assertTrue("Reading the guide must not construct microphone/STT input", inputs.constructed().isEmpty());
    }

    @Test public void examplesConsumeTapsWithoutSubmittingOrChangingTheExplanation() {
        for (int page = 0; page < 5; page++) {
            TutorialExplanationView example = activity.findViewById(R.id.tutorial_phone_stage)
                .findViewWithTag("tutorial_readonly_example");
            assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, example.getDescendantFocusability());
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, example.getImportantForAccessibility());
            String before = title();
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 30, 30, 0);
            MotionEvent up = MotionEvent.obtain(0, 100, MotionEvent.ACTION_UP, 30, 30, 0);
            try {
                assertTrue(example.onInterceptTouchEvent(down));
                assertTrue(example.onTouchEvent(up));
            } finally { down.recycle(); up.recycle(); }
            assertEquals(before, title());
            if (page < 4) activity.findViewById(R.id.tutorial_next).performClick();
        }
        assertTrue(inputs.constructed().isEmpty());
    }

    @Test public void previousAndRecreationRestoreTheSameExplanation() {
        assertFalse(activity.findViewById(R.id.tutorial_back).isEnabled());
        activity.findViewById(R.id.tutorial_next).performClick();
        activity.findViewById(R.id.tutorial_next).performClick();
        Bundle saved = new Bundle();
        controller.saveInstanceState(saved).pause().stop().destroy();
        controller = Robolectric.buildActivity(TutorialActivity.class).setup(saved);
        activity = controller.get();
        assertEquals(activity.getString(R.string.tutorial_explain_review_title), title());
        activity.findViewById(R.id.tutorial_back).performClick();
        assertEquals(activity.getString(R.string.tutorial_explain_input_title), title());
        assertTrue(activity.findViewById(R.id.tutorial_next).isEnabled());
    }

    @Test public void speechAndReplayExplainTheSameVisibleInstructions() {
        TextView heading = activity.findViewById(R.id.tutorial_title);
        TextView body = activity.findViewById(R.id.tutorial_explanation_description);
        String cue = heading.getText() + " " + body.getText() + " "
            + activity.getString(R.string.tutorial_explain_caption);
        verify(narrator).speak(cue);
        activity.findViewById(R.id.tutorial_explanation_replay).performClick();
        verify(narrator).replay(cue);
        clearInvocations(narrator);
        activity.findViewById(R.id.tutorial_next).performClick();
        cue = title() + " " + ((TextView) activity.findViewById(R.id.tutorial_explanation_description)).getText();
        verify(narrator).speak(cue);
        activity.findViewById(R.id.tutorial_explanation_replay).performClick();
        verify(narrator).replay(cue);
    }

    @Test @Config(qualifiers = "w320dp-h640dp")
    public void nextAndPreviousRemainOutsideTheScrollableExampleOnASmallScreen() {
        ViewGroup root = activity.findViewById(android.R.id.content);
        float density = activity.getResources().getDisplayMetrics().density;
        for (int page = 0; page < 5; page++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(Math.round(320 * density), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(640 * density), View.MeasureSpec.EXACTLY));
            root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
            View next = activity.findViewById(R.id.tutorial_next);
            assertFalse(isInside(activity.findViewById(R.id.tutorial_explanation_scroll), next));
            assertFalse(isInside(activity.findViewById(R.id.tutorial_explanation_scroll),
                activity.findViewById(R.id.tutorial_back)));
            assertTrue(next.getHeight() >= 48 * density);
            assertTrue(next.isEnabled());
            if (page < 4) next.performClick();
        }
    }

    private boolean isInside(View parent, View child) {
        android.view.ViewParent current = child.getParent();
        while (current != null) {
            if (current == parent) return true;
            current = current.getParent();
        }
        return false;
    }
    private String title() {
        return ((TextView) activity.findViewById(R.id.tutorial_title)).getText().toString();
    }
}
