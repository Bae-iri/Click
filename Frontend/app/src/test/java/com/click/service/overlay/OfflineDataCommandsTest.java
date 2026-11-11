package com.click.service.overlay;
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
public class OfflineDataCommandsTest {
 @Test public void manualResultAnswersCanBeStoredButNeverBecomeActionApprovals() throws Exception {
  var prefs=RuntimeEnvironment.getApplication().getSharedPreferences("offline-results",0);prefs.edit().clear().commit();
  var journal=new DeviceTaskJournal(prefs,"owner");journal.prepare("s","e","settings.screen_lock.change","screen_lock","");
  var result=new JSONObject().put("session_id","s").put("episode_id","e").put("request_id","result")
   .put("command_type","respond_result").put("user_result","satisfied").put("result_prompt_id","prompt");
  journal.stage(result);assertNotNull(journal.pending("s"));journal.acknowledge("s","result");
  result.put("command_type","respond_confirmation").put("user_confirmation","accepted");journal.stage(result);assertNull(journal.pending("s"));
 }
}
