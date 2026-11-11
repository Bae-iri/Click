package com.click.service.overlay;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.local.DeviceTaskJournal;
import com.click.data.remote.ExecutionState;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in device persistence probe. Uses an isolated encrypted file and sends no fixture events. */
@RunWith(AndroidJUnit4.class)
public class ExecutionStateDeviceTest {
 private SharedPreferences preferences(Context app) throws Exception {
  MasterKey key=new MasterKey.Builder(app,"click_execution_probe_master").setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
  return EncryptedSharedPreferences.create(app,"click_execution_contract_probe",key,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
 }
 @Test public void persistsUnknownExecutionAcrossProcessRestartAndNetworkLoss() throws Exception {
  String phase=InstrumentationRegistry.getArguments().getString("executionContractProbe");
  assumeTrue(phase!=null);
  Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
  SharedPreferences prefs=preferences(app);
  if("cleanup".equals(phase)){assertTrue(prefs.edit().clear().commit());return;}
  DeviceTaskJournal journal=new DeviceTaskJournal(prefs,"fixture-owner");
  if("stage".equals(phase)) {
   assertTrue(prefs.edit().clear().commit());
   journal.prepare("fixture-session","fixture-episode","settings.wifi.disable","wifi","off");
   assertTrue(journal.begin("fixture-session","fixture-episode","fixture-guide","fixture-not-dispatched",
       "fixture-attempt","fixture-decision","fixture-device-read"));
   JSONObject receipt=new JSONObject().put("session_id","fixture-session").put("episode_id","fixture-episode")
       .put("request_id","fixture-receipt").put("expected_version",1).put("command_type","observe")
       .put("user_action_event",new JSONObject().put("action_type","tap_completed").put("guide_command_id","fixture-guide"));
   journal.stage(receipt);
   assertNotNull(journal.pending("fixture-session"));
  } else {
   assertEquals("verify",phase);
   assertEquals("Probe runs during the host-controlled Wi-Fi interruption",WifiManager.WIFI_STATE_DISABLED,
       app.getSystemService(WifiManager.class).getWifiState());
   assertEquals("fixture-receipt",journal.pending("fixture-session").getString("request_id"));
   assertFalse(journal.begin("fixture-session","fixture-episode","another-guide","fixture-not-dispatched"));
   assertNull(new DeviceTaskJournal(prefs,"different-owner").pending("fixture-session"));
   journal.verifiedAttempt("fixture-session","fixture-episode","wrong-attempt");
   assertFalse(journal.begin("fixture-session","fixture-episode","another-guide","fixture-not-dispatched"));
   journal.verifiedAttempt("fixture-session","fixture-episode","fixture-attempt");
   assertTrue(journal.begin("fixture-session","fixture-episode","next-guide","fixture-not-dispatched"));
  }
  try(java.io.InputStream input=app.getAssets().open("execution_state_contract.json")) {
   JSONObject policy=new JSONObject(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
   assertEquals(ExecutionState.version(),policy.getString("version"));
  }
  TaskExecutionController state=new TaskExecutionController();state.bindSession("fixture-session");
  state.receiveStatus("IN_PROGRESS");state.pause();
  assertFalse(state.receiveStatus("IN_PROGRESS"));assertTrue(state.paused());assertFalse(state.canAct());
 }
}
