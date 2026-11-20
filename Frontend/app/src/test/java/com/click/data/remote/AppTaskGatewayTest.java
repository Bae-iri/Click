package com.click.data.remote;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.click.testing.AppTaskFixtures.*;

public class AppTaskGatewayTest {
    private JSONObject request()throws Exception{return new JSONObject().put("request_id","r").put("state",new JSONObject().put("task_id","t")).put("screen",observation("org.example.app","검색")).put("apps",new JSONArray());}
    private JSONObject response(JSONObject req)throws Exception {JSONObject screen=req.getJSONObject("screen");return new JSONObject().put("protocol_version","app-task.v2").put("request_id","r").put("task_id","t").put("observation_id",screen.getString("observation_id")).put("fingerprint",screen.getString("fingerprint")).put("status","proposed").put("requires_confirmation",true).put("action",action(screen,"tap","screen_changed"));}
    private void rejects(JSONObject req,JSONObject res)throws Exception {try{AppGuideGateway.validateTask(req,res);fail("Invalid task proposal accepted");}catch(java.io.IOException expected){}}
    @Test public void validatesTaskAndObservationIdentityAndRequiresApproval()throws Exception {
        JSONObject req=request(),res=response(req);AppGuideGateway.validateTask(req,res);
        res.put("task_id","old-task");rejects(req,res);res=response(req).put("observation_id","old-screen");rejects(req,res);res=response(req).put("fingerprint","old");rejects(req,res);res=response(req).put("requires_confirmation",false);rejects(req,res);
    }
    @Test public void rejectsInventedBoundsToolCapabilitiesAndExpectationBypass()throws Exception {
        JSONObject req=request(),res=response(req);res.getJSONObject("action").put("bounds",new JSONArray(new int[]{1,2,3,4}));rejects(req,res);
        res=response(req);res.getJSONObject("action").put("tool","set_text").put("expectation",new JSONObject().put("kind","input_present"));rejects(req,res);
        res=response(req);res.getJSONObject("action").put("expectation",new JSONObject().put("kind","package_opened").put("package","org.example.app"));rejects(req,res);
    }
    @Test public void onlyExplicitlySelectedAppCanOpen()throws Exception {
        JSONObject req=request(),res=response(req);res.getJSONObject("action").put("tool","open_app").put("package","org.other.app").put("expectation",new JSONObject().put("kind","package_opened").put("package","org.other.app"));rejects(req,res);
        req.getJSONArray("apps").put(new JSONObject().put("package","org.other.app").put("label","다른 앱"));AppGuideGateway.validateTask(req,res);
    }
    @Test public void finalResultCannotChangePackagesDisappearOrBeReplacedDuringPlanning()throws Exception {
        JSONObject req=request(),res=response(req),completion=com.click.service.overlay.AppTaskCompletionTest.completion("org.example.app","목록");
        res.put("completion",completion);AppGuideGateway.validateTask(req,res);
        req.getJSONObject("state").put("completion",new JSONObject(completion.toString()));
        res.put("completion",com.click.service.overlay.AppTaskCompletionTest.completion("org.other.app","목록"));rejects(req,res);
        res.put("completion",JSONObject.NULL);rejects(req,res);
        res.put("completion",completion);AppGuideGateway.validateTask(req,res);
        req.getJSONObject("state").remove("completion");res.put("completion",com.click.service.overlay.AppTaskCompletionTest.completion("org.other.app","목록"));rejects(req,res);
    }
}
