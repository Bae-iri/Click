package com.click.service.accessibility;

import android.app.Application;
import android.graphics.Rect;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Native metadata varies by Android version; none of these cases authorize a tap. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 28, 30, 34, 35}, application = Application.class)
public class NativeScreenSemanticsTest {
    private AccessibilityNodeInfo node(String text, String type, boolean heading, AccessibilityNodeInfo... children) {
        var n = mock(AccessibilityNodeInfo.class);
        when(n.getPackageName()).thenReturn("com.android.settings");
        when(n.getText()).thenReturn(text);
        when(n.getClassName()).thenReturn(type);
        when(n.isVisibleToUser()).thenReturn(true);
        when(n.isEnabled()).thenReturn(true);
        when(n.getWindowId()).thenReturn(7);
        if (Build.VERSION.SDK_INT >= 28) when(n.isHeading()).thenReturn(heading);
        else if (heading) when(n.getCollectionItemInfo()).thenReturn(
                AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, 0, 1, true));
        doAnswer(call -> { ((Rect) call.getArgument(0)).set(20, 40, 900, 110); return null; })
                .when(n).getBoundsInScreen(any(Rect.class));
        when(n.getChildCount()).thenReturn(children.length);
        for (int i = 0; i < children.length; i++) when(n.getChild(i)).thenReturn(children[i]);
        return n;
    }
    private ScreenSnapshot collect(AccessibilityNodeInfo... children) {
        var root = node(null, "android.widget.FrameLayout", false, children);
        return new AccessibilitySnapshotCollector().collect(root, "com.android.settings", 1080, 2400, null);
    }
    private JSONObject export(ScreenSnapshot screen) throws Exception {
        try (var v = getClass().getResourceAsStream("/vision_vocabulary.json");
             var p = getClass().getResourceAsStream("/screen_privacy_policy.json")) {
            return SettingsScreenPrivacy.load(v, p).filter(new JSONObject()
                    .put("accessibility_tree", new JSONObject(screen.toJson())));
        }
    }
    private AccessibilityNodeInfo search(String text, String hint, boolean showingHint, boolean password) {
        var input = node(text, "android.widget.EditText", false);
        when(input.isEditable()).thenReturn(true);
        when(input.getHintText()).thenReturn(hint);
        when(input.isShowingHintText()).thenReturn(showingHint);
        when(input.isPassword()).thenReturn(password);
        when(input.getViewIdResourceName()).thenReturn("com.android.settings:id/vendor_query");
        return input;
    }
    @Test public void explicitNativeHeadingIdentifiesVirtualAndTraditionalTitles() throws Exception {
        for (String type : new String[]{"android.widget.TextView", "android.view.View", "android.widget.LinearLayout"}) {
            var body = node("설정", "android.widget.TextView", false);
            doAnswer(call -> { ((Rect) call.getArgument(0)).set(20, 0, 900, 30); return null; })
                    .when(body).getBoundsInScreen(any(Rect.class));
            var screen = collect(body, node("디스플레이", type, true));
            assertEquals("디스플레이", ScreenIdentity.pageTitle(screen));
            assertEquals(ScreenEnvironment.Kind.SETTINGS_SUBPAGE,
                    ScreenEnvironment.classify(screen, true, false, "com.click").kind());
            String safe = export(screen).toString();
            assertTrue(safe.contains("디스플레이"));
            assertFalse("Local heading metadata is not a server action capability", safe.contains("\"heading\""));
        }
    }
    @Test public void emptyNativeSearchHintDoesNotMakeTheSettingsPagePrivate() throws Exception {
        for (String hint : new String[]{"검색", "Search"}) {
            var screen = collect(node("디스플레이", "android.view.View", true), search(hint, hint, true, false));
            assertEquals("디스플레이", ScreenIdentity.pageTitle(screen));
            assertEquals(ScreenEnvironment.Kind.SETTINGS_SUBPAGE,
                    ScreenEnvironment.classify(screen, true, false, "com.click").kind());
            String safe = export(screen).toString();
            assertFalse(safe.contains("vendor_query"));
            assertFalse(safe.contains("empty_search_input"));
            assertFalse(safe.contains("editable"));
        }
    }
    @Test public void queryPasswordAndUnknownEditorsStillStopBeforeHttp() throws Exception {
        for (AccessibilityNodeInfo input : new AccessibilityNodeInfo[]{
                search("PRIVATE_QUERY", "검색", false, false),
                search(null, "검색", false, true),
                search(null, "입력", false, false)}) {
            var screen = collect(node("디스플레이", "android.view.View", true), input);
            assertNull(ScreenIdentity.pageTitle(screen));
            assertEquals(ScreenEnvironment.Kind.SENSITIVE,
                    ScreenEnvironment.classify(screen, true, false, "com.click").kind());
            assertFalse(screen.toJson().contains("PRIVATE_QUERY"));
            assertThrows(SettingsScreenPrivacy.Blocked.class, () -> export(screen));
        }
    }
    @Test public void inputValueExposedOnlyAsDescriptionIsStillPrivate() throws Exception {
        var input = search(null, "검색", false, false);
        when(input.getContentDescription()).thenReturn("PRIVATE_DESCRIPTION");
        var screen = collect(node("디스플레이", "android.view.View", true), input);
        assertNull(ScreenIdentity.pageTitle(screen));
        assertFalse(screen.toJson().contains("PRIVATE_DESCRIPTION"));
        assertThrows(SettingsScreenPrivacy.Blocked.class, () -> export(screen));
    }
    @Test public void unmarkedVirtualBodyTextDoesNotInventAPageTitle() {
        assertNull(ScreenIdentity.pageTitle(collect(node("디스플레이", "android.view.View", false))));
    }
    @Test public void nativeToolbarResourceSuppliesTitleOnOlderUnmarkedViews() {
        var title = node("디스플레이", "android.view.View", false);
        when(title.getViewIdResourceName()).thenReturn("com.android.settings:id/toolbar_title");
        assertEquals("디스플레이", ScreenIdentity.pageTitle(collect(title)));
    }
    @Test public void headingsInsideInteractiveOrScrollableRowsAreNotPageTitles() {
        for (boolean scrolling : new boolean[]{false, true}) {
            var row = node(null, "android.widget.FrameLayout", false, node("디스플레이", "android.view.View", true));
            when(row.isScrollable()).thenReturn(scrolling);
            when(row.isClickable()).thenReturn(!scrolling);
            assertNull(ScreenIdentity.pageTitle(collect(row)));
        }
    }
    @Test public void nativeRadioButtonRetainsItsSelectionRole() throws Exception {
        var radio = node("사용", "android.widget.RadioButton", false);
        when(radio.isCheckable()).thenReturn(true);
        when(radio.isChecked()).thenReturn(true);
        var raw = new JSONObject(collect(radio).toJson()).getJSONArray("nodes").getJSONObject(1);
        assertEquals("radio_button", raw.getString("role"));
        assertTrue(raw.getJSONObject("extras").getBoolean("checked"));
    }
}
