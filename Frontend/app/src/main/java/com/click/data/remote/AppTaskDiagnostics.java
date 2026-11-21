package com.click.data.remote;

import android.content.Context;
import com.click.data.local.CandidateDiagnosticLog;
import com.click.data.snapshot.AppTaskObservation;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Set;

/** Bounded local request receipts. Never persists request/response bodies or exception messages. */
public final class AppTaskDiagnostics {
    private static final int MAX_BYTES=256*1024;
    private static final Set<String> STATUSES=Set.of("proposed","needs_input","confirm_result","unavailable","protected");
    private static final Set<String> VALIDATION_CODES=Set.of("INVALID_APP_TASK_RESPONSE","INVALID_TASK_COMPLETION",
        "UNBOUND_TASK_COMPLETION","CHANGED_TASK_COMPLETION","INVALID_APP_TASK_ACTION","UNSELECTED_APP",
        "UNGROUNDED_APP_TASK_TARGET","UNSUPPORTED_APP_TASK_TOOL","INVALID_TASK_PLANNING_MODE",
        "INVALID_TASK_GOAL_ASSESSMENT","READ_ONLY_TASK_ACTION","CHANGED_READ_ONLY_TASK_PLAN");
    private static final Set<String> FAILURE_KINDS=Set.of("provider","plan","configuration","parse","observation");
    private static final Set<String> FAILURE_STAGES=Set.of("none","configuration","provider","provider_response",
        "model_output","plan_validation","completion_contract","subgoal_contract","action_contract","observation");
    private static final Set<String> REASON_CODES=Set.of("provider_unavailable","provider_timeout","provider_http_error",
        "provider_transport_error","missing_api_key","invalid_model_configuration","contract_validation_failed",
        "invalid_json","provider_response_invalid","model_output_invalid","plan_validation_failed",
        "result_evidence_candidate","result_evidence_unavailable","bound_result_available",
        "protected_observation","public_observation_unavailable","stale_observation","no_public_controls","unusable_goal",
        "oversized","incomplete","shape","subgoal_result","expected_state","completion","changed_completion",
        "completion_shape","completion_target","completion_label","completion_app","completion_target_app",
        "ungrounded_result_state","completion_control","subgoals","changed_existing_plan","unknown_target",
        "not_clickable","not_editable","not_scrollable","unknown_tool","unselected_app","repeated_failed_action",
        "expectation","ungrounded_expected_state","result_only_action_forbidden","result_only_progress_forbidden",
        "no_allowed_actions","action_not_allowed_by_capabilities","recovery_action_blocked",
        "goal_evidence_unavailable","goal_assessment_invalid","goal_result_candidate","goal_assessment_uncertain",
        "jev_completion_candidate","jev_completion_abstained","jev_completion_no_evidence","jev_completion_too_many_candidates","jev_completion_unconfigured","jev_completion_timeout","jev_completion_transport_error","jev_completion_invalid_response");
    private static final Set<String> PROVIDER_CODES=Set.of("provider_invalid_request","provider_invalid_budget",
        "provider_capacity_exhausted","provider_startup_failed","provider_deadline_exceeded","provider_timeout",
        "provider_transport_error","provider_response_too_large","provider_protocol_error","provider_worker_failed","provider_cleanup_failed");
    private static final Set<String> RECOVERY_POLICIES=Set.of("mc_v0","mc_v1","mc_v2");
    private static final Set<String> RECOVERY_REASONS=Set.of("accepted_action_unconfirmed","observed_transition_unconfirmed");
    private static final Set<String> VALIDATION_FIELDS=Set.of("checks","package","label","resource","role","checked","selected",
        "id","title","status","expectation","kind","bounds","tool","target_id","advances_subgoal","screen_key","index","other","contract");
    private static final Set<String> VALIDATION_TYPES=Set.of("missing","extra_forbidden","literal_error","value_error",
        "string_type","string_too_short","string_too_long","string_pattern_mismatch","bool_type","int_type",
        "float_type","list_type","tuple_type","too_short","too_long","model_type","other");
    private final File directory;
    public AppTaskDiagnostics(Context context){directory=new File(context.getApplicationContext().getNoBackupFilesDir(),"app-task-diagnostics");}

    public static String ref(String value){return value==null||value.isEmpty()?null:AppTaskObservation.hash(value).substring(0,24);}
    /** Unbound response metadata cannot establish provider facts about this request. */
    public static boolean identityBound(JSONObject request,JSONObject response){
        if(request==null||response==null||!"app-task.v2".equals(response.optString("protocol_version"))
                ||!Boolean.TRUE.equals(response.opt("requires_confirmation")))return false;
        JSONObject state=request.optJSONObject("state"),screen=request.optJSONObject("screen");
        return sameIdentity(request,response,"request_id")&&sameIdentity(state,response,"task_id")
            &&sameIdentity(screen,response,"observation_id")&&sameIdentity(screen,response,"fingerprint");
    }
    private static boolean sameIdentity(JSONObject source,JSONObject response,String key){
        return source!=null&&source.opt(key) instanceof String&&!source.optString(key).isEmpty()
            &&source.optString(key).equals(response.optString(key));
    }
    public static JSONObject receipt(JSONObject request,JSONObject response,Integer status,Throwable failure,
                                     long queueMs,long workMs,long totalMs){
        JSONObject out=new JSONObject();
        try{
            JSONObject state=request.optJSONObject("state"),screen=request.optJSONObject("screen");
            out.put("schema_version","app-task-request-receipt.v1")
                .put("request_ref",nullable(ref(request.optString("request_id"))))
                .put("task_ref",nullable(ref(state==null?null:state.optString("task_id"))))
                .put("observation_ref",nullable(ref(screen==null?null:screen.optString("observation_id"))))
                .put("queue_ms",bounded(queueMs)).put("work_ms",bounded(workMs)).put("total_ms",bounded(totalMs))
                .put("raw_content_saved",false);
            if(failure instanceof ApiException)status=((ApiException)failure).status;
            out.put("http_status",status!=null&&status>=100&&status<=599?status:JSONObject.NULL);
            String outcome=response==null?"unknown":response.optString("status");
            out.put("response_status",STATUSES.contains(outcome)?outcome:"unknown")
                .put("outcome",failure==null?"response_received":"request_failed")
                .put("error_code",failure==null?JSONObject.NULL:failureCode(failure));
            if(failure instanceof java.io.IOException&&failure.getMessage()!=null&&VALIDATION_CODES.contains(failure.getMessage()))
                out.put("validation_code",failure.getMessage());
            JSONObject diagnostic=identityBound(request,response)?response.optJSONObject("diagnostic"):null;
            Object called=diagnostic==null?null:diagnostic.opt("called");
            // No response is not evidence that a provider was never invoked.
            out.put("model_call_state",called instanceof Boolean?"observed":"unknown")
                .put("observed_model_calls",called instanceof Boolean?((Boolean)called?1:0):JSONObject.NULL);
            double wait=diagnostic==null?Double.NaN:diagnostic.optDouble("latency_ms",Double.NaN);
            out.put("model_wait_ms",called instanceof Boolean?(!(Boolean)called?0:
                Double.isFinite(wait)&&wait>=0&&wait<=120000?wait:JSONObject.NULL):JSONObject.NULL);
            if(diagnostic!=null){
                String model=diagnostic.optString("model");
                out.put("model_ref",nullable(model.length()<=200?ref(model):null));
                copyEnum(out,diagnostic,"planning_mode",Set.of("next_action","goal_first","result_only"));
                copyEnum(out,diagnostic,"goal_assessment",Set.of("satisfied","not_satisfied","uncertain"));
                copyEnum(out,diagnostic,"completion_provider",Set.of("gemini","jev"));
                copyEnum(out,diagnostic,"failure_kind",FAILURE_KINDS);
                copyEnum(out,diagnostic,"failure_stage",FAILURE_STAGES);
                copyEnum(out,diagnostic,"reason_code",REASON_CODES);copyEnum(out,diagnostic,"reason_code",PROVIDER_CODES);
                copyEnum(out,diagnostic,"completion_grounding",Set.of("deferred_actionable_proof","deferred_unobserved_result","deferred_invalid_contract","deferred_goal_not_satisfied"));
                copyEnum(out,diagnostic,"completion_deferred_reason",Set.of("completion_contract_invalid"));
                Object budget=diagnostic.opt("provider_budget_ms");
                if(budget instanceof Number){double n=((Number)budget).doubleValue();if(Double.isFinite(n)&&n>=0&&n<=9000)out.put("provider_budget_ms",n);}
                if(Integer.valueOf(1).equals(diagnostic.opt("provider_attempt_limit")))out.put("provider_attempt_limit",1);
                for(String key:Set.of("recovery_context_used","capabilities_applied","allowed_actions_context_used"))
                    if(diagnostic.opt(key) instanceof Boolean)out.put(key,diagnostic.opt(key));
                for(String key:Set.of("allowed_action_count","recovery_blocked_action_count","recovery_attempt")){
                    Object value=diagnostic.opt(key);int high=key.equals("allowed_action_count")?322:key.equals("recovery_blocked_action_count")?16:3;
                    int low=key.equals("recovery_attempt")?1:0;
                    if((value instanceof Integer||value instanceof Long)&&((Number)value).longValue()>=low&&((Number)value).longValue()<=high
                        &&(!key.equals("recovery_attempt")||Boolean.TRUE.equals(diagnostic.opt("recovery_context_used"))))out.put(key,value);
                }
                copyRecoveryAttribution(out,diagnostic);
                for(String key:Set.of("input_tokens","output_tokens","thinking_tokens","total_tokens")){
                    Object value=diagnostic.opt(key);
                    if((value instanceof Integer||value instanceof Long)&&((Number)value).longValue()>=0
                        &&((Number)value).longValue()<=1000000)out.put(key,value);
                }
                JSONArray errors=diagnostic.optJSONArray("validation_errors"),safeErrors=new JSONArray();
                for(int i=0;errors!=null&&i<Math.min(4,errors.length());i++){
                    JSONObject error=errors.optJSONObject(i);if(error==null)continue;
                    String raw=error.optString("field");String[] parts=raw.length()<=100?raw.split("\\."):new String[]{"other"};
                    StringBuilder field=new StringBuilder();
                    for(int j=0;j<Math.min(3,parts.length);j++){
                        if(j>0)field.append('.');field.append(VALIDATION_FIELDS.contains(parts[j])?parts[j]:"other");
                    }
                    String type=error.optString("type");
                    safeErrors.put(new JSONObject().put("field",field.length()==0?"contract":field.toString())
                        .put("type",VALIDATION_TYPES.contains(type)?type:"other"));
                }
                if(safeErrors.length()>0)out.put("validation_errors",safeErrors);
            }
            if(failure instanceof ApiException)out.put("server_trace_ref",nullable(ref(((ApiException)failure).traceId)));
        }catch(Exception ignored){ /* Diagnostics must not interfere with execution. */ }
        return out;
    }
    private static void copyEnum(JSONObject out,JSONObject source,String key,Set<String> allowed)throws Exception {
        String value=source.optString(key);if(allowed.contains(value))out.put(key,value);
    }
    private static void copyRecoveryAttribution(JSONObject out,JSONObject diagnostic)throws Exception {
        if(!Boolean.TRUE.equals(diagnostic.opt("recovery_context_used")))return;
        Object rawAttempt=diagnostic.opt("recovery_attempt"),rawPolicy=diagnostic.opt("recovery_policy"),rawReason=diagnostic.opt("recovery_reason");
        if(!(rawAttempt instanceof Integer||rawAttempt instanceof Long)||!(rawPolicy instanceof String)||!(rawReason instanceof String))return;
        long attempt=((Number)rawAttempt).longValue();String policy=(String)rawPolicy,reason=(String)rawReason;
        if(attempt<1||attempt>3||!RECOVERY_POLICIES.contains(policy)||!RECOVERY_REASONS.contains(reason)
            ||reason.equals("observed_transition_unconfirmed")&&(!Set.of("mc_v1","mc_v2").contains(policy)||attempt>2))return;
        out.put("recovery_policy",policy).put("recovery_reason",reason);
    }
    private static Object nullable(String value){return value==null?JSONObject.NULL:value;}
    private static long bounded(long value){return Math.max(0,Math.min(120000,value));}
    private static String failureCode(Throwable error){
        if(error instanceof ApiException)return "http_rejected";
        if(error instanceof java.net.SocketTimeoutException)return "timeout";
        if(error instanceof java.util.concurrent.CancellationException)return "cancelled";
        if(error instanceof org.json.JSONException)return "invalid_response_json";
        if(error instanceof java.io.IOException){
            String message=error.getMessage();
            if("REQUEST_DEADLINE_EXCEEDED".equals(message))return "deadline_exceeded";
            if("AUTH_REQUIRED".equals(message))return "auth_required";
            if("REQUEST_CANCELLED".equals(message))return "cancelled";
            if(message!=null&&VALIDATION_CODES.contains(message))return "response_validation_failed";
            return "network_error";
        }
        return "unexpected_error";
    }
    public void record(JSONObject report){
        if(!CandidateDiagnosticLog.enabled())return;
        try{
            report.put("recorded_at_ms",System.currentTimeMillis());
            byte[] bytes=(report.toString()+"\n").getBytes(StandardCharsets.UTF_8);if(bytes.length>4096)return;
            synchronized(AppTaskDiagnostics.class){
                Files.createDirectories(directory.toPath());File current=new File(directory,"current.jsonl");
                if(current.length()+bytes.length>MAX_BYTES)Files.move(current.toPath(),new File(directory,"previous.jsonl").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.write(current.toPath(),bytes,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            }
        }catch(Exception ignored){ /* A full disk or diagnostic failure cannot break a task. */ }
    }
}
