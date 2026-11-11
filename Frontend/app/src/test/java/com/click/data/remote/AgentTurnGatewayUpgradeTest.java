package com.click.data.remote;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public class AgentTurnGatewayUpgradeTest {
 private JSONObject schema() throws Exception {
  try(var input=getClass().getResourceAsStream("/openapi.json")){return new JSONObject(new String(input.readAllBytes(),StandardCharsets.UTF_8));}
 }
 private void field(Object target,String name,Object value) throws Exception {
  Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);
 }
 private AgentTurnGateway unsupported() throws Exception {
  AgentTurnGateway gateway=mock(AgentTurnGateway.class,CALLS_REAL_METHODS);
  AgentServerContract old=AgentServerContract.fromOpenApi(schema());old.executionState=false;
  field(gateway,"contract",old);return gateway;
 }
 @Test public void oldContractIsNotCachedForeverAcrossServerDeployment() throws Exception {
  AgentTurnGateway gateway=unsupported();Method load=AgentTurnGateway.class.getDeclaredMethod("loadContract");load.setAccessible(true);
  try {load.invoke(gateway);fail();}catch(InvocationTargetException failed) {
   assertEquals(ExecutionState.ContractMismatch.SERVER_UPGRADE_REQUIRED,((ExecutionState.ContractMismatch)failed.getCause()).code);
  }
  try(MockedStatic<AuthGateway> http=mockStatic(AuthGateway.class)) {
   http.when(()->AuthGateway.get("/openapi.json",null)).thenReturn(new HttpResult(200,schema().toString(),null));
   assertTrue(((AgentServerContract)load.invoke(gateway)).executionState);
   http.verify(()->AuthGateway.get("/openapi.json",null));
  }
 }
 @Test public void restoringOldServerStateStopsBeforeReadingOrMisdiagnosingTaskHistory() throws Exception {
  AgentTurnGateway gateway=unsupported();AuthenticatedHttpClient http=mock(AuthenticatedHttpClient.class);field(gateway,"http",http);
  Method read=AgentTurnGateway.class.getDeclaredMethod("readSession",String.class);read.setAccessible(true);
  try {read.invoke(gateway,"s");fail();}catch(InvocationTargetException failed) {
   assertEquals(ExecutionState.ContractMismatch.SERVER_UPGRADE_REQUIRED,((ExecutionState.ContractMismatch)failed.getCause()).code);
  }
  verifyNoInteractions(http);
 }

 @Test public void aServerWithExecutionStateButNoDeclineSupportMustBeUpgraded() throws Exception {
  AgentTurnGateway gateway=unsupported();AgentServerContract old=AgentServerContract.fromOpenApi(schema());
  old.userDecline=false;field(gateway,"contract",old);
  Method load=AgentTurnGateway.class.getDeclaredMethod("loadContract");load.setAccessible(true);
  try {load.invoke(gateway);fail();}catch(InvocationTargetException failed) {
   assertEquals(ExecutionState.ContractMismatch.SERVER_UPGRADE_REQUIRED,((ExecutionState.ContractMismatch)failed.getCause()).code);
  }
  assertNull(gateway.currentContract());
 }
}
