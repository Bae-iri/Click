package com.click.data.snapshot;

import android.app.Application;
import com.click.data.remote.CoupangBrowseGateway;
import com.click.domain.goal.GoalPreparationState;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.time.Instant;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class CoupangBrowsePrivacyTest {
    private static final String PKG="com.coupang.mobile";
    private AccessibilityNodeSnapshot node(String resource,String label,boolean sensitive,boolean password) {
        return new AccessibilityNodeSnapshot(resource,null,true,PKG,label,null,PKG+":id/"+resource,
            sensitive?"android.widget.EditText":"android.widget.TextView",null,new int[]{0,20,300,120},
            true,true,false,false,false,null,42,sensitive,null,null,password);
    }
    private ScreenSnapshot screen(AccessibilityNodeSnapshot... nodes) {
        return new ScreenSnapshot(PKG,Instant.now().toString(),1080,2400,List.of(nodes),42);
    }
    @Test public void actualCoupangSearchFieldNeedsBothItsSearchParentAndPublicSearchPage() throws Exception {
        var privacy=new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication());
        var bar=node("rds_search_bar",null,false,false);
        var field=new AccessibilityNodeSnapshot("input",bar.nodeId,true,PKG,"무선 이어폰",null,PKG+":id/edit_text",
            "android.widget.EditText",null,new int[]{30,25,290,110},true,true,false,false,false,null,42,true,null,null,false);
        var page=node("search_home_container",null,false,false);
        JSONObject safe=privacy.filter(screen(bar,field,page));
        assertEquals("public",safe.getString("status"));
        assertEquals(CoupangBrowsePrivacy.queryHash("무선 이어폰"),safe.getString("query_hash"));
        assertFalse(safe.toString().contains("무선 이어폰"));
        assertEquals("sensitive",privacy.filter(screen(bar,field)).getString("status"));
        var outside=new AccessibilityNodeSnapshot("unrelated",null,true,PKG,"private-address",null,PKG+":id/edit_text",
            "android.widget.EditText",null,new int[]{30,25,290,110},true,true,false,false,false,null,42,true,null,null,false);
        JSONObject blocked=privacy.filter(screen(bar,page,outside));
        assertEquals("sensitive",blocked.getString("status"));assertFalse(blocked.has("query_hash"));
    }
    @Test public void searchInputExportsOnlyItsHashAndPublicProductControls() throws Exception {
        var input=node("search_input","무선 이어폰",true,false);
        var view=screen(input,node("search_results","",false,false),node("product_title","무선 이어폰",false,false),
            node("buy_now","바로 구매",false,false));
        JSONObject result=new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication()).filter(view);
        assertNull(input.text);
        assertEquals(CoupangBrowsePrivacy.queryHash("무선이어폰"),result.getString("query_hash"));
        assertEquals("public",result.getString("status"));
        assertFalse(result.toString().contains("바로 구매"));
        assertEquals("",result.getJSONArray("nodes").getJSONObject(0).getString("label"));
    }
    @Test public void passwordAndUnknownInputsBlockAllScreenContent() throws Exception {
        var privacy=new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication());
        for(var input:List.of(node("search_input","private-password",true,true),node("address","private-address",true,false))) {
            JSONObject result=privacy.filter(screen(node("product_title","상품",false,false),input));
            assertEquals("sensitive",result.getString("status"));
            assertEquals(0,result.getJSONArray("nodes").length());assertFalse(result.has("query_hash"));
            assertFalse(result.toString().contains("private"));
        }
    }
    @Test public void personalPageAndOtherAppAreNeverExported() throws Exception {
        var privacy=new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication());
        assertEquals("sensitive",privacy.filter(screen(node("heading","주문/결제",false,false))).getString("status"));
        var outside=new ScreenSnapshot("another.app",Instant.now().toString(),1080,2400,
            List.of(node("product_title","개인 내용",false,false)),42);
        JSONObject result=privacy.filter(outside);
        assertEquals("outside_app",result.getString("status"));assertFalse(result.toString().contains("개인 내용"));
    }
    @Test public void responseCannotInventAnActionTargetOrVersion() throws Exception {
        var privacy=new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication());
        JSONObject observation=privacy.filter(screen(node("search_bar","검색",false,false)));
        JSONObject request=new JSONObject().put("session_id","session").put("request_id","request")
            .put("expected_version",0).put("command","start").put("screen",observation);
        JSONObject response=new JSONObject().put("protocol_version","coupang-browse.v1").put("manual_only",true)
            .put("session_id","session").put("request_id","request").put("session_version",1)
            .put("target_node_id","buy_now").put("target_bounds",new JSONArray(new int[]{0,20,300,120}))
            .put("observation_id",observation.getString("observation_id"));
        try {CoupangBrowseGateway.validate(request,response);fail("Invented purchase target");}
        catch(java.io.IOException expected){}
        response.put("target_node_id","search_bar");
        CoupangBrowseGateway.validate(request,response);
        response.put("session_version",3);
        try {CoupangBrowseGateway.validate(request,response);fail("Wrong version");}
        catch(java.io.IOException expected){}
    }
    @Test public void confirmedBrowseUsesNoSyntheticTaskGraph() throws Exception {
        JSONObject data=new JSONObject().put("version","goal-inputs.v1").put("id","prepared")
            .put("source_text","쿠팡 상품 검색").put("execution_mode","browse").put("status","ready")
            .put("missing_fields",new JSONArray()).put("browse_goal",new JSONObject()
                .put("goal_type","coupang.product.search").put("query","이어폰"));
        GoalPreparationState state=new GoalPreparationState(data,null,"상품 검색을 안내할까요?");
        assertTrue(state.browsing());assertTrue(state.ready());assertNull(state.goal);
        data.remove("browse_goal");assertFalse(state.ready());
    }
}
