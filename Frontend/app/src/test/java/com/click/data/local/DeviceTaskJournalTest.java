package com.click.data.local;
import android.app.Application;
import android.content.SharedPreferences;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34,application=Application.class)
public class DeviceTaskJournalTest {
 private SharedPreferences storage;private DeviceTaskJournal journal;
 @Before public void setup() throws Exception {
  storage=RuntimeEnvironment.getApplication().getSharedPreferences("journal-test",0);storage.edit().clear().commit();
  journal=new DeviceTaskJournal(storage,"owner-a");
  journal.prepare("session","episode","settings.wifi.disable","wifi","off");
 }
 private JSONObject wire(String id) throws Exception {
  return new JSONObject().put("session_id","session").put("episode_id","episode").put("request_id",id).put("expected_version",2)
   .put("command_type","observe").put("user_action_event",new JSONObject().put("action_type","tap_completed").put("event_id","event"))
   .put("accessibility_tree",new JSONObject().put("applied_settings",new JSONObject().put("wifi_state",1)));
 }
 @Test public void processRecreationCannotReissueAnUnconfirmedOperation() throws Exception {
  assertTrue(journal.begin("session","episode","guide","safe-operation"));
  journal=new DeviceTaskJournal(storage,"owner-a");
  assertFalse(journal.begin("session","episode","guide","safe-operation"));
  assertFalse(journal.begin("session","episode","new-guide","another-operation"));
  journal.verified("session","wrong-guide");assertFalse(journal.begin("session","episode","new-guide","another-operation"));
  journal.verified("session","guide");assertTrue(journal.begin("session","episode","new-guide","another-operation"));
  assertFalse(storage.getAll().toString().contains("safe-operation"));
 }
 @Test public void outboxSurvivesRestartAndIsAccountBound() throws Exception {
  journal.stage(wire("receipt"));
  assertEquals("receipt",new DeviceTaskJournal(storage,"owner-a").pending("session").getString("request_id"));
  assertNull(new DeviceTaskJournal(storage,"owner-b").pending("session"));
  assertNull(journal.pending("another-session"));
  journal.acknowledge("session","different-receipt");assertNotNull(journal.pending("session"));
  journal.acknowledge("session","receipt");assertNull(journal.pending("session"));
 }
 @Test public void pendingActionEvidenceIsNotOverwrittenByANewObservation() throws Exception {
  journal.stage(wire("receipt"));
  try {journal.stage(wire("new"));fail();}catch(java.io.IOException expected){}
  assertEquals("receipt",journal.pending("session").getString("request_id"));
 }
 @Test public void approvalsRawGoalsImagesAndUnexecutedPlansAreNeverReplayable() throws Exception {
  journal.stage(wire("r").put("command_type","respond_confirmation").put("user_confirmation","accepted"));assertNull(journal.pending("session"));
  journal.stage(wire("r").put("source_text","private goal"));assertNull(journal.pending("session"));
  journal.stage(wire("r").put("vision_snapshot",new JSONObject()));assertNull(journal.pending("session"));
  var noAction=wire("r");noAction.remove("user_action_event");journal.stage(noAction);assertNull(journal.pending("session"));
 }
 @Test public void finishingTheOwnedSessionRetiresItsReplayReceipt() throws Exception {
  journal.stage(wire("r"));journal.finish("other");assertNotNull(journal.pending("session"));
  journal.finish("session");assertNull(journal.pending("session"));assertNull(journal.plan("session"));
 }
 @Test public void replacingATaskDoesNotLoseOrOverwriteThePreviousReceipt() throws Exception {
  journal.stage(wire("old-receipt"));
  journal.prepare("new-session","new-episode","settings.wifi.enable","wifi","on");
  var next=wire("new-receipt").put("session_id","new-session").put("episode_id","new-episode");journal.stage(next);
  assertEquals("old-receipt",journal.pending("session").getString("request_id"));
  assertEquals("new-receipt",journal.pending("new-session").getString("request_id"));
  journal.finish("new-session");assertNotNull(journal.pending("session"));
 }

 @Test public void accountChangeInvalidatesAnAlreadyConstructedJournal() throws Exception {
  var current=new java.util.concurrent.atomic.AtomicReference<String>("owner-a");
  var scoped=new DeviceTaskJournal(storage,"owner-a",current::get);scoped.stage(wire("owned"));
  current.set("owner-b");assertNull(scoped.pending("session"));assertNull(scoped.plan("session"));
  try {scoped.begin("session","episode","guide","operation");fail();}catch(java.io.IOException expected){}
  current.set("owner-a");assertEquals("owned",scoped.pending("session").getString("request_id"));
 }

 @Test public void delayedVerificationOnlyReleasesTheMatchingEpisodeAndAttempt() throws Exception {
  assertTrue(journal.begin("session","episode","guide","operation","attempt"));
  journal=new DeviceTaskJournal(storage,"owner-a");
  journal.verifiedAttempt("session","other-episode","attempt");
  journal.verifiedAttempt("session","episode","other-attempt");
  assertFalse(journal.begin("session","episode","next","operation"));
  journal.verifiedAttempt("session","episode","attempt");
  assertTrue(journal.begin("session","episode","next","operation","next-attempt"));
 }

 @Test public void nativeDispatchLinksTheDecisionObservationToTheFreshDeviceRead() throws Exception {
  assertTrue(journal.begin("session","episode","guide","operation","attempt","decision-observation","fresh-observation"));
  String saved=storage.getAll().toString();
  assertTrue(saved.contains("decision-observation"));assertTrue(saved.contains("fresh-observation"));
  assertFalse(journal.begin("session","episode","other-guide","operation","next-attempt"));
 }

}
