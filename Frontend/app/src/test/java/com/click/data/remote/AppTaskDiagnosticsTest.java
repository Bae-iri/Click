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
    @Test public void goalAssessmentIsTypedClosedAndBoundToTheResponseIdentity()throws Exception {
        JSONObject diagnostic=new JSONObject().put("called",true).put("planning_mode","goal_first").put("goal_assessment","satisfied").put("completion_provider","jev");
        JSONObject response=bound(new JSONObject().put("status","confirm_result").put("diagnostic",diagnostic));
        JSONObject record=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);
        assertEquals("goal_first",record.getString("planning_mode"));assertEquals("satisfied",record.getString("goal_assessment"));assertEquals("jev",record.getString("completion_provider"));
        for (String provider : new String[]{"openai", "local"}) {
            diagnostic.put("completion_provider", provider);
            assertEquals(provider, AppTaskDiagnostics.receipt(request(), response, 200, null, 0, 1, 1).getString("completion_provider"));
        }
        for(Object bad:new Object[]{true,"PRIVATE",1,JSONObject.NULL}){
            diagnostic.put("goal_assessment",bad);record=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);assertFalse(record.has("goal_assessment"));
        }
        diagnostic.put("goal_assessment","satisfied");response.put("task_id","unrelated");record=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);
        assertFalse(record.has("goal_assessment"));assertFalse(record.has("planning_mode"));
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
    @Test public void boundContextDiagnosticsCopyOnlyTypedFlagsAndBoundedCounts()throws Exception {
        JSONObject diagnostic=new JSONObject().put("called",true).put("recovery_context_used",true).put("capabilities_applied",true)
            .put("allowed_actions_context_used",true).put("allowed_action_count",322).put("recovery_blocked_action_count",16)
            .put("recovery_attempt",3).put("raw_recovery","PRIVATE controls and input");
        JSONObject response=bound(new JSONObject().put("status","proposed").put("diagnostic",diagnostic));
        JSONObject record=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);
        assertTrue(record.getBoolean("recovery_context_used"));assertTrue(record.getBoolean("capabilities_applied"));assertTrue(record.getBoolean("allowed_actions_context_used"));
        assertEquals(322,record.getInt("allowed_action_count"));assertEquals(16,record.getInt("recovery_blocked_action_count"));assertEquals(3,record.getInt("recovery_attempt"));
        assertFalse(record.has("raw_recovery"));assertFalse(record.toString().contains("PRIVATE"));
        diagnostic.put("recovery_context_used",false).put("recovery_attempt",2);
        record=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);
        assertFalse(record.getBoolean("recovery_context_used"));assertFalse(record.has("recovery_attempt"));
    }
    @Test public void malformedOrUnboundContextMetadataCannotBecomeForwardingEvidence()throws Exception {
        JSONObject diagnostic=new JSONObject().put("called",true).put("recovery_context_used","true").put("capabilities_applied",1)
            .put("allowed_actions_context_used","PRIVATE").put("allowed_action_count",322.0).put("recovery_blocked_action_count",17).put("recovery_attempt",4);
        JSONObject response=bound(new JSONObject().put("status","proposed").put("diagnostic",diagnostic));
        JSONObject record=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);
        for(String key:java.util.Set.of("recovery_context_used","capabilities_applied","allowed_actions_context_used","allowed_action_count","recovery_blocked_action_count","recovery_attempt"))assertFalse(record.has(key));
        diagnostic.put("recovery_context_used",true).put("allowed_action_count",2).put("recovery_attempt",1);response.put("task_id","unrelated-task");
        record=AppTaskDiagnostics.receipt(request(),response,200,new java.io.IOException("INVALID_APP_TASK_RESPONSE"),0,1,1);
        assertEquals("unknown",record.getString("model_call_state"));assertFalse(record.has("recovery_context_used"));assertFalse(record.has("allowed_action_count"));
    }
    @Test public void recoveryAttributionCopiesOnlyCompatibleClosedPolicyReasonPairs()throws Exception {
        for(String[] pair:new String[][]{{"mc_v0","accepted_action_unconfirmed","3"},{"mc_v1","accepted_action_unconfirmed","3"},
                {"mc_v1","observed_transition_unconfirmed","1"},{"mc_v1","observed_transition_unconfirmed","2"},
                {"mc_v2","accepted_action_unconfirmed","3"},{"mc_v2","observed_transition_unconfirmed","1"},
                {"mc_v2","observed_transition_unconfirmed","2"}}){
            JSONObject diagnostic=new JSONObject().put("called",true).put("recovery_context_used",true)
                .put("recovery_policy",pair[0]).put("recovery_reason",pair[1]).put("recovery_attempt",Integer.parseInt(pair[2]));
            JSONObject record=AppTaskDiagnostics.receipt(request(),bound(new JSONObject().put("status","proposed").put("diagnostic",diagnostic)),200,null,0,1,1);
            assertEquals(pair[0],record.getString("recovery_policy"));assertEquals(pair[1],record.getString("recovery_reason"));
        }
    }
    @Test public void invalidRecoveryAttributionCannotLeakStringsOrMasqueradeAsProgress()throws Exception {
        for(String invalid:java.util.List.of("used_false","used_string","policy","reason","missing_policy","missing_reason",
                "v0_progress","progress_attempt3","v2_progress_attempt3","future_policy","attempt_float","attempt_bool","attempt_missing","attempt4")){
            JSONObject diagnostic=new JSONObject().put("called",true).put("recovery_context_used",true)
                .put("recovery_policy","mc_v1").put("recovery_reason","accepted_action_unconfirmed").put("recovery_attempt",1);
            switch(invalid){
                case "used_false" -> diagnostic.put("recovery_context_used",false);
                case "used_string" -> diagnostic.put("recovery_context_used","true");
                case "policy" -> diagnostic.put("recovery_policy","PRIVATE policy");
                case "reason" -> diagnostic.put("recovery_reason","PRIVATE reason");
                case "missing_policy" -> diagnostic.remove("recovery_policy");
                case "missing_reason" -> diagnostic.remove("recovery_reason");
                case "v0_progress" -> diagnostic.put("recovery_policy","mc_v0").put("recovery_reason","observed_transition_unconfirmed");
                case "progress_attempt3" -> diagnostic.put("recovery_reason","observed_transition_unconfirmed").put("recovery_attempt",3);
                case "v2_progress_attempt3" -> diagnostic.put("recovery_policy","mc_v2").put("recovery_reason","observed_transition_unconfirmed").put("recovery_attempt",3);
                case "future_policy" -> diagnostic.put("recovery_policy","mc_v3");
                case "attempt_float" -> diagnostic.put("recovery_attempt",1.0);
                case "attempt_bool" -> diagnostic.put("recovery_attempt",true);
                case "attempt_missing" -> diagnostic.remove("recovery_attempt");
                case "attempt4" -> diagnostic.put("recovery_attempt",4);
            }
            JSONObject record=AppTaskDiagnostics.receipt(request(),bound(new JSONObject().put("status","proposed").put("diagnostic",diagnostic)),200,null,0,1,1);
            assertFalse(invalid,record.has("recovery_policy"));assertFalse(invalid,record.has("recovery_reason"));assertFalse(record.toString().contains("PRIVATE"));
        }
        JSONObject diagnostic=new JSONObject().put("called",true).put("recovery_context_used",true).put("recovery_policy","mc_v1")
            .put("recovery_reason","observed_transition_unconfirmed").put("recovery_attempt",1);
        JSONObject response=bound(new JSONObject().put("diagnostic",diagnostic)).put("task_id","unrelated-task");
        JSONObject unbound=AppTaskDiagnostics.receipt(request(),response,200,new java.io.IOException("INVALID_APP_TASK_RESPONSE"),0,1,1);
        assertFalse(unbound.has("recovery_policy"));assertFalse(unbound.has("recovery_reason"));
    }
    @Test public void tokenUsageCopiesOnlyReportedBoundedIntegersWithoutInferringMissingCounts()throws Exception {
        JSONObject diagnostic=new JSONObject().put("called",true).put("input_tokens",100).put("output_tokens",25L)
            .put("thinking_tokens",0).put("total_tokens",125).put("usageMetadata",new JSONObject().put("private","PRIVATE provider body"));
        JSONObject response=bound(new JSONObject().put("status","proposed").put("diagnostic",diagnostic));
        JSONObject record=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);
        assertEquals(100,record.getInt("input_tokens"));assertEquals(25,record.getInt("output_tokens"));
        assertEquals(0,record.getInt("thinking_tokens"));assertEquals(125,record.getInt("total_tokens"));
        assertFalse(record.has("usageMetadata"));assertFalse(record.toString().contains("PRIVATE"));
        diagnostic.remove("output_tokens");diagnostic.remove("thinking_tokens");diagnostic.remove("total_tokens");
        record=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);
        assertEquals(100,record.getInt("input_tokens"));
        assertFalse(record.has("output_tokens"));assertFalse(record.has("thinking_tokens"));assertFalse(record.has("total_tokens"));
        JSONObject missing=AppTaskDiagnostics.receipt(request(),bound(new JSONObject().put("status","needs_input")),200,null,0,1,1);
        for(String key:java.util.Set.of("input_tokens","output_tokens","thinking_tokens","total_tokens"))assertFalse(missing.has(key));
    }
    @Test public void malformedOutOfRangeAndUnboundTokenUsageNeverBecomesCostEvidence()throws Exception {
        for(Object invalid:new Object[]{true,1.0,-1,1000001L,"12",JSONObject.NULL}){
            JSONObject diagnostic=new JSONObject().put("called",true);
            for(String key:java.util.Set.of("input_tokens","output_tokens","thinking_tokens","total_tokens"))diagnostic.put(key,invalid);
            JSONObject record=AppTaskDiagnostics.receipt(request(),bound(new JSONObject().put("diagnostic",diagnostic)),200,null,0,1,1);
            for(String key:java.util.Set.of("input_tokens","output_tokens","thinking_tokens","total_tokens"))assertFalse(key+" "+invalid,record.has(key));
        }
        JSONObject diagnostic=new JSONObject().put("called",true);
        for(String key:java.util.Set.of("input_tokens","output_tokens","thinking_tokens","total_tokens"))diagnostic.put(key,1000000L);
        JSONObject response=bound(new JSONObject().put("diagnostic",diagnostic));
        JSONObject maximum=AppTaskDiagnostics.receipt(request(),response,200,null,0,1,1);
        for(String key:java.util.Set.of("input_tokens","output_tokens","thinking_tokens","total_tokens"))assertEquals(1000000,maximum.getInt(key));
        response.put("task_id","unrelated-task");
        JSONObject unbound=AppTaskDiagnostics.receipt(request(),response,200,new java.io.IOException("INVALID_APP_TASK_RESPONSE"),0,1,1);
        for(String key:java.util.Set.of("input_tokens","output_tokens","thinking_tokens","total_tokens"))assertFalse(unbound.has(key));
    }
}
