package com.click.data.snapshot;
import android.app.Application;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=34,application=Application.class)
public class Gov24LoginPrivacyTest {
    private AccessibilityNodeSnapshot node(String id,String label,boolean input,boolean click) {
        return new AccessibilityNodeSnapshot(id,null,true,Gov24LoginPrivacy.PACKAGE,label,null,null,
            input?"android.widget.EditText":"android.widget.Button",input?"text_field":"button",
            new int[]{30,100,400,200},click,true,false,false,false,null,42,input,null,null,false);
    }
    private ScreenSnapshot screen(AccessibilityNodeSnapshot... nodes) {
        return new ScreenSnapshot(Gov24LoginPrivacy.PACKAGE,java.time.Instant.now().toString(),1080,2400,List.of(nodes),42);
    }
    @Test public void resourceLessWebViewButtonsAreSemanticCandidatesWithoutRawLabels() throws Exception {
        var result=new Gov24LoginPrivacy().filter(screen(node("a","간편인증",false,true),node("b","공동인증서",false,true),node("private","홍길동님",false,false)));
        assertEquals("public",result.getString("status"));assertEquals(2,result.getJSONArray("nodes").length());
        assertFalse(result.toString().contains("홍길동"));assertEquals("",result.getJSONArray("nodes").getJSONObject(0).getString("resource_id"));
    }
    @Test public void credentialInputStopsAllExportEvenIfUnderlyingMethodsAreVisible() throws Exception {
        var result=new Gov24LoginPrivacy().filter(screen(node("a","간편인증",false,true),node("pii","010-1234-5678",true,false)));
        assertEquals("handoff",result.getString("status"));assertEquals("credential_input",result.getString("handoff_reason"));
        assertEquals(0,result.getJSONArray("nodes").length());assertFalse(result.toString().contains("010"));
    }
    @Test public void providerDialogSupersedesUnderlyingMethodsAndAmbiguousDuplicatesAreExcluded() throws Exception {
        var result=new Gov24LoginPrivacy().filter(screen(node("a","간편인증",false,true),node("b","KAKAO(카카오)",false,true),node("c","NAVER(네이버)",false,true)));
        assertEquals(2,result.getJSONArray("nodes").length());assertEquals("provider_kakao",result.getJSONArray("nodes").getJSONObject(0).getString("option_id"));
        result=new Gov24LoginPrivacy().filter(screen(node("a","간편인증",false,true),node("b","간편인증",false,true)));
        assertEquals("unrecognized",result.getString("status"));
    }
    @Test public void certificateSelectionAndExternalAuthenticationAreHandoffs() throws Exception {
        var consent=new Gov24LoginPrivacy().filter(screen(node("a","간편인증",false,true),node("b","서비스 이용에 대한 동의",false,false)));
        assertEquals("handoff",consent.getString("status"));assertEquals("consent_required",consent.getString("handoff_reason"));assertEquals(0,consent.getJSONArray("nodes").length());
        var result=new Gov24LoginPrivacy().filter(screen(node("a","인증서 선택",false,false)));
        assertEquals("handoff",result.getString("status"));
        result=new Gov24LoginPrivacy().filter(new ScreenSnapshot("com.kakao.talk",java.time.Instant.now().toString(),1080,2400,List.of(node("pii","private",false,false)),42));
        assertEquals("external_auth",result.getString("handoff_reason"));assertEquals(0,result.getJSONArray("nodes").length());
    }
    @Test public void unknownAppNeverExportsContent() throws Exception {
        var result=new Gov24LoginPrivacy().filter(new ScreenSnapshot("other.app",java.time.Instant.now().toString(),1080,2400,List.of(node("pii","private",false,false)),42));
        assertEquals("outside_app",result.getString("status"));assertFalse(result.toString().contains("private"));
    }
    @Test public void civilModeExportsOnlyBoundPublicNavigationAndBoundaryMarkers() throws Exception {
        var result=new Gov24LoginPrivacy(true).filter(screen(node("tile","주민등록등본",false,true),
            node("menu","민원서비스",false,true),node("pii","홍길동님",false,false)));
        assertEquals("public",result.getString("status"));assertEquals(2,result.getJSONArray("nodes").length());
        assertEquals("nav_resident",result.getJSONArray("nodes").getJSONObject(0).getString("option_id"));
        assertFalse(result.toString().contains("홍길동"));
        result=new Gov24LoginPrivacy(true).filter(screen(node("heading","주민등록표 등본(초본) 발급",false,false),
            node("apply","발급하기",false,true)));
        assertEquals("marker_resident",result.getJSONArray("nodes").getJSONObject(0).getString("option_id"));
        assertFalse(result.getJSONArray("nodes").getJSONObject(0).getBoolean("clickable"));
        assertEquals("boundary_apply",result.getJSONArray("nodes").getJSONObject(1).getString("option_id"));
    }
    @Test public void civilModeDoesNotExportCredentialsOrChangeTheLoginFilter() throws Exception {
        var source=screen(node("tile","주민등록등본",false,true));
        assertEquals("unrecognized",new Gov24LoginPrivacy().filter(source).getString("status"));
        var result=new Gov24LoginPrivacy(true).filter(screen(node("tile","주민등록등본",false,true),
            node("input","private",true,false)));
        assertEquals("handoff",result.getString("status"));assertEquals(0,result.getJSONArray("nodes").length());
    }
    @Test public void experimentalCivilLabelsRemainPublicAndRequireEnabledVisibleNodes() throws Exception {
        var result=new Gov24LoginPrivacy(true).filter(screen(node("extract","주민등록초본",false,true),
            node("move","전입신고",false,true),node("building","건축물대장",false,true),
            node("private","홍길동 주소",false,false)));
        assertEquals(3,result.getJSONArray("nodes").length());
        assertFalse(result.toString().contains("홍길동"));
        var detail=new Gov24LoginPrivacy(true).filter(screen(node("title","전입신고",false,false),
            node("apply","신청하기",false,true)));
        assertEquals("marker_move_in",detail.getJSONArray("nodes").getJSONObject(0).getString("option_id"));
        assertFalse(detail.getJSONArray("nodes").getJSONObject(0).getBoolean("clickable"));
        assertTrue(Gov24LoginPrivacy.isPlannerGoal("gov24.move_in.open"));
        assertFalse(Gov24LoginPrivacy.isPlannerGoal("gov24.login"));
    }
}
