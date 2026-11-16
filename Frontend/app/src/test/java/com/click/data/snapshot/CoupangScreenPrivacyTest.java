package com.click.data.snapshot;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoupangScreenPrivacyTest {
    private SettingsScreenPrivacy privacy;
    @Before public void load() throws Exception {
        try (java.io.InputStream vocabulary = getClass().getResourceAsStream("/vision_vocabulary.json");
             java.io.InputStream policy = getClass().getResourceAsStream("/screen_privacy_policy.json")) {
            privacy = SettingsScreenPrivacy.load(vocabulary, policy);
        }
    }
    private JSONObject node(String id, String text) throws Exception {
        return new JSONObject().put("node_id", id).put("text", text).put("package_name", "com.coupang.mobile")
            .put("bounds_in_screen", new JSONArray(new int[]{10, 10, 400, 100})).put("clickable", true);
    }
    private JSONObject request(JSONObject... nodes) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject node : nodes) array.put(node);
        return new JSONObject().put("accessibility_tree", new JSONObject()
            .put("package_name", "com.coupang.mobile").put("nodes", array));
    }
    @Test public void exportsNativeMenusButNeverCustomerProductOrInputContents() throws Exception {
        JSONObject input = request(node("title", "배송지 관리"), node("my", "마이쿠팡"),
            node("customer", "김민수 owner@example.com"),
            node("private-label", "장바구니").put("resource_id", "com.coupang.mobile:id/receiver_name"),
            node("input", "secret-123").put("class_name", "android.widget.EditText"),
            node("product", "고객이 선택한 상품"));
        String output = privacy.filter(input, "commerce").toString();
        assertTrue(output.contains("배송지 관리"));
        assertTrue(output.contains("마이쿠팡"));
        for (String privateText : new String[]{"김민수", "owner@example.com", "secret-123", "고객이 선택한 상품", "장바구니"})
            assertFalse(privateText, output.contains(privateText));
        assertTrue(input.toString().contains("secret-123"));
    }
    @Test public void commerceRequiresItsExplicitScopeAndExactPackage() throws Exception {
        try { privacy.filter(request(node("cart", "장바구니"))); fail(); }
        catch (SettingsScreenPrivacy.Blocked expected) {}
        JSONObject foreign = request(node("cart", "장바구니"));
        foreign.getJSONObject("accessibility_tree").put("package_name", "com.coupang.mobile.forged");
        try { privacy.filter(foreign, "commerce"); fail(); }
        catch (SettingsScreenPrivacy.Blocked expected) {}
    }
    @Test public void nativeLoginMenusCanBeObservedWhileCredentialsStayLocal() throws Exception {
        String exported = privacy.filter(request(node("title", "로그인"),
            node("password", "do-not-export").put("role", "text_input")), "commerce").toString();
        assertTrue(exported.contains("로그인"));
        assertFalse(exported.contains("do-not-export"));
    }
    @Test public void launcherExportsOnlyTheRequestedAppIcon() throws Exception {
        JSONObject input = request(node("coupang", "쿠팡"), node("settings", "설정"));
        JSONObject tree = input.getJSONObject("accessibility_tree");
        tree.put("package_name", "com.android.launcher3");
        JSONArray nodes = tree.getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) nodes.getJSONObject(i).put("package_name", "com.android.launcher3");
        String output = privacy.filter(input, "commerce").toString();
        assertTrue(output.contains("쿠팡"));
        assertFalse(output.contains("설정"));
    }
}
