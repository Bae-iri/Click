package com.click.data.remote;

import android.app.Application;
import com.click.data.local.DeviceTaskJournal;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class OfflineReceiptVerificationTest {
 @Test public void reconnectKeepsOnlyMatchingExecutionEvidenceBeforeDroppingTheGuide() throws Exception {
  var prefs=RuntimeEnvironment.getApplication().getSharedPreferences("receipt-proof",0);prefs.edit().clear().commit();
  var journal=new DeviceTaskJournal(prefs,"owner");journal.begin("s","e","guide","operation","attempt");
  var wire=new JSONObject().put("session_id","s").put("episode_id","e")
    .put("user_action_event",new JSONObject().put("guide_command_id","guide"));
  var result=new JSONObject().put("status","SUCCESS").put("attempt_id","other-attempt");
  var body=new JSONObject().put("request_id","r").put("session_id","s").put("session_version",3)
    .put("task_status","IN_PROGRESS").put("metadata",new JSONObject().put("verification",new JSONObject().put("result",result)));
  AgentTurnGateway.recordReceiptVerification(journal,wire,new AgentTurnContract.Response(body.toString()));
  assertFalse(journal.begin("s","e","next-guide","next-operation"));
  result.put("attempt_id","attempt");
  AgentTurnGateway.recordReceiptVerification(journal,wire,new AgentTurnContract.Response(body.toString()));
  assertTrue(journal.begin("s","e","next-guide","next-operation"));
 }
}
