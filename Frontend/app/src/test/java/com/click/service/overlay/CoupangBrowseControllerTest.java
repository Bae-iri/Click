package com.click.service.overlay;

import android.app.Application;
import com.click.data.remote.CoupangBrowseGateway;
import com.click.data.snapshot.CoupangBrowsePrivacy;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class CoupangBrowseControllerTest {
    @Test public void localGuidanceDoesNotConsumeAModelTurn() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);
        var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication()),listener);
        controller.start("session","prepared");
        var callback=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway).send(any(),callback.capture());
        callback.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        when(listener.localGuide(null)).thenReturn(true);
        controller.observe(null);
        verify(gateway,times(1)).send(any(),any());
    }
    @Test public void oneExplicitRequestAndNoLatePresentationAfterStop() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);
        var listener=mock(CoupangBrowseController.Listener.class);
        var controller=new CoupangBrowseController(gateway,new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication()),listener);
        controller.start("session","prepared");
        controller.observe(null);
        var request=ArgumentCaptor.forClass(JSONObject.class);
        var callback=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway,times(1)).send(request.capture(),callback.capture());
        assertEquals("start",request.getValue().getString("command"));
        controller.close();
        callback.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        verify(listener,never()).present(any(),any());
    }
    @Test public void lostResponseRetryKeepsTheExactRequestIdentity() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);
        var listener=mock(CoupangBrowseController.Listener.class);
        var controller=new CoupangBrowseController(gateway,new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication()),listener);
        controller.start("session","prepared");
        var requests=ArgumentCaptor.forClass(JSONObject.class);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway).send(requests.capture(),callbacks.capture());
        String original=requests.getValue().toString();
        callbacks.getValue().failure(new java.io.IOException("timeout"));
        var retry=ArgumentCaptor.forClass(Runnable.class);
        verify(listener).failed(any(),retry.capture());retry.getValue().run();
        verify(gateway,times(2)).send(requests.capture(),any());
        assertEquals(original,requests.getValue().toString());
    }

    private com.click.data.snapshot.ScreenSnapshot appScreen(String pkg) {
        var search = new com.click.data.snapshot.AccessibilityNodeSnapshot("search",null,true,pkg,"검색",null,
            pkg+":id/search_bar","android.widget.TextView",null,new int[]{0,100,300,200},true,true,false,false,false,null,42);
        return new com.click.data.snapshot.ScreenSnapshot(pkg,java.time.Instant.now().toString(),1080,2400,java.util.List.of(search),42);
    }

    @Test public void entryDuringStartIsObservedOnceAfterTheSessionVersionArrives() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);
        var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication()),listener);
        controller.start("session","prepared");
        controller.screenChanged(appScreen("com.coupang.mobile"));
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        var requests=ArgumentCaptor.forClass(JSONObject.class);
        verify(gateway,times(2)).send(requests.capture(),callbacks.capture());
        assertEquals("observe",requests.getValue().getString("command"));
        assertEquals(1,requests.getValue().getInt("expected_version"));
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",2));
        controller.screenChanged(appScreen("com.coupang.mobile"));
        verify(gateway,times(2)).send(any(),any());
    }

    @Test public void entryWaitsWhilePausedAndIgnoresOtherApps() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);
        var listener=mock(CoupangBrowseController.Listener.class);
        var controller=new CoupangBrowseController(gateway,new CoupangBrowsePrivacy(RuntimeEnvironment.getApplication()),listener);
        controller.start("session","prepared");
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        controller.screenChanged(appScreen("com.coupang.mobile"));
        verify(gateway,times(1)).send(any(),any());
        when(listener.canObserve()).thenReturn(true);
        controller.screenChanged(appScreen("com.sec.android.app.launcher"));
        verify(gateway,times(1)).send(any(),any());
        controller.screenChanged(appScreen("com.coupang.mobile"));
        verify(gateway,times(2)).send(any(),any());
    }
    @Test public void trustedLocalHomeNavigationPrecedesInputHandoffAndAllowsNextChoiceScreen() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var home=appScreen("kr.go.minwon.m");when(listener.localGuide(home)).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,view->view==home
            ?new JSONObject().put("status","handoff").put("nodes",new JSONArray())
            :new com.click.data.snapshot.Gov24LoginPrivacy().filter(view),"kr.go.minwon.m","public-guide.v1",listener);
        controller.start("session","prepared");controller.screenChanged(home);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        verify(gateway,times(1)).send(any(),any());assertFalse(controller.terminal());
        controller.screenChanged(loginScreen("간편인증","공동인증서"));
        verify(gateway,times(2)).send(any(),any());
    }
    private com.click.data.snapshot.ScreenSnapshot loginScreen(String... labels) {
        var nodes=new java.util.ArrayList<com.click.data.snapshot.AccessibilityNodeSnapshot>();
        for(int i=0;i<labels.length;i++)nodes.add(new com.click.data.snapshot.AccessibilityNodeSnapshot(
            "node_"+i,null,true,"kr.go.minwon.m",labels[i],null,null,"android.widget.Button",null,
            new int[]{10,100+i*100,400,180+i*100},true,true,false,false,false,null,42));
        return new com.click.data.snapshot.ScreenSnapshot("kr.go.minwon.m",java.time.Instant.now().toString(),1080,2400,nodes,42);
    }
    @Test public void directNativeLoginMethodChangeRefreshesProviderQuestionWithoutAGuidedTap() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,new com.click.data.snapshot.Gov24LoginPrivacy()::filter,
            "kr.go.minwon.m","public-guide.v1",listener);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        var requests=ArgumentCaptor.forClass(JSONObject.class);
        controller.start("session","prepared");verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        controller.screenChanged(loginScreen("간편인증","공동인증서"));
        verify(gateway,times(2)).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",2)
            .put("question",new JSONObject().put("id","methods")));
        controller.screenChanged(loginScreen("카카오톡","네이버"));
        verify(gateway,times(3)).send(requests.capture(),callbacks.capture());
        assertEquals("provider_kakao",requests.getValue().getJSONObject("screen").getJSONArray("nodes").getJSONObject(0).getString("option_id"));
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",3)
            .put("question",new JSONObject().put("id","providers")));
        controller.screenChanged(loginScreen("카카오톡","네이버"));verify(gateway,times(3)).send(any(),any());
        controller.answer(loginScreen("카카오톡","네이버"),"카카오로 할래",false);
        verify(gateway,times(4)).send(requests.capture(),any());
        assertEquals("providers",requests.getValue().getString("question_id"));
    }
    @Test public void nativeChangeDuringRequestRefreshesOnceAndPendingPrivateBoundaryStopsExport() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,new com.click.data.snapshot.Gov24LoginPrivacy()::filter,
            "kr.go.minwon.m","public-guide.v1",listener);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        var requests=ArgumentCaptor.forClass(JSONObject.class);
        controller.start("session","prepared");verify(gateway).send(any(),callbacks.capture());
        controller.screenChanged(loginScreen("간편인증"));
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        verify(gateway,times(2)).send(any(),callbacks.capture());
        controller.screenChanged(loginScreen("카카오톡","네이버"));verify(gateway,times(2)).send(any(),any());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",2));
        verify(gateway,times(3)).send(any(),callbacks.capture());
        controller.screenChanged(loginScreen("인증서 선택"));
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",3));
        verify(gateway,times(4)).send(requests.capture(),callbacks.capture());
        assertEquals("handoff",requests.getValue().getJSONObject("screen").getString("status"));
        assertEquals(0,requests.getValue().getJSONObject("screen").getJSONArray("nodes").length());
        callbacks.getValue().success(new JSONObject().put("status","COMPLETED").put("session_version",4));
        controller.screenChanged(loginScreen("카카오톡"));verify(gateway,times(4)).send(any(),any());
    }
    @Test public void publicLoginQuestionSurvivesScreenChangeAndReplyCarriesFreshQuestionBinding() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,view->new JSONObject().put("status","outside_app"),
            "kr.go.minwon.m","public-guide.v1",listener);
        controller.start("session","prepared");
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1)
            .put("question",new JSONObject().put("id","current-question")));
        controller.screenChanged(appScreen("other.app"));assertTrue(controller.awaitingAnswer());
        controller.answer(appScreen("kr.go.minwon.m"),"카카오로",false);
        var requests=ArgumentCaptor.forClass(JSONObject.class);verify(gateway,times(2)).send(requests.capture(),any());
        assertEquals("public-guide.v1",requests.getValue().getString("protocol_version"));
        assertEquals("current-question",requests.getValue().getString("question_id"));
        assertEquals("카카오로",requests.getValue().getString("answer"));
        controller.close();assertFalse(controller.awaitingAnswer());
    }
    private com.click.data.snapshot.ScreenSnapshot privateInputScreen() {
        var input=new com.click.data.snapshot.AccessibilityNodeSnapshot("input",null,true,"kr.go.minwon.m","PRIVATE_VALUE",null,null,
            "android.widget.EditText","text_field",new int[]{10,100,400,180},false,true,false,false,false,null,42,true,null,null,false);
        return new com.click.data.snapshot.ScreenSnapshot("kr.go.minwon.m",java.time.Instant.now().toString(),1080,2400,java.util.List.of(input),42);
    }
    @Test public void transientInputBetweenHomeAndMethodsNeverTerminatesTheGuide() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        when(listener.freshScreen()).thenAnswer(call->loginScreen("간편인증","공동인증서"));
        var controller=new CoupangBrowseController(gateway,new com.click.data.snapshot.Gov24LoginPrivacy()::filter,
            "kr.go.minwon.m","public-guide.v1",listener);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        controller.start("session","prepared");verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        controller.screenChanged(privateInputScreen());verify(gateway,times(1)).send(any(),any());
        assertTrue(controller.settlingPrivateScreen());verify(listener).settlingPrivateScreen();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
        var requests=ArgumentCaptor.forClass(JSONObject.class);
        verify(gateway,times(2)).send(requests.capture(),callbacks.capture());
        assertEquals("public",requests.getValue().getJSONObject("screen").getString("status"));
        assertFalse(requests.getValue().toString().contains("PRIVATE_VALUE"));
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",2)
            .put("question",new JSONObject().put("id","methods")));
        assertFalse(controller.terminal());assertTrue(controller.awaitingAnswer());assertFalse(controller.settlingPrivateScreen());
        controller.close();
    }
    @Test public void stableInputNeedsFreshNativeReadsAndExportsNoValuesOrNodes() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);when(listener.freshScreen()).thenAnswer(call->privateInputScreen());
        var controller=new CoupangBrowseController(gateway,new com.click.data.snapshot.Gov24LoginPrivacy()::filter,
            "kr.go.minwon.m","public-guide.v1",listener);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        controller.start("session","prepared");verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        controller.screenChanged(privateInputScreen());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1199));
        verify(gateway,times(1)).send(any(),any());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1));
        var requests=ArgumentCaptor.forClass(JSONObject.class);verify(gateway,times(2)).send(requests.capture(),callbacks.capture());
        assertEquals("handoff",requests.getValue().getJSONObject("screen").getString("status"));
        assertEquals(0,requests.getValue().getJSONObject("screen").getJSONArray("nodes").length());
        assertFalse(requests.getValue().toString().contains("PRIVATE_VALUE"));
        callbacks.getValue().success(new JSONObject().put("status","COMPLETED").put("outcome","handoff").put("session_version",2));
        controller.screenChanged(loginScreen("간편인증"));
        assertTrue(controller.terminal());assertEquals("COMPLETED",controller.status());assertEquals("handoff",controller.outcome());
        assertEquals("credential_input",controller.handoffReason());controller.close();
    }
    @Test public void cachedPrivateSnapshotCannotStandInForFreshNativeEvidence() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);var cached=privateInputScreen();when(listener.freshScreen()).thenReturn(cached);
        var controller=new CoupangBrowseController(gateway,new com.click.data.snapshot.Gov24LoginPrivacy()::filter,
            "kr.go.minwon.m","public-guide.v1",listener);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        controller.start("session","prepared");verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        controller.screenChanged(cached);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3));
        verify(gateway,times(1)).send(any(),any());controller.close();
    }
    @Test public void pausingOrClosingDropsPendingPrivateConfirmation() throws Exception {
        for(boolean close:new boolean[]{false,true}) {
            var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
            when(listener.canObserve()).thenReturn(true);when(listener.freshScreen()).thenAnswer(call->privateInputScreen());
            var controller=new CoupangBrowseController(gateway,new com.click.data.snapshot.Gov24LoginPrivacy()::filter,
                "kr.go.minwon.m","public-guide.v1",listener);
            var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
            controller.start("session","prepared");verify(gateway).send(any(),callbacks.capture());
            callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
            controller.screenChanged(privateInputScreen());
            if(close)controller.close();else when(listener.canObserve()).thenReturn(false);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
            verify(gateway,times(1)).send(any(),any());assertFalse(controller.settlingPrivateScreen());controller.close();
        }
    }
    @Test public void lateAccessibilityBroadcastStillRequiresANativeReadBeforeHandoff() throws Exception {
        var gateway=mock(CoupangBrowseGateway.class);var listener=mock(CoupangBrowseController.Listener.class);
        when(listener.canObserve()).thenReturn(true);
        var controller=new CoupangBrowseController(gateway,new com.click.data.snapshot.Gov24LoginPrivacy()::filter,
            "kr.go.minwon.m","public-guide.v1",listener);
        var callbacks=ArgumentCaptor.forClass(CoupangBrowseGateway.Callback.class);
        controller.start("session","prepared");verify(gateway).send(any(),callbacks.capture());
        callbacks.getValue().success(new JSONObject().put("status","IN_PROGRESS").put("session_version",1));
        controller.screenChanged(privateInputScreen());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
        controller.screenChanged(privateInputScreen());
        verify(gateway,times(1)).send(any(),any());
        when(listener.freshScreen()).thenAnswer(call->privateInputScreen());
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
        var requests=ArgumentCaptor.forClass(JSONObject.class);verify(gateway,times(2)).send(requests.capture(),any());
        assertEquals("handoff",requests.getValue().getJSONObject("screen").getString("status"));
        assertEquals(0,requests.getValue().getJSONObject("screen").getJSONArray("nodes").length());controller.close();
    }
}
