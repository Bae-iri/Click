package com.click.service.overlay;
import org.junit.Test;
import static org.junit.Assert.*;
public class TaskExecutionControllerTest {
 @Test public void lateResponseCannotResumePausedTaskWithoutExplicitCommand() {
  TaskExecutionController state=new TaskExecutionController();
  state.receiveStatus("IN_PROGRESS");state.pause();
  assertFalse(state.receiveStatus("IN_PROGRESS"));assertFalse(state.canObserve());
  assertFalse(state.receiveStatus("WAITING_RESULT"));
  state.requestResume();assertTrue(state.receiveStatus("IN_PROGRESS"));assertTrue(state.canObserve());
 }
 @Test public void duplicateAndReplacedCallbacksAreConsumedOnlyOnce() {
  TaskExecutionController state=new TaskExecutionController();
  TaskExecutionController.RequestToken first=state.beginRequest();
  assertTrue(state.finishRequest(first));assertFalse(state.finishRequest(first));
  TaskExecutionController.RequestToken old=state.beginRequest();state.newTask();
  TaskExecutionController.RequestToken current=state.beginRequest();
  assertFalse(state.finishRequest(old));assertTrue(state.busy());assertTrue(state.finishRequest(current));
 }
 @Test public void actionRequiresPresentedGuideAndTransitionsToVerification() {
  TaskExecutionController state=new TaskExecutionController();assertFalse(state.canAct());
  state.guidePresented(true);assertFalse(state.canAct());
  state.guidePresented(false);assertTrue(state.canAct());
  state.actionStarted();assertFalse(state.canAct());assertFalse(state.canObserve());
  state.actionDispatched(true);assertEquals(TaskExecutionController.Phase.VERIFYING,state.phase());assertTrue(state.canObserve());
 }
 @Test public void manualAndResultStatesNeverRestartFromScreenEvents() {
  TaskExecutionController state=new TaskExecutionController();state.guidePresented(false);state.manualPresented();
  state.requestObservation();assertFalse(state.canObserve());assertFalse(state.observationRequested());
  state.receiveStatus("IN_PROGRESS");assertTrue(state.manual());
  state.receiveStatus("WAITING_RESULT");assertFalse(state.manual());assertFalse(state.canObserve());
  assertFalse(state.receiveStatus("IN_PROGRESS"));state.guidePresented(false);assertFalse(state.canAct());
  state.receiveStatus("WAITING_ADJUSTMENT");assertFalse(state.canObserve());
  state.finishRequest(state.beginRequest("adjust_task"));assertTrue(state.receiveStatus("IN_PROGRESS"));
 }
 @Test public void retryAndResumeRestoreManualStateWithoutNestedPauseDeadlock() {
  TaskExecutionController state=new TaskExecutionController();state.manualPresented();state.pause();state.recovery();
  assertTrue(state.retryRequired());state.retry();state.resume();assertTrue(state.manual());
  state.exitManual();assertTrue(state.canObserve());
 }
 @Test public void terminalStateRequiresNewTaskEvenWhenResumeWasRequested() {
  TaskExecutionController state=new TaskExecutionController();state.stop();state.requestResume();
  assertFalse(state.receiveStatus("IN_PROGRESS"));assertFalse(state.canAct());assertFalse(state.canObserve());
  state.newTask();assertTrue(state.canObserve());
 }
 @Test public void explicitResumeFromManualReobservesBeforeRecreatingInstructions() {
  TaskExecutionController state=new TaskExecutionController();state.manualPresented();state.pause();
  state.requestResume();assertTrue(state.receiveStatus("IN_PROGRESS"));assertTrue(state.canObserve());assertFalse(state.manual());
 }
 @Test public void staleConfirmationCannotEscapeRecoveryOrResultWaiting() {
  TaskExecutionController state=new TaskExecutionController();state.recovery();
  assertFalse(state.receiveStatus("WAITING_CONFIRMATION"));assertTrue(state.retryRequired());
  state.retry();state.receiveStatus("WAITING_RESULT");state.manualPresented();state.guidePresented(false);
  assertEquals(TaskExecutionController.Phase.RESULT,state.phase());assertFalse(state.canAct());
 }

 @Test public void realPageChangeReopensRecoveryButKeepsPersonalChoiceHandoffWaiting() {
  var state=new TaskExecutionController();state.guidePresented(false);state.recovery();
  assertTrue(state.pageChanged());assertTrue(state.canObserve());assertFalse(state.canAct());
  state.manualPresented();assertFalse(state.pageChanged());assertFalse(state.canObserve());state.exitManual();
  state.pause();assertFalse(state.pageChanged());assertTrue(state.paused());
  state.resume();state.receiveStatus("WAITING_RESULT");assertFalse(state.pageChanged());assertFalse(state.canObserve());
 }

 @Test public void pageAndEnvironmentEventsCannotResolveNetworkOrUnknownResultRecovery() {
  for(var cause:new TaskExecutionController.RecoveryCause[]{TaskExecutionController.RecoveryCause.NETWORK,TaskExecutionController.RecoveryCause.RESULT_UNKNOWN}) {
   var state=new TaskExecutionController();state.guidePresented(false);state.recovery(cause);
   assertFalse(state.pageChanged());assertFalse(state.environmentReady());assertFalse(state.canObserve());assertFalse(state.canAct());
   state.requestObservation();assertFalse(state.canObserve());
   assertFalse(state.receiveStatus("IN_PROGRESS"));state.retry();assertTrue(state.canObserve());assertFalse(state.canAct());
  }
 }
 @Test public void lateReceiptSynchronizationCannotResumePauseOrTerminalState() {
  var state=new TaskExecutionController();state.guidePresented(false);state.recovery(TaskExecutionController.RecoveryCause.NETWORK);
  var token=state.beginRequest("reconcile_offline");state.pause();assertTrue(state.finishRequest(token));
  assertFalse(state.receiveStatus("IN_PROGRESS"));assertFalse(state.canObserve());state.stop();
  assertFalse(state.receiveStatus("WAITING_RESULT"));assertFalse(state.canAct());
 }

 @Test public void sensitiveModeInterruptsExecutingButCannotResumeExplicitPause() {
  var state=new TaskExecutionController();state.guidePresented(false);state.actionStarted();
  assertEquals(TaskExecutionController.Phase.EXECUTING,state.phase());
  assertFalse(state.environmentBlocked());assertTrue(state.sensitiveEnvironmentBlocked());
  assertFalse(state.canAct());assertFalse(state.canObserve());
  state.actionDispatched(true);assertEquals(TaskExecutionController.Phase.RECOVERY,state.phase());
  state.pause();assertFalse(state.sensitiveEnvironmentBlocked());assertTrue(state.paused());
 }
}
