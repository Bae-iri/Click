package com.click.ui.overlay;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.HomeActivity;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FloatingHeaderDeviceTest {
    @Test public void inputHeadersRenderAndKeepTheirPositionAtLargeFonts() {
        try (ActivityScenario<HomeActivity> scenario = ActivityScenario.launch(HomeActivity.class)) {
            for (float scale : new float[]{1f, 2f}) for (int mode = 0; mode < 3; mode++) {
                final int choice = mode;
                final ClickOverlayLayout[] shown = {null};
                scenario.onActivity(activity -> {
                    Configuration config = new Configuration(activity.getResources().getConfiguration());
                    config.fontScale = scale;
                    Context context = activity.createConfigurationContext(config);
                    ClickOverlayLayout panel = new ClickOverlayLayout(context); shown[0] = panel;
                    panel.setOnLibraryRequested(() -> {});
                    if (choice == 0) panel.openMenu();
                    else if (choice == 1) panel.openVoice();
                    else panel.openText();
                    FrameLayout host = new FrameLayout(activity);
                    host.setBackgroundColor(0xFF7098BB);
                    int width = panel.getWindowWidth(panel.getMode(), context.getResources().getDisplayMetrics().widthPixels);
                    host.addView(panel, new FrameLayout.LayoutParams(width, panel.getWindowHeight(panel.getMode())));
                    activity.setContentView(host);
                });
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(activity -> {
                    ClickOverlayLayout panel = shown[0];
                    FloatingPanelHeader header = panel.findViewWithTag("floating_header");
                    TextView collapse = header.collapse;
                    assertTrue(collapse.isShown());
                    TextView title = header.title;
                    assertNull(header.findViewWithTag("floating_logo"));
                    assertTrue(title.getTop() >= header.divider.getBottom());
                    assertNotNull(header.findViewWithTag("floating_wordmark"));
                    assertTrue(title.getWidth() > 0);
                    assertTrue(title.getLayout().getHeight() <= title.getHeight());
                    assertEquals("줄이기", collapse.getText().toString());
                    assertNotNull(collapse.getLayout());
                    assertEquals(1, collapse.getLineCount());
                    assertTrue(collapse.getLayout().getLineWidth(0) <= collapse.getWidth()
                            - collapse.getCompoundPaddingLeft() - collapse.getCompoundPaddingRight());
                    int left = header.getLeft(), top = header.getTop();
                    panel.getClickCenterX(panel.getMode()); panel.getClickCenterY(panel.getMode());
                    assertEquals(left, header.getLeft()); assertEquals(top, header.getTop());
                    assertNotNull(collapse.getLayout());
                    assertEquals(1, collapse.getLineCount());
                    Bitmap bitmap = Bitmap.createBitmap(panel.getWidth(), panel.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawColor(0xFF7098BB);
                    panel.draw(canvas);
                    try (FileOutputStream out = new FileOutputStream(new File(activity.getCacheDir(),
                            "shared-input-header-" + choice + "-" + scale + ".png"))) {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                    } catch (Exception error) { throw new AssertionError(error); }
                    bitmap.recycle();
                });
            }
        }
    }
}
