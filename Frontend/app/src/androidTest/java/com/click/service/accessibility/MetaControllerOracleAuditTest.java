package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.app.UiAutomation;
import android.content.Context;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Run ONLY after the frozen performance cohort. This audits the unchanged goal
 * predicates with fixed public navigation taps, not a model/controller policy.
 * Reflection deliberately avoids modifying the already-frozen benchmark source.
 */
@RunWith(AndroidJUnit4.class)
public class MetaControllerOracleAuditTest {
    private static final Map<String,List<String>> EXPECTED_TARGETS=new LinkedHashMap<>();
    static {
        EXPECTED_TARGETS.put("settings_display",Arrays.asList("디스플레이"));
        EXPECTED_TARGETS.put("settings_sound",Arrays.asList("소리 및 진동"));
        EXPECTED_TARGETS.put("settings_notifications",Arrays.asList("알림"));
        EXPECTED_TARGETS.put("clock_world",Arrays.asList("세계시각"));
        EXPECTED_TARGETS.put("clock_stopwatch",Arrays.asList("스톱워치"));
        EXPECTED_TARGETS.put("clock_timer",Arrays.asList("타이머"));
        EXPECTED_TARGETS.put("chrome_menu",Arrays.asList("Chrome 맞춤설정 및 제어"));
        EXPECTED_TARGETS.put("chrome_new_tab",Arrays.asList("새 탭"));
        EXPECTED_TARGETS.put("chrome_settings",Arrays.asList("설정"));
        EXPECTED_TARGETS.put("youtube_shorts",Arrays.asList("Shorts"));
        EXPECTED_TARGETS.put("youtube_subscriptions",Arrays.asList("구독"));
        EXPECTED_TARGETS.put("youtube_search",Arrays.asList("검색","YouTube 검색"));
    }

    @Test public void auditFrozenGoalPredicatesWithPositiveControls()throws Exception {
        Assume.assumeTrue("Run after main cohort with -e metaOracleAudit true",
            Boolean.parseBoolean(InstrumentationRegistry.getArguments().getString("metaOracleAudit","false")));
        Class<?> benchClass=MetaControllerDeviceExperimentTest.class;
        Class<?> taskClass=Class.forName(benchClass.getName()+"$Task");
        Class<?> sessionClass=Class.forName(benchClass.getName()+"$DeviceSession");
        Class<?> traceClass=Class.forName(benchClass.getName()+"$SetupTrace");
        Object bench=new MetaControllerDeviceExperimentTest();
        Method catalogue=method(benchClass,"catalogue");
        Method prepare=method(benchClass,"prepareStart",sessionClass,taskClass,traceClass);
        Method click=method(benchClass,"clickPublic",UiAutomation.class,String.class,String.class);
        Method goalCount=method(benchClass,"goalCount",UiAutomation.class,taskClass);
        Method controls=method(benchClass,"probeControls",UiAutomation.class,String.class);
        Method launch=method(benchClass,"launch",String.class);
        Method goalRoot=method(benchClass,"goalRoot",UiAutomation.class);
        Method service=method(benchClass,"service");
        Method traceJson=method(traceClass,"json");
        Constructor<?> sessionConstructor=sessionClass.getDeclaredConstructor(benchClass);sessionConstructor.setAccessible(true);
        Constructor<?> traceConstructor=traceClass.getDeclaredConstructor();traceConstructor.setAccessible(true);
        List<?> tasks=(List<?>)catalogue.invoke(null);
        Assert.assertEquals("Audit the complete frozen catalogue",12,tasks.size());
        for(Object task:tasks)Assert.assertTrue("Unexpected frozen task",EXPECTED_TARGETS.containsKey(field(task,"id").toString()));
        JSONObject report=new JSONObject().put("schema_version","meta-controller-oracle-positive-controls.v1")
            .put("source","real_device_postrun_oracle_positive_control").put("performance_cohort",false)
            .put("oracle_predicates_modified",false).put("model_calls",0).put("raw_screen_saved",false)
            .put("target_contract_version","native-navigation-public-target-audit.v1");
        JSONArray checks=new JSONArray();Object session=null;
        try{
            session=sessionConstructor.newInstance(bench);
            UiAutomation automation=(UiAutomation)field(session,"automation");
            for(Object task:tasks){
                String id=field(task,"id").toString(),pkg=field(task,"pkg").toString();
                JSONObject check=new JSONObject().put("task_id",id).put("app_id",pkg)
                    .put("source","real_device_postrun_oracle_positive_control").put("model_calls",0)
                    .put("expected_tap_accepted",false).put("matched_positive",false).put("setup_status","not_ready")
                    .put("validation_status","not_run");
                Object trace=traceConstructor.newInstance();int known=0,unknown=0,ambiguous=0;
                boolean accepted=false,matched=false;long started=SystemClock.elapsedRealtime(),goalAt=-1;
                String phase="fixture_reset";
                try{
                    if(id.startsWith("youtube_")){
                        JSONObject reset=resetYouTubeFixture(bench,launch,controls,click,goalRoot,service,automation,pkg,true);
                        check.put("fixture_reset",reset).put("fixture_reset_back_count",reset.getInt("back_count"))
                            .put("fixture_reset_home_found",reset.getBoolean("home_found"));
                        if(!reset.getBoolean("ready"))throw new AuditFixtureFailure("youtube_home_reset_unavailable");
                    }
                    phase="frozen_setup";
                    trace=traceConstructor.newInstance(); // The unchanged 10s setup bound excludes this postrun fixture repair.
                    prepare.invoke(bench,session,task,trace);
                    check.put("setup_status","ready").put("initial_goal_matches",0);
                    phase="positive_control";
                    for(String target:EXPECTED_TARGETS.get(id)){
                        if((Boolean)click.invoke(null,automation,pkg,target)){
                            accepted=true;check.put("expected_public_target",target);break;
                        }
                    }
                    check.put("expected_tap_accepted",accepted);
                    if(accepted){
                        long actionAt=SystemClock.elapsedRealtime(),deadline=actionAt+5000,stableSince=-1;
                        while(SystemClock.elapsedRealtime()<deadline){
                            int count=(Integer)goalCount.invoke(null,automation,task);long now=SystemClock.elapsedRealtime();
                            if(count<0)unknown++;else known++;
                            if(count>1)ambiguous++;
                            if(count==1){
                                if(stableSince<0)stableSince=now;
                                if(now-stableSince>=700){matched=true;goalAt=stableSince-actionAt;break;}
                            }else stableSince=-1;
                            SystemClock.sleep(100);
                        }
                        int lastCount=(Integer)goalCount.invoke(null,automation,task);
                        check.put("final_goal_match_count",lastCount<0?JSONObject.NULL:lastCount)
                            .put("oracle_observable",lastCount>=0);
                    }
                    if(!matched)check.put("post_public_controls",controls.invoke(null,automation,pkg));
                    check.put("validation_status",matched?"positive_confirmed":accepted?"unmatched_positive_requires_review":"expected_tap_unavailable");
                }catch(Exception failed){check.put("failure_reason",safeFailure(failed)).put("failure_phase",phase)
                    .put("validation_status","positive_control".equals(phase)?"validation_unavailable":"setup_unavailable");}
                finally{
                    check.put("expected_tap_accepted",accepted).put("matched_positive",matched)
                        .put("goal_observed_ms",goalAt<0?JSONObject.NULL:goalAt)
                        .put("elapsed_ms",SystemClock.elapsedRealtime()-started)
                        .put("root_observability_counts",new JSONObject().put("known",known).put("unknown",unknown).put("ambiguous",ambiguous));
                    try{check.put("setup_trace",traceJson.invoke(trace));}
                    catch(Exception failed){check.put("trace_failure",safeFailure(failed));}
                    try{
                        if("chrome_new_tab".equals(id)&&accepted&&matched)homeAction(service,AccessibilityService.GLOBAL_ACTION_BACK);
                        if("youtube_search".equals(id))check.put("cleanup_fixture_reset",
                            resetYouTubeFixture(bench,launch,controls,click,goalRoot,service,automation,pkg,false));
                    }catch(Exception failed){check.put("cleanup_failure",safeFailure(failed));}
                    finally{
                        try{homeAction(service,AccessibilityService.GLOBAL_ACTION_HOME);SystemClock.sleep(200);}
                        catch(Exception failed){check.put("home_cleanup_failure",safeFailure(failed));}
                    }
                }
                checks.put(check);System.out.println("META_ORACLE_POSITIVE "+check);
            }
        }catch(Exception failed){report.put("audit_failure",safeFailure(failed));}
        finally{
            if(session!=null){try{((AutoCloseable)session).close();}catch(Exception failed){report.put("cleanup_failure",safeFailure(failed));}}
            int accepted=0,matched=0,ready=0;
            for(int i=0;i<checks.length();i++){
                JSONObject check=checks.getJSONObject(i);
                if("ready".equals(check.optString("setup_status")))ready++;
                if(check.optBoolean("expected_tap_accepted"))accepted++;
                if(check.optBoolean("matched_positive"))matched++;
            }
            report.put("checks",checks).put("scheduled",12).put("checked",checks.length())
                .put("setup_ready",ready).put("expected_taps_accepted",accepted).put("matched_positives",matched)
                .put("interpretation","Postrun validity audit only. YouTube may receive a bounded manual Home reset here that was absent from the frozen performance harness. Setup-unavailable cases do not invalidate a goal predicate. No audit result replaces performance scores.");
            write(report);
        }
        Assert.assertEquals("All frozen tasks must have an audit record",12,checks.length());
    }

    private static Method method(Class<?> owner,String name,Class<?>...parameters)throws Exception {
        Method method=owner.getDeclaredMethod(name,parameters);method.setAccessible(true);return method;
    }
    private static Object field(Object object,String name)throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);
    }
    private static String safeFailure(Throwable failed){
        while(failed instanceof InvocationTargetException&&failed.getCause()!=null)failed=failed.getCause();
        String message=failed.getMessage();
        return (failed.getClass().getSimpleName().equals("SetupFailure")||failed instanceof AuditFixtureFailure)&&message!=null&&message.matches("[a-z0-9_]+")
            ?message:failed.getClass().getSimpleName();
    }
    /** Postrun fixture repair only; never modifies the frozen performance setup or goal predicate. */
    private static JSONObject resetYouTubeFixture(Object bench,Method launch,Method controls,Method click,Method goalRoot,
            Method service,UiAutomation automation,String pkg,boolean launchFirst)throws Exception {
        if(launchFirst)launch.invoke(bench,pkg);
        int backs=0,unknown=0;boolean found=false,accepted=false;
        long deadline=SystemClock.elapsedRealtime()+3500;
        while(SystemClock.elapsedRealtime()<deadline){
            AccessibilityNodeInfo root=(AccessibilityNodeInfo)goalRoot.invoke(null,automation);
            if(root==null){unknown++;SystemClock.sleep(150);continue;}
            boolean sameApp;
            try{sameApp=pkg.equals(String.valueOf(root.getPackageName()));}finally{root.recycle();}
            if(!sameApp)break; // Never navigate Back in another app or a system modal.
            JSONArray publicControls=(JSONArray)controls.invoke(null,automation,pkg);
            for(int i=0;i<publicControls.length();i++)if("홈".equals(publicControls.getJSONObject(i).optString("label"))){found=true;break;}
            if(found){accepted=(Boolean)click.invoke(null,automation,pkg,"홈");SystemClock.sleep(350);break;}
            if(backs>=2)break;
            homeAction(service,AccessibilityService.GLOBAL_ACTION_BACK);backs++;SystemClock.sleep(450);
        }
        return new JSONObject().put("source","postrun_only_manual_fixture_navigation")
            .put("back_count",backs).put("home_found",found).put("home_click_accepted",accepted)
            .put("root_unknown_reads",unknown).put("ready",found&&accepted);
    }
    private static final class AuditFixtureFailure extends Exception {AuditFixtureFailure(String message){super(message);}}
    private static void homeAction(Method service,int action)throws Exception {
        AccessibilityService current=(AccessibilityService)service.invoke(null);if(current!=null)current.performGlobalAction(action);
    }
    private static void write(JSONObject report)throws IOException {
        Context app=InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory=new File(app.getFilesDir(),"meta-controller");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create audit output directory");
        try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(directory,"oracle-positive-controls.json")),StandardCharsets.UTF_8)){
            out.write(report.toString()+"\n");
        }
    }
}
