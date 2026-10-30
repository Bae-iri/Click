package com.click.service.overlay;
import com.click.data.remote.AgentTurnContract;
import com.click.data.snapshot.*;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class AutomaticFontSizeApprovalTest {
 private final int[] bounds={0,100,500,200};
 private ScreenSnapshot screen() {
  AccessibilityNodeSnapshot n=new AccessibilityNodeSnapshot("node_0_1","node_0",true,"com.android.settings",null,null,"font_size","android.widget.SeekBar","slider",bounds,false,true,false,false,false,2f,5,false,0f,7f);
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
 @Test public void acceptsOnlyConcreteFontSliderApproval() throws Exception {
  assertTrue(AutomaticFontSizeApproval.eligible(command("drag",new JSONObject()),metadata("font_size"),screen()));
  assertFalse(AutomaticFontSizeApproval.eligible(command("tap",new JSONObject()),metadata("font_size"),screen()));
  assertFalse(AutomaticFontSizeApproval.eligible(command("drag",new JSONObject()),metadata("volume"),screen()));
 }
 @Test public void neverCoversVisionConsentOrUnknownRecovery() throws Exception {
  assertFalse(AutomaticFontSizeApproval.eligible(command("drag",new JSONObject().put("vision_fallback",new JSONObject())),metadata("font_size"),screen()));
  assertFalse(AutomaticFontSizeApproval.eligible(command("drag",new JSONObject().put("recovery",new JSONObject())),metadata("font_size"),screen()));
 }
}
