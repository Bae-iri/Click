package com.click.data.snapshot;

import android.app.Application;
import java.time.Instant;
import java.util.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class CoupangScreenGuideTest {
    private AccessibilityNodeSnapshot node(String id,String parent,String pkg,String resource,String text,boolean clickable,int... bounds) {
        return new AccessibilityNodeSnapshot(id,parent,true,pkg,text,null,resource,"android.widget.TextView",null,
            bounds,clickable,true,false,false,false,null,42);
    }
    private ScreenSnapshot screen(String pkg,AccessibilityNodeSnapshot... nodes) {
        return new ScreenSnapshot(pkg,Instant.now().toString(),1080,2400,List.of(nodes),42);
    }
    @Test public void capturedDeviceNetworkErrorIsReadAndItsActualRetryButtonIsLocated() throws Exception {
        var screen=deviceScreen();
        var guide=CoupangScreenGuide.identify(screen);
        assertNotNull(guide);assertEquals(CoupangScreenGuide.Kind.NETWORK_ERROR,guide.kind);
        assertEquals("com.coupang.mobile:id/error_view_first_button",guide.target.resourceId);
        assertArrayEquals(new int[]{343,874,736,985},guide.target.bounds);
        assertTrue(guide.message.contains("네트워크 연결"));assertFalse(guide.message.contains("성장"));
        assertTrue(guide.sameStep(CoupangScreenGuide.identify(screen)));
    }
    @Test public void nativeAliasesAndUnlabelledSearchBarSurvivePrivacyWithoutInputText() throws Exception {
        SettingsScreenPrivacy privacy;
        try(var v=getClass().getResourceAsStream("/vision_vocabulary.json");var p=getClass().getResourceAsStream("/screen_privacy_policy.json")) {
            privacy=SettingsScreenPrivacy.load(v,p);
        }
        JSONObject output=privacy.filter(new JSONObject().put("accessibility_tree",new JSONObject(deviceScreen().toJson())),"commerce");
        String text=output.toString();
        assertTrue(text.contains("상품 검색"));assertTrue(text.contains("카테고리"));assertTrue(text.contains("홈"));
        assertFalse(text.contains("네트워크 연결"));
    }
    @Test public void publicAliasInRecentSearchHistoryDoesNotBecomeANavigationTarget() throws Exception {
        SettingsScreenPrivacy privacy;
        try(var v=getClass().getResourceAsStream("/vision_vocabulary.json");var p=getClass().getResourceAsStream("/screen_privacy_policy.json")) {
            privacy=SettingsScreenPrivacy.load(v,p);
        }
        var root=node("history",null,"com.coupang.mobile","com.coupang.mobile:id/search_home_container",null,false,0,300,1080,900);
        var query=node("query","history","com.coupang.mobile","com.coupang.mobile:id/text_view","전체 카테고리",true,10,400,800,500);
        var bar=node("bar",null,"com.coupang.mobile","com.coupang.mobile:id/rds_search_bar",null,true,10,100,1000,240);
        var snapshot=screen("com.coupang.mobile",root,query,bar);
        String safe=privacy.filter(new JSONObject().put("accessibility_tree",new JSONObject(snapshot.toJson())),"commerce").toString();
        assertTrue(safe.contains("상품 검색"));assertFalse(safe.contains("카테고리"));
    }
    private ScreenSnapshot deviceScreen() throws Exception {
        JSONObject fixture;
        try(var input=getClass().getResourceAsStream("/coupang-native-network.json")) {
            fixture=new JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }
        List<AccessibilityNodeSnapshot> nodes=new ArrayList<>();
        JSONArray rows=fixture.getJSONArray("nodes");
        for(int i=0;i<rows.length();i++) {
            var row=rows.getJSONObject(i);var parts=row.getString("bounds").split("[^0-9]+");
            var values=new ArrayList<Integer>();for(String p:parts)if(!p.isEmpty())values.add(Integer.parseInt(p));
            int[] b=values.stream().mapToInt(Integer::intValue).toArray();
            if(b[0]>=b[2] || b[1]>=b[3])continue;
            boolean input=row.optString("class").contains("EditText");
            nodes.add(new AccessibilityNodeSnapshot(row.getString("node_id"),row.optString("parent_id",null),true,
                "com.coupang.mobile",row.optString("text",null),row.optString("description",null),row.optString("resource-id"),
                row.optString("class"),null,b,"true".equals(row.optString("clickable")),"true".equals(row.optString("enabled")),
                false,"true".equals(row.optString("scrollable")),false,null,42,input));
        }
        return new ScreenSnapshot("com.coupang.mobile",Instant.now().toString(),1080,2400,nodes,42);
    }
}
