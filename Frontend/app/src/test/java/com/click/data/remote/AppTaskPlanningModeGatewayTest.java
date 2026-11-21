package com.click.data.remote;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

public class AppTaskPlanningModeGatewayTest {
    private JSONObject request(String mode)throws Exception {
        JSONObject req=new JSONObject().put("request_id","r").put("goal","Open destination")
            .put("state",new JSONObject().put("task_id","t").put("subgoals",new JSONArray().put(new JSONObject()
                .put("id","g0").put("title","Open destination").put("status","pending"))))
            .put("screen",observation("org.example.app","Destination")).put("apps",new JSONArray());
        if(mode!=null)req.put("planning_mode",mode);return req;
    }
    private JSONObject response(JSONObject req,String assessment)throws Exception {
        JSONObject screen=req.getJSONObject("screen");return new JSONObject().put("protocol_version","app-task.v2")
            .put("request_id","r").put("task_id","t").put("observation_id",screen.getString("observation_id"))
            .put("fingerprint",screen.getString("fingerprint")).put("requires_confirmation",true).put("status","confirm_result")
            .put("subgoals",new JSONArray(req.getJSONObject("state").getJSONArray("subgoals").toString())).put("active_subgoal","g0")
            .put("completion",com.click.service.overlay.AppTaskCompletionTest.completion("org.example.app","Destination"))
            .put("diagnostic",new JSONObject().put("goal_assessment",assessment));
    }
    private void rejects(JSONObject request,JSONObject response)throws Exception {
        try{AppGuideGateway.validateTask(request,response);fail("Rejected planning-mode contract accepted");}catch(java.io.IOException expected){}
    }
    @Test public void satisfiedPendingPlanHasEvidenceButDoesNotCompleteThePlan()throws Exception {
        JSONObject req=request("goal_first"),res=response(req,"satisfied");AppGuideGateway.validateTask(req,res);
        assertEquals("pending",req.getJSONObject("state").getJSONArray("subgoals").getJSONObject(0).getString("status"));
    }
    @Test public void satisfiedAndUncertainCannotCarryAnotherAction()throws Exception {
        for(String assessment:new String[]{"satisfied","uncertain"}){
            JSONObject req=request("goal_first"),res=response(req,assessment);res.put("action",action(req.getJSONObject("screen"),"tap","screen_changed"));rejects(req,res);
        }
    }
    @Test public void uncertainAssessmentCannotInstallNewGoalEvidence()throws Exception {
        JSONObject req=request("goal_first"),res=response(req,"uncertain");res.put("status","needs_input");rejects(req,res);
        res.remove("completion");AppGuideGateway.validateTask(req,res);
    }
    @Test public void completionEvidenceCannotBeOmittedForSatisfiedAssessment()throws Exception {
        JSONObject req=request("goal_first"),res=response(req,"satisfied");res.remove("completion");rejects(req,res);
    }
    @Test public void readOnlyPlanStatusTitleAndActiveIdentityStayImmutable()throws Exception {
        for(String mode:new String[]{"goal_first","result_only"})for(String field:new String[]{"status","title","active_subgoal"}){
            JSONObject req=request(mode),res=response(req,"satisfied");
            if("active_subgoal".equals(field))res.put(field,JSONObject.NULL);
            else res.getJSONArray("subgoals").getJSONObject(0).put(field,"status".equals(field)?"verified":"Another task");
            rejects(req,res);
        }
    }
    @Test public void explicitResultOnlyCanRunWithPendingPlanButCannotProposeAction()throws Exception {
        JSONObject req=request("result_only"),res=response(req,"satisfied");AppGuideGateway.validateTask(req,res);
        res.put("status","proposed").put("action",action(req.getJSONObject("screen"),"tap","screen_changed"));rejects(req,res);
    }
    @Test public void negativeAssessmentCanContinueTheExistingOneCallActionPath()throws Exception {
        JSONObject req=request("goal_first"),res=response(req,"not_satisfied");res.remove("completion");
        res.put("status","proposed").put("action",action(req.getJSONObject("screen"),"tap","screen_changed"));AppGuideGateway.validateTask(req,res);
    }
    @Test public void missingOrMalformedAssessmentNeverGrantsAnAction()throws Exception {
        for(Object value:new Object[]{JSONObject.NULL,true,"yes"}){
            JSONObject req=request("goal_first"),res=response(req,"not_satisfied");res.getJSONObject("diagnostic").put("goal_assessment",value);
            res.put("status","proposed").put("action",action(req.getJSONObject("screen"),"tap","screen_changed"));rejects(req,res);
        }
    }
    @Test public void providerFailureWithoutAssessmentRemainsAnOrdinaryFailure()throws Exception {
        JSONObject req=request("goal_first"),res=response(req,"satisfied");res.remove("completion");res.remove("diagnostic");
        res.put("status","unavailable");AppGuideGateway.validateTask(req,res);
    }
    @Test public void legacyRequestRemainsCompatible()throws Exception {
        JSONObject req=request(null),res=response(req,"satisfied");res.remove("diagnostic");res.remove("completion");
        res.put("status","proposed").put("action",action(req.getJSONObject("screen"),"tap","screen_changed"));AppGuideGateway.validateTask(req,res);
    }
}
