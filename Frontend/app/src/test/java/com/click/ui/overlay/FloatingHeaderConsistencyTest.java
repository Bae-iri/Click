package com.click.ui.overlay;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import com.click.domain.goal.GoalPreparationState;
import com.click.domain.goal.RequirementReview;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34, application = android.app.Application.class)
public class FloatingHeaderConsistencyTest {
    @Test public void embeddedRequestUsesTheRealActionsAndRestoresNormalControlsOnTheNextScreen() {
        var listener = mock(TaskFlowOverlayView.Listener.class);
        var edited = new java.util.concurrent.atomic.AtomicBoolean();
        var flow = new TaskFlowOverlayView(RuntimeEnvironment.getApplication(), listener);
        flow.requestConfirmation("글자 크기 키워줘", "다시 입력하기", () -> edited.set(true));
        flow.hideAuxiliaryControls();
        TextView summary = flow.findViewWithTag("review_summary_text");
        assertEquals("글자 크기 키워줘", summary.getText().toString());
        FloatingPanelHeader header = flow.findViewWithTag("floating_header");
        assertEquals(View.GONE, header.collapse.getVisibility());
        header.edit.performClick(); assertTrue(edited.get());
        verify(listener, never()).confirmRequirements();
        flow.findViewById(com.click.R.id.task_flow_begin).performClick();
        verify(listener).confirmRequirements();
        flow.progress("글자 크기", null, "디스플레이를 눌러주세요.", false);
        assertEquals(View.VISIBLE, header.collapse.getVisibility());
        assertEquals(View.GONE, header.edit.getVisibility());
        assertEquals("내용 수정", header.edit.getText().toString());
    }

    @Test public void titlesKeepOneSizeAndTypefaceAcrossInputProcessingGuidanceAndResults() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        int originalLevel = com.click.ui.common.ClickTextScale.level(app);
        try {
            for (float scale : new float[]{1f, 2f}) {
                Configuration config = new Configuration(app.getResources().getConfiguration());
                config.fontScale = scale;
                Context context = app.createConfigurationContext(config);
                for (int level = 0; level < 4; level++) {
                    com.click.ui.common.ClickTextScale.saveLevel(app, level);
                    int width = Math.round(340 * context.getResources().getDisplayMetrics().density);
                    ClickOverlayLayout input = new ClickOverlayLayout(context);
                    input.openMenu();
                    geometry(input, width);
                    TextView reference = ((FloatingPanelHeader) input.findViewWithTag("floating_header")).title;
                    float expectedSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                            com.click.ui.common.ClickTextScale.size(context, 22), context.getResources().getDisplayMetrics());
                    assertEquals(expectedSize, reference.getTextSize(), .01f);
                    TaskFlowOverlayView flow = new TaskFlowOverlayView(context, mock(TaskFlowOverlayView.Listener.class));
                    for (Runnable screen : new Runnable[]{
                            () -> flow.waiting("사용자 요청을 처리하고 있어요"),
                            () -> flow.requirements(new RequirementReview("글자를 키워줘"), false),
                            () -> flow.paused(),
                            () -> flow.result(new JSONObject(), true),
                            () -> flow.completed("완료했어요.", () -> {}),
                            () -> flow.accessibilityRecovery("접근성 연결을 확인해 주세요", () -> {}, () -> {}),
                            () -> flow.waiting("사용자 요청을 처리하고 있어요")}) {
                        screen.run();
                        geometry(flow, width);
                        assertTitleMatches(reference, ((FloatingPanelHeader) flow.findViewWithTag("floating_header")).title);
                    }
                    flow.progress("글자 크기", null, "디스플레이를 눌러주세요.", false);
                    geometry(flow, width);
                    assertTitleMatches(reference, findText(flow, "디스플레이를 눌러주세요."));
                    flow.waitingProgress("필요한 항목을 찾고 있어요.");
                    geometry(flow, width);
                    assertTitleMatches(reference, findText(flow, "필요한 항목을 찾고 있어요."));
                    flow.targetHelp("디스플레이", () -> {});
                    geometry(flow, width);
                    assertTitleMatches(reference, findText(flow, "디스플레이 항목을 찾아주세요"));
                    flow.confirmation("계속 진행할까요?", answer -> {});
                    geometry(flow, width);
                    assertTitleMatches(reference, findText(flow, "계속 진행할까요?"));
                    input.openVoice();
                    geometry(input, width);
                    assertTitleMatches(reference, ((FloatingPanelHeader) input.findViewWithTag("floating_header")).title);
                    input.openText();
                    geometry(input, width);
                    assertTitleMatches(reference, ((FloatingPanelHeader) input.findViewWithTag("floating_header")).title);
                }
            }
        } finally {
            com.click.ui.common.ClickTextScale.saveLevel(app, originalLevel);
        }
    }

    private void assertTitleMatches(TextView expected, TextView actual) {
        assertNotNull(actual);
        assertEquals(actual.getText().toString(), expected.getTextSize(), actual.getTextSize(), .01f);
        assertEquals(expected.getTypeface(), actual.getTypeface());
        assertEquals(expected.getLineSpacingExtra(), actual.getLineSpacingExtra(), .01f);
        assertEquals(expected.getLineSpacingMultiplier(), actual.getLineSpacingMultiplier(), .01f);
        assertFalse(actual.getIncludeFontPadding());
        assertNotNull(actual.getLayout());
        for (int line = 0; line < actual.getLineCount(); line++) assertEquals(0, actual.getLayout().getEllipsisCount(line));
    }

    @Test public void requestEditMicrophoneFillsDraftAndStillRequiresApply() throws Exception {
        TaskFlowOverlayView.Listener listener=mock(TaskFlowOverlayView.Listener.class);
        TaskFlowOverlayView flow=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),listener);
        flow.requirements(new RequirementReview("휴대폰 전체 글자를 크게 해줘"),true);
        assertNull(flow.requestEditSpeechTarget());
        findText(flow,"내용 수정").performClick();
        View microphone=flow.findViewWithTag("request_edit_microphone");assertNotNull(microphone);
        assertEquals("말로 내용 수정",microphone.getContentDescription());
        microphone.performClick();verify(listener).openInput();verify(listener,atLeastOnce()).stopGuidance();
        var target=flow.requestEditSpeechTarget();assertNotNull(target);
        assertTrue(target.apply("  화면 밝기를 높여줘  "));
        assertEquals("화면 밝기를 높여줘",findEditor(flow).getText().toString());
        verify(listener,never()).rephrase(anyString());verify(listener,never()).confirmRequirements();
        findText(flow,"수정한 내용 확인").performClick();verify(listener).rephrase("화면 밝기를 높여줘");
    }

    @Test public void closedOrReplacedEditorDiscardsItsPendingVoiceText() throws Exception {
        TaskFlowOverlayView.Listener listener=mock(TaskFlowOverlayView.Listener.class);
        TaskFlowOverlayView flow=new TaskFlowOverlayView(RuntimeEnvironment.getApplication(),listener);
        flow.requirements(new RequirementReview("휴대폰 전체 글자를 크게 해줘"),true);
        findText(flow,"내용 수정").performClick();var old=flow.requestEditSpeechTarget();
        assertFalse(old.apply("  "));
        flow.requirements(new RequirementReview("휴대폰 전체 글자를 작게 해줘"),true);
        assertFalse(old.apply("지연된 이전 음성"));assertNull(flow.requestEditSpeechTarget());
        verify(listener,never()).rephrase(anyString());
    }

    @Test public void everyConfirmationReplacesHeaderReplayWithEditing() throws Exception {
        for (float scale : new float[]{1f, 1.5f, 2f}) {
            Context app = RuntimeEnvironment.getApplication();
            Configuration config = new Configuration(app.getResources().getConfiguration());
            config.fontScale = scale;
            Context context = app.createConfigurationContext(config);
            TaskFlowOverlayView.Listener listener = mock(TaskFlowOverlayView.Listener.class);
            TaskFlowOverlayView flow = new TaskFlowOverlayView(context, listener);
            try (var host = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup()) {
            host.get().setContentView(flow);
            for (int mode = 0; mode < 3; mode++) {
                clearInvocations(listener);
                if (mode == 0) {
                    flow.preparation(new GoalPreparationState(new JSONObject("{\"version\":\"goal-inputs.v1\",\"id\":\"ready\",\"source_text\":\"글자 크기 키워줘\",\"status\":\"ready\",\"values\":{},\"missing_fields\":[],\"graph_reference\":{}}"),
                            new JSONObject("{\"goal_type\":\"settings.font_size.increase\"}"), "글자를 키울게요."));
                } else {
                    RequirementReview review = new RequirementReview("휴대폰 전체 글자를 크게 해줘");
                    if (mode == 1) flow.requirements(review, true);
                    else flow.requirementsForPreparation(review, true, "다시 확인해 주세요.", () -> {});
                }
                geometry(flow, Math.round(340 * context.getResources().getDisplayMetrics().density));
                ViewGroup card = flow.findViewWithTag("review_summary_card");
                ViewGroup section = flow.findViewWithTag("review_summary_section");
                TextView edit = findText(flow, "내용 수정");
                FloatingPanelHeader header = flow.findViewWithTag("floating_header");
                assertSame(header.edit, edit);
                assertEquals(View.GONE, header.replay.getVisibility());
                assertTrue(edit instanceof Button);
                assertTrue(edit.isFocusable());
                assertTrue(edit.isShown());
                assertSame(card, section.getChildAt(0));
                Rect editBounds = new Rect(); edit.getDrawingRect(editBounds);
                flow.offsetDescendantRectToMyCoords(edit, editBounds);
                assertTrue(editBounds.left >= 0);
                assertTrue(editBounds.right <= flow.getWidth());
                Rect dividerBounds = new Rect(); header.divider.getDrawingRect(dividerBounds);
                flow.offsetDescendantRectToMyCoords(header.divider, dividerBounds);
                Rect cardBounds = new Rect(); card.getDrawingRect(cardBounds);
                flow.offsetDescendantRectToMyCoords(card, cardBounds);
                assertEquals("Divider has the same gap above and below",
                        ((android.widget.LinearLayout.LayoutParams) header.divider.getLayoutParams()).topMargin,
                        cardBounds.top - dividerBounds.bottom);
                View summaryRow = card.getChildAt(0);
                assertEquals(card.getPaddingTop(), summaryRow.getTop());
                assertTrue(editBounds.bottom <= cardBounds.top);
                assertTrue(findText(flow, "안내 시작하기") != null || findText(flow, "선택한 내용 확인") != null);
                EditText input = findEditor(card);
                assertFalse(input.isShown());
                edit.performClick();
                assertFalse(edit.isShown());
                assertTrue(input.isShown());
                TextView start = findText(flow, mode == 2 ? "선택한 내용 확인" : "안내 시작하기");
                assertFalse(start.isShown());
                verify(listener).stopGuidance();
                input.setText("   ");
                findText(card, "수정한 내용 확인").performClick();
                verify(listener, never()).rephrase(anyString());
                input.setText(" 화면 밝기를 높여줘 ");
                findText(card, "수정한 내용 확인").performClick();
                verify(listener).rephrase("화면 밝기를 높여줘");
                verify(listener, never()).confirmRequirements();
            }
            flow.progress("글자 크기", null, "디스플레이를 눌러주세요.", false);
            assertNull(flow.findViewWithTag("review_summary_card"));
            assertEquals(View.GONE, findText(flow, "내용 수정").getVisibility());
            }
        }
    }

    private TextView findText(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private EditText findEditor(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEditor(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void everyExpandedPanelKeepsTitleBelowBrandAtLargeFonts() throws Exception {
        for (float scale : new float[]{1f, 1.3f, 2f}) {
            Context app = RuntimeEnvironment.getApplication();
            Configuration config = new Configuration(app.getResources().getConfiguration());
            config.fontScale = scale;
            Context context = app.createConfigurationContext(config);
            int width = Math.round(340 * context.getResources().getDisplayMetrics().density);
            TaskFlowOverlayView flow = new TaskFlowOverlayView(context, mock(TaskFlowOverlayView.Listener.class));
            flow.preparation(new GoalPreparationState(new JSONObject("{\"version\":\"goal-inputs.v1\",\"id\":\"ready\",\"source_text\":\"글자 크기 키워줘\",\"status\":\"ready\",\"values\":{},\"missing_fields\":[],\"graph_reference\":{}}"),
                    new JSONObject("{\"goal_type\":\"settings.font_size.increase\"}"), "글자를 키울게요."));
            geometry(flow, width);
            android.view.ViewGroup card = flow.findViewWithTag("review_summary_card");
            View icon = card.findViewWithTag("review_summary_icon");
            View label = card.findViewWithTag("review_summary_text");
            assertEquals(icon.getTop() + icon.getHeight() / 2f,
                    label.getTop() + label.getHeight() / 2f, 1f);
            flow.progress("글자 크기", null, "디스플레이를 눌러주세요.", false);
            geometry(flow, width);
            flow.setGuidanceTarget("디스플레이");
            assertNotNull(findDescription(flow, "디스플레이"));
            flow.setGuidanceTarget("설정");
            assertNull(findDescription(flow, "설정. 안내를 접고 화면에서 찾기"));
            assertNull(findDescription(flow, "디스플레이"));
            flow.result(new JSONObject(), true);
            geometry(flow, width);
            flow.observationRecovery("화면을 확인해 주세요.", () -> {});
            geometry(flow, width);
            assertEquals(0, ((FloatingPanelHeader) flow.findViewWithTag("floating_header")).title.length());
            assertNotNull(findText(flow, "‘다시 시도하기’를 눌러 안내를 이어가 주세요."));
            assertFalse(flow.guidanceMessage().contains("팝업"));
            assertFalse(flow.guidanceMessage().contains("잠금"));
            assertNull(findText(flow, "현재 화면에서 안내할 위치를 확인하지 못했어요. 다시 확인하거나 요청을 고쳐 주세요."));
            flow.recoveryApology(() -> {});
            geometry(flow, width);
            assertNotNull(findText(flow, "현재 화면에서 안내할 위치를 확인하지 못했어요. 다시 확인하거나 요청을 고쳐 주세요."));
            for (int repeat = 0; repeat < 2; repeat++) {
                flow.setMinimizedProgress(true);
                TextView compact = (TextView) findDescription(flow, "죄송합니다.. 눌러서 안내 펼치기");
                assertNotNull(compact);
                assertEquals("죄송합니다.", compact.getText().toString());
                flow.setMinimizedProgress(false);
                assertNull(findText(flow, "도움이 필요해요!"));
            }
            flow.completed("완료했어요.", () -> {});
            geometry(flow, width);
            ClickOverlayLayout input = new ClickOverlayLayout(context);
            for (ClickOverlayLayout.Mode mode : new ClickOverlayLayout.Mode[]{ClickOverlayLayout.Mode.MENU,
                    ClickOverlayLayout.Mode.VOICE, ClickOverlayLayout.Mode.TEXT}) {
                if (mode == ClickOverlayLayout.Mode.MENU) input.openMenu();
                if (mode == ClickOverlayLayout.Mode.VOICE) input.openVoice();
                if (mode == ClickOverlayLayout.Mode.TEXT) input.openText();
                assertEquals(flow.getWindowWidth(width + 100), input.getWindowWidth(mode, width + 100));
                geometry(input, width);
                FloatingPanelHeader header = input.findViewWithTag("floating_header");
                Rect close = new Rect(); header.collapse.getDrawingRect(close);
                input.offsetDescendantRectToMyCoords(header.collapse, close);
                int headerLeft = header.getLeft(), headerTop = header.getTop();
                assertEquals(close.exactCenterX(), input.getClickCenterX(mode, width), 1f);
                assertEquals(close.exactCenterY(), input.getClickCenterY(mode), 1f);
                assertEquals(headerLeft, header.getLeft());
                assertEquals(headerTop, header.getTop());
                assertTrue(header.collapse.isClickable());
            }
        }
    }

    private Rect geometry(View panel, int width) {
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.AT_MOST));
        panel.layout(0, 0, panel.getMeasuredWidth(), panel.getMeasuredHeight());
        FloatingPanelHeader header = panel.findViewWithTag("floating_header");
        assertNotNull(header);
        assertNull(header.findViewWithTag("floating_logo"));
        assertNull(findDescription(header, "이전 단계"));
        assertTrue(header.collapse.getMeasuredWidth() > 0);
        int minimumTouchWidth=Math.round(48*header.getResources().getDisplayMetrics().density);
        assertTrue(header.collapse.getMeasuredWidth()>=minimumTouchWidth);
        if (header.replay.getVisibility() != View.GONE) {
            assertTrue(header.replay.getMeasuredWidth()>=minimumTouchWidth);
            assertTrue(header.replay.getMeasuredWidth()>=header.collapse.getMeasuredWidth());
        }
        Rect titleBounds = new Rect(); header.title.getDrawingRect(titleBounds);
        header.offsetDescendantRectToMyCoords(header.title, titleBounds);
        View wordmark = header.findViewWithTag("floating_wordmark");
        assertNotNull(wordmark);
        Rect wordmarkBounds = new Rect(); wordmark.getDrawingRect(wordmarkBounds);
        header.offsetDescendantRectToMyCoords(wordmark, wordmarkBounds);
        assertEquals(0, wordmarkBounds.left);
        if (header.title.length() > 0) assertTrue(titleBounds.top >= header.divider.getBottom());
        assertTrue(titleBounds.right <= header.getWidth());
        if (header.title.length() > 0) {
            assertTrue(header.title.getMeasuredWidth() > 0);
            assertTrue(titleBounds.bottom <= header.getHeight());
        }
        assertEquals(header.replay.getMeasuredHeight(), header.collapse.getMeasuredHeight());
        return new Rect(header.getWidth(), header.getHeight(), header.divider.getTop(), header.divider.getHeight());
    }
    private View findDescription(View view, String description) {
        if (description.equals(view.getContentDescription())) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }
}
