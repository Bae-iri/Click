package com.click.ui.overlay;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.click.domain.goal.RequirementReview;
import com.click.ui.common.ClickTextScale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class FloatingReadabilityRegressionTest {
    @Test public void largeTextReviewKeepsHeaderActionsInsideTheNarrowPanel() {
        Context app = RuntimeEnvironment.getApplication();
        int original = ClickTextScale.level(app);
        try {
            ClickTextScale.saveLevel(app, 3);
            Configuration config = new Configuration(app.getResources().getConfiguration());
            config.fontScale = 2f;
            Context context = app.createConfigurationContext(config);
            TaskFlowOverlayView panel = new TaskFlowOverlayView(context, mock(TaskFlowOverlayView.Listener.class));
            panel.requirements(new RequirementReview("휴대폰 글자 크기를 키워줘"), true);
            panel.setAvailableHeight(dp(context, 780));
            layout(panel, 296, 780);
            FloatingPanelHeader header = panel.findViewWithTag("floating_header");
            for (TextView action : new TextView[]{header.edit, header.collapse}) {
                assertMinimumSp(action, 15);
                assertFits(header, action);
                assertTrue(action.getHeight() >= dp(context, 48));
            }
            TextView summary = panel.findViewWithTag("review_summary_text");
            assertMinimumSp(summary, 18);
            assertMinimumSp(find(panel, "안내 시작하기"), 18);
        } finally { ClickTextScale.saveLevel(app, original); }
    }

    @Test public void confirmationButtonsGrowWithLargeTextInsteadOfClippingAt52Dp() {
        Context app = RuntimeEnvironment.getApplication();
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.fontScale = 2f;
        Context context = app.createConfigurationContext(config);
        ConfirmationOverlayView panel = new ConfirmationOverlayView(context,
                "이 내용으로 계속 진행할까요?", accepted -> {});
        layout(panel, 296, 700);
        for (String value : new String[]{"아니요", "네, 계속할게요"}) {
            TextView action = find(panel, value);
            assertMinimumSp(action, 18);
            assertFits(panel, action);
            assertNotNull(action.getLayout());
            assertTrue(action.getLayout().getHeight() <= action.getHeight()
                    - action.getPaddingTop() - action.getPaddingBottom());
        }
    }

    private void assertMinimumSp(TextView text, int base) {
        assertNotNull(text);
        float minimum = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
                ClickTextScale.size(text.getContext(), base), text.getResources().getDisplayMetrics());
        assertTrue(text.getText().toString(), text.getTextSize() + .1f >= minimum);
    }

    private void assertFits(ViewGroup root, View view) {
        Rect bounds = new Rect(0, 0, view.getWidth(), view.getHeight());
        root.offsetDescendantRectToMyCoords(view, bounds);
        assertTrue(bounds.toShortString(), bounds.left >= 0 && bounds.right <= root.getWidth()
                && bounds.top >= 0 && bounds.bottom <= root.getHeight());
    }

    private void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(view.getContext(), width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(view.getContext(), height), View.MeasureSpec.AT_MOST));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    private TextView find(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            TextView found = find(((ViewGroup) root).getChildAt(i), value);
            if (found != null) return found;
        }
        return null;
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
