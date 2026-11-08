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

    @Test public void highResolutionSamsungBrightnessSurvivesPrivacyAsAStableRatio() throws Exception {
        AccessibilityNodeSnapshot slider = new AccessibilityNodeSnapshot("brightness", "title", true,
                "com.android.settings", null, "156237824", "com.android.settings:id/brightness_seekbar",
                "android.widget.SeekBar", "slider", new int[]{158, 1218, 1012, 1308},
                false, true, false, false, false, 156237824f, 7, false, 0f, 267386880f);
        JSONObject exported = privacy.filter(request(node("title", "디스플레이"), slider.toJson()));
        JSONObject result = exported.getJSONObject("accessibility_tree").getJSONArray("nodes").getJSONObject(1);
        JSONObject range = result.getJSONObject("extras");
        assertEquals(3, range.length());
        assertEquals(0, range.getInt("min")); assertEquals(1, range.getInt("max"));
        assertEquals(149d / 255d, range.getDouble("progress"), .000001);
        assertTrue(result.isNull("content_description"));
        assertEquals(156237824f, slider.progress, 0f);
        assertEquals(267386880f, slider.rangeMax, 0f);
        AccessibilityNodeSnapshot changed = new AccessibilityNodeSnapshot("brightness", "title", true,
                "com.android.settings", null, null, "com.android.settings:id/brightness_seekbar",
                "android.widget.SeekBar", "slider", new int[]{158, 1218, 1012, 1308},
                false, true, false, false, false, 182976512f, 7, false, 0f, 267386880f);
        JSONObject next = changed.toJson().getJSONObject("extras");
        assertEquals(range.getDouble("min"), next.getDouble("min"), 0);
        assertEquals(range.getDouble("max"), next.getDouble("max"), 0);
        assertTrue(next.getDouble("progress") > range.getDouble("progress"));
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

    @Test public void unrecognizedSettingsNeedsAnotherReadAndExportsNoUnapprovedText() throws Exception {
        for (JSONObject input : new JSONObject[]{request(node("container", null)),
                request(node("back", null).put("content_description", "뒤로")),
                request(node("title", "처음 보는 제조사 화면"))}) {
            try { privacy.filter(input); fail("An unreadable Settings observation must stay local"); }
            catch (SettingsScreenPrivacy.Incomplete expected) { }
        }
        assertTrue(privacy.filter(request(node("title", "디스플레이"))).toString().contains("디스플레이"));
        assertTrue(privacy.filter(request(node("title", "글자 크기와 스타일"))).toString().contains("글자 크기와 스타일"));
    }

    @Test public void unsupportedAppDoesNotClaimPersonalInformationWasFound() throws Exception {
        JSONObject input = request(node("title", "글자 크기"));
        input.getJSONObject("accessibility_tree").put("package_name", "com.other.app");
        try { privacy.filter(input); fail("The other app must remain blocked"); }
        catch (SettingsScreenPrivacy.Blocked blocked) {
            assertEquals(SettingsScreenPrivacy.Blocked.Reason.UNSUPPORTED_APP, blocked.reason);
            String message = com.click.data.remote.ErrorDetails.requestMessage(blocked);
            assertFalse(message.contains("개인정보"));
            assertTrue(message.contains("설정 화면"));
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
    @Test public void soundModesRemainContextAndNeverBecomeClickableNavigationCandidates() throws Exception {
        for(String resource:new String[]{"low_button","mid_button","high_button"}) {
            JSONObject mode=node("mode",null).put("resource_id","com.android.settings:id/"+resource)
                    .put("class_name","android.widget.LinearLayout").put("role","menu_item").put("clickable",true);
            JSONObject title=node("mode-label","소리").put("parent_id","mode").put("clickable",true);
            JSONObject menu=node("volume-menu","음량").put("role","menu_item").put("clickable",true);
            JSONArray safe=privacy.filter(request(node("title","소리 및 진동"),mode,title,menu))
                    .getJSONObject("accessibility_tree").getJSONArray("nodes");
            for(int n=0;n<safe.length();n++) {
                JSONObject value=safe.getJSONObject(n);String id=value.getString("node_id");
                if(id.equals("mode") || id.equals("mode-label"))assertFalse(value.getBoolean("clickable"));
                if(id.equals("mode-label"))assertEquals("소리",value.getString("text"));
                if(id.equals("volume-menu"))assertTrue(value.getBoolean("clickable"));
            }
        }
    }
    @Test public void confirmedSettingScopeHidesOnlyKnownOtherSliders() throws Exception {
        JSONObject brightness=node("brightness",null).put("role","slider").put("resource_id","com.android.settings:id/brightness_seekbar")
            .put("extras",new JSONObject().put("progress",.6).put("min",0).put("max",1));
        JSONObject font=node("font",null).put("role","slider").put("resource_id","com.android.settings:id/seekBarForFontSize")
            .put("extras",new JSONObject().put("progress",3).put("min",0).put("max",7));
        JSONObject media=node("media","미디어").put("role","slider").put("resource_id","android:id/seekbar")
            .put("extras",new JSONObject().put("progress",3).put("min",0).put("max",15));
        JSONObject input=request(node("title","디스플레이"),brightness,font,media);
        for(String setting:new String[]{"font_size","brightness","volume"}) {
            String safe=privacy.filter(input,setting).toString();
            assertEquals(setting.equals("brightness"),safe.contains("brightness_seekbar"));
            assertEquals(setting.equals("font_size"),safe.contains("seekBarForFontSize"));
            assertTrue("Unknown channel identities must remain available for grounding",safe.contains("android:id/seekbar"));
            assertTrue(safe.contains("디스플레이"));
        }
        assertTrue(privacy.filter(input).toString().contains("brightness_seekbar"));
        assertTrue(input.toString().contains("brightness_seekbar"));
    }
    @Test public void sharedObservationCasesMatchBackendAcrossTaskFamilies() throws Exception {
        JSONArray cases;
        try (java.io.InputStream in = getClass().getResourceAsStream("/fixtures/observation-privacy-cases.json")) {
            assertNotNull(in);
            cases = new JSONArray(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        for (int i = 0; i < cases.length(); i++) {
            JSONObject value = cases.getJSONObject(i);
            JSONObject body = new JSONObject().put("accessibility_tree", new JSONObject()
                    .put("package_name", "com.android.settings").put("nodes", value.getJSONArray("nodes")));
            String expected = value.getString("expected");
            try {
                JSONObject safe = privacy.filter(body, value.getString("setting"));
                assertEquals(value.getString("name"), "allowed", expected);
                JSONArray nodes = safe.getJSONObject("accessibility_tree").getJSONArray("nodes");
                boolean control = false;
                for (int j = 0; j < nodes.length(); j++) {
                    JSONObject node = nodes.getJSONObject(j);
                    assertFalse(value.getString("name"), node.optString("node_id").startsWith("private"));
                    control |= "control".equals(node.optString("node_id"));
                }
                assertTrue(value.getString("name"), control);
            } catch (SettingsScreenPrivacy.Blocked blocked) {
                assertNotEquals(value.getString("name"), "allowed", expected);
                assertEquals(value.getString("name"), expected.equals("sensitive_input")
                        ? SettingsScreenPrivacy.Blocked.Reason.SENSITIVE_INPUT : SettingsScreenPrivacy.Blocked.Reason.SENSITIVE_SCREEN,
                        blocked.reason);
            }
        }
    }

    @Test public void nativeTextNeedsNoScreenshotMaskButImagesStillNeedProof() throws Exception {
        JSONObject body = request(node("title", "디스플레이"), node("brightness", "밝기").put("role", "slider"));
        assertNotNull(privacy.filter(body, "brightness", false));
        body.put("vision_snapshot", new JSONObject().put("package_name", "com.android.settings").put("redaction_applied", true));
        try { privacy.filter(body, "brightness", false); fail("Unproven image masking must remain blocked"); }
        catch (SettingsScreenPrivacy.Blocked blocked) { assertEquals(SettingsScreenPrivacy.Blocked.Reason.VISION_MASK_UNAVAILABLE, blocked.reason); }
    }

}
