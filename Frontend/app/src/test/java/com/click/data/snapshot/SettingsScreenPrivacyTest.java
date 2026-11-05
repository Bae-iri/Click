package com.click.data.snapshot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class SettingsScreenPrivacyTest {
    private SettingsScreenPrivacy privacy;

    @Before public void loadSharedPolicy() throws Exception {
        try (java.io.InputStream vocabulary = getClass().getResourceAsStream("/vision_vocabulary.json");
             java.io.InputStream policy = getClass().getResourceAsStream("/screen_privacy_policy.json")) {
            assertNotNull(vocabulary);
            assertNotNull(policy);
            privacy = SettingsScreenPrivacy.load(vocabulary, policy);
        }
    }

    private JSONObject node(String id, String text) throws Exception {
        return new JSONObject().put("node_id", id).put("text", text)
                .put("package_name", "com.android.settings").put("visible_to_user", true)
                .put("bounds_in_screen", new JSONArray(new int[]{0, 0, 200, 100}));
    }

    private JSONObject request(JSONObject... nodes) throws Exception {
        JSONArray values = new JSONArray();
        for (JSONObject node : nodes) values.put(node);
        return new JSONObject().put("request_id", "request-1").put("accessibility_tree",
                new JSONObject().put("package_name", "com.android.settings").put("nodes", values));
    }

    @Test public void dropsAccountNamesEmailsNotificationsAndSummariesFromActualJson() throws Exception {
        JSONObject input = request(node("title", "설정"), node("name", "김민수"),
                node("email", "owner@example.com"), node("alert", "친구에게서 새 메시지가 왔습니다"),
                node("menu", "디스플레이").put("content_description", "김민수의 설정 설명"),
                node("summary", "글자 크기").put("resource_id", "android:id/summary"));
        JSONObject exported = privacy.filter(input);
        String json = exported.toString();
        for (String secret : new String[]{"김민수", "owner@example.com", "친구에게서", "글자 크기"})
            assertFalse(secret, json.contains(secret));
        assertTrue(json.contains("설정"));
        assertTrue(json.contains("디스플레이"));
        assertTrue("input is left local", input.toString().contains("owner@example.com"));
    }

    @Test public void preservesSliderHierarchyGeometryAndFiniteValueOnly() throws Exception {
        JSONObject exported = privacy.filter(request(node("title", "글자 크기"),
                node("slider", "알 수 없는 설명 김민수").put("parent_id", "title").put("role", "slider")
                        .put("class_name", "android.widget.SeekBar").put("clickable", true)
                        .put("extras", new JSONObject().put("progress", 3).put("min", 0).put("max", 10)
                                .put("account_name", "김민수").put("value", "1234"))));
        JSONObject slider = exported.getJSONObject("accessibility_tree").getJSONArray("nodes").getJSONObject(1);
        assertEquals("title", slider.getString("parent_id"));
        assertEquals(200, slider.getJSONArray("bounds_in_screen").getInt(2));
        assertEquals(3, slider.getJSONObject("extras").getInt("progress"));
        assertEquals(3, slider.getJSONObject("extras").length());
        assertTrue(slider.isNull("text"));
        assertFalse(exported.toString().contains("김민수"));
    }

    @Test public void blocksSensitiveAndUnknownScreensBeforeHttp() throws Exception {
        for (JSONObject input : new JSONObject[]{
                request(node("title", "삼성 계정"), node("settings", "설정")),
                request(node("title", "알림 기록")),
                request(node("title", "처음 보는 제조사 화면")),
                request(node("title", "글자 크기")).getJSONObject("accessibility_tree")
                        .put("package_name", "com.bank.app"),
                request(node("title", "글자 크기"), node("input", "비밀값")
                        .put("role", "text_field").put("extras", new JSONObject().put("editable", true)))}) {
            // Wrap the intentionally foreign tree in the same HTTP envelope.
            if (input.has("nodes")) input = new JSONObject().put("accessibility_tree", input);
            try { privacy.filter(input); fail("screen must be blocked"); }
            catch (SettingsScreenPrivacy.Blocked expected) { assertEquals("SCREEN_PRIVACY_BLOCKED", expected.getMessage()); }
        }
    }

    @Test public void approvedTitleWithUnknownSuffixNeverPassesThrough() throws Exception {
        JSONObject safe = privacy.filter(request(node("title", "설정"), node("mixed", "디스플레이 김민수")));
        assertEquals(1, safe.getJSONObject("accessibility_tree").getJSONArray("nodes").length());
        assertFalse(safe.toString().contains("mixed"));
    }

    @Test public void launcherExportsOnlySettingsIconAndBlocksNotificationShade() throws Exception {
        JSONObject input = request(node("settings", "설정"), node("apps", "디스플레이"));
        JSONObject tree = input.getJSONObject("accessibility_tree");
        tree.put("package_name", "com.sec.android.app.launcher");
        JSONArray nodes = tree.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) nodes.getJSONObject(i).put("package_name", "com.sec.android.app.launcher");
        assertFalse(privacy.filter(input).toString().contains("디스플레이"));
        tree.put("package_name", "com.android.systemui");
        try { privacy.filter(input); fail("notification shade must be blocked"); }
        catch (SettingsScreenPrivacy.Blocked expected) { }
    }

    @Test public void rechecksOcrElementsAndNeverExportsImagesOrRawOcrText() throws Exception {
        JSONObject input = request(node("title", "글자 크기"));
        input.put("vision_snapshot", new JSONObject().put("package_name", "com.android.settings")
                .put("redaction_applied", true).put("ocr_text", "김민수").put("screenshot_base64", "PRIVATE_IMAGE")
                .put("elements", new JSONArray().put(new JSONObject().put("element_id", "private-name")
                        .put("text", "글자 크기")).put(new JSONObject().put("text", "owner@example.com"))));
        String safe = privacy.filter(input).toString();
        assertFalse(safe.contains("PRIVATE_IMAGE"));
        assertFalse(safe.contains("owner@example.com"));
        assertFalse(safe.contains("김민수"));
        assertFalse(safe.contains("private-name"));
        assertTrue(safe.contains("ocr_0"));
    }

    @Test public void exportsExplicitManufacturerAliasesButDropsUnknownMetadata() throws Exception {
        JSONObject input = request(node("title", "글자 크기 및 스타일"), node("volume", "미디어 볼륨"),
                node("brightness", "Display & touch"), node("switch", "밝기 최적화")
                        .put("role", "switch").put("class_name", "com.vendor.OwnerPrivateSwitch"));
        input.getJSONObject("accessibility_tree").put("account", "owner@example.com")
                .put("applied_settings", new JSONObject().put("account", "owner@example.com")
                        .put("font_scale", 1.2).put("source", "android.provider.Settings.System.FONT_SCALE"));
        JSONObject result = privacy.filter(input);
        String json = result.toString();
        assertTrue(json.contains("글자 크기 및 스타일"));
        assertTrue(json.contains("미디어 볼륨"));
        assertTrue(json.contains("display & touch"));
        assertFalse(json.contains("owner@example.com"));
        assertFalse(json.contains("OwnerPrivateSwitch"));
        assertEquals(1.2, result.getJSONObject("accessibility_tree").getJSONObject("applied_settings").getDouble("font_scale"), 0.001);
        assertEquals("android.widget.Switch", result.getJSONObject("accessibility_tree").getJSONArray("nodes")
                .getJSONObject(3).getString("class_name"));
    }

    @Test public void onlyAnEmptyNonPasswordSettingsSearchFieldCanRemainOnThePage() throws Exception {
        for (String value : new String[]{"", "private-query"}) for (boolean password : new boolean[]{false, true}) {
            AccessibilityNodeSnapshot input = new AccessibilityNodeSnapshot("search", null, true,
                    "com.android.settings", value, "검색", "android:id/search_src_text", "android.widget.EditText",
                    "text_field", new int[]{0, 0, 200, 100}, true, true, false, false, false,
                    null, 1, true, null, null, password);
            JSONObject body = request(node("title", "설정"), input.toJson());
            if (!value.isEmpty() || password) {
                try { privacy.filter(body); fail("search input and passwords must be blocked"); }
                catch (SettingsScreenPrivacy.Blocked expected) { }
            } else {
                String safe = privacy.filter(body).toString();
                assertFalse(safe.contains("검색"));
                assertFalse(safe.contains("private-query"));
            }
        }
    }

    @Test public void privateFieldsNeverBecomeMenuLabelsEvenIfTheirContentIsAllowlisted() throws Exception {
        JSONObject safe = privacy.filter(request(node("title", "설정"),
                node("account", "디스플레이").put("resource_id", "com.android.settings:id/account_name"),
                node("preview", "글자 크기").put("resource_id", "com.android.settings:id/preview_text")));
        assertFalse(safe.toString().contains("디스플레이"));
        assertFalse(safe.toString().contains("글자 크기"));
        assertFalse(safe.toString().contains("account_name"));
    }
}
