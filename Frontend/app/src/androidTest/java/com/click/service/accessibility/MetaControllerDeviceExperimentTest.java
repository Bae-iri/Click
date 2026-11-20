package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.*;
import android.net.Uri;
import android.graphics.Rect;
import android.os.*;
import android.provider.Browser;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.remote.AppGuideGateway;
import com.click.data.local.AuthTokenStore;
import com.click.data.snapshot.*;
import com.click.service.overlay.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opt-in native navigation experiment, not a human study or a model benchmark.
 * The frozen catalogue contains only public navigation controls. No text is entered,
 * no settings values are changed, and no raw accessibility tree is written to disk.
 */
@RunWith(AndroidJUnit4.class)
public class MetaControllerDeviceExperimentTest {
    private static final String VERSION="native-navigation.v4", SETTINGS="com.android.settings",
        CLOCK="com.sec.android.app.clockpackage", CHROME="com.android.chrome", YOUTUBE="com.google.android.youtube";
    private static final long DELAY_MS=5500;
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private final Bundle args=InstrumentationRegistry.getArguments();
    private boolean live(){return Boolean.parseBoolean(args.getString("liveMeta","false"));}

    private static final class Task {
        final String id,pkg,goal,startLabel,resultLabel,resultResource,role;
        final boolean selected,menu;
        Task(String id,String pkg,String goal,String startLabel,String resultLabel,String resultResource,String role,boolean selected,boolean menu){
            this.id=id;this.pkg=pkg;this.goal=goal;this.startLabel=startLabel;this.resultLabel=resultLabel;
            this.resultResource=resultResource;this.role=role;this.selected=selected;this.menu=menu;
        }
        JSONObject completion()throws JSONException {
            JSONObject check=new JSONObject().put("package",pkg);
            if(!resultLabel.isEmpty())check.put("label",resultLabel);
            if(!resultResource.isEmpty())check.put("resource",resultResource);
            if(!role.isEmpty())check.put("role",role);
            if(selected)check.put("selected",true);
            return new JSONObject().put("checks",new JSONArray().put(check));
        }
        JSONObject describe()throws JSONException {
            return new JSONObject().put("task_id",id).put("app_id",pkg).put("goal",goal)
                .put("start_label",startLabel).put("open_menu_in_setup",menu).put("completion",completion());
        }
    }
    // Unsupported controls remain recorded as setup failures; do not select tasks by A/B outcome.
    private static List<Task> catalogue(){return Arrays.asList(
        new Task("settings_display",SETTINGS,"디스플레이 설정 화면을 열어줘","","밝기","","",false,false),
        new Task("settings_sound",SETTINGS,"소리 및 진동 설정 화면을 열어줘","","소리 모드","","",false,false),
        new Task("settings_notifications",SETTINGS,"알림 설정 화면을 열어줘","","앱 알림","","",false,false),
        new Task("clock_world",CLOCK,"세계시각 탭으로 이동해줘","알람","세계시각","","tab",true,false),
        new Task("clock_stopwatch",CLOCK,"스톱워치 탭으로 이동해줘","알람","스톱워치","","tab",true,false),
        new Task("clock_timer",CLOCK,"타이머 탭으로 이동해줘","알람","타이머","","tab",true,false),
        new Task("chrome_menu",CHROME,"Chrome 메뉴를 열어줘","","새 시크릿 탭","","",false,false),
        new Task("chrome_new_tab",CHROME,"Chrome에서 새 탭을 열어줘","","","com.android.chrome:id/search_box_text","",false,true),
        new Task("chrome_settings",CHROME,"Chrome 설정 화면을 열어줘","","검색엔진","","",false,true),
        new Task("youtube_shorts",YOUTUBE,"YouTube에서 Shorts 탭으로 이동해줘","홈","Shorts","","button",true,false),
        new Task("youtube_subscriptions",YOUTUBE,"YouTube에서 구독 탭으로 이동해줘","홈","구독","","button",true,false),
        new Task("youtube_search",YOUTUBE,"YouTube 검색창을 열어줘","홈","","com.google.android.youtube:id/search_edit_text","text_field",false,false)
    );}
    private static final Set<String> PUBLIC_LABELS=new HashSet<>(Arrays.asList(
        "설정","디스플레이","소리 및 진동","알림","밝기","소리 모드","앱 알림","알람","세계시각","스톱워치","타이머",
        "시작","일시정지","초기화","Chrome 맞춤설정 및 제어","Chrome 설정","새 탭","새 시크릿 탭","검색엔진",
        "검색 또는 웹 주소 입력","홈","Shorts","구독","검색","음성으로 검색","YouTube 검색","검색어 입력"));
    // Observation labels are broader than the actions authorized by this navigation-only pilot.
    private static final Set<String> NAVIGATION_LABELS=new HashSet<>(Arrays.asList(
        "설정","디스플레이","소리 및 진동","알림","알람","세계시각","스톱워치","타이머",
        "Chrome 맞춤설정 및 제어","Chrome 설정","새 탭","홈","Shorts","구독","검색","YouTube 검색"));
    private static String publicLabel(CharSequence value){
        String label=AppTaskObservation.label(value==null?"":value.toString());
        if(PUBLIC_LABELS.contains(label))return label;
        for(String allowed:PUBLIC_LABELS)if(label.equals(allowed+", 탭")||label.equals(allowed+", 버튼"))return allowed;
        return "";
    }

    @Test public void probePublicControls()throws Exception {
        Assume.assumeTrue("Opt in with -e metaProbe true",Boolean.parseBoolean(args.getString("metaProbe","false")));
        JSONObject report=new JSONObject().put("schema_version",VERSION).put("source","real_device_public_control_probe")
            .put("raw_screen_saved",false).put("model_calls",0);
        JSONArray pages=new JSONArray(),tasks=new JSONArray();for(Task task:catalogue())tasks.put(task.describe());
        report.put("frozen_catalogue",tasks);
        try(DeviceSession session=new DeviceSession()){
            for(String pkg:Arrays.asList(SETTINGS,CLOCK,CHROME,YOUTUBE)){
                JSONObject page=new JSONObject().put("app_id",pkg);
                try{launch(pkg);page.put("controls",probeControls(session.automation,pkg)).put("setup_status","opened");}
                catch(Exception failed){page.put("setup_status","unavailable").put("reason",failed.getClass().getSimpleName());}
                pages.put(page);
            }
        }
        report.put("pages",pages);write("probe.json",report.toString(2),false);
        System.out.println("META_PROBE "+new JSONObject().put("file","files/meta-controller/probe.json").put("apps",pages.length()));
    }

    @Test public void runNavigationExperiment()throws Exception {
        Assume.assumeTrue("Opt in with -e metaExperiment true",Boolean.parseBoolean(args.getString("metaExperiment","false")));
        int repeat=Integer.parseInt(args.getString("repeat","1"));
        long budget=Long.parseLong(args.getString("budgetMs","15000"));
        Assert.assertTrue("repeat must be 1..10",repeat>=1&&repeat<=10);
        Assert.assertTrue("budgetMs must be 8000..180000",budget>=8000&&budget<=180000);
        Set<String> wanted=csv(args.getString("tasks",""));
        List<String> conditions=new ArrayList<>(csv(args.getString("conditions","normal,noeffect,delay")));
        Assert.assertFalse("Select at least one condition",conditions.isEmpty());
        for(String condition:conditions)Assert.assertTrue("Unknown condition",Arrays.asList("normal","noeffect","delay").contains(condition));
        List<AppTaskMetaController.Arm> requestedArms=new ArrayList<>();
        for(String arm:csv(args.getString("arms","A,B"))){
            Assert.assertTrue("arms must contain only A, B, C",Arrays.asList("A","B","C").contains(arm));
            requestedArms.add("A".equals(arm)?AppTaskMetaController.Arm.CURRENT:"B".equals(arm)?AppTaskMetaController.Arm.META:AppTaskMetaController.Arm.NO_EVENTS);
        }
        Assert.assertFalse("Select at least one arm",requestedArms.isEmpty());
        List<Task> tasks=new ArrayList<>();for(Task task:catalogue())if(wanted.isEmpty()||wanted.contains(task.id))tasks.add(task);
        Assert.assertFalse("No selected tasks",tasks.isEmpty());
        for(String name:wanted)Assert.assertTrue("Unknown task",tasks.stream().anyMatch(t->t.id.equals(name)));
        String runId=args.getString("runId",UUID.randomUUID().toString());
        if(live()){
            Assert.assertEquals("Live facade requires isolated test app","com.click.metaexperiment.uitestdriver",app.getPackageName());
            Assert.assertEquals("Live facade must be loopback only","http://127.0.0.1:8766",com.click.BuildConfig.CLICK_API_BASE_URL.replaceAll("/$",""));
            AuthTokenStore tokens=new AuthTokenStore(app);String previous=tokens.accessToken();
            Assert.assertTrue("Refuse to overwrite an existing real login",previous==null||"local-meta-experiment".equals(previous));
            tokens.save(new JSONObject().put("user_id","local-meta-experiment").put("access_token","local-meta-experiment")
                .put("refresh_token","local-meta-experiment-unused").put("expires_in",3600).put("refresh_expires_in",3600));
        }
        JSONArray manifest=new JSONArray(),armLabels=new JSONArray(),scheduledOrders=new JSONArray();
        for(Task task:tasks)manifest.put(task.describe());
        for(AppTaskMetaController.Arm arm:requestedArms)armLabels.put(armLabel(arm));
        int scheduledIndex=0;
        for(int r=0;r<repeat;r++)for(Task task:tasks)for(String condition:conditions){
            JSONArray order=new JSONArray();for(AppTaskMetaController.Arm arm:armOrder(requestedArms,scheduledIndex++ + r))order.put(armLabel(arm));
            scheduledOrders.put(new JSONObject().put("pair_id",runId+":"+task.id+":"+condition+":"+r).put("order",order));
        }
        write("manifest.json",new JSONObject().put("schema_version",VERSION).put("run_id",runId)
            .put("tasks",manifest).put("conditions",new JSONArray(conditions)).put("repeat",repeat).put("budget_ms",budget)
            .put("arms",armLabels).put("scheduled_arm_orders",scheduledOrders)
            .put("arm_order_scheme","deterministic_rotation_and_reversal_repeat_offset_not_random_assignment")
            .put("delay_ms",DELAY_MS).put("task_selection_frozen_before_run",true)
            .put("planner_arm",live()?"live_app_task":"local_app_task")
            .put("deviation","Public navigation only; scripted approvals; budget differs from proposed 180s if below 180000").toString(2),false);
        if(Boolean.parseBoolean(args.getString("clearOutput","true")))write("episodes.jsonl","",false);
        int pairIndex=0,count=0;
        try(DeviceSession session=new DeviceSession()){
            for(int r=0;r<repeat;r++)for(Task task:tasks)for(String condition:conditions){
                String pairId=runId+":"+task.id+":"+condition+":"+r;
                List<AppTaskMetaController.Arm> arms=armOrder(requestedArms,pairIndex++ + r);
                for(AppTaskMetaController.Arm arm:arms){
                    JSONObject episode=episode(session,task,condition,arm,pairId,budget,r);
                    write("episodes.jsonl",episode.toString()+"\n",true);
                    System.out.println("META_EPISODE "+episode);count++;
                }
            }
        }
        Assert.assertEquals(tasks.size()*conditions.size()*repeat*requestedArms.size(),count);
    }

    /** Validate all starting states without invoking the controller or any model. */
    @Test public void probeTaskSetups()throws Exception {
        Assume.assumeTrue("Opt in with -e metaSetupProbe true",Boolean.parseBoolean(args.getString("metaSetupProbe","false")));
        JSONArray checks=new JSONArray();
        try(DeviceSession session=new DeviceSession()){
            for(Task task:catalogue()){
                JSONObject check=new JSONObject().put("task_id",task.id).put("app_id",task.pkg);
                SetupTrace setupTrace=new SetupTrace();
                try{
                    ScreenSnapshot initial=prepareStart(session,task,setupTrace);
                    check.put("initial_goal_matches",0).put("native_status","public").put("ready",true)
                        .put("initial_state_hash",initialPublicHash(initial));
                }catch(Exception failed){check.put("ready",false).put("reason",failed.getMessage()==null?failed.getClass().getSimpleName():failed.getMessage());}
                finally{if(service()!=null)service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);SystemClock.sleep(200);}
                check.put("setup_trace",setupTrace.json());
                checks.put(check);
            }
        }
        write("setup-probe.json",new JSONObject().put("schema_version",VERSION).put("model_calls",0).put("checks",checks).toString(2),false);
    }

    /** Preflight only: validate the unchanged search oracle with an actual IME, without any model. */
    @Test public void probeSearchOracle()throws Exception {
        Assume.assumeTrue("Opt in with -e metaSearchProbe true",Boolean.parseBoolean(args.getString("metaSearchProbe","false")));
        Task task=null;for(Task candidate:catalogue())if("youtube_search".equals(candidate.id))task=candidate;
        Assert.assertNotNull(task);
        JSONObject report=new JSONObject().put("schema_version",VERSION).put("source","real_device_search_oracle_preflight")
            .put("task_id",task.id).put("model_calls",0).put("raw_screen_saved",false)
            .put("goal_confirmed",false).put("search_click_accepted",false).put("keyboard_visible",false).put("keyboard_observed",false);
        SetupTrace trace=new SetupTrace();int known=0,unknown=0,ambiguous=0;boolean confirmed=false,keyboard=false,keyboardSeen=false;
        long probeAt=SystemClock.elapsedRealtime();
        try(DeviceSession session=new DeviceSession()){
            try{
                prepareStart(session,task,trace);
                boolean clicked=clickPublic(session.automation,task.pkg,"검색");report.put("search_click_accepted",clicked);
                if(clicked){
                    long deadline=SystemClock.elapsedRealtime()+5000,goalSince=-1;
                    while(SystemClock.elapsedRealtime()<deadline){
                        int matches=goalCount(session.automation,task);long now=SystemClock.elapsedRealtime();
                        if(matches<0)unknown++;else known++;
                        if(matches>1)ambiguous++;
                        ScreenSnapshot fresh=ClickAccessibilityService.freshSnapshot();
                        keyboard=fresh!=null&&fresh.windowContext.keyboardVisible();keyboardSeen|=keyboard;
                        if(matches==1){if(goalSince<0)goalSince=now;if(now-goalSince>=700){confirmed=true;break;}}
                        else goalSince=-1;
                        SystemClock.sleep(100);
                    }
                }
            }finally{if(service()!=null)service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);}
        }catch(Exception failed){report.put("failure_reason",failed instanceof SetupFailure?failed.getMessage():failed.getClass().getSimpleName());}
        report.put("goal_confirmed",confirmed).put("capability_ready",confirmed).put("keyboard_visible",keyboard)
            .put("keyboard_observed",keyboardSeen).put("probe_elapsed_ms",SystemClock.elapsedRealtime()-probeAt)
            .put("root_observability_counts",new JSONObject().put("known",known).put("unknown",unknown).put("ambiguous",ambiguous))
            .put("setup_trace",trace.json());
        write("search-oracle-probe.json",report.toString(2),false);
        System.out.println("META_SEARCH_PROBE "+report);
    }

    private JSONObject episode(DeviceSession session,Task task,String condition,AppTaskMetaController.Arm arm,String pairId,long budget,int repeat)throws Exception {
        String armLabel=armLabel(arm);
        JSONObject result=new JSONObject().put("schema_version",VERSION).put("trial_id",pairId+":"+armLabel)
            .put("pair_id",pairId).put("task_id",task.id).put("app_id",task.pkg).put("condition",condition)
            .put("arm",armLabel).put("repeat",repeat).put("source","real_device_scripted_driver")
            .put("driver_mode","scripted_approvals").put("planner_arm",live()?"live_app_task":"local_app_task")
            .put("model_calls",live()?JSONObject.NULL:0).put("model_wait_ms",live()?JSONObject.NULL:0)
            .put("model_measurement_source",live()?"join_facade_log_by_runtime_task_id":"no_remote_calls")
            .put("budget_ms",budget).put("policy_version",VERSION)
            .put("oracle_source","ui_automation_goal_check").put("independent_oracle",true)
            .put("fault_injected",false).put("oracle_success",false).put("declared_done",false).put("elapsed_ms",0)
            .put("wrong_target_count",JSONObject.NULL)
            .put("goal_observed_ms",JSONObject.NULL).put("runtime_done_ms",JSONObject.NULL)
            .put("runtime_verified_offer",false).put("runtime_verified_offer_ms",JSONObject.NULL)
            .put("oracle_observable",JSONObject.NULL).put("oracle_final_match_count",JSONObject.NULL)
            .put("false_verified_offer",JSONObject.NULL)
            .put("provider_drain_ms",0).put("provider_drain_applied",live()).put("provider_drain_ready",JSONObject.NULL)
            .put("runtime_completion_ack","not_simulated_no_user_result_confirmation");
        long setupAt=SystemClock.elapsedRealtime();Driver driver=null;SetupTrace setupTrace=null;
        try{
            if(live()){
                long drainAt=SystemClock.elapsedRealtime();result.put("provider_drain_ready",false);
                try{awaitProviderDrain();result.put("provider_drain_ready",true);}
                finally{result.put("provider_drain_ms",SystemClock.elapsedRealtime()-drainAt);}
            }
            setupTrace=new SetupTrace();
            ScreenSnapshot initial=prepareStart(session,task,setupTrace);
            result.put("setup_elapsed_ms",SystemClock.elapsedRealtime()-setupAt).put("setup_status","ready");
            result.put("initial_state_hash",initialPublicHash(initial))
                .put("initial_hash_scope","barrier_snapshot_sorted_allowlisted_public_label_resource_class_clickable_selected_bounds");
            driver=new Driver(task,condition,arm);final Driver current=driver;
            long started=SystemClock.elapsedRealtime(),goalSince=-1,offerWithoutGoalSince=-1;
            boolean stoppedForOffer=false;
            current.startedAt=started;
            instrumentation.runOnMainSync(current::start);
            while(SystemClock.elapsedRealtime()-started<budget){
                instrumentation.runOnMainSync(current::poll);
                int observedMatches=goalCount(session.automation,task);
                boolean goal=observedMatches==1;
                long now=SystemClock.elapsedRealtime();
                if(goal){if(goalSince<0)goalSince=now;}else goalSince=-1;
                if(goalSince>=0&&now-goalSince>=700){result.put("oracle_success",true).put("goal_observed_ms",goalSince-started);break;}
                if(current.error!=null)break;
                if(current.runtimeVerifiedOffer&&observedMatches==0){
                    if(offerWithoutGoalSince<0)offerWithoutGoalSince=now;
                    if(now-offerWithoutGoalSince>=700){stoppedForOffer=true;break;}
                }else offerWithoutGoalSince=-1;
                SystemClock.sleep(100);
            }
            int finalMatchCount=goalCount(session.automation,task);
            if(finalMatchCount<0&&!result.optBoolean("oracle_success"))result.put("oracle_success",JSONObject.NULL);
            result.put("elapsed_ms",SystemClock.elapsedRealtime()-started)
                .put("declared_done",current.declaredDone).put("fault_injected",current.injected)
                .put("runtime_verified_offer",current.runtimeVerifiedOffer)
                .put("runtime_verified_offer_ms",current.offerAt<0?JSONObject.NULL:current.offerAt-started)
                .put("false_verified_offer",finalMatchCount<0||finalMatchCount>1?JSONObject.NULL:current.runtimeVerifiedOffer&&finalMatchCount==0)
                .put("native_execution_attempts",current.nativeAttempts).put("native_rejections",current.nativeRejections)
                .put("native_acceptances",current.nativeAcceptances)
                .put("scripted_approval_count",current.approvals).put("observed_change_notifications",current.changeNotifications)
                .put("terminal_runtime_status",current.latest==null?"none":current.latest.optString("status"))
                .put("delayed_execution_accepted",current.delayedAccepted)
                .put("runtime_task_id",current.runtimeTaskId==null?JSONObject.NULL:current.runtimeTaskId)
                .put("runtime_done_ms",current.doneAt<0?JSONObject.NULL:current.doneAt-started)
                .put("first_proposal_ms",current.firstProposalAt<0?JSONObject.NULL:current.firstProposalAt-started)
                .put("oracle_final_match_count",finalMatchCount<0?JSONObject.NULL:finalMatchCount)
                .put("oracle_observable",finalMatchCount>=0)
                .put("observed_model_calls",current.observedModelCalls).put("observed_model_wait_ms",current.observedModelWait)
                .put("runtime_failure",current.error==null?JSONObject.NULL:current.error);
            result.put("outcome",result.optBoolean("oracle_success")?"goal_observed":current.error!=null?"controller_error":stoppedForOffer?"runtime_offer_without_goal":finalMatchCount<0?"oracle_unobservable":"budget_exhausted");
            synchronized(current.acceptedPublicTargets){result.put("accepted_native_public_targets",new JSONArray(current.acceptedPublicTargets));}
            synchronized(current.acceptedNativeTools){result.put("accepted_native_tools",new JSONArray(current.acceptedNativeTools));}
            result.put("meta_diagnostic",current.controller.metaController().diagnostic());
        }catch(SetupFailure failed){
            result.put("setup_status","failed").put("failure_reason",failed.getMessage())
                .put("setup_elapsed_ms",SystemClock.elapsedRealtime()-setupAt).put("outcome","setup_failure");
        }catch(Exception failed){
            result.put("setup_status","error").put("failure_reason",failed.getClass().getSimpleName())
                .put("setup_elapsed_ms",SystemClock.elapsedRealtime()-setupAt).put("outcome","harness_error");
        }finally{
            if(driver!=null){final Driver current=driver;instrumentation.runOnMainSync(current::close);}
            // One Back is allowed only from a new tab that this trial actually opened.
            // Never iterate tab-close actions or clear browser state/history.
            if("chrome_new_tab".equals(task.id)&&driver!=null&&driver.nativeAcceptances>0&&result.optBoolean("oracle_success")&&service()!=null){
                service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(250);
            }
            if(service()!=null)service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            SystemClock.sleep(200);
        }
        result.put("setup_trace",setupTrace==null?JSONObject.NULL:setupTrace.json());
        return result;
    }

    /** The serial facade answers only after earlier provider work is drained; no warmup/model call. */
    private void awaitProviderDrain()throws SetupFailure {
        String base=com.click.BuildConfig.CLICK_API_BASE_URL.replaceAll("/$","");
        if(!"http://127.0.0.1:8766".equals(base))throw new SetupFailure("loopback_facade_not_configured");
        HttpURLConnection connection=null;long deadline=SystemClock.elapsedRealtime()+15000;
        try{
            connection=(HttpURLConnection)new URL(base+"/health").openConnection();
            connection.setRequestMethod("GET");connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setConnectTimeout(15000);connection.setReadTimeout(15000);connection.connect();
            connection.setReadTimeout(remainingDrainTime(deadline));
            if(connection.getResponseCode()!=200)throw new SetupFailure("local_facade_not_ready");
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            try(InputStream input=connection.getInputStream()){
                byte[] buffer=new byte[1024];int length;
                while(true){
                    connection.setReadTimeout(remainingDrainTime(deadline));length=input.read(buffer);
                    if(length<0)break;
                    if(body.size()+length>8192)throw new SetupFailure("local_facade_health_response_invalid");
                    body.write(buffer,0,length);
                }
            }
            if(!"ready".equals(new JSONObject(body.toString(StandardCharsets.UTF_8.name())).optString("status")))
                throw new SetupFailure("local_facade_not_ready");
        }catch(SetupFailure failed){throw failed;}
        catch(Exception failed){throw new SetupFailure("local_facade_not_ready");}
        finally{if(connection!=null)connection.disconnect();}
    }
    private static int remainingDrainTime(long deadline)throws SetupFailure {
        long remaining=deadline-SystemClock.elapsedRealtime();
        if(remaining<=0)throw new SetupFailure("local_facade_drain_deadline");return (int)remaining;
    }

    private final class Driver implements AppTaskController.Listener {
        final Task task;final String condition;final AppTaskMetaController.Arm arm;
        final List<String> acceptedPublicTargets=Collections.synchronizedList(new ArrayList<>());
        final List<String> acceptedNativeTools=Collections.synchronizedList(new ArrayList<>());
        final Set<String> approvalsSeen=new HashSet<>();final Handler delayed=new Handler(Looper.getMainLooper());
        final AtomicBoolean closed=new AtomicBoolean();AppTaskController controller;JSONObject latest;
        final Set<JSONObject> seenDiagnostics=Collections.newSetFromMap(new IdentityHashMap<>());
        String fingerprint,error,runtimeTaskId;boolean injected,declaredDone,delayedAccepted,runtimeVerifiedOffer;int approvals,nativeAttempts,nativeRejections,nativeAcceptances,changeNotifications,observedModelCalls;long observedModelWait,startedAt,doneAt=-1,firstProposalAt=-1,offerAt=-1;
        Driver(Task task,String condition,AppTaskMetaController.Arm arm){this.task=task;this.condition=condition;this.arm=arm;}
        void start(){try{
            AppTaskMemory memory=new AppTaskMemory(task.goal);runtimeTaskId=memory.id();if(!live())memory.completion(task.completion());
            controller=new AppTaskController(new AppGuideGateway(app),memory,null,this,live(),false,true,arm);
            controller.targetApp(task.pkg,task.pkg);fingerprint=AppTaskObservation.capture(fresh()).optString("fingerprint");controller.refresh();
        }catch(Exception failed){error=failed.getClass().getSimpleName();}}
        void poll(){try{
            if(controller==null||closed.get())return;
            ScreenSnapshot screen=fresh();String next=AppTaskObservation.capture(screen).optString("fingerprint");
            if(!next.equals(fingerprint)){fingerprint=next;changeNotifications++;controller.screenChanged(screen);}
            JSONObject response=latest;if(response==null)return;
            JSONObject action=response.optJSONObject("action");
            if("proposed".equals(response.optString("status"))&&action!=null&&approvalsSeen.add(action.optString("id"))){
                approvals++;controller.execute(action.optString("id"),null);
            }
            // Goal truth is measured independently and never fed back as a simulated user confirmation.
        }catch(Exception failed){error=failed.getClass().getSimpleName();}}
        void close(){closed.set(true);delayed.removeCallbacksAndMessages(null);if(controller!=null)controller.close();}
        public ScreenSnapshot fresh(){return ClickAccessibilityService.freshSnapshot();}
        public boolean canObserve(){return !closed.get();}
        public void busy(boolean value){}
        public void present(JSONObject response,ScreenSnapshot submitted){
            latest=response;
            if("completed".equals(response.optString("status"))){declaredDone=true;if(doneAt<0)doneAt=SystemClock.elapsedRealtime();}
            JSONObject assessment=response.optJSONObject("result_assessment");
            if("confirm_result".equals(response.optString("status"))&&assessment!=null&&"verified".equals(assessment.optString("status"))){
                runtimeVerifiedOffer=true;if(offerAt<0)offerAt=SystemClock.elapsedRealtime();
            }
            if("proposed".equals(response.optString("status"))&&firstProposalAt<0)firstProposalAt=SystemClock.elapsedRealtime();
            JSONObject diagnostic=response.optJSONObject("diagnostic");
            if(diagnostic!=null&&diagnostic.optBoolean("called")&&seenDiagnostics.add(diagnostic)){
                observedModelCalls++;observedModelWait+=diagnostic.optLong("latency_ms");
            }
        }
        public void failure(Throwable failed){error=failed.getClass().getSimpleName();}
        public boolean inputMatches(String target,String expectedHash){return false;}
        public String scope(String pkg){return pkg+"|device-experiment|"+VERSION;}
        public boolean execute(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String input,String selectedPackage){
            String tool=action.optString("tool");
            JSONObject target=AppTaskObservation.find(observation,action.optString("target_id"));
            String publicTarget=target==null?"":publicLabel(target.optString("label"));
            boolean samePublic=task.pkg.equals(observation.optString("package"))&&"public".equals(observation.optString("status"));
            boolean safeTap=samePublic&&"tap".equals(tool)&&NAVIGATION_LABELS.contains(publicTarget);
            boolean safeOpen="open_app".equals(tool)&&task.pkg.equals(selectedPackage)&&task.pkg.equals(action.optString("package"));
            boolean safeNavigation=samePublic&&("back".equals(tool)
                ||("scroll_forward".equals(tool)||"scroll_backward".equals(tool))&&target!=null&&target.optBoolean("scrollable"));
            if(!safeTap&&!safeOpen&&!safeNavigation){
                nativeRejections++;return false;
            }
            nativeAttempts++;
            if(safeTap&&!injected&&!"normal".equals(condition)){
                // A stale request must be rejected by the existing policy before it can receive a fault.
                JSONObject current;
                try{current=AppTaskObservation.capture(fresh());}catch(JSONException unavailable){nativeRejections++;return false;}
                if(!AppTaskPolicy.allowed(observation,current,action,selectedPackage)){
                    nativeRejections++;return false;
                }
                injected=true;
                if("noeffect".equals(condition))return true; // Accepted adapter request, deliberately no native click.
                delayed.postDelayed(()->{
                    if(closed.get())return;
                    delayedAccepted=AppTaskExecutor.execute(service(),baseline,fresh(),observation,action,null,selectedPackage);
                    if(!delayedAccepted)nativeRejections++;else recordNativeAcceptance(tool,publicTarget);
                },DELAY_MS);
                return true;
            }
            boolean accepted=AppTaskExecutor.execute(service(),baseline,fresh(),observation,action,null,selectedPackage);
            if(!accepted)nativeRejections++;else recordNativeAcceptance(tool,publicTarget);return accepted;
        }
        private void recordNativeAcceptance(String tool,String publicTarget){
            nativeAcceptances++;acceptedNativeTools.add(tool);
            if("tap".equals(tool)&&!publicTarget.isEmpty())acceptedPublicTargets.add(publicTarget);
        }
    }

    private static final class SetupTrace {
        final long started=SystemClock.elapsedRealtime();
        final Set<String> packages=new LinkedHashSet<>();
        int reads,retries,startControlRetries,stableReads,oracleUnknownReads;long stableSpan,finished=-1;
        JSONObject json()throws JSONException{return new JSONObject().put("deadline_ms",10000)
            .put("elapsed_ms",(finished<0?SystemClock.elapsedRealtime():finished)-started).put("snapshot_reads",reads).put("retry_count",retries)
            .put("start_control_retry_count",startControlRetries).put("stable_reads",stableReads).put("stable_span_ms",stableSpan)
            .put("oracle_unknown_reads",oracleUnknownReads)
            .put("observed_package_categories",new JSONArray(packages));}
    }
    /** One reset path shared by preflight and measured trials; never retries a controller action. */
    private ScreenSnapshot prepareStart(DeviceSession session,Task task,SetupTrace trace)throws Exception {
        try{
            long deadline=trace.started+10000;
            launchIntent(task.pkg);
            awaitPublicWindow(task.pkg,deadline,trace);
            if(!task.startLabel.isEmpty()){
                awaitSetupControl(session.automation,task.pkg,task.startLabel,deadline,trace);
                awaitPublicWindow(task.pkg,deadline,trace);
            }
            if(task.menu)awaitSetupControl(session.automation,task.pkg,"Chrome 맞춤설정 및 제어",deadline,trace);
            while(SystemClock.elapsedRealtime()<deadline){
                ScreenSnapshot initial=awaitPublicWindow(task.pkg,deadline,trace);
                int matches=startGoalCount(session.automation,task,initial);
                if(matches<0){trace.oracleUnknownReads++;trace.retries++;SystemClock.sleep(150);continue;}
                if(matches!=0)throw new SetupFailure(matches==1?"goal_already_present_at_start":"goal_ambiguous_at_start");
                initialPublicHash(initial); // Require actual allowlisted public context; never hash an empty context.
                return initial;
            }
            throw new SetupFailure("independent_start_oracle_setup_deadline");
        }finally{trace.finished=SystemClock.elapsedRealtime();}
    }
    private void awaitSetupControl(UiAutomation automation,String pkg,String label,long deadline,SetupTrace trace)throws Exception {
        while(SystemClock.elapsedRealtime()<deadline){
            if(clickPublic(automation,pkg,label))return;
            trace.startControlRetries++;SystemClock.sleep(150);
        }
        throw new SetupFailure("start_control_missing_before_setup_deadline");
    }
    private ScreenSnapshot awaitPublicWindow(String pkg,long deadline,SetupTrace trace)throws Exception {
        Integer window=null;long stableAt=-1;int count=0;String previousObservation=null;
        while(SystemClock.elapsedRealtime()<deadline){
            ScreenSnapshot screen=ClickAccessibilityService.freshSnapshot();trace.reads++;
            String actual=screen==null?null:screen.packageName;
            trace.packages.add(actual==null?"no_snapshot":Arrays.asList(SETTINGS,CLOCK,CHROME,YOUTUBE).contains(actual)?actual:
                app.getPackageName().equals(actual)?"experiment_app":"com.android.systemui".equals(actual)?"system_ui":"other_package");
            boolean valid=screen!=null&&pkg.equals(actual)&&screen.activeWindowId!=null
                &&"public".equals(AppTaskObservation.capture(screen).optString("status"));
            long now=SystemClock.elapsedRealtime();
            if(valid){
                if(!Objects.equals(window,screen.activeWindowId)){window=screen.activeWindowId;stableAt=now;count=0;previousObservation=null;}
                if(!screen.observationId.equals(previousObservation)){count++;previousObservation=screen.observationId;}
                trace.stableReads=count;trace.stableSpan=now-stableAt;
                if(count>=3&&now-stableAt>=600)return screen;
            }else{window=null;stableAt=-1;count=0;previousObservation=null;trace.retries++;trace.stableReads=0;trace.stableSpan=0;}
            SystemClock.sleep(150);
        }
        throw new SetupFailure("public_start_window_setup_deadline");
    }
    private static String initialPublicHash(ScreenSnapshot snapshot)throws Exception {
        List<String> context=new ArrayList<>();
        for(AccessibilityNodeSnapshot node:snapshot.nodes){
            if(!node.visibleToUser||!snapshot.packageName.equals(node.packageName)
                ||snapshot.activeWindowId!=null&&node.windowId!=snapshot.activeWindowId||node.passwordInput())continue;
            String label=publicLabel(node.contentDescription!=null?node.contentDescription:node.text);
            if(label.isEmpty())continue;
            context.add(new JSONObject().put("label",label).put("resource",node.resourceId)
                .put("class",node.className).put("clickable",node.clickable).put("selected",node.selected)
                .put("bounds",node.bounds==null?JSONObject.NULL:new JSONArray(node.bounds)).toString());
        }
        if(context.isEmpty())throw new SetupFailure("public_start_controls_unavailable");
        Collections.sort(context);return AppTaskObservation.hash(snapshot.packageName+"\n"+String.join("\n",context));
    }

    private void launch(String pkg)throws Exception {
        launchIntent(pkg);
        long deadline=SystemClock.elapsedRealtime()+7000;
        while(SystemClock.elapsedRealtime()<deadline){ScreenSnapshot s=ClickAccessibilityService.freshSnapshot();
            if(s!=null&&pkg.equals(s.packageName)&&!s.nodes.isEmpty()){SystemClock.sleep(500);return;}SystemClock.sleep(150);}
        throw new SetupFailure("launch_timeout");
    }
    private void launchIntent(String pkg)throws Exception {
        Intent launch;
        if(SETTINGS.equals(pkg))launch=new Intent(Settings.ACTION_SETTINGS).setPackage(pkg);
        else if(CHROME.equals(pkg))launch=new Intent(Intent.ACTION_VIEW,Uri.parse("about:blank")).setPackage(pkg)
            .putExtra(Browser.EXTRA_APPLICATION_ID,app.getPackageName()).putExtra("create_new_tab",false);
        else launch=app.getPackageManager().getLaunchIntentForPackage(pkg);
        if(launch==null)throw new SetupFailure("app_unavailable");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        app.startActivity(launch);
    }
    private static boolean clickPublic(UiAutomation automation,String pkg,String label){
        AccessibilityNodeInfo root=goalRoot(automation);if(root==null)return false;
        try{return clickWalk(root,pkg,label);}finally{root.recycle();}
    }
    private static boolean clickWalk(AccessibilityNodeInfo node,String pkg,String label){
        if(node.isVisibleToUser()&&pkg.equals(String.valueOf(node.getPackageName()))&&label.equals(nodePublicLabel(node))){
            // An already-selected start tab is a valid initial state; many apps
            // disable its click action. This is setup only, never runtime evidence.
            if(node.isSelected())return true;
            AccessibilityNodeInfo target=AccessibilityNodeInfo.obtain(node);
            try{for(int i=0;i<3;i++){
                if(target.isClickable()&&target.isEnabled())return target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                AccessibilityNodeInfo parent=target.getParent();if(parent==null)break;target.recycle();target=parent;
            }}finally{target.recycle();}
        }
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{if(clickWalk(child,pkg,label))return true;}finally{child.recycle();}}
        return false;
    }
    private static String nodePublicLabel(AccessibilityNodeInfo node){
        String label=publicLabel(node.getContentDescription());return label.isEmpty()?publicLabel(node.getText()):label;
    }
    /** Independent UiAutomation traversal; never reads runtime result or observation JSON. */
    private static boolean goalObserved(UiAutomation automation,Task task){
        return goalCount(automation,task)==1;
    }
    private static int goalCount(UiAutomation automation,Task task){
        AccessibilityNodeInfo root=goalRoot(automation);if(root==null)return -1;
        try{return goalWalk(root,task);}finally{root.recycle();}
    }
    private static int startGoalCount(UiAutomation automation,Task task,ScreenSnapshot initial){
        AccessibilityNodeInfo root=goalRoot(automation);if(root==null)return -1;
        try{
            if(!task.pkg.equals(String.valueOf(root.getPackageName()))||initial.activeWindowId==null
                ||root.getWindowId()!=initial.activeWindowId)return -1;
            return goalWalk(root,task);
        }finally{root.recycle();}
    }
    /** Select the foreground window without consulting the task or its expected app/result. */
    private static AccessibilityNodeInfo goalRoot(UiAutomation automation){
        List<AccessibilityWindowInfo> windows=automation.getWindows();
        if(windows==null||windows.isEmpty())return null;
        AccessibilityNodeInfo chosen=null;int chosenLayer=Integer.MIN_VALUE;
        String ownPackage=InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
        try{
            for(AccessibilityWindowInfo window:windows){
                if(window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD||!window.isActive()&&!window.isFocused())continue;
                AccessibilityNodeInfo root=window.getRoot();
                if(window.getType()==AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY&&root!=null
                    &&ownPackage.equals(String.valueOf(root.getPackageName()))){root.recycle();continue;}
                if(window.getLayer()>chosenLayer){
                    if(chosen!=null)chosen.recycle();chosen=root;chosenLayer=window.getLayer();
                }else if(root!=null)root.recycle();
            }
            // A higher focused system/other-app modal with a missing root remains UNKNOWN;
            // never search behind it for a node that satisfies the task.
            return chosen;
        }finally{for(AccessibilityWindowInfo window:windows)window.recycle();}
    }
    private static int goalWalk(AccessibilityNodeInfo node,Task task){
        if(node.isVisibleToUser()&&task.pkg.equals(String.valueOf(node.getPackageName()))
                &&(task.resultLabel.isEmpty()||task.resultLabel.equals(nodePublicLabel(node)))
                &&(task.resultResource.isEmpty()||task.resultResource.equals(node.getViewIdResourceName()))
                &&(!task.selected||node.isSelected()))return 1; // Label child and its matching ancestor are one control.
        int count=0;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{count+=goalWalk(child,task);if(count>1)return 2;}finally{child.recycle();}}
        return count;
    }
    private static JSONArray probeControls(UiAutomation automation,String pkg)throws JSONException {
        JSONArray controls=new JSONArray();AccessibilityNodeInfo root=goalRoot(automation);if(root==null)return controls;
        try{probeWalk(root,pkg,controls);}finally{root.recycle();}return controls;
    }
    private static void probeWalk(AccessibilityNodeInfo node,String pkg,JSONArray controls)throws JSONException {
        String label=nodePublicLabel(node);
        if(node.isVisibleToUser()&&pkg.equals(String.valueOf(node.getPackageName()))&&!label.isEmpty()&&!node.isPassword()){
            Rect bounds=new Rect();node.getBoundsInScreen(bounds);
            controls.put(new JSONObject().put("label",label).put("resource",node.getViewIdResourceName())
                .put("class",String.valueOf(node.getClassName())).put("clickable",node.isClickable()).put("selected",node.isSelected())
                .put("bounds",new JSONArray(new int[]{bounds.left,bounds.top,bounds.right,bounds.bottom})));
        }
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{probeWalk(child,pkg,controls);}finally{child.recycle();}}
    }
    private final class DeviceSession implements AutoCloseable {
        final UiAutomation automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        final String original;final int enabled,originalAutomationFlags;
        DeviceSession()throws Exception {
            original=Settings.Secure.getString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            enabled=Settings.Secure.getInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0);
            AccessibilityServiceInfo automationInfo=automation.getServiceInfo();
            originalAutomationFlags=automationInfo==null?0:automationInfo.flags;
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS");
            try{
                if(automationInfo!=null){automationInfo.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(automationInfo);}
                try(var wake=automation.executeShellCommand("input keyevent KEYCODE_WAKEUP")){}
                ComponentName component=new ComponentName(app,ClickAccessibilityService.class);List<String> services=new ArrayList<>();
                if(original!=null)for(String value:original.split(":")){
                    ComponentName existing=ComponentName.unflattenFromString(value);
                    // Other CLICK builds must not react to the same scripted actions.
                    // Non-CLICK accessibility aids remain enabled; restore everything below.
                    boolean clickService=existing!=null&&existing.getClassName().equals(ClickAccessibilityService.class.getName());
                    if(!value.isEmpty()&&!clickService)services.add(value);
                }
                Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
                SystemClock.sleep(400);services.add(component.flattenToString());
                Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,String.join(":",services));
                Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,1);
                long deadline=SystemClock.elapsedRealtime()+10000;
                while(service()==null&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
                if(service()==null)throw new SetupFailure("accessibility_service_unavailable");
            }catch(Exception failed){close();throw failed;}
        }
        public void close(){
            try{if(service()!=null)service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);}
            finally{
                try{
                    Settings.Secure.putString(app.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,original==null?"":original);
                    Settings.Secure.putInt(app.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,enabled);
                }finally{
                    try{automation.dropShellPermissionIdentity();}
                    finally{AccessibilityServiceInfo info=automation.getServiceInfo();if(info!=null){info.flags=originalAutomationFlags;automation.setServiceInfo(info);}}
                }
            }
        }
    }
    private static ClickAccessibilityService service(){try{
        var field=ClickAccessibilityService.class.getDeclaredField("connectedService");field.setAccessible(true);
        WeakReference<?> ref=(WeakReference<?>)field.get(null);return ref==null?null:(ClickAccessibilityService)ref.get();
    }catch(Exception failed){throw new AssertionError(failed);}}
    private static Set<String> csv(String text){Set<String> result=new LinkedHashSet<>();for(String item:text.split(","))if(!item.trim().isEmpty())result.add(item.trim());return result;}
    private static String armLabel(AppTaskMetaController.Arm arm){return arm==AppTaskMetaController.Arm.CURRENT?"A":arm==AppTaskMetaController.Arm.META?"B":"C";}
    private static List<AppTaskMetaController.Arm> armOrder(List<AppTaskMetaController.Arm> requested,int index){
        List<AppTaskMetaController.Arm> order=new ArrayList<>(requested);
        if(order.size()==2){if(index%2!=0)Collections.reverse(order);}
        else if(order.size()>2){Collections.rotate(order,-(index%order.size()));if((index/order.size())%2!=0)Collections.reverse(order);}
        return order;
    }
    private void write(String name,String text,boolean append)throws IOException {
        File directory=new File(app.getFilesDir(),"meta-controller");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create experiment directory");
        try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(directory,name),append),StandardCharsets.UTF_8)){out.write(text);}
    }
    private static final class SetupFailure extends Exception {SetupFailure(String reason){super(reason);}}
}
