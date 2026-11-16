package com.click.service.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
public class AdjustmentDelegationTest {
 @Test public void delegationIsExplicitGoalBoundExpiringAndSingleUse() throws Exception {
  var consent=new AdjustmentDelegation();assertFalse(consent.permits("settings.volume.increase",100));
  consent.grant("settings.volume.increase",100);
  assertFalse(consent.permits("settings.brightness.increase",200));assertFalse(consent.permits("settings.volume.increase",99));
  var provenance=consent.consume("settings.volume.increase",200);
  assertEquals("delegated_goal",provenance.getString("source"));assertEquals(1,provenance.getInt("max_actions"));
  assertEquals(.2,provenance.getDouble("max_delta_fraction"),0);assertNull(consent.consume("settings.volume.increase",201));
  consent.grant("settings.volume.increase",100);assertFalse(consent.permits("settings.volume.increase",300100));
  consent.grant("settings.volume.increase",100);consent.clear();assertFalse(consent.permits("settings.volume.increase",101));
 }
 @Test public void localReceiptsNeverTurnSwitchObservationIntoTechnicalSuccess() throws Exception {
  var plan=new org.json.JSONObject().put("setting","wifi");
  String text=LocalTaskReceipt.message(plan,null,1,null);
  assertTrue(text.contains("꺼져"));assertTrue(text.contains("네트워크 연결 여부와는 별개"));assertTrue(text.contains("다시 실행하지"));
  assertFalse(text.contains("완료했"));assertFalse(LocalTaskReceipt.message(plan,null,0,null).contains("꺼져"));
 }
 @Test public void repeatedReconnectSignalsCannotPollForever() {
  var gate=new ReceiptReconciliationGate();
  assertTrue(gate.allow("receipt","wifi",0));assertFalse(gate.allow("receipt","wifi",6000));
  assertTrue(gate.allow("receipt","mobile",6000));assertFalse(gate.allow("receipt","wifi",6001));
  assertTrue(gate.allow("receipt","wifi",12000));assertFalse(gate.allow("receipt","mobile",18000));
  assertTrue(gate.allow("new-receipt","mobile",18000));
 }

}
