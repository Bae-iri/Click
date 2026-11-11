package com.click.service.overlay;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class CompletionEvidenceTest {
 @Test public void missingUnverifiedAndUnchangedEvidenceNeverCompletesWithoutAUserAnswer() throws Exception {
  assertFalse(CompletionEvidence.accepted(null));assertFalse(CompletionEvidence.accepted(new JSONObject()));
  for(String status:new String[]{"UNVERIFIABLE","NOT_ACHIEVED"})
   assertFalse(CompletionEvidence.accepted(new JSONObject().put("task_outcome",new JSONObject().put("technical",new JSONObject().put("status",status)))));
  JSONObject reported=new JSONObject().put("user_result","satisfied");
  assertTrue(CompletionEvidence.accepted(reported));assertFalse(CompletionEvidence.verified(reported));
  JSONObject verified=new JSONObject().put("technical_outcome",new JSONObject().put("status","CHANGED"));
  assertFalse(CompletionEvidence.accepted(verified));assertFalse(CompletionEvidence.verified(verified));
 }
 @Test public void nativePageEvidenceCompletesOnlyItsMatchingNavigationGoal() throws Exception {
  JSONObject proof=new JSONObject().put("status","OPENED").put("reason","settings_page_visible").put("setting","navigation_volume").put("observation_id","page");
  JSONObject metadata=new JSONObject().put("task_outcome",new JSONObject().put("technical",proof));
  assertTrue(CompletionEvidence.accepted(metadata,"settings.navigation.volume"));
  assertFalse(CompletionEvidence.verified(metadata));
  for(String goal:new String[]{null,"settings.volume.increase","settings.dark_mode.enable","settings.navigation.font_size"})
   assertFalse(CompletionEvidence.accepted(metadata,goal));
  proof.put("reason","insufficient_evidence");
  assertFalse(CompletionEvidence.accepted(metadata,"settings.navigation.volume"));
 }

}
