package com.click.data.remote;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppTaskDiagnosticsTest {
    private JSONObject request()throws Exception{return new JSONObject().put("request_id","private-request-id")
        .put("goal","PRIVATE goal").put("state",new JSONObject().put("task_id","private-task-id"))
        .put("screen",new JSONObject().put("observation_id","private-frame-id").put("fingerprint","a".repeat(64)).put("nodes","PRIVATE controls"));}
    private JSONObject bound(JSONObject response)throws Exception {
        return response.put("protocol_version","app-task.v2").put("requires_confirmation",true).put("request_id","private-request-id")
            .put("task_id","private-task-id").put("observation_id","private-frame-id").put("fingerprint","a".repeat(64));
    }
    @Test public void failureKeepsHttpStatusAndHashedIdentityWithoutRawBodiesOrExceptionMessages()throws Exception {
        ApiException error=new ApiException("POST","/private/path",new HttpResult(422,"PRIVATE token and response","PRIVATE conflict","private-trace"));
        JSONObject record=AppTaskDiagnostics.receipt(request(),null,null,error,1,2,3);
        assertEquals(422,record.getInt("http_status"));assertEquals("http_rejected",record.getString("error_code"));
        assertEquals(AppTaskDiagnostics.ref("private-request-id"),record.getString("request_ref"));
        assertEquals(AppTaskDiagnostics.ref("private-task-id"),record.getString("task_ref"));
        assertEquals("unknown",record.getString("model_call_state"));assertTrue(record.isNull("observed_model_calls"));
        String text=record.toString();assertFalse(text.contains("PRIVATE"));assertFalse(text.contains("private-"));assertFalse(text.contains("/private/path"));
    }
    @Test public void receivedModelFailureRemainsDistinctFromTransportFailureAndUsesOnlyBoundedEnums()throws Exception {
        JSONObject response=bound(new JSONObject().put("status","unavailable").put("diagnostic",new JSONObject()
            .put("called",true).put("latency_ms",8100).put("failure_kind","provider").put("failure_stage","provider")
            .put("reason_code","provider_timeout").put("raw_error","PRIVATE").put("model","gemini-3.8-flash")));
        JSONObject record=AppTaskDiagnostics.receipt(request(),response,200,null,1,8100,8101);
        assertEquals("response_received",record.getString("outcome"));assertEquals("unavailable",record.getString("response_status"));
        assertEquals(1,record.getInt("observed_model_calls"));assertEquals(8100,record.getInt("model_wait_ms"));
        assertEquals("provider_timeout",record.getString("reason_code"));assertFalse(record.toString().contains("PRIVATE"));
        response.getJSONObject("diagnostic").put("reason_code","PRIVATE").put("failure_stage","PRIVATE");
        record=AppTaskDiagnostics.receipt(request(),response,200,null,1,2,3);
        assertFalse(record.has("reason_code"));assertFalse(record.has("failure_stage"));
    }
    @Test public void timeoutAndResponseValidationDoNotPretendThatModelWasNeverCalled()throws Exception {
        JSONObject timed=AppTaskDiagnostics.receipt(request(),null,null,new java.net.SocketTimeoutException("PRIVATE endpoint"),0,15000,15000);
        assertEquals("timeout",timed.getString("error_code"));assertTrue(timed.isNull("observed_model_calls"));assertFalse(timed.toString().contains("PRIVATE"));
        JSONObject invalid=AppTaskDiagnostics.receipt(request(),new JSONObject(),200,new java.io.IOException("CHANGED_TASK_COMPLETION"),0,10,10);
        assertEquals("response_validation_failed",invalid.getString("error_code"));assertEquals(200,invalid.getInt("http_status"));
        assertEquals("CHANGED_TASK_COMPLETION",invalid.getString("validation_code"));
        assertTrue(invalid.isNull("observed_model_calls"));
    }
    @Test public void explicitNoModelAndMalformedTimingAreSafe()throws Exception {
        JSONObject response=bound(new JSONObject().put("status","confirm_result").put("diagnostic",new JSONObject().put("called",false).put("latency_ms",-5)));
        JSONObject record=AppTaskDiagnostics.receipt(request(),response,200,null,-5,Long.MAX_VALUE,Long.MAX_VALUE);
        assertEquals(0,record.getInt("observed_model_calls"));assertEquals(0,record.getInt("model_wait_ms"));
        assertEquals(0,record.getLong("queue_ms"));assertEquals(120000,record.getLong("total_ms"));
        record=AppTaskDiagnostics.receipt(request(),null,null,new java.io.IOException(),0,1,1);
        assertEquals("network_error",record.getString("error_code"));assertTrue(record.isNull("observed_model_calls"));
    }
    @Test public void validationDetailsKeepOnlyClosedFieldAndTypeNames()throws Exception {
        JSONObject diagnostic=new JSONObject().put("called",true).put("validation_errors",new org.json.JSONArray()
            .put(new JSONObject().put("field","checks.index.role").put("type","value_error").put("input","PRIVATE"))
            .put(new JSONObject().put("field","PRIVATE.secret").put("type","PRIVATE")));
        JSONObject record=AppTaskDiagnostics.receipt(request(),bound(new JSONObject().put("diagnostic",diagnostic)),200,null,0,1,1);
        assertEquals("checks.index.role",record.getJSONArray("validation_errors").getJSONObject(0).getString("field"));
        assertEquals("other.other",record.getJSONArray("validation_errors").getJSONObject(1).getString("field"));
        assertFalse(record.toString().contains("PRIVATE"));
    }
    @Test public void unboundResponsesAndMissingCalledFlagCannotProveZeroOrTiming()throws Exception {
        JSONObject response=bound(new JSONObject().put("diagnostic",new JSONObject().put("called",false).put("latency_ms",10)))
            .put("task_id","wrong-task");
        JSONObject record=AppTaskDiagnostics.receipt(request(),response,200,new java.io.IOException("INVALID_APP_TASK_RESPONSE"),0,1,1);
        assertEquals("unknown",record.getString("model_call_state"));assertTrue(record.isNull("observed_model_calls"));assertTrue(record.isNull("model_wait_ms"));
        response=bound(new JSONObject().put("diagnostic",new JSONObject().put("latency_ms",10)));
        record=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);
        assertEquals("unknown",record.getString("model_call_state"));assertTrue(record.isNull("model_wait_ms"));
    }
    @Test public void recoveredCompletionAndProviderBudgetKeepOnlyClosedDiagnosticFields()throws Exception {
        JSONObject response=bound(new JSONObject().put("status","proposed").put("diagnostic",new JSONObject()
            .put("called",true).put("completion_grounding","deferred_invalid_contract")
            .put("completion_deferred_reason","completion_contract_invalid").put("provider_budget_ms",9000).put("provider_attempt_limit",1)));
        JSONObject record=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);
        assertEquals("deferred_invalid_contract",record.getString("completion_grounding"));assertEquals(9000,record.getInt("provider_budget_ms"));
        assertEquals(1,record.getInt("provider_attempt_limit"));
        response.getJSONObject("diagnostic").put("called",JSONObject.NULL).put("reason_code","provider_startup_failed")
            .put("failure_kind","provider").put("provider_budget_ms",99999).put("provider_attempt_limit",9);
        record=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);
        assertEquals("unknown",record.getString("model_call_state"));assertTrue(record.isNull("observed_model_calls"));
        assertEquals("provider_startup_failed",record.getString("reason_code"));assertFalse(record.has("provider_budget_ms"));assertFalse(record.has("provider_attempt_limit"));
    }
}
