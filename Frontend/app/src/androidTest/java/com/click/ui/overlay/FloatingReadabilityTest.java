package com.click.ui.overlay;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FloatingReadabilityTest {
    @Test public void largeTextKeepsStopAndFoldReachableAndLongSpeechScrollable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
            Configuration config = new Configuration(app.getResources().getConfiguration());
            config.fontScale = 2f;
            ClickOverlayLayout control = new ClickOverlayLayout(app.createConfigurationContext(config));
            control.openVoice();
            String message = "요청하신 글자 크기 설정 화면을 확인하고 있어요. 화면의 안내를 따라 천천히 진행해 주세요. "
                    + "이 안내가 길어져도 마지막 문장까지 읽을 수 있고 언제든지 듣기를 멈출 수 있어야 해요.";
            control.showRecognizedSpeechResult(message);
            layout(control, 296, 460);
            TextView stop = find(control, "취소"), fold = find(control, "줄이기  −");
            assertActionVisible(control, stop);
            assertActionVisible(control, fold);
            TextView transcript = find(control, message);
            assertNotNull(transcript);
            assertTrue(transcript.getMaxLines() > 3);
            View parent = (View) transcript.getParent();
            while (!(parent instanceof ScrollView)) parent = (View) parent.getParent();
            ScrollView scroll = (ScrollView) parent;
            assertTrue("Long guidance must remain scrollable", scroll.getChildAt(0).getHeight() > scroll.getHeight());
            stop.performClick();
            assertEquals(ClickOverlayLayout.Mode.MENU, control.getMode());
            layout(control, 296, 460);
            TextView voice = find(control, "말로 입력하기"), write = find(control, "글로 입력하기");
            assertLabelFits(voice);
            assertLabelFits(write);
            find(control, "줄이기  −").performClick();
            assertEquals(ClickOverlayLayout.Mode.COLLAPSED, control.getMode());
        });
    }

    @Test public void largeTextInputAndSendFitTheSmallerPanelWithKeyboard() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (float scale : new float[]{1f, 1.5f, 2f}) {
                Configuration config = new Configuration(app.getResources().getConfiguration());
                config.fontScale = scale;
                ClickOverlayLayout control = new ClickOverlayLayout(app.createConfigurationContext(config));
                control.openText();
                control.setDraftText("글자 크기를 조금 더 크게");
                layout(control, 296, 260);
                assertActionVisible(control, find(control, "줄이기  −"));
                assertActionVisible(control, find(control, "↑"));
                TextView input = find(control, "글자 크기를 조금 더 크게");
                assertLabelFits(input);
                Rect bounds = new Rect(0, 0, input.getWidth(), input.getHeight());
                control.offsetDescendantRectToMyCoords(input, bounds);
                assertTrue(bounds.bottom <= control.getHeight());
                assertEquals("글자 크기를 조금 더 크게", control.getDraftText());
            }
        });
    }

    private void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(view, height), View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private void assertActionVisible(ClickOverlayLayout root, TextView action) {
        assertNotNull(action);
        assertTrue(action.isClickable());
        assertTrue(action.getHeight() >= dp(action, 48));
        Rect bounds = new Rect(0, 0, action.getWidth(), action.getHeight());
        root.offsetDescendantRectToMyCoords(action, bounds);
        assertTrue("Action must fit inside panel", bounds.top >= 0 && bounds.bottom <= root.getHeight());
        assertLabelFits(action);
    }

    private void assertLabelFits(TextView label) {
        assertNotNull(label);
        assertTrue("Action label must not be clipped", label.getLayout().getHeight()
                <= label.getHeight() - label.getPaddingTop() - label.getPaddingBottom());
    }

    private TextView find(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            TextView result = find(((ViewGroup) root).getChildAt(i), value);
            if (result != null) return result;
        }
        return null;
    }

    private int dp(View view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }
}
