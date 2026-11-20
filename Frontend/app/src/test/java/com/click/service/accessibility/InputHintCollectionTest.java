package com.click.service.accessibility;

import android.app.Application;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.data.snapshot.SettingsScreenPrivacy;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class InputHintCollectionTest {
    private AccessibilityNodeInfo gov24Input(String text, String description, boolean showingHint, boolean password) {
        AccessibilityNodeInfo node=mock(AccessibilityNodeInfo.class);
        when(node.getClassName()).thenReturn("android.widget.EditText");
        when(node.getPackageName()).thenReturn("kr.go.minwon.m");
        when(node.getText()).thenReturn(text);when(node.getContentDescription()).thenReturn(description);
        when(node.isShowingHintText()).thenReturn(showingHint);when(node.isPassword()).thenReturn(password);
        when(node.isEditable()).thenReturn(true);when(node.isVisibleToUser()).thenReturn(true);when(node.isEnabled()).thenReturn(true);
        doAnswer(call->{((Rect)call.getArgument(0)).set(0,200,1000,350);return null;}).when(node).getBoundsInScreen(any(Rect.class));
        return node;
    }
    private com.click.data.snapshot.AccessibilityNodeSnapshot collect(AccessibilityNodeInfo node) {
        return new AccessibilitySnapshotCollector().collect(node,"kr.go.minwon.m",1080,2400,null).nodes.get(0);
    }
    @Test public void governmentWebViewSearchPlaceholdersAreRecognizedWithoutExportingValues() {
        assertTrue(collect(gov24Input("검색어를 입력하세요",null,true,false)).publicSearchInput());
        var filled=collect(gov24Input("PRIVATE_QUERY","검색어를 입력하세요",false,false));
        assertTrue(filled.publicSearchInput());assertNull(filled.text);assertNull(filled.contentDescription);
    }
    @Test public void installedGovernmentHomeSearchHintDoesNotBlockPublicNavigation() throws Exception {
        var input=gov24Input("PRIVATE_QUERY",null,false,false);
        when(input.getHintText()).thenReturn("정부 서비스를 쉽게 검색하세요.");
        var field=collect(input);assertTrue(field.publicSearchInput());
        var brand=new com.click.data.snapshot.AccessibilityNodeSnapshot("brand",null,true,"kr.go.minwon.m","정부24",null,null,
            "android.widget.TextView","text",new int[]{0,20,200,100},false,true,false,false,false,null,field.windowId);
        var services=new com.click.data.snapshot.AccessibilityNodeSnapshot("services",null,true,"kr.go.minwon.m","자주 찾는 서비스",null,null,
            "android.widget.TextView","text",new int[]{0,400,600,500},false,true,false,false,false,null,field.windowId);
        var login=new com.click.data.snapshot.AccessibilityNodeSnapshot("login",null,true,"kr.go.minwon.m","로그인",null,null,
            "android.widget.Button","button",new int[]{600,20,800,100},true,true,false,false,false,null,field.windowId);
        var screen=new ScreenSnapshot("kr.go.minwon.m",java.time.Instant.now().toString(),1080,2400,java.util.List.of(brand,services,login,field),field.windowId);
        var safe=new com.click.data.snapshot.Gov24LoginPrivacy().filter(screen);
        assertEquals("public",safe.getString("status"));assertEquals("signed_out",safe.getString("auth_state"));
        assertFalse(safe.toString().contains("PRIVATE_QUERY"));assertFalse(safe.toString().contains("text_field"));
        when(input.isPassword()).thenReturn(true);
        assertFalse(collect(input).publicSearchInput());
    }
    @Test public void filledValuesAndPasswordHintsCannotIdentifyAPublicSearch() {
        assertFalse(collect(gov24Input("검색어를 입력하세요",null,false,false)).publicSearchInput());
        assertFalse(collect(gov24Input("검색어를 입력하세요",null,true,true)).publicSearchInput());
        assertFalse(collect(gov24Input(null,"이름",false,false)).publicSearchInput());
    }
    @Test public void nativeHintIsNotMistakenForAUserQueryOnAnySettingsPage() throws Exception {
        for (boolean hint : new boolean[]{true, false}) {
            AccessibilityNodeInfo node = mock(AccessibilityNodeInfo.class);
            when(node.getClassName()).thenReturn("android.widget.EditText");
            when(node.getPackageName()).thenReturn("com.android.settings");
            when(node.getViewIdResourceName()).thenReturn("android:id/search_src_text");
            when(node.getText()).thenReturn(hint ? "검색" : "PRIVATE_QUERY");
            when(node.isShowingHintText()).thenReturn(hint);
            when(node.isEditable()).thenReturn(true);
            when(node.isVisibleToUser()).thenReturn(true);
            when(node.isEnabled()).thenReturn(true);
            doAnswer(call -> { ((Rect) call.getArgument(0)).set(0, 200, 1000, 350); return null; })
                    .when(node).getBoundsInScreen(any(Rect.class));
            ScreenSnapshot snapshot = new AccessibilitySnapshotCollector().collect(node, "com.android.settings", 1080, 2400, null);
            JSONObject raw = new JSONObject(snapshot.toJson()).getJSONArray("nodes").getJSONObject(0);
            assertTrue(raw.isNull("text"));
            assertEquals(!hint, raw.getJSONObject("extras").optBoolean("input_content_present"));
            assertFalse(snapshot.toJson().contains("PRIVATE_QUERY"));
        }
    }
}
