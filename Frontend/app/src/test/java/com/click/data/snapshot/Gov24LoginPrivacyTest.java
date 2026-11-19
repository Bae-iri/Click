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
    private AccessibilityNodeSnapshot search(String value,boolean searchIdentity) {
        return new AccessibilityNodeSnapshot("search",null,true,Gov24LoginPrivacy.PACKAGE,value,"검색",null,
            "android.widget.EditText","text_input",new int[]{10,20,600,90},false,true,false,false,false,null,42,
            true,null,null,false,null,false,searchIdentity);
    }
    @Test public void emptyPublicHomeSearchDoesNotBlockServiceNavigation() throws Exception {
        var result=new Gov24LoginPrivacy(true).filter(screen(node("brand","정부24",false,false),
            node("frequent","자주 찾는 서비스",false,false),node("tile","건축물대장",false,true),search(null,true)));
        assertEquals("public",result.getString("status"));
        String exported=result.toString();
        assertTrue(exported.contains("nav_building"));
        assertFalse(exported.contains("text_input"));
        assertFalse(exported.contains("검색"));
    }
    @Test public void filledSearchOrNonSearchFieldStillStopsAllScreenExport() throws Exception {
        for(var input:List.of(search("PRIVATE_QUERY",true),search(null,false))) {
            var result=new Gov24LoginPrivacy(true).filter(screen(node("brand","정부24",false,false),
                node("frequent","자주 찾는 서비스",false,false),node("tile","토지대장",false,true),input));
            assertEquals("handoff",result.getString("status"));
            assertEquals(0,result.getJSONArray("nodes").length());
            assertFalse(result.toString().contains("PRIVATE_QUERY"));
        }
    }
    @Test public void serviceTitleInAClickableParentIsNavigationRatherThanAFalsePageMarker() throws Exception {
        var result=new Gov24LoginPrivacy(true).filter(screen(node("row",null,false,true),child("label","row","자동차등록원부")));
        assertEquals("public",result.getString("status"));
        assertEquals("nav_vehicle",result.getJSONArray("nodes").getJSONObject(0).getString("option_id"));
        assertEquals("row",result.getJSONArray("nodes").getJSONObject(0).getString("node_id"));
    }
    private AccessibilityNodeSnapshot child(String id,String parent,String label) {
        return new AccessibilityNodeSnapshot(id,parent,true,Gov24LoginPrivacy.PACKAGE,label,null,null,
            "android.view.View","text",new int[]{40,110,350,180},false,true,false,false,false,null,42);
    }
    @Test public void virtualTextChildUsesTheNativeButtonsIdentityAndBounds() throws Exception {
        var result=new Gov24LoginPrivacy().filter(screen(node("button",null,false,true),child("label","button","간편인증")));
        assertEquals("public",result.getString("status"));
        var target=result.getJSONArray("nodes").getJSONObject(0);
        assertEquals("button",target.getString("node_id"));
        assertEquals(30,target.getJSONArray("bounds").getInt(0));
        assertEquals("method_simple",target.getString("option_id"));
    }
    @Test public void repeatedLabelInOneButtonIsOneCandidateButSeparateButtonsRemainAmbiguous() throws Exception {
        var result=new Gov24LoginPrivacy().filter(screen(node("button","간편인증",false,true),child("label","button","간편인증")));
        assertEquals(1,result.getJSONArray("nodes").length());
        result=new Gov24LoginPrivacy().filter(screen(node("button",null,false,true),child("label","button","간편인증"),node("other","간편인증",false,true)));
        assertEquals("unrecognized",result.getString("status"));
    }
    @Test public void mixedButtonCannotBorrowOneChildsIdentityOrHideCredentials() throws Exception {
        for(String label:new String[]{"금융인증서","개인 이름"}) {
            var result=new Gov24LoginPrivacy().filter(screen(node("button",null,false,true),child("a","button","간편인증"),child("b","button",label)));
            assertEquals("unrecognized",result.getString("status"));
        }
        var result=new Gov24LoginPrivacy().filter(screen(node("button",null,false,true),child("label","button","간편인증"),node("input","PRIVATE_VALUE",true,false)));
        assertEquals("handoff",result.getString("status"));assertFalse(result.toString().contains("PRIVATE_VALUE"));
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
        assertTrue(Gov24LoginPrivacy.supportsCivilGoal("gov24.move_in.open"));
        assertFalse(Gov24LoginPrivacy.supportsCivilGoal("gov24.login"));
    }
    private AccessibilityNodeSnapshot diagnosticNode(String label,boolean input,boolean password,
            String pkg,int window,String resource,boolean visible) {
        return new AccessibilityNodeSnapshot("private-node",null,visible,pkg,label,input?null:"private-description",resource,
            input?"android.widget.EditText":"android.widget.TextView",input?"text_field":"text",
            new int[]{30,100,400,200},false,true,false,false,false,null,window,input,null,null,password);
    }

    private void assertDiagnostic(ScreenSnapshot source,String expected,String status,String handoff) throws Exception {
        JSONObject result=new Gov24LoginPrivacy(true,true).filter(source);
        assertEquals(expected,result.getString("privacy_reason"));
        assertEquals(status,result.getString("status"));
        assertEquals(handoff,result.optString("handoff_reason",null));
        assertEquals(0,result.getJSONArray("nodes").length());
        for(String secret:new String[]{"홍길동","010-1234-5678","private-node","private-description","other.private.app"})
            assertFalse(result.toString().contains(secret));
        // No new field is sent until the connected server advertises support.
        assertFalse(new Gov24LoginPrivacy(true).filter(source).has("privacy_reason"));
    }

    @Test public void diagnosticDistinguishesPasswordInputSearchAndBoundaryTextWithoutContent() throws Exception {
        assertDiagnostic(screen(diagnosticNode("010-1234-5678",true,true,Gov24LoginPrivacy.PACKAGE,42,null,true)),
            "password_field","handoff","credential_input");
        assertDiagnostic(screen(diagnosticNode("홍길동",true,false,Gov24LoginPrivacy.PACKAGE,42,null,true)),
            "sensitive_input_field","handoff","credential_input");
        assertDiagnostic(screen(diagnosticNode(null,true,false,Gov24LoginPrivacy.PACKAGE,42,"android:id/search_src_text",true)),
            "empty_search_field","handoff","credential_input");
        assertDiagnostic(screen(node("private","인증서 선택",false,false)),"certificate_prompt","handoff","certificate_selection");
        assertDiagnostic(screen(node("private","주민등록번호",false,false)),"private_identity_label","handoff","certificate_selection");
        assertDiagnostic(screen(node("private","서비스 이용에 대한 동의",false,false)),"consent_prompt","handoff","consent_required");
    }

    @Test public void diagnosticDistinguishesMixedAppAndWindowFromAuthentication() throws Exception {
        assertDiagnostic(screen(diagnosticNode("홍길동",false,false,"other.private.app",42,null,true)),
            "foreign_package_node","sensitive",null);
        assertDiagnostic(screen(diagnosticNode("홍길동",false,false,Gov24LoginPrivacy.PACKAGE,99,null,true)),
            "inactive_window_node","sensitive",null);
        assertDiagnostic(new ScreenSnapshot("com.kakao.talk",java.time.Instant.now().toString(),1080,2400,List.of(),42),
            "external_auth_app","handoff","external_auth");
        assertDiagnostic(new ScreenSnapshot("other.private.app",java.time.Instant.now().toString(),1080,2400,List.of(),42),
            "outside_gov24_app","outside_app",null);
    }

    @Test public void diagnosticSeparatesMissingTreeFromFilteredAndDuplicatePublicOptions() throws Exception {
        assertDiagnostic(null,"snapshot_missing","unrecognized",null);
        assertDiagnostic(screen(),"empty_accessibility_tree","unrecognized",null);
        assertDiagnostic(screen(diagnosticNode("홍길동",false,false,Gov24LoginPrivacy.PACKAGE,42,null,false)),
            "no_visible_nodes","unrecognized",null);
        assertDiagnostic(screen(node("private","홍길동",false,false)),"no_public_options","unrecognized",null);
        assertDiagnostic(screen(node("a","주민등록등본",false,true),node("b","주민등록등본",false,true)),
            "ambiguous_public_options","unrecognized",null);
        JSONObject publicScreen=new Gov24LoginPrivacy(true,true).filter(screen(node("a","주민등록등본",false,true)));
        assertEquals("public",publicScreen.getString("status"));
        assertFalse(publicScreen.has("privacy_reason"));
    }
}
