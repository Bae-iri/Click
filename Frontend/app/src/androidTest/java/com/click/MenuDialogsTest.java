package com.click;

import android.app.UiAutomation;
import android.graphics.Bitmap;
import android.view.View;
import android.view.ViewGroup;
import android.view.KeyEvent;
import android.view.inspector.WindowInspector;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.filters.SdkSuppress;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
@SdkSuppress(minSdkVersion = 29)
public class MenuDialogsTest {
    @Test public void settingsAndPolicyDialogsKeepAllTextReadable() throws Exception {
        String[] titles = {"권한 상태 확인", "맞춤 안내 기록 설정", "서비스 이용약관", "개인정보 처리방침"};
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        try (ActivityScenario<MenuActivity> scenario = ActivityScenario.launch(MenuActivity.class)) {
            for (int index = 0; index < titles.length; index++) {
                String title = titles[index];
                scenario.onActivity(activity -> {
                    View row = find(activity.getWindow().getDecorView(), title);
                    assertNotNull(row);
                    while (!row.isClickable()) row = (View) row.getParent();
                    row.performClick();
                });
                instrumentation.waitForIdleSync();
                scenario.onActivity(activity -> {
                    View dialog = null;
                    for (View root : WindowInspector.getGlobalWindowViews())
                        if (root != activity.getWindow().getDecorView() && root.isShown() && find(root, title) != null)
                            dialog = root;
                    assertNotNull("Click dialog must open: " + title, dialog);
                    assertTextFits(dialog);
                    ScrollView scroll = findScroll(dialog);
                    assertNotNull(scroll);
                    scroll.fullScroll(View.FOCUS_DOWN);
                    assertTrue(scroll.getHeight() <= activity.getResources().getDisplayMetrics().heightPixels);
                });
                instrumentation.waitForIdleSync();
                screenshot("menu-dialog-" + index);
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                instrumentation.waitForIdleSync();
            }
        }
    }

    private TextView find(View view, String title) {
        if (view instanceof TextView && title.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = find(((ViewGroup) view).getChildAt(i), title);
            if (found != null) return found;
        }
        return null;
    }

    private ScrollView findScroll(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            ScrollView found = findScroll(((ViewGroup) view).getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private void assertTextFits(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.getLayout() != null && text.length() > 0) {
                assertTrue("Clipped text: " + text.getText(), text.getLayout().getHeight()
                        <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
                for (int line = 0; line < text.getLayout().getLineCount(); line++)
                    assertEquals("Truncated text: " + text.getText(), 0, text.getLayout().getEllipsisCount(line));
            }
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertTextFits(((ViewGroup) view).getChildAt(i));
    }

    private void screenshot(String name) throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        float scale = instrumentation.getTargetContext().getResources().getConfiguration().fontScale;
        Bitmap bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(instrumentation.getTargetContext().getCacheDir(),
                name + "-" + scale + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
}
