package com.click;

import android.app.Application;
import android.content.Intent;
import android.view.View;
import com.click.service.overlay.ClickOverlayService;
import com.click.ui.overlay.ClickOverlayLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.mockito.MockedConstruction;
import com.click.data.local.AuthTokenStore;
import static org.mockito.Mockito.mockConstruction;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class HomeLibraryNavigationTest {
    private MockedConstruction<AuthTokenStore> auth;
    @Before public void useTestTokenStore() { auth = mockConstruction(AuthTokenStore.class); }
    @After public void closeTestTokenStore() { auth.close(); }

    @Test public void retiredPhoneShortcutDisappearsFromBothListsAfterAnUpdate() {
        var app = org.robolectric.RuntimeEnvironment.getApplication();
        var favorites = app.getSharedPreferences("home_favorites", android.content.Context.MODE_PRIVATE);
        favorites.edit().putStringSet("selected_ids", java.util.Set.of("phone", "brightness"))
                .putBoolean("accessibility_added_v1", true).commit();
        new com.click.data.local.GuidancePreferences(app).markHomeTutorialSeen();
        try (var controller = Robolectric.buildActivity(HomeActivity.class).setup()) {
            View root = controller.get().findViewById(android.R.id.content);
            assertNull(root.findViewWithTag("first_help_suggestion"));
            for (int list : new int[]{R.id.home_scroll, R.id.home_favorites_scroll}) {
                if (list == R.id.home_favorites_scroll) root.findViewById(R.id.home_nav_favorites).performClick();
                View visibleList = root.findViewById(list);
                assertTrue(visibleList.isShown());
                assertNull(visibleList.findViewWithTag("library_help:phone"));
                assertNotNull(visibleList.findViewWithTag("library_help:brightness"));
            }
            assertEquals(java.util.Set.of("brightness"), favorites.getStringSet("selected_ids", null));
        }
    }

    @Test public void floatingFavoriteOpensLibraryOnColdLaunch() throws Exception {
        ClickOverlayService service = Robolectric.buildService(ClickOverlayService.class).get();
        ClickOverlayLayout panel = new ClickOverlayLayout(service);
        Field overlay = ClickOverlayService.class.getDeclaredField("overlayView");
        overlay.setAccessible(true); overlay.set(service, panel);
        Method open = ClickOverlayService.class.getDeclaredMethod("openLibrary");
        open.setAccessible(true);
        panel.setOnLibraryRequested(() -> {
            try { open.invoke(service); } catch (Exception error) { throw new AssertionError(error); }
        });
        panel.openMenu();
        assertTrue(panel.findViewWithTag("overlay_open_library").performClick());
        assertEquals(ClickOverlayLayout.Mode.COLLAPSED, panel.getMode());
        Intent intent = shadowOf(service).getNextStartedActivity();
        assertEquals(HomeActivity.class.getName(), intent.getComponent().getClassName());
        assertTrue(intent.getBooleanExtra(HomeActivity.EXTRA_OPEN_LIBRARY, false));
        try (var controller = Robolectric.buildActivity(HomeActivity.class, intent).setup()) {
            assertEquals(View.VISIBLE, controller.get().findViewById(R.id.home_favorites_scroll).getVisibility());
            assertEquals(View.GONE, controller.get().findViewById(R.id.home_scroll).getVisibility());
        }
    }

    @Test public void warmLaunchOpensFavoritesAndConsumesNavigation() {
        try (var controller = Robolectric.buildActivity(HomeActivity.class).setup()) {
            HomeActivity activity = controller.get();
            assertEquals(View.VISIBLE, activity.findViewById(R.id.home_scroll).getVisibility());
            Intent intent = new Intent(activity, HomeActivity.class)
                    .putExtra(HomeActivity.EXTRA_OPEN_LIBRARY, true);
            activity.onNewIntent(intent);
            assertEquals(View.VISIBLE, activity.findViewById(R.id.home_favorites_scroll).getVisibility());
            assertFalse(activity.getIntent().hasExtra(HomeActivity.EXTRA_OPEN_LIBRARY));
            activity.findViewById(R.id.home_nav_home).performClick();
            controller.recreate();
            assertEquals(View.VISIBLE, controller.get().findViewById(R.id.home_scroll).getVisibility());
        }
    }
}
