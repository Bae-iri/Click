package com.click.testutil;
import com.click.service.overlay.ClickOverlayService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/** Instrumentation inspects the controller that owns task state, never removed service copies. */
public final class ServiceStateAccess {
 private static final Set<String> CURSOR=Set.of("agentSessionId","agentSessionVersion","agentEpisodeId","acceptedGoal","firstTurnSent");
 private static final Map<String,String> READERS=Map.of("turnInFlight","busy","agentPaused","paused",
     "executionGeneration","generation","manualSettingsHandoff","manual","waitingForExplicitRetry","retryRequired",
     "forceNextObservation","observationRequested","pendingControl","pendingControl","resumeAfterPause","resumeAfterPause");
 private static Object controller(Object target) throws ReflectiveOperationException {
  Field field=ClickOverlayService.class.getDeclaredField("execution");field.setAccessible(true);return field.get(target);
 }
 public static Object get(Object target,String name) {
  try {
   if(target instanceof ClickOverlayService && READERS.containsKey(name)) {
    Object owner=controller(target);Method method=owner.getClass().getDeclaredMethod(READERS.get(name));method.setAccessible(true);return method.invoke(owner);
   }
   Object owner=target instanceof ClickOverlayService && CURSOR.contains(name)?controller(target):target;
   Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
  }catch(ReflectiveOperationException failed){throw new AssertionError(failed);}
 }
 public static void set(Object target,String name,Object value) {
  try {
   Object owner=target instanceof ClickOverlayService && CURSOR.contains(name)?controller(target):target;
   Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);
  }catch(ReflectiveOperationException failed){throw new AssertionError(failed);}
 }
}
