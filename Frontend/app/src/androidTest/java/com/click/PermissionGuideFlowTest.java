package com.click;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.designpreview.PermissionGuideActivity;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PermissionGuideFlowTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void threeStaticCardsKeepHeaderProgressAndPreviousNavigation() {
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try (ActivityScenario<PermissionGuideActivity> scenario = ActivityScenario.launch(PermissionGuideActivity.class)) {
            String[] titles = {"위쪽 스위치를 켜주세요", "확인창에서 ‘허용’을 눌러요", "‘사용 중’이면 설정 완료예요"};
            for (int page = 0; page < 3; page++) {
                settle();
                final int currentPage = page;
                scenario.onActivity(activity -> {
                    assertPage(activity, currentPage + 1);
                    assertEquals(titles[currentPage], ((TextView) activity.findViewById(
                            com.click.designpreview.R.id.guide_card_title)).getText().toString());
                    assertNull(activity.findViewById(com.click.designpreview.R.id.guide_enable_animation));
                    View screenshot = activity.findViewById(com.click.designpreview.R.id.guide_screenshot);
                    assertNotNull(screenshot);
                    assertFalse(screenshot.isClickable());
                    assertFalse(screenshot.performClick());
                    assertEquals(3, ((ViewGroup) activity.findViewById(
                            com.click.designpreview.R.id.guide_progress_bars)).getChildCount());
                    TextView replay = activity.getWindow().getDecorView().findViewWithTag("permission_replay");
                    assertEquals(1, replay.getLineCount());
                    assertEquals(((ViewGroup) replay.getParent()).getWidth(), replay.getRight());
                    View previous = activity.findViewById(com.click.designpreview.R.id.guide_back);
                    assertSame(previous.getParent(), replay.getParent());
                    assertEquals(0, previous.getLeft());
                    assertEquals(currentPage > 0, previous.isEnabled());
                    int[] controlsAt = new int[2], progressAt = new int[2], titleAt = new int[2];
                    replay.getLocationOnScreen(controlsAt);
                    View progress = activity.findViewById(com.click.designpreview.R.id.guide_progress_bars);
                    progress.getLocationOnScreen(progressAt);
                    activity.findViewById(com.click.designpreview.R.id.guide_card_title).getLocationOnScreen(titleAt);
                    assertTrue(progressAt[1] >= controlsAt[1] + replay.getHeight());
                    assertTrue(titleAt[1] > progressAt[1] + progress.getHeight());
                    assertNull(findText(activity.getWindow().getDecorView(), "사진을 누르면 전체 화면을 크게 볼 수 있어요"));
                    if (currentPage == 1) assertNotNull(findText(activity.getWindow().getDecorView(),
                            "휴대전화 제어 권한을 허용하는 창이에요. ‘허용’을 눌러야 클릭이 도와드릴 수 있어요."));
                    if (currentPage == 2) assertNotNull(findText(activity.getWindow().getDecorView(),
                            "사용 중으로 표시되면 완료예요."));
                    readable(activity.getWindow().getDecorView());
                    save(activity, "card-" + (currentPage + 1));
                    if (currentPage < 2) next(activity);
                });
            }
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertPage(activity, 3);
                assertEquals("지금 설정하기", activity.findViewById(com.click.designpreview.R.id.guide_action).getContentDescription());
                activity.findViewById(com.click.designpreview.R.id.guide_back).performClick();
                assertPage(activity, 2);
            });
            scenario.moveToState(Lifecycle.State.CREATED);
            scenario.moveToState(Lifecycle.State.RESUMED);
            SystemClock.sleep(1000);
            scenario.onActivity(activity -> assertPage(activity, 2));
        }
    }

    @Test public void largestAppTextKeepsAllThreeCardsReadable() {
        android.content.Context context = instrumentation.getTargetContext();
        int original = com.click.ui.common.ClickTextScale.level(context);
        try {
            com.click.ui.common.ClickTextScale.saveLevel(context, 3);
            threeStaticCardsKeepHeaderProgressAndPreviousNavigation();
        } finally {
            com.click.ui.common.ClickTextScale.saveLevel(context, original);
        }
    }

    @Test public void horizontalSwipesTurnCardsButNeverOpenSettingsOrWrapAtEnds() {
        try (ActivityScenario<PermissionGuideActivity> scenario = ActivityScenario.launch(PermissionGuideActivity.class)) {
            settle();
            scenario.onActivity(activity -> {
                swipe(activity, true); assertPage(activity, 2);
                swipe(activity, false); assertPage(activity, 1);
                swipe(activity, false); assertPage(activity, 1);
                for (int i = 0; i < 2; i++) next(activity);
            });
            settle();
            scenario.onActivity(activity -> {
                swipe(activity, true); assertPage(activity, 3);
                assertFalse(activity.isFinishing());
                swipe(activity, false); assertPage(activity, 2);
            });
        }
    }

    private static void swipe(PermissionGuideActivity activity, boolean forward) {
        ScrollView view = activity.findViewById(com.click.designpreview.R.id.guide_scroll);
        float start = view.getWidth() * (forward ? .85f : .15f);
        float end = view.getWidth() * (forward ? .15f : .85f);
        float y = Math.min(view.getHeight() * .5f, 300);
        long at = SystemClock.uptimeMillis();
        for (int i = 0; i <= 8; i++) {
            int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 8 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(at, at + i * 25, action, start + (end - start) * i / 8, y, 0);
            view.dispatchTouchEvent(event);
            event.recycle();
        }
    }
    private void settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(180); }
    private static void next(PermissionGuideActivity activity) {
        activity.findViewById(com.click.designpreview.R.id.guide_action).performClick();
    }
    private static void assertPage(PermissionGuideActivity activity, int page) {
        assertNull(activity.findViewById(com.click.designpreview.R.id.guide_step_counter));
        View progress = activity.findViewById(com.click.designpreview.R.id.guide_progress_bars);
        assertEquals("접근성 안내, 3단계 중 " + page + "단계", progress.getContentDescription());
        assertEquals(View.VISIBLE, progress.getVisibility());
    }

    private static TextView findText(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) view).getChildAt(i), value);
            if (found != null) return found;
        }
        return null;
    }

    private static void readable(View view) {
        if (!view.isShown()) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getLayout() == null) return;
            for (int i = 0; i < text.getLineCount(); i++) {
                assertEquals(text.getText().toString(), 0, text.getLayout().getEllipsisCount(i));
                assertTrue("Horizontal clipping: " + text.getText(), text.getLayout().getLineMax(i)
                        <= text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight() + 1);
            }
            assertTrue("Vertical clipping: " + text.getText(), text.getLayout().getHeight()
                    <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            readable(((ViewGroup) view).getChildAt(i));
    }
    private static void save(PermissionGuideActivity activity, String stage) {
        View view = activity.getWindow().getDecorView();
        Bitmap image = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(image));
        String name = "permission-" + activity.getResources().getConfiguration().fontScale + "-text-"
                + com.click.ui.common.ClickTextScale.level(activity) + "-" + stage + ".png";
        try (FileOutputStream out = new FileOutputStream(new File(activity.getCacheDir(), name))) {
            image.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception e) { throw new AssertionError(e); }
        image.recycle();
    }
}
