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
    @Test public void everyConfirmationKeepsEditBelowItsBalancedSummaryBox() throws Exception {
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
                assertSame(section, edit.getParent());
                assertSame(card, section.getChildAt(0));
                assertSame(edit, section.getChildAt(1));
                assertTrue((edit.getPaintFlags() & android.graphics.Paint.UNDERLINE_TEXT_FLAG) != 0);
                assertFalse(edit instanceof Button);
                assertTrue(edit.isFocusable());
                assertTrue(edit.isShown());
                Rect editBounds = new Rect(); edit.getDrawingRect(editBounds);
                section.offsetDescendantRectToMyCoords(edit, editBounds);
                assertTrue(editBounds.left >= 0);
                assertEquals(section.getWidth(), editBounds.right);
                assertTrue(editBounds.top >= card.getBottom());
                FloatingPanelHeader header = flow.findViewWithTag("floating_header");
                Rect dividerBounds = new Rect(); header.divider.getDrawingRect(dividerBounds);
                flow.offsetDescendantRectToMyCoords(header.divider, dividerBounds);
                Rect cardBounds = new Rect(); card.getDrawingRect(cardBounds);
                flow.offsetDescendantRectToMyCoords(card, cardBounds);
                assertEquals("Divider has the same gap above and below",
                        ((android.widget.LinearLayout.LayoutParams) header.divider.getLayoutParams()).topMargin,
                        cardBounds.top - dividerBounds.bottom);
                View summaryRow = card.getChildAt(0);
                assertEquals("Summary has equal space above and below", summaryRow.getTop(),
                        card.getHeight() - summaryRow.getBottom());
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
            assertNull(findText(flow, "내용 수정"));
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
            flow.result(new JSONObject(), true);
            geometry(flow, width);
            flow.observationRecovery("화면을 확인해 주세요.", () -> {});
            geometry(flow, width);
            assertEquals(0, ((FloatingPanelHeader) flow.findViewWithTag("floating_header")).title.length());
            assertNotNull(findText(flow, "휴대폰 잠금을 풀고, 열려 있는 팝업을 닫은 뒤 ‘다시 시도하기’를 눌러주세요."));
            assertNull(findText(flow, "죄송합니다. 클릭이 아직 배우는 중이라 많이 미숙합니다."));
            flow.recoveryApology(() -> {});
            geometry(flow, width);
            assertNotNull(findText(flow, "죄송합니다. 클릭이 아직 배우는 중이라 많이 미숙합니다."));
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
        if (header.replay.getVisibility() != View.GONE)
            assertTrue(header.replay.getMeasuredWidth() > header.collapse.getMeasuredWidth());
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
