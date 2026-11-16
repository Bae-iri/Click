package com.click.data.remote;

import org.json.*;
import java.io.IOException;
import java.util.*;

/** Immutable server snapshot. It is evidence for a version, never permission to replay a tool. */
public final class ExecutionState {
    public static final class ContractMismatch extends IOException {
        public static final String SERVER_UPGRADE_REQUIRED="SERVER_UPGRADE_REQUIRED";
        public final String code;
        public ContractMismatch(){this("STATE_MISMATCH");}
        public ContractMismatch(String code){
            super(SERVER_UPGRADE_REQUIRED.equals(code)?"서버 업데이트가 아직 반영되지 않았어요. 업데이트가 끝난 뒤 다시 시작해 주세요.":"서버와 앱의 실행 계약이 맞지 않아요. 업데이트 후 다시 시작해 주세요.");
            this.code=code;
        }
    }
    private static final JSONObject POLICY;
    static {try {POLICY=new JSONObject(com.click.generated.ExecutionStateData.json());}
        catch(JSONException invalid){throw new ExceptionInInitializerError(invalid);}}
    public static String version(){return POLICY.optString("version");}
    public static String phase(String status){return POLICY.optJSONObject("status_phases").optString(status,null);}
    public static String phaseFor(AgentTurnContract.Response response,String command,boolean snapshot) {
        if(POLICY.optJSONObject("command_phases").has(command))return POLICY.optJSONObject("command_phases").optString(command);
        String base=phase(response.taskStatus);
        if(snapshot || !"IN_PROGRESS".equals(response.taskStatus))return base;
        JSONObject verification=response.metadata==null?null:response.metadata.optJSONObject("verification");
        JSONObject result=verification==null?null:verification.optJSONObject("result");
        if(result!=null && nullable(result,"attempt_id")!=null && contains("verification_wait_statuses",result.optString("status")))return POLICY.optString("verification_phase");
        AgentTurnContract.GuideCommand guide=response.guideCommand;
        if(guide!=null && guide.renderPolicy!=null && guide.renderPolicy.optBoolean("manual_handoff"))return POLICY.optString("manual_phase");
        if(guide!=null && guide.requiresUserAction && ("tap".equals(guide.actionType) || "drag".equals(guide.actionType)))return POLICY.optString("guidance_phase");
        return base;
    }
    public static boolean episodeChanging(String command){return contains("episode_changing_commands",command);}
    public static boolean preparation(String command){return contains("preparation_commands",command);}
    private static boolean contains(String key,String value){JSONArray a=POLICY.optJSONArray(key);for(int i=0;i<a.length();i++)if(a.optString(i).equals(value))return true;return false;}
    public final String sessionId,episodeId,requestId,command,status,phase,observationId,attemptId,guideId,source,graphHash,goalType;
    public final int version;
    public final String verificationAttemptId,verificationStatus;
    public ExecutionState(JSONObject value) throws JSONException {
        if(!version().equals(value.getString("protocol_version")))throw new JSONException("EXECUTION_CONTRACT_MISMATCH");
        long number=value.getLong("session_version");
        if(number<0 || number>Integer.MAX_VALUE)throw new JSONException("EXECUTION_VERSION_INVALID");
        version=(int)number;sessionId=value.getString("session_id");episodeId=nullable(value,"episode_id");
        requestId=nullable(value,"request_id");command=value.getString("command_type");status=value.getString("task_status");
        phase=value.getString("phase");source=value.getString("source");
        graphHash=nullable(value,"graph_hash");goalType=nullable(value,"goal_type");
        observationId=nullable(value,"observation_id");attemptId=nullable(value,"attempt_id");guideId=nullable(value,"guide_command_id");
        verificationAttemptId=nullable(value,"verification_attempt_id");verificationStatus=nullable(value,"verification_status");
        if(sessionId.isEmpty() || !knownPhase(phase) || !(source.equals("turn") || source.equals("snapshot")))
            throw new JSONException("EXECUTION_STATE_INVALID");
    }
    private static boolean knownPhase(String value){
        JSONObject phases=POLICY.optJSONObject("status_phases");Iterator<String> keys=phases.keys();
        while(keys.hasNext())if(value.equals(phases.optString(keys.next())))return true;
        return value.equals(POLICY.optString("guidance_phase")) || value.equals(POLICY.optString("manual_phase")) || value.equals(POLICY.optString("verification_phase"));
    }
    private static String nullable(JSONObject value,String key){return value.isNull(key)?null:value.optString(key,null);}
    public static JSONObject context(AgentTurnContract.Request request) throws JSONException {
        return new JSONObject().put("protocol_version",version()).put("session_id",request.sessionId)
            .put("session_version",request.expectedVersion==null?0:request.expectedVersion)
            .put("episode_id",request.episodeId==null?JSONObject.NULL:request.episodeId)
            .put("request_id",request.requestId).put("observation_id",request.accessibilityTree==null?JSONObject.NULL:request.accessibilityTree.observationId);
    }
    public static void validate(AgentTurnContract.Response response,AgentTurnContract.Request request,boolean snapshot) throws IOException {
        try {validateStored(response,request==null?null:new JSONObject(request.toJson()),snapshot);}
        catch(JSONException invalid){throw new ContractMismatch();}
    }
    public static void validateStored(AgentTurnContract.Response response,JSONObject wire,boolean snapshot) throws IOException {
        ExecutionState state=response.executionState;
        if(state==null)throw new ContractMismatch();
        if(!response.sessionId.equals(state.sessionId) || !Objects.equals(response.episodeId,state.episodeId)
                || response.sessionVersion!=state.version || !response.taskStatus.equals(state.status)
                || !Objects.equals(state.phase,phaseFor(response,snapshot?"snapshot":state.command,snapshot)))fail();
        if(response.acceptedGoalType!=null && !Objects.equals(response.acceptedGoalType,state.goalType))fail();
        if(response.graphHash!=null && !Objects.equals(response.graphHash,state.graphHash))fail();
        if(snapshot){if(!"snapshot".equals(state.source) || response.guideCommand!=null || state.requestId!=null)fail();return;}
        JSONObject tree=wire==null?null:wire.optJSONObject("accessibility_tree");
        if(wire==null || !"turn".equals(state.source) || !wire.optString("request_id").equals(state.requestId)
                || !wire.optString("session_id").equals(state.sessionId) || !wire.optString("command_type").equals(state.command)
                || !Objects.equals(tree==null?null:nullable(tree,"observation_id"),state.observationId))fail();
        JSONObject goal=wire.optJSONObject("goal");
        if(goal!=null && !Objects.equals(goal.optString("goal_type"),state.goalType))fail();
        long before=wire.optLong("expected_version",0);
        if(state.version!=(preparation(state.command) || "unchanged".equals(response.commandResultStatus)?before:before+1))fail();
        if(!episodeChanging(state.command) && !preparation(state.command) && !Objects.equals(nullable(wire,"episode_id"),state.episodeId))fail();
        JSONObject verification=response.metadata==null?null:response.metadata.optJSONObject("verification");
        JSONObject result=verification==null?null:verification.optJSONObject("result");
        if(!Objects.equals(result==null?null:nullable(result,"attempt_id"),state.verificationAttemptId)
                || !Objects.equals(result==null?null:nullable(result,"status"),state.verificationStatus))fail();
        AgentTurnContract.GuideCommand guide=response.guideCommand;
        if(!Objects.equals(guide==null?null:guide.guideCommandId,state.guideId)
                || !Objects.equals(guide==null || guide.renderPolicy==null?null:nullable(guide.renderPolicy,"attempt_id"),state.attemptId))fail();
    }
    private static void fail() throws IOException {throw new ContractMismatch();}
}
