package com.click.data.snapshot;

import java.time.Instant;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Public home navigation must work even when WebView omits the search field's metadata. */
public class Gov24HomeLoginObservationTest {
    private static final String PKG=Gov24LoginPrivacy.PACKAGE;
    private AccessibilityNodeSnapshot node(String id,String parent,String text,boolean click,boolean input,boolean password) {
        return new AccessibilityNodeSnapshot(id,parent,true,PKG,text,null,null,
            input?"android.widget.EditText":"android.view.View",input?"text_field":null,
            new int[]{20,100,300,180},click,true,false,false,false,null,42,input,null,null,password,null,false,false);
    }
    private ScreenSnapshot home(String marker,AccessibilityNodeSnapshot... extra) {
        var nodes=new ArrayList<>(List.of(node("brand",null,"정부24",false,false,false),
            node("services",null,marker,false,false,false),
            node("login",null,null,true,false,false),node("label","login","로그인",false,false,false),
            node("query",null,"PRIVATE_QUERY",true,true,false),
            node("query-child","query","PRIVATE_CHILD",false,false,false)));
        nodes.addAll(List.of(extra));
        return new ScreenSnapshot(PKG,Instant.now().toString(),1080,2400,nodes,42);
    }
    @Test public void unidentifiedHomeSearchAllowsOnlyThePublicLoginButton() throws Exception {
        for(String marker:List.of("자주 찾는 서비스","민원서비스")) {
            JSONObject out=AppTaskObservation.capture(home(marker));
            assertEquals("public",out.getString("status"));
            assertEquals(1,out.getJSONArray("nodes").length());
            JSONObject login=out.getJSONArray("nodes").getJSONObject(0);
            assertEquals("login",login.getString("id"));assertEquals("로그인",login.getString("label"));
            assertFalse(login.getBoolean("editable"));
            assertFalse(out.toString().contains("PRIVATE"));assertFalse(out.toString().contains("검색어 입력"));
        }
    }
    @Test public void authenticationAndAmbiguousScreensStayProtected() throws Exception {
        for(var extra:List.of(node("password",null,"SECRET",true,true,true),
            node("second-input",null,"PRIVATE_ID",true,true,false),
            node("consent",null,"서비스 이용에 대한 동의",true,false,false),
            node("method",null,"간편인증",true,false,false),
            node("certificate",null,"인증서",true,false,false),
            node("logout",null,"로그아웃",true,false,false),
            node("other-login",null,"로그인",true,false,false))) {
            JSONObject out=AppTaskObservation.capture(home("민원서비스",extra));
            assertEquals(extra.nodeId,"protected",out.getString("status"));assertEquals(0,out.getJSONArray("nodes").length());
        }
        assertEquals("protected",AppTaskObservation.capture(home("다른 화면")).getString("status"));
        var base=home("민원서비스");
        assertEquals("protected",AppTaskObservation.capture(new ScreenSnapshot(PKG,base.capturedAt,1080,2400,base.nodes,42,false)).getString("status"));
        assertEquals("protected",AppTaskObservation.capture(new ScreenSnapshot("other.app",base.capturedAt,1080,2400,base.nodes,42)).getString("status"));
    }
}
