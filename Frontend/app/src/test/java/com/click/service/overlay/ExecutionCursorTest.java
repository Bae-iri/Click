package com.click.service.overlay;
import android.app.Application;
import com.click.data.remote.*;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Collections;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class ExecutionCursorTest {
 private AgentTurnContract.Response response(int version,String episode,String source,ScreenSnapshot screen) throws Exception {
  var state=new JSONObject().put("protocol_version",ExecutionState.version()).put("session_id","s").put("session_version",version)
    .put("episode_id",episode).put("request_id","r"+version).put("command_type","observe").put("task_status","IN_PROGRESS")
    .put("phase",source.equals("turn")?"GUIDING":"OBSERVING").put("source",source)
    .put("observation_id",screen.observationId).put("guide_command_id","g").put("attempt_id","a");
  var guide=new JSONObject().put("guide_command_id","g").put("command_type","highlight").put("action_type","tap")
    .put("requires_user_action",true).put("render_policy",new JSONObject().put("attempt_id","a"));
  return new AgentTurnContract.Response(new JSONObject().put("request_id","r"+version).put("session_id","s")
    .put("session_version",version).put("episode_id",episode).put("task_status","IN_PROGRESS")
    .put("guide_command",guide).put("execution_state",state).toString());
 }
 private ScreenSnapshot screen(){return new ScreenSnapshot("com.android.settings","2026-11-11T00:00:00Z",1080,2400,Collections.emptyList(),5);}
 @Test public void oneOwnerRejectsOldVersionsAndForeignEpisodes() throws Exception {
  var owner=new TaskExecutionController();owner.bindSession("s");var screen=screen();
  assertTrue(owner.acceptResponse(response(3,"e","turn",screen),"start_task",false));
  assertFalse(owner.acceptResponse(response(2,"e","turn",screen),"observe",false));
  assertFalse(owner.acceptResponse(response(4,"other","turn",screen),"observe",false));
  assertEquals(3,owner.sessionVersion());assertEquals("e",owner.episodeId());
  assertTrue(owner.acceptResponse(response(4,"other","turn",screen),"replace_task",false));
  owner.clearSession();assertNull(owner.sessionId());assertNull(owner.serverState());
 }
 @Test public void aStatusSnapshotOrDifferentObservationCannotAuthorizeAnAction() throws Exception {
  var owner=new TaskExecutionController();owner.bindSession("s");var screen=screen();var reply=response(1,"e","turn",screen);
  assertTrue(owner.acceptResponse(reply,"start_task",false));assertTrue(owner.matchesGuide(reply.guideCommand,screen));
  assertFalse(owner.matchesGuide(reply.guideCommand,screen()));
  assertTrue(owner.acceptResponse(response(2,"e","snapshot",screen),null,true));
  assertFalse(owner.matchesGuide(reply.guideCommand,screen));
 }
 @Test public void lateVersionMaySynchronizeButCannotUnpauseTheDevice() throws Exception {
  var owner=new TaskExecutionController();owner.bindSession("s");var screen=screen();
  owner.receiveStatus("IN_PROGRESS");owner.pause();
  assertTrue(owner.acceptResponse(response(2,"e","turn",screen),"observe",false));
  assertFalse(owner.receiveStatus("IN_PROGRESS"));assertTrue(owner.paused());assertFalse(owner.canAct());
 }
}
