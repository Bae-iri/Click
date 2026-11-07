package com.click.service.overlay;
import com.click.data.remote.AgentTurnContract;
import com.click.data.snapshot.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class AutomaticAdjustmentApprovalTest {
 private final int[] bounds={0,100,500,200};
 private ScreenSnapshot screen() { return screen("font_size"); }
 private ScreenSnapshot screen(String setting) {
  AccessibilityNodeSnapshot n=new AccessibilityNodeSnapshot("node_0_1","node_0",true,"com.android.settings",Map.of("font_size","글자 크기","brightness","밝기","volume","미디어").get(setting),null,"settings:id/"+setting,"android.widget.SeekBar","slider",bounds,false,true,false,false,false,2f,5,false,0f,7f);
  return new ScreenSnapshot("com.android.settings","2026-10-30T00:00:00Z",1080,2400,Arrays.asList(n),5);
 }
 private JSONObject metadata(String setting) throws Exception {
  return new JSONObject().put("typed_goal",new JSONObject().put("target_app","android_settings")
    .put("slots",new JSONObject().put("setting",setting).put("direction","increase")));
 }
 private AgentTurnContract.GuideCommand command(String proposed,JSONObject policy) throws Exception {
  JSONObject guide=new JSONObject().put("command_type","confirmation").put("action_type","observe_only")
    .put("proposed_action",proposed).put("requires_user_action",true).put("target_node_id","node_0_1")
    .put("target_bounds",new JSONArray(bounds)).put("render_policy",policy);
  return new AgentTurnContract.Response(new JSONObject().put("request_id","r").put("session_id","s")
    .put("task_status","IN_PROGRESS").put("guide_command",guide).toString()).guideCommand;
 }
 @Test public void supportedSettingsUseTheSameConcreteSliderApproval() throws Exception {
  assertTrue(AutomaticAdjustmentApproval.eligible(command("drag",new JSONObject()),metadata("font_size"),screen()));
  assertFalse(AutomaticAdjustmentApproval.eligible(command("tap",new JSONObject()),metadata("font_size"),screen()));
  assertTrue(AutomaticAdjustmentApproval.eligible(command("drag",new JSONObject()),metadata("volume"),screen("volume")));
  assertTrue(AutomaticAdjustmentApproval.eligible(command("drag",new JSONObject()),metadata("brightness"),screen("brightness")));
  assertFalse(AutomaticAdjustmentApproval.eligible(command("drag",new JSONObject()),metadata("unknown"),screen()));
  assertFalse(AutomaticAdjustmentApproval.eligible(command("drag",new JSONObject().put("direction","left")),metadata("brightness"),screen("brightness")));
 }
 @Test public void acceptsBackendDefaultPlacementPolicy() throws Exception {
  JSONObject policy = new JSONObject().put("avoid_covering_target", true).put("preferred_position", "below_target");
  assertTrue(AutomaticAdjustmentApproval.eligible(command("drag", policy), metadata("font_size"), screen()));
  policy.put("unknown_consent", true);
  assertFalse(AutomaticAdjustmentApproval.eligible(command("drag", policy), metadata("font_size"), screen()));
 }
 @Test public void neverCoversVisionConsentOrUnknownRecovery() throws Exception {
  assertFalse(AutomaticAdjustmentApproval.eligible(command("drag",new JSONObject().put("vision_fallback",new JSONObject())),metadata("font_size"),screen()));
  assertFalse(AutomaticAdjustmentApproval.eligible(command("drag",new JSONObject().put("recovery",new JSONObject())),metadata("font_size"),screen()));
 }
 @Test public void acceptsJevProvenanceWithoutBypassingConcreteSliderChecks() throws Exception {
  JSONObject policy = new JSONObject().put("ui_selector", "jev").put("revalidate_before_action", true)
    .put("runtime_strategy", "continue");
  assertTrue(AutomaticAdjustmentApproval.eligible(command("drag", policy), metadata("font_size"), screen()));
  ScreenSnapshot moved = new ScreenSnapshot("com.android.settings", "2026-10-30T00:00:00Z",1080,2400,Collections.emptyList(),5);
  assertFalse(AutomaticAdjustmentApproval.eligible(command("drag", policy), metadata("font_size"), moved));
  policy.put("recovery", new JSONObject());
  assertFalse(AutomaticAdjustmentApproval.eligible(command("drag", policy), metadata("font_size"), screen()));
  policy.remove("recovery"); policy.put("revalidate_before_action", false);
  assertFalse(AutomaticAdjustmentApproval.eligible(command("drag", policy), metadata("font_size"), screen()));
  policy.put("revalidate_before_action", true).put("ui_selector", "unknown");
  assertFalse(AutomaticAdjustmentApproval.eligible(command("drag", policy), metadata("font_size"), screen()));
 }
}
