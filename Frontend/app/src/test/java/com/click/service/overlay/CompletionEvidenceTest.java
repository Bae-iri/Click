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
  assertTrue(CompletionEvidence.accepted(verified));assertTrue(CompletionEvidence.verified(verified));
 }
}
