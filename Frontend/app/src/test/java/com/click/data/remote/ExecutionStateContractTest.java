package com.click.data.remote;
import android.app.Application;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.*;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class ExecutionStateContractTest {
 private AgentTurnContract.Request request(){return AgentTurnContract.Request.control("s",4,"e","observe",
     new ScreenSnapshot("com.android.settings","2026-11-11T00:00:00Z",1080,2400,Collections.emptyList(),5));}
 private JSONObject response(AgentTurnContract.Request request) throws Exception {
  JSONObject guide=new JSONObject().put("guide_command_id","g").put("command_type","highlight").put("action_type","tap")
    .put("requires_user_action",true).put("render_policy",new JSONObject().put("attempt_id","a"));
  JSONObject state=new JSONObject().put("protocol_version",ExecutionState.version()).put("session_id","s")
    .put("session_version",5).put("episode_id","e").put("request_id",request.requestId).put("command_type","observe")
    .put("task_status","IN_PROGRESS").put("phase","GUIDING").put("source","turn")
    .put("observation_id",request.accessibilityTree.observationId).put("guide_command_id","g").put("attempt_id","a");
  return new JSONObject().put("request_id",request.requestId).put("session_id","s").put("session_version",5)
    .put("episode_id","e").put("task_status","IN_PROGRESS").put("guide_command",guide).put("execution_state",state);
 }
 @Test public void rejectsMixedRequestVersionEpisodeObservationGuideAndAttempt() throws Exception {
  var request=request();ExecutionState.validate(new AgentTurnContract.Response(response(request).toString()),request,false);
  for(String key:List.of("request_id","session_id","episode_id","observation_id","guide_command_id","attempt_id","phase")) {
   JSONObject wire=response(request);wire.getJSONObject("execution_state").put(key,"other");
   try {ExecutionState.validate(new AgentTurnContract.Response(wire.toString()),request,false);fail(key);}
   catch(IOException|JSONException expected){}
  }
  for(long version:new long[]{-1,0,4,6,Long.MAX_VALUE}) {
   JSONObject wire=response(request);wire.getJSONObject("execution_state").put("session_version",version);
   try {ExecutionState.validate(new AgentTurnContract.Response(wire.toString()),request,false);fail();}
   catch(IOException|JSONException expected){}
  }
 }
 @Test public void rejectsMissingEnvelopeAndReplayableSnapshotGuide() throws Exception {
  var request=request();JSONObject wire=response(request);wire.remove("execution_state");
  try {ExecutionState.validate(new AgentTurnContract.Response(wire.toString()),request,false);fail();}catch(IOException expected){}
  wire=response(request);wire.getJSONObject("execution_state").put("source","snapshot").put("phase","OBSERVING");
  try {ExecutionState.validate(new AgentTurnContract.Response(wire.toString()),null,true);fail();}catch(IOException expected){}
 }
 @Test public void sharedCasesHaveTheSamePhaseInPythonAndAndroid() throws Exception {
  try(InputStream input=getClass().getResourceAsStream("/execution_phase_cases.json")) {
   assertNotNull(input);JSONArray cases=new JSONArray(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
   for(int i=0;i<cases.length();i++) {
    JSONObject test=cases.getJSONObject(i);JSONObject wire=new JSONObject().put("request_id","r").put("session_id","s")
      .put("task_status",test.getString("status"));
    JSONObject guide=test.optJSONObject("guide");
    if(guide!=null)wire.put("guide_command",new JSONObject(guide.toString()).put("command_type","highlight").put("action_type",guide.optString("action_type","observe_only")));
    if(test.has("verification"))wire.put("metadata",new JSONObject().put("verification",new JSONObject().put("result",test.getJSONObject("verification"))));
    assertEquals(test.getString("phase"),ExecutionState.phaseFor(new AgentTurnContract.Response(wire.toString()),test.getString("command"),false));
   }
  }
 }
}
