package com.click.service.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.click.service.overlay.AutomationRunGuard.Decision.*;
public class AutomationRunGuardTest {
 @Test public void waitsForObservationBeforeNextAction() {
  AutomationRunGuard guard=new AutomationRunGuard();
  assertEquals(ALLOW,guard.begin("s","e","g1","screen1"));
  assertEquals(WAITING_FOR_OBSERVATION,guard.begin("s","e","g2","screen1"));
  assertEquals(ALLOW,guard.begin("s","e","g2","screen2"));
 }
 @Test public void failedOrTimedOutActionCannotBeRepeated() {
  AutomationRunGuard guard=new AutomationRunGuard();
  guard.begin("s","e","g1","screen1");guard.finish("g1");
  assertFalse(guard.awaiting("g1"));
  assertEquals(DUPLICATE,guard.begin("s","e","g1","screen2"));
 }
 @Test public void capsActionsAndResetsOnlyForNewEpisodeOrSession() {
  AutomationRunGuard guard=new AutomationRunGuard();
  for(int i=0;i<24;i++) assertEquals(ALLOW,guard.begin("s","e","g"+i,"screen"+i));
  assertEquals(LIMIT_REACHED,guard.begin("s","e","g24","screen24"));
  assertEquals(ALLOW,guard.begin("s","e2","g1","screen1"));
  assertEquals(ALLOW,guard.begin("s2","e2","g1","screen1"));
 }
 @Test public void nullObservationDoesNotReleasePendingAction() {
  AutomationRunGuard guard=new AutomationRunGuard();
  guard.begin("s","e","g","screen");guard.observe(null);
  assertTrue(guard.awaiting("g"));guard.clear();assertFalse(guard.awaiting("g"));
 }
}
