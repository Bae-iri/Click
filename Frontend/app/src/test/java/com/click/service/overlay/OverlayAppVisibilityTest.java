package com.click.service.overlay;

import android.app.Application;
import android.view.View;
import android.view.WindowManager;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class OverlayAppVisibilityTest {
    private ClickOverlayService service;
    private Method add, visibility;
    private Field running;

    @Before public void setup() throws Exception {
        service = Robolectric.buildService(ClickOverlayService.class).get();
        running = ClickOverlayService.class.getDeclaredField("runningService");
        running.setAccessible(true);
        running.set(null, new WeakReference<>(service));
        Field windows = ClickOverlayService.class.getDeclaredField("windowManager");
        windows.setAccessible(true);
        windows.set(service, mock(WindowManager.class));
        add = ClickOverlayService.class.getDeclaredMethod("addOverlayView", View.class, WindowManager.LayoutParams.class);
        add.setAccessible(true);
        visibility = ClickOverlayService.class.getDeclaredMethod("setOverlayVisibility", View.class, int.class);
        visibility.setAccessible(true);
    }
    @After public void cleanup() throws Exception { running.set(null, new WeakReference<>(null)); }

    @Test public void enteringAppHidesAllWindowsAndLeavingRestoresTheirState() throws Exception {
        View button = new View(service), guide = new View(service), hidden = new View(service);
        hidden.setVisibility(View.GONE);
        for (View view : new View[]{button, guide, hidden}) add.invoke(service, view, new WindowManager.LayoutParams());
        ClickOverlayService.onAppVisibilityChanged(true);
        assertEquals(View.GONE, button.getVisibility());
        assertEquals(View.GONE, guide.getVisibility());
        ClickOverlayService.onAppVisibilityChanged(false);
        assertEquals(View.VISIBLE, button.getVisibility());
        assertEquals(View.VISIBLE, guide.getVisibility());
        assertEquals(View.GONE, hidden.getVisibility());
    }
    @Test public void asynchronousWindowsStayHiddenInsideApp() throws Exception {
        ClickOverlayService.onAppVisibilityChanged(true);
        View panel = new View(service);
        add.invoke(service, panel, new WindowManager.LayoutParams());
        assertEquals(View.GONE, panel.getVisibility());
        visibility.invoke(service, panel, View.VISIBLE);
        assertEquals(View.GONE, panel.getVisibility());
        ClickOverlayService.onAppVisibilityChanged(false);
        assertEquals(View.VISIBLE, panel.getVisibility());
    }
}
