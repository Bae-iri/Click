package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.local.AuthTokenStore;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.overlay.AppTaskCompletion;
import com.click.service.overlay.AppTaskController;
import com.click.service.overlay.AppTaskMemory;
import com.click.service.overlay.AppTaskMetaController;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Diagnostic bridge only; never a replacement for a frozen navigation performance episode. */
@RunWith(AndroidJUnit4.class)
public final class MetaControllerCompletionProbeTest {
    private static final String PKG="com.android.settings",SCHEMA="click.native-completion-probe.v1";
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private final Bundle args=InstrumentationRegistry.getArguments();
    private final String probeRef=ref(UUID.randomUUID().toString());
    private final Set<String> copiedCompletionEvents=new LinkedHashSet<>();
    private volatile int traceErrors;

    private final class Fixture {
        final String goal,title;final Set<String> titleResources=new LinkedHashSet<>();final String catalogueRef;
        Fixture()throws Exception {
            JSONObject catalogue;String encoded=args.getString("catalogue64");
            if(encoded!=null)catalogue=new JSONObject(new String(Base64.decode(encoded,Base64.DEFAULT),StandardCharsets.UTF_8));
            else catalogue=readJson(new File(app.getFilesDir(),"settings-meta/manifest.json")).getJSONObject("catalogue");
            assertEquals(PKG,catalogue.getString("app_id"));JSONObject display=null;JSONArray tasks=catalogue.getJSONArray("tasks");
            for(int i=0;i<tasks.length();i++)if("settings_display".equals(tasks.getJSONObject(i).optString("task_id")))display=tasks.getJSONObject(i);
            assertNotNull("Frozen Display definition is required",display);assertEquals(Settings.ACTION_DISPLAY_SETTINGS,display.getString("intent"));
            goal=display.getString("goal");title=display.getString("oracle_title");JSONArray resources=display.getJSONArray("oracle_title_resources");
            for(int i=0;i<resources.length();i++)titleResources.add(resources.getString(i));
            catalogueRef=ref(catalogue.toString());
        }
    }

    @Test public void probeNativeCompletion()throws Exception {
        Assume.assumeTrue("Opt in with completionProbe",flag("completionProbe"));isolated();Fixture fixture=new Fixture();
        JSONObject report=report("model_free",fixture).put("model_calls",0);JSONArray cases=new JSONArray();
        try(Session session=new Session()){
            try{
                open(Settings.ACTION_DISPLAY_SETTINGS);ScreenSnapshot display=stable(PKG,"public");
                JSONObject prototype=prototype(fixture,display),projection=projectionChecks(fixture,prototype,display,session.automation);
                report.put("prototype",prototype).put("projection_checks",projection);
                for(String arm:Arrays.asList("A","B")){
                    open(Settings.ACTION_DISPLAY_SETTINGS);ScreenSnapshot positive=stable(PKG,"public");AppTaskMemory memory=new AppTaskMemory(fixture.goal);
                    boolean offered=memory.offerCompletion(prototype,AppTaskObservation.capture(positive,AppTaskCompletion.labels(prototype)),positive);
                    trace("model_free","display",arm,"manual_offer_completion_return",new JSONObject().put("task_ref",ref(memory.id()))
                        .put("candidate_ref",candidateRef(prototype)).put("accepted",offered));
                    JSONObject result=runLocal(fixture,memory,arm,"display",true).put("manual_offer_accepted",offered);cases.put(result);
                }
                home();ScreenSnapshot menu=stable(PKG,"public");
                AppTaskCompletion.Verdict menuVerdict=AppTaskCompletion.checkForGoal(fixture.goal,prototype,AppTaskObservation.capture(menu,AppTaskCompletion.labels(prototype)),menu);
                report.put("home_menu_verdict",menuVerdict.name());
                for(String arm:Arrays.asList("A","B")){AppTaskMemory memory=new AppTaskMemory(fixture.goal);memory.completion(prototype);
                    cases.put(runLocal(fixture,memory,arm,"home_menu",false));}
                String privatePackage=instrumentation.getContext().getPackageName();
                app.startActivity(new Intent().setComponent(new ComponentName(instrumentation.getContext(),MetaControllerPrivateGuardFixtureActivity.class))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
                ScreenSnapshot privateScreen=stable(privatePackage,"protected");
                AppTaskCompletion.Verdict privateVerdict=AppTaskCompletion.checkForGoal(fixture.goal,prototype,AppTaskObservation.capture(privateScreen),privateScreen);
                report.put("private_screen_verdict",privateVerdict.name());
                for(String arm:Arrays.asList("A","B")){AppTaskMemory memory=new AppTaskMemory(fixture.goal);memory.completion(prototype);
                    cases.put(runLocal(fixture,memory,arm,"private_screen",false));}
                boolean passed=projection.optBoolean("passed")&&menuVerdict!=AppTaskCompletion.Verdict.MATCHED&&privateVerdict==AppTaskCompletion.Verdict.PROTECTED&&cases.length()==6;
                for(int i=0;i<cases.length();i++)passed&=cases.getJSONObject(i).optBoolean("passed");report.put("passed",passed);
            }finally{home();}
        }catch(AssertionError|Exception failed){report.put("passed",false).put("failure",failed.getClass().getSimpleName());}
        report.put("cases",cases).put("trace_errors",traceErrors);if(traceErrors>0)report.put("passed",false);
        write("report.json",report.toString(2),false);System.out.println("COMPLETION_PROBE "+report);
    }

    @Test public void probeLiveCompletionBridge()throws Exception {
        Assume.assumeTrue("Live bridge needs both completionProbe and completionProbeLive",flag("completionProbe")&&flag("completionProbeLive"));
        isolated();assertEquals("Live probe must use the isolated loopback facade","http://127.0.0.1:8768",com.click.BuildConfig.CLICK_API_BASE_URL.replaceAll("/$",""));
        Fixture fixture=new Fixture();JSONObject report=report("bounded_live_bridge",fixture)
            .put("bridge","one_gateway_response_then_same_memory_offer_then_local_controller").put("full_end_to_end_trajectory",false)
            .put("controlled_result_only_state",true).put("state_fixture_reason","already_arrived_display_one_verified_subgoal_no_action_history")
            .put("gateway_request_limit",2);JSONArray arms=new JSONArray();int sends=0;
        AuthTokenStore tokens=new AuthTokenStore(app);String token=args.getString("testToken","local-meta-settings-experiment"),old=tokens.accessToken();
        assertTrue("Do not overwrite a real login",old==null||old.equals(token));tokens.save(new JSONObject().put("user_id","local-meta-settings-experiment")
            .put("access_token",token).put("refresh_token","local-meta-settings-unused").put("expires_in",3600).put("refresh_expires_in",3600));
        try(Session session=new Session()){
            try{
                for(String arm:Arrays.asList("A","B")){
                    open(Settings.ACTION_DISPLAY_SETTINGS);ScreenSnapshot display=stable(PKG,"public");
                    JSONObject stored=new AppTaskMemory(fixture.goal).stored();
                    stored.put("subgoals",new JSONArray().put(new JSONObject().put("id","g0").put("title",fixture.goal).put("status","verified")));
                    AppTaskMemory memory=new AppTaskMemory(stored);
                    JSONObject screen=AppTaskObservation.capture(display),request=new JSONObject().put("protocol_version","app-task.v2").put("request_id",UUID.randomUUID().toString())
                        .put("goal",fixture.goal).put("screen",screen).put("state",memory.state())
                        .put("apps",new JSONArray().put(new JSONObject().put("package",PKG).put("label","Settings")));
                    JSONObject row=new JSONObject().put("arm",arm).put("task_ref",ref(memory.id())).put("request_ref",ref(request.getString("request_id")))
                        .put("controlled_result_only_state",true).put("state_fixture_reason","already_arrived_display_one_verified_subgoal_no_action_history")
                        .put("gateway_send_attempted",true).put("manual_offer_accepted",false).put("passed",false);
                    AtomicReference<JSONObject> response=new AtomicReference<>();AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);
                    AppGuideGateway gateway=new AppGuideGateway(app);long at=SystemClock.elapsedRealtime();sends++;
                    trace("bounded_live_bridge","display",arm,"gateway_send",new JSONObject().put("task_ref",ref(memory.id())).put("request_ref",ref(request.getString("request_id")))
                        .put("observation_ref",ref(screen.getString("observation_id"))).put("controlled_result_only_state",true).put("subgoal_count",1).put("action_history_count",memory.actions()));
                    try{
                        gateway.send(request,new AppGuideGateway.Callback(){public void success(JSONObject value){response.set(value);done.countDown();}
                            public void failure(Throwable error){failure.set(error);done.countDown();}});
                        boolean returned=done.await(16,TimeUnit.SECONDS);row.put("gateway_callback_received",returned).put("gateway_wait_ms",SystemClock.elapsedRealtime()-at);
                        JSONObject value=response.get();if(value!=null){JSONObject candidate=value.optJSONObject("completion"),diagnostic=value.optJSONObject("diagnostic");
                            row.put("candidate_received",candidate!=null).put("response_status",finite(value.optString("status"))).put("candidate_ref",nullable(candidateRef(candidate)));
                            if(diagnostic!=null)row.put("diagnostic_called",diagnostic.opt("called")).put("diagnostic_planning_mode",finite(diagnostic.optString("planning_mode")));
                            trace("bounded_live_bridge","display",arm,"gateway_callback_received",new JSONObject().put("task_ref",ref(memory.id()))
                                .put("request_ref",ref(request.getString("request_id"))).put("candidate_received",candidate!=null).put("candidate_ref",nullable(candidateRef(candidate))));
                            ScreenSnapshot current=ClickAccessibilityService.freshSnapshot();assertNotNull(current);
                            boolean offered=candidate!=null&&memory.offerCompletion(candidate,AppTaskObservation.capture(current,AppTaskCompletion.labels(candidate)),current);
                            row.put("manual_offer_accepted",offered);trace("bounded_live_bridge","display",arm,"manual_offer_completion_return",new JSONObject()
                                .put("task_ref",ref(memory.id())).put("request_ref",ref(request.getString("request_id"))).put("candidate_ref",nullable(candidateRef(candidate))).put("accepted",offered));
                            if(offered){JSONObject local=runLocal(fixture,memory,arm,"live_display",true);row.put("local_controller",local).put("passed",local.optBoolean("passed"));}
                        }else row.put("gateway_failure",failure.get()==null?"callback_timeout":failure.get().getClass().getSimpleName());
                    }finally{gateway.close();}
                    row.put("request_receipts",requestReceipts(ref(memory.id()),1));collectProductionCompletionEvents(ref(memory.id()),"live_display",arm);arms.put(row);
                }
                boolean passed=arms.length()==2;for(int i=0;i<arms.length();i++)passed&=arms.getJSONObject(i).optBoolean("passed");report.put("passed",passed);
            }finally{home();}
        }catch(AssertionError|Exception failed){report.put("passed",false).put("failure",failed.getClass().getSimpleName());}
        report.put("arms",arms).put("gateway_send_attempts",sends).put("trace_errors",traceErrors);if(traceErrors>0)report.put("passed",false);
        write("live-report.json",report.toString(2),false);System.out.println("COMPLETION_LIVE_PROBE "+report);
    }

    private JSONObject prototype(Fixture fixture,ScreenSnapshot screen)throws Exception {
        JSONObject observed=AppTaskObservation.capture(screen),heading=null;JSONArray nodes=observed.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(!fixture.title.equals(n.optString("label"))||!"text".equals(n.optString("role"))
                ||!fixture.titleResources.contains(n.optString("resource")))continue;
            AccessibilityNodeSnapshot nativeNode=nativeNode(screen,n.optString("id"));
            if(nativeNode!=null&&nativeNode.heading&&nativeNode.visibleToUser&&!nativeNode.clickable&&!nativeNode.scrollable&&!nativeNode.checkable){assertNull("Unique actual native Display heading required",heading);heading=n;}}
        assertNotNull("Default observation must already contain the actual native heading",heading);
        return new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("package",PKG).put("label",heading.getString("label"))
            .put("resource",heading.getString("resource")).put("role","text")));
    }
    private JSONObject projectionChecks(Fixture fixture,JSONObject prototype,ScreenSnapshot screen,UiAutomation automation)throws Exception {
        JSONObject plain=AppTaskObservation.capture(screen),projected=AppTaskObservation.capture(screen,AppTaskCompletion.labels(prototype));
        JSONObject selector=prototype.getJSONArray("checks").getJSONObject(0);int defaultTitles=0,resultTitles=0,passiveLists=0;
        for(int i=0;i<plain.getJSONArray("nodes").length();i++){JSONObject n=plain.getJSONArray("nodes").getJSONObject(i);
            if(matches(selector,n))defaultTitles++;if(fixture.title.equals(n.optString("label"))&&"list".equals(n.optString("role"))&&n.optBoolean("scrollable")&&!n.optBoolean("clickable"))passiveLists++;}
        for(int i=0;i<projected.getJSONArray("nodes").length();i++)if(matches(selector,projected.getJSONArray("nodes").getJSONObject(i)))resultTitles++;
        boolean nativeHeading=nativeHeadingPresent(automation,fixture.title,selector.getString("resource"),screen.activeWindowId);
        AppTaskCompletion.Verdict before=AppTaskCompletion.checkForGoal(fixture.goal,prototype,plain,screen),after=AppTaskCompletion.checkForGoal(fixture.goal,prototype,projected,screen);
        JSONObject result=new JSONObject().put("default_native_heading_unique",defaultTitles==1).put("result_native_heading_unique",resultTitles==1)
            .put("independent_native_heading",nativeHeading).put("passive_scroll_same_title_present",passiveLists>0)
            .put("default_fingerprint_ref",ref(plain.optString("fingerprint"))).put("result_fingerprint_ref",ref(projected.optString("fingerprint")))
            .put("default_verdict",before.name()).put("result_projection_verdict",after.name());
        return result.put("passed",defaultTitles==1&&resultTitles==1&&nativeHeading&&before==AppTaskCompletion.Verdict.MATCHED&&after==AppTaskCompletion.Verdict.MATCHED);
    }
    private JSONObject runLocal(Fixture fixture,AppTaskMemory memory,String arm,String scenario,boolean positive)throws Exception {
        LocalDriver driver=new LocalDriver(fixture,memory,arm,scenario);long started=SystemClock.elapsedRealtime();
        try{instrumentation.runOnMainSync(driver::start);long end=started+(positive?6500:1000);
            while(SystemClock.elapsedRealtime()<end){if(positive&&driver.verified)break;SystemClock.sleep(50);}
            instrumentation.runOnMainSync(driver::stop);JSONObject result=driver.result().put("elapsed_ms",SystemClock.elapsedRealtime()-started).put("expected_positive",positive);
            boolean passed=driver.failures==0&&driver.executeAttempts==0&&driver.turns()==0&&(positive?driver.verified&&driver.memory.completionSupported()
                &&driver.firstObservation!=null&&driver.secondObservation!=null&&!driver.firstObservation.equals(driver.secondObservation)&&driver.secondCaptured-driver.firstCaptured>=350:!driver.everVerified);
            result.put("passed",passed);collectProductionCompletionEvents(ref(memory.id()),scenario,arm);return result;
        }finally{instrumentation.runOnMainSync(driver::stop);}
    }
    private final class LocalDriver implements AppTaskController.Listener {
        final Fixture fixture;final AppTaskMemory memory;final String arm,scenario;final Map<String,Frame> frames=new LinkedHashMap<>();
        AppTaskController controller;volatile boolean observing=true,verified,everVerified;volatile int failures,executeAttempts;
        String firstObservation,secondObservation;long firstCaptured,secondCaptured;String status="none";
        LocalDriver(Fixture fixture,AppTaskMemory memory,String arm,String scenario){this.fixture=fixture;this.memory=memory;this.arm=arm;this.scenario=scenario;}
        void start(){controller=new AppTaskController(new AppGuideGateway(app),memory,null,this,false,false,true,
            "A".equals(arm)?AppTaskMetaController.Arm.CURRENT:AppTaskMetaController.Arm.META);controller.targetApp(PKG,"Settings");controller.refresh();}
        void stop(){observing=false;if(controller!=null)controller.close();}
        public boolean canObserve(){return observing;}public void busy(boolean value){}public boolean inputMatches(String id,String hash){return false;}
        public boolean execute(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String input,String selected){executeAttempts++;return false;}
        public ScreenSnapshot fresh(){ScreenSnapshot screen=ClickAccessibilityService.freshSnapshot();if(screen==null)return null;
            try{JSONObject observation=AppTaskObservation.capture(screen,AppTaskCompletion.labels(memory.completion()));long captured=Instant.parse(screen.capturedAt).toEpochMilli();
                frames.put(screen.observationId,new Frame(captured));while(frames.size()>64)frames.remove(frames.keySet().iterator().next());
                trace("local_controller",scenario,arm,"native_frame_read",new JSONObject().put("task_ref",ref(memory.id())).put("observation_ref",ref(screen.observationId))
                    .put("window_ref",ref(screen.activeWindowId+"|"+screen.windowContext.toJson())).put("observation_status",finite(observation.optString("status")))
                    .put("frame_age_ms",Math.max(0,System.currentTimeMillis()-captured)).put("candidate_ref",nullable(candidateRef(memory.completion())))
                    .put("verdict",AppTaskCompletion.checkForGoal(fixture.goal,memory.completion(),observation,screen).name()));
            }catch(Exception failed){failures++;}return screen;}
        public void present(JSONObject response,ScreenSnapshot baseline){if(!observing)return;status=finite(response.optString("status"));JSONObject assessment=response.optJSONObject("result_assessment");
            verified=assessment!=null&&"verified".equals(assessment.optString("status"));everVerified|=verified;
            try{if("checking_result".equals(status)&&firstObservation==null){firstObservation=(String)field(controller,"resultObservation");firstCaptured=(Long)field(controller,"resultCapturedAt");}
                if(verified){secondObservation=assessment.optString("observation_id");Frame frame=frames.get(secondObservation);secondCaptured=frame==null?0:frame.captured;}
                trace("local_controller",scenario,arm,"controller_present",new JSONObject().put("task_ref",ref(memory.id())).put("status",status)
                    .put("assessment_status",assessment==null?"absent":finite(assessment.optString("status"))).put("candidate_ref",nullable(candidateRef(memory.completion())))
                    .put("first_observation_ref",nullable(ref(firstObservation))).put("second_observation_ref",nullable(ref(secondObservation)))
                    .put("evidence_version",memory.evidenceVersion()).put("evidence_supported",memory.completionSupported()));
            }catch(Exception failed){failures++;}}
        public void failure(Throwable error){failures++;trace("local_controller",scenario,arm,"controller_failure",safe("failure_type",error.getClass().getSimpleName()));}
        int turns(){try{return controller==null?0:(Integer)field(controller,"turns");}catch(Exception error){return -1;}}
        JSONObject result()throws Exception{return new JSONObject().put("arm",arm).put("scenario",scenario).put("task_ref",ref(memory.id()))
            .put("candidate_ref",nullable(candidateRef(memory.completion()))).put("runtime_verified_offer",verified).put("ever_runtime_verified_offer",everVerified)
            .put("first_observation_ref",nullable(ref(firstObservation))).put("second_observation_ref",nullable(ref(secondObservation)))
            .put("two_distinct_fresh_frames",firstObservation!=null&&secondObservation!=null&&!firstObservation.equals(secondObservation))
            .put("fresh_frame_delta_ms",firstObservation==null||secondObservation==null?JSONObject.NULL:secondCaptured-firstCaptured)
            .put("evidence_version",memory.evidenceVersion()).put("evidence_status",finite(memory.evidenceState().optString("status")))
            .put("final_status",status).put("controller_gateway_turns",turns()).put("execute_attempts",executeAttempts).put("listener_failures",failures);}
    }
    private static final class Frame {final long captured;Frame(long captured){this.captured=captured;}}

    private final class Session implements AutoCloseable {
        final UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        final String original;final int enabled,flags,eventTypes;
        Session()throws Exception {original=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            enabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);AccessibilityServiceInfo info=automation.getServiceInfo();flags=info.flags;eventTypes=info.eventTypes;
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");try{
                info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;info.eventTypes=AccessibilityEvent.TYPES_ALL_MASK;automation.setServiceInfo(info);
                Set<String> services=new LinkedHashSet<>();if(original!=null)for(String service:original.split(":"))if(!service.isEmpty())services.add(service);
                String own=new ComponentName(app,ClickAccessibilityService.class).flattenToString();services.remove(own);Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
                SystemClock.sleep(350);services.add(own);Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
                Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);long end=SystemClock.elapsedRealtime()+10000;
                while(service()==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);assertNotNull("Actual accessibility service must connect",service());
            }catch(Exception|AssertionError failed){close();throw failed;}}
        public void close(){try{Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,original==null?"":original);
            Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,enabled);AccessibilityServiceInfo info=automation.getServiceInfo();info.flags=flags;info.eventTypes=eventTypes;automation.setServiceInfo(info);
        }finally{automation.dropShellPermissionIdentity();}}
    }
    private ScreenSnapshot stable(String pkg,String expected)throws Exception {long end=SystemClock.elapsedRealtime()+10000,since=-1;String last=null;
        while(SystemClock.elapsedRealtime()<end){ScreenSnapshot screen=ClickAccessibilityService.freshSnapshot();long now=SystemClock.elapsedRealtime();
            if(screen!=null&&pkg.equals(screen.packageName)){JSONObject observed=AppTaskObservation.capture(screen);String key=observed.optString("fingerprint")+"|"+screen.activeWindowId;
                if(expected.equals(observed.optString("status"))){if(key.equals(last)){if(since>=0&&now-since>=700)return screen;}else{last=key;since=now;}}
                else{last=null;since=-1;}}else{last=null;since=-1;}SystemClock.sleep(100);
        }throw new IllegalStateException("native_fixture_unavailable");}
    private void home()throws Exception {shell("am force-stop com.android.settings");open(Settings.ACTION_SETTINGS);stable(PKG,"public");}
    private void open(String intent){app.startActivity(new Intent(intent).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));}
    private static AccessibilityNodeSnapshot nativeNode(ScreenSnapshot screen,String id){for(AccessibilityNodeSnapshot n:screen.nodes)if(id.equals(n.nodeId))return n;return null;}
    private static boolean matches(JSONObject selector,JSONObject node){for(String key:Arrays.asList("label","resource","role"))if(!selector.optString(key).equals(node.optString(key)))return false;return true;}
    private static boolean nativeHeadingPresent(UiAutomation automation,String label,String resource,Integer window){AccessibilityNodeInfo root=automation.getRootInActiveWindow();if(root==null)return false;
        try{return window!=null&&root.getWindowId()==window&&headingCount(root,label,resource)==1;}finally{root.recycle();}}
    private static int headingCount(AccessibilityNodeInfo node,String label,String resource){int total=0;
        String description=node.getContentDescription()==null?"":AppTaskObservation.label(node.getContentDescription().toString());
        String text=description.isEmpty()?AppTaskObservation.label(node.getText()==null?"":node.getText().toString()):description;
        if(node.isVisibleToUser()&&PKG.contentEquals(String.valueOf(node.getPackageName()))&&node.isHeading()&&!node.isClickable()&&!node.isScrollable()
                &&resource.equals(node.getViewIdResourceName())&&label.equals(text))total++;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;try{total+=headingCount(child,label,resource);}finally{child.recycle();}}return total;}
    private JSONArray requestReceipts(String taskRef,int expected)throws Exception {JSONArray rows=new JSONArray();for(int attempt=0;attempt<30;attempt++){
        rows=journal("app-task-diagnostics",taskRef);if(rows.length()>=expected)break;SystemClock.sleep(100);}return rows;}
    private JSONArray journal(String directory,String taskRef)throws Exception {JSONArray rows=new JSONArray();for(String name:Arrays.asList("previous.jsonl","current.jsonl")){
        File file=new File(app.getNoBackupFilesDir(),directory+"/"+name);if(!file.isFile())continue;
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(file),StandardCharsets.UTF_8))){String line;
            while((line=reader.readLine())!=null){if(line.length()>8192)continue;try{JSONObject row=new JSONObject(line);if(taskRef.equals(row.optString("task_ref")))rows.put(row);}catch(Exception partial){}}}}
        return rows;}
    private void collectProductionCompletionEvents(String taskRef,String scenario,String arm)throws Exception {JSONArray rows=journal("app-task-completion-diagnostics",taskRef);
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String eventRef=ref(row.toString());if(copiedCompletionEvents.add(eventRef))
            trace("production_completion_journal",scenario,arm,"production_event",new JSONObject().put("event_ref",eventRef).put("event",row));}}
    private JSONObject report(String mode,Fixture fixture)throws Exception{return new JSONObject().put("schema_version",SCHEMA).put("probe_ref",probeRef).put("mode",mode)
        .put("diagnostic_only",true).put("performance_cohort",false).put("full_end_to_end_trajectory",false).put("raw_screen_saved",false)
        .put("catalogue_ref",fixture.catalogueRef).put("goal_ref",ref(fixture.goal)).put("sdk",Build.VERSION.SDK_INT).put("passed",false);}
    private synchronized void trace(String mode,String scenario,String arm,String stage,JSONObject extra){try{JSONObject row=new JSONObject().put("schema_version","click.native-completion-probe-trace.v1")
        .put("probe_ref",probeRef).put("mode",mode).put("scenario",scenario).put("arm",arm).put("stage",stage).put("raw_content_saved",false);
        for(java.util.Iterator<String> keys=extra.keys();keys.hasNext();){String key=keys.next();row.put(key,extra.get(key));}write("safe-trace.jsonl",row+"\n",true);
    }catch(Exception error){traceErrors++;}}
    private void isolated(){assertEquals("Use only the isolated test app","com.click.metaexperiment.settings",app.getPackageName());assertEquals("Fresh Android emulator required","ranchu",Build.HARDWARE);}
    private static ClickAccessibilityService service()throws Exception {Field field=ClickAccessibilityService.class.getDeclaredField("connectedService");field.setAccessible(true);return (ClickAccessibilityService)((WeakReference<?>)field.get(null)).get();}
    private static Object field(Object owner,String name)throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private boolean flag(String key){return Boolean.parseBoolean(args.getString(key,"false"));}
    private File directory(){File file=new File(app.getFilesDir(),"completion-probe");file.mkdirs();return file;}
    private void write(String name,String value,boolean append)throws Exception {try(FileOutputStream out=new FileOutputStream(new File(directory(),name),append)){out.write(value.getBytes(StandardCharsets.UTF_8));}}
    private void shell(String command)throws Exception {try(var fd=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command);InputStream in=new FileInputStream(fd.getFileDescriptor())){while(in.read()!=-1){}}}
    private static JSONObject readJson(File file)throws Exception {try(InputStream in=new FileInputStream(file)){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));}}
    private static String candidateRef(JSONObject value){return value==null?null:AppTaskCompletion.key(value).substring(0,24);}
    private static String ref(String value){return value==null||value.isEmpty()?null:AppTaskObservation.hash(value).substring(0,24);}
    private static Object nullable(Object value){return value==null?JSONObject.NULL:value;}
    private static String finite(String value){return value!=null&&value.matches("[a-zA-Z0-9_]{1,80}")?value:"unknown";}
    private static JSONObject safe(String key,String value){try{return new JSONObject().put(key,finite(value));}catch(Exception error){return new JSONObject();}}
}
