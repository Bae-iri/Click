package com.click.service.overlay;

import android.app.Application;
import com.click.data.remote.CoupangBrowseGateway;
import com.click.data.snapshot.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Regressions for public observation refresh and manual authentication recovery. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=34,application=Application.class)
public class Gov24RecoveryTest {
    @Test public void incompatibleServerIsDetectedBeforeStartingARecoverySession() throws Exception {
        assertFalse(CoupangBrowseGateway.supportsRecovery(new JSONObject()));
        JSONObject properties=new JSONObject().put("subgoal",new JSONObject().put("enum",new JSONArray(List.of("await_login","await_public"))))
            .put("recovery_version",new JSONObject().put("const","gov24-recovery.v1"));
        JSONObject schemas=new JSONObject().put("PublicGuideResponse",new JSONObject().put("properties",properties))
            .put("PublicGuideRequest",new JSONObject().put("properties",new JSONObject().put("recovery_version",new JSONObject().put("const","gov24-recovery.v1"))))
            .put("PublicGuideScreen",new JSONObject().put("properties",new JSONObject().put("auth_state",new JSONObject())));
        JSONObject document=new JSONObject().put("components",new JSONObject().put("schemas",schemas));
        assertTrue(CoupangBrowseGateway.supportsRecovery(document));
        properties.getJSONObject("recovery_version").put("const","gov24-recovery.v2");
        assertFalse(CoupangBrowseGateway.supportsRecovery(document));
    }
    private AccessibilityNodeSnapshot node(String id,String text,String description) {
        return new AccessibilityNodeSnapshot(id,null,true,Gov24LoginPrivacy.PACKAGE,text,description,null,
            "android.widget.Button","button",new int[]{10,100,500,180},true,true,false,false,false,null,42);
    }
    private AccessibilityNodeSnapshot search(String value) {
        return new AccessibilityNodeSnapshot("search",null,true,Gov24LoginPrivacy.PACKAGE,value,"검색",null,
            "android.widget.EditText","text_field",new int[]{10,200,500,280},false,true,false,false,false,null,42,
            true,null,null,false,null,false,true);
    }
    private ScreenSnapshot screen(AccessibilityNodeSnapshot... nodes) {
        return new ScreenSnapshot(Gov24LoginPrivacy.PACKAGE,java.time.Instant.now().toString(),1080,2400,List.of(nodes),42);
    }
    private Set<String> options(JSONObject filtered) throws Exception {
        Set<String> result=new TreeSet<>();JSONArray nodes=filtered.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++)result.add(nodes.getJSONObject(i).getString("option_id"));
        return result;
    }
    @Test public void publicHomeSearchWithAQueryStaysPublicAndKeepsTheQueryLocal() throws Exception {
        var privacy=new Gov24LoginPrivacy(true);
        var brand=node("brand","정부24",null);var services=node("services","자주 찾는 서비스",null);
        var resident=node("resident","주민등록등본",null);
        assertEquals("public",privacy.filter(screen(brand,services,resident,search(null))).getString("status"));
        var filled=privacy.filter(screen(brand,services,resident,search("주민등록등본")));
        assertEquals("public",filled.getString("status"));assertFalse(filled.toString().contains("text_field"));
    }
    @Test public void identifiedPublicServiceSearchOutsideHomeStaysPublic() throws Exception {
        var filtered=new Gov24LoginPrivacy(true).filter(screen(node("title","민원서비스",null),
            node("resident","주민등록등본",null),search(null)));
        assertEquals("public",filtered.getString("status"));
    }
    @Test public void emptyTextFallsBackToRecognizedAccessibilityDescription() throws Exception {
        var privacy=new Gov24LoginPrivacy();
        assertEquals("public",privacy.filter(screen(node("method",null,"간편인증"))).getString("status"));
        assertEquals("public",privacy.filter(screen(node("method","","간편인증"))).getString("status"));
    }
    @Test public void civilPublicObservationDistinguishesSignedOutHomeFromSignedInHome() throws Exception {
        var privacy=new Gov24LoginPrivacy(true);
        var brand=node("brand","정부24",null);var services=node("services","자주 찾는 서비스",null);
        var resident=node("resident","주민등록등본",null);
        var signedOut=privacy.filter(screen(brand,services,resident,node("auth","로그인",null)));
        var signedIn=privacy.filter(screen(brand,services,resident,node("auth","로그아웃",null)));
        assertEquals("signed_out",signedOut.getString("auth_state"));assertEquals("signed_in",signedIn.getString("auth_state"));
        assertTrue(options(signedOut).contains("entry_login"));assertFalse(options(signedIn).contains("entry_login"));
    }
    @Test public void authenticationWaitSuppressesPrivateScreensAndResumesTheSameRequest() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,new Gov24LoginPrivacy()::filter,
            Gov24LoginPrivacy.PACKAGE,"public-guide.v1",listener);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        controller.start("original-request","resolution");verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        controller.screenChanged(screen(node("certificate","인증서 선택",null)));
        verify(gateway,times(2)).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("subgoal","await_login").put("session_version",2));
        controller.screenChanged(screen(search("PRIVATE_VALUE")));
        verify(gateway,times(2)).send(any(),any());
        controller.screenChanged(screen(node("home","정부24",null),node("services","자주 찾는 서비스",null),node("logout","로그아웃",null)));
        controller.observe(screen(node("resident","주민등록등본",null)));
        verify(gateway,times(3)).send(any(),any());assertFalse(controller.terminal());controller.close();
    }
    @Test public void sameMethodWithNewNativeIdentityRequestsAFreshBoundGuide() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,new Gov24LoginPrivacy()::filter,
            Gov24LoginPrivacy.PACKAGE,"public-guide.v1",listener);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        controller.start("request","resolution");verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        controller.screenChanged(screen(node("old","간편인증",null)));
        verify(gateway,times(2)).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",2)
            .put("target_node_id","old").put("guide_id","guide").put("observation_id","observation"));
        controller.screenChanged(screen(node("new","간편인증",null)));
        verify(gateway,times(3)).send(any(),any());controller.close();
    }
    @Test public void returningFromAnotherAppRefreshesEvenAnUnchangedNativeButton() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,new Gov24LoginPrivacy()::filter,
            Gov24LoginPrivacy.PACKAGE,"public-guide.v1",listener);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        controller.start("request","resolution");verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        controller.screenChanged(screen(node("button","간편인증",null)));
        verify(gateway,times(2)).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",2));
        controller.screenChanged(new ScreenSnapshot("other.app",java.time.Instant.now().toString(),1080,2400,List.of(),99));
        controller.screenChanged(screen(node("button","간편인증",null)));
        verify(gateway,times(3)).send(any(),any());controller.close();
    }
    @Test public void ambiguousHomeAndCredentialModalCannotSupplySignedInEvidence() throws Exception {
        var brand=node("brand","정부24",null);var services=node("services","자주 찾는 서비스",null);
        var privacy=new Gov24LoginPrivacy(true);
        assertEquals("unrecognized",privacy.filter(screen(brand,services,node("login","로그인",null),node("logout","로그아웃",null))).getString("status"));
        var privateModal=privacy.filter(screen(brand,services,node("logout","로그아웃",null),node("method","간편인증",null),search("PRIVATE_VALUE")));
        assertEquals("handoff",privateModal.getString("status"));assertEquals(0,privateModal.getJSONArray("nodes").length());
        assertFalse(privateModal.toString().contains("PRIVATE_VALUE"));assertFalse(privateModal.has("auth_state"));
    }
}
