package com.click.service.overlay;

import android.app.Application;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** Failed requests retain identity/status without saving raw error bodies. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class MetaControllerRequestTraceTest {
    private static JSONObject trace()throws Exception{return new JSONObject().put("request_ref","a".repeat(24))
        .put("task_ref","b".repeat(24)).put("outcome","started").put("model_call_state","unknown")
        .put("model_calls",JSONObject.NULL).put("model_wait_ms",JSONObject.NULL);}
    private static HttpURLConnection connection(int status,String body)throws Exception{
        HttpURLConnection connection=mock(HttpURLConnection.class);
        when(connection.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(connection.getResponseCode()).thenReturn(status);
        when(connection.getHeaderField("X-Click-Server-Ref")).thenReturn("c".repeat(24));
        if(status==200)when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        else when(connection.getErrorStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return connection;
    }
    @Test public void httpRejectionKeepsStatusReasonAndConfirmedZeroModelCalls()throws Exception{
        JSONObject receipt=trace();HttpURLConnection connection=connection(403,"{\"error\":\"outside_frozen_public_fixture\",\"model_call_state\":\"not_started\"}");
        assertThrows(IOException.class,()->MetaControllerClosedLoopExperimentTest.exchange(connection,new byte[0],receipt));
        assertEquals(403,receipt.getInt("http_status"));assertEquals("outside_frozen_public_fixture",receipt.getString("error_code"));
        assertEquals("not_started",receipt.getString("model_call_state"));assertEquals(0,receipt.getInt("model_calls"));
        assertEquals("c".repeat(24),receipt.getString("server_ref"));verify(connection).disconnect();
    }
    @Test public void serviceExceptionKeepsUnknownModelCountRatherThanZero()throws Exception{
        JSONObject receipt=trace();HttpURLConnection connection=connection(500,"{\"error\":\"synthetic_experiment_service_error\",\"model_call_state\":\"unknown\"}");
        assertThrows(IOException.class,()->MetaControllerClosedLoopExperimentTest.exchange(connection,new byte[0],receipt));
        assertEquals(500,receipt.getInt("http_status"));assertEquals("unknown",receipt.getString("model_call_state"));assertTrue(receipt.isNull("model_calls"));
    }
    @Test public void timeoutAndNetworkExceptionsNeverPersistExceptionMessages()throws Exception{
        for(IOException error:new IOException[]{new SocketTimeoutException("PRIVATE_SECRET"),new IOException("PRIVATE_SECRET")}){
            JSONObject receipt=trace();HttpURLConnection connection=connection(200,"{}");when(connection.getResponseCode()).thenThrow(error);
            assertThrows(IOException.class,()->MetaControllerClosedLoopExperimentTest.exchange(connection,new byte[0],receipt));
            assertEquals(error instanceof SocketTimeoutException?"transport_timeout":"transport_io_error",receipt.getString("error_code"));
            assertTrue(receipt.isNull("model_calls"));assertFalse(receipt.toString().contains("PRIVATE_SECRET"));
        }
    }
    @Test public void malformedAndUntrustedErrorBodiesKeepOnlyBoundedCodes()throws Exception{
        JSONObject malformed=trace();
        assertThrows(org.json.JSONException.class,()->MetaControllerClosedLoopExperimentTest.exchange(connection(200,"PRIVATE NOT JSON"),new byte[0],malformed));
        assertEquals("malformed_response",malformed.getString("error_code"));assertFalse(malformed.toString().contains("PRIVATE"));
        JSONObject unknown=trace();
        assertThrows(IOException.class,()->MetaControllerClosedLoopExperimentTest.exchange(connection(422,"{\"error\":\"PRIVATE_SECRET\"}"),new byte[0],unknown));
        assertEquals("http_error_unclassified",unknown.getString("error_code"));assertTrue(unknown.isNull("model_calls"));assertFalse(unknown.toString().contains("PRIVATE_SECRET"));
    }
    @Test public void successfulProviderResponseKeepsCallAndWaitObservation()throws Exception{
        JSONObject receipt=trace();MetaControllerClosedLoopExperimentTest.exchange(connection(200,"{\"diagnostic\":{\"called\":true,\"latency_ms\":12}}"),new byte[0],receipt);
        assertEquals("observed",receipt.getString("model_call_state"));assertEquals(1,receipt.getInt("model_calls"));assertEquals(12,receipt.getInt("model_wait_ms"));
    }
    private static JSONObject identityRequest()throws Exception{return new JSONObject().put("protocol_version","app-task.v2")
        .put("request_id","request").put("state",new JSONObject().put("task_id","task"))
        .put("screen",new JSONObject().put("observation_id","observation").put("fingerprint","a".repeat(64)));}
    private static JSONObject identityResponse()throws Exception{return new JSONObject().put("protocol_version","app-task.v2")
        .put("requires_confirmation",true).put("request_id","request").put("task_id","task")
        .put("observation_id","observation").put("fingerprint","a".repeat(64))
        .put("diagnostic",new JSONObject().put("called",false).put("latency_ms",12));}
    @Test public void responseForAnotherRequestCannotClaimZeroCallsForThisRequest()throws Exception{
        JSONObject receipt=trace().put("model_call_state","not_started").put("model_calls",0).put("model_wait_ms",0);
        assertFalse(MetaControllerClosedLoopExperimentTest.applyResponseTrace(identityRequest(),identityResponse().put("request_id","someone_else"),receipt));
        assertEquals("unknown",receipt.getString("model_call_state"));assertTrue(receipt.isNull("model_calls"));assertTrue(receipt.isNull("model_wait_ms"));
    }
    @Test public void boundResponseRetainsCallMetadataEvenWhenActionWillBeRejected()throws Exception{
        JSONObject response=identityResponse().put("action",new JSONObject().put("tool","invalid"));
        JSONObject receipt=trace().put("model_call_state","observed").put("model_calls",1).put("model_wait_ms",12);
        response.getJSONObject("diagnostic").put("called",true);
        assertTrue(MetaControllerClosedLoopExperimentTest.applyResponseTrace(identityRequest(),response,receipt));
        assertEquals("observed",receipt.getString("model_call_state"));assertEquals(1,receipt.getInt("model_calls"));
    }
}
