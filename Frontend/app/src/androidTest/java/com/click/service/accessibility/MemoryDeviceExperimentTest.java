package com.click.service.accessibility;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Browser;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.click.data.remote.AppGuideGateway;
import com.click.data.local.AppTaskStore;
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
import java.util.function.Supplier;

/**
 * Opt-in real-device memory-reuse experiment (MemGUI-style multi-session protocol).
 * Arms: A no memory, B current production memory, C B + server goal normalization.
 * Each task runs 3 learning sessions (original goal), 1 retention session (original goal)
 * and 2 transfer sessions (paraphrased mirror goal) per arm, with arm-isolated memory.
 * A simulated user confirms the result only after the independent UiAutomation oracle
 * has observed the goal for 700 ms; the runtime's own verdict is never used as truth.
 * Public navigation only: no text input, no settings values, no raw tree on disk.
 */
@RunWith(AndroidJUnit4.class)
public class MemoryDeviceExperimentTest {
    private static final String VERSION="memory-reuse.v1", SETTINGS="com.android.settings",
        CLOCK="com.sec.android.app.clockpackage", CHROME="com.android.chrome", YOUTUBE="com.google.android.youtube";
    private static final String BASE="http://127.0.0.1:8766", AUTH="Bearer local-meta-experiment";
    private static final String[] SESSIONS={"learn1","learn2","learn3","retain","mirror1","mirror2"};
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context app=instrumentation.getTargetContext();
    private final Bundle args=InstrumentationRegistry.getArguments();

    private static final class Task {
        final String id,pkg,goal,mirror,startLabel,resultLabel,resultResource,role;
        final boolean selected,menu;
        Task(String id,String pkg,String goal,String mirror,String startLabel,String resultLabel,String resultResource,String role,boolean selected,boolean menu){
            this.id=id;this.pkg=pkg;this.goal=goal;this.mirror=mirror;this.startLabel=startLabel;this.resultLabel=resultLabel;
            this.resultResource=resultResource;this.role=role;this.selected=selected;this.menu=menu;
        }
        JSONObject describe()throws JSONException {
            return new JSONObject().put("task_id",id).put("app_id",pkg).put("goal_ref",AppTaskObservation.hash(goal))
                .put("mirror_goal_ref",AppTaskObservation.hash(mirror)).put("start_label",startLabel).put("open_menu_in_setup",menu)
                .put("oracle",new JSONObject().put("label",resultLabel).put("resource",resultResource).put("role",role).put("selected",selected));
        }
    }
    // Same tasks, start states and oracles as the MetaController v4 pilot; mirror goals frozen in catalogue.json.
    private static List<Task> catalogue(){return Arrays.asList(
        new Task("settings_display",SETTINGS,"디스플레이 설정 화면을 열어줘","설정에서 디스플레이 메뉴로 들어가줘","","밝기","","",false,false),
        new Task("settings_sound",SETTINGS,"소리 및 진동 설정 화면을 열어줘","설정에서 소리 및 진동 메뉴로 들어가줘","","소리 모드","","",false,false),
        new Task("settings_notifications",SETTINGS,"알림 설정 화면을 열어줘","설정에서 알림 메뉴로 들어가줘","","앱 알림","","",false,false),
        new Task("clock_world",CLOCK,"세계시각 탭으로 이동해줘","시계 앱에서 세계시각 화면 보여줘","알람","세계시각","","tab",true,false),
        new Task("clock_stopwatch",CLOCK,"스톱워치 탭으로 이동해줘","시계 앱에서 스톱워치 화면 보여줘","알람","스톱워치","","tab",true,false),
        new Task("clock_timer",CLOCK,"타이머 탭으로 이동해줘","시계 앱에서 타이머 화면 보여줘","알람","타이머","","tab",true,false),
        new Task("chrome_menu",CHROME,"Chrome 메뉴를 열어줘","크롬 오른쪽 위 메뉴 좀 펼쳐줘","","새 시크릿 탭","","",false,false),
        new Task("chrome_new_tab",CHROME,"Chrome에서 새 탭을 열어줘","크롬에 새 탭 하나 띄워줘","","","com.android.chrome:id/search_box_text","",false,true),
        new Task("chrome_settings",CHROME,"Chrome 설정 화면을 열어줘","크롬 설정으로 들어가줘","","검색엔진","","",false,true),
        new Task("youtube_shorts",YOUTUBE,"YouTube에서 Shorts 탭으로 이동해줘","유튜브 쇼츠 화면으로 가줘","홈","Shorts","","button",true,false),
        new Task("youtube_subscriptions",YOUTUBE,"YouTube에서 구독 탭으로 이동해줘","유튜브 구독 목록 화면으로 가줘","홈","구독","","button",true,false),
        new Task("youtube_search",YOUTUBE,"YouTube 검색창을 열어줘","유튜브에서 검색하는 창 띄워줘","홈","","com.google.android.youtube:id/search_edit_text","text_field",false,false)
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


    /** Model-free start-state check; task readiness is frozen from this, never from arm outcomes. */
    @Test public void probeMemorySetups()throws Exception {
        Assume.assumeTrue("Opt in with -e memorySetupProbe true",Boolean.parseBoolean(args.getString("memorySetupProbe","false")));
        JSONArray checks=new JSONArray();
        try(DeviceSession session=new DeviceSession()){
            for(Task task:catalogue()){
                Set<String> wanted=csv(args.getString("tasks",""));if(!wanted.isEmpty()&&!wanted.contains(task.id))continue;
                JSONObject check=new JSONObject().put("task_id",task.id).put("app_id",task.pkg);SetupTrace trace=new SetupTrace();
                try{ScreenSnapshot initial=prepareStart(session,task,trace);check.put("ready",true).put("initial_state_hash",initialPublicHash(initial));}
                catch(Exception failed){check.put("ready",false).put("reason",failed.getMessage()==null?failed.getClass().getSimpleName():failed.getMessage());}
                finally{if(service()!=null)service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);SystemClock.sleep(200);}
                checks.put(check.put("setup_trace",trace.json()));
            }
        }
        write("setup-probe.json",new JSONObject().put("schema_version",VERSION).put("model_calls",0).put("checks",checks).toString(2),false);
        System.out.println("MEMORY_SETUP_PROBE "+checks);
    }

    @Test public void runMemoryExperiment()throws Exception {
        Assume.assumeTrue("Opt in with -e memoryExperiment true",Boolean.parseBoolean(args.getString("memoryExperiment","false")));
        Assert.assertEquals("Live facade requires isolated test app",(Boolean.parseBoolean(args.getString("metaMemoryIntegration","false"))||Boolean.parseBoolean(args.getString("firstStepCostExperiment","false")))?"com.click.metamemoryexperiment.uitestdriver":"com.click.memoryexperiment.uitestdriver",app.getPackageName());
        Assert.assertEquals("Live facade must be loopback only",BASE,com.click.BuildConfig.CLICK_API_BASE_URL.replaceAll("/$",""));
        long budget=Long.parseLong(args.getString("budgetMs","25000"));
        Assert.assertTrue("budgetMs must be 8000..180000",budget>=8000&&budget<=180000);
        List<String> arms=new ArrayList<>(csv(args.getString("arms","A,B,C")));
        for(String arm:arms)Assert.assertTrue("arms must be A,B,C",Arrays.asList("A","B","C").contains(arm));
        Set<String> wanted=csv(args.getString("tasks",""));
        List<Task> tasks=new ArrayList<>();for(Task task:catalogue())if(wanted.isEmpty()||wanted.contains(task.id))tasks.add(task);
        for(String name:wanted)Assert.assertTrue("Unknown task "+name,tasks.stream().anyMatch(t->t.id.equals(name)));
        boolean integrated=Boolean.parseBoolean(args.getString("metaMemoryIntegration","false"));
        boolean firstStepCost=Boolean.parseBoolean(args.getString("firstStepCostExperiment","false"));
        if(firstStepCost){Assert.assertFalse(integrated);Assert.assertEquals(Arrays.asList("A","B","C"),arms);Assert.assertEquals("CURRENT",args.getString("metaArm","CURRENT"));}
        String[] schedule=firstStepCost?new String[]{"learn1","learn2","learn3","learn4","eval1","eval2","eval3"}:integrated?new String[]{"learn1","learn2","eval_normal","eval_no_effect","eval_delayed"}:SESSIONS;
        AppTaskMetaController.Arm.valueOf(args.getString("metaArm","CURRENT"));
        if(integrated)Assert.assertEquals("Integrated comparison uses memory off/on only",Arrays.asList("A","B"),arms);
        int sessions=Integer.parseInt(args.getString("sessions",String.valueOf(schedule.length)));
        Assert.assertTrue("sessions must be 1..6",sessions>=1&&sessions<=schedule.length);
        String runId=args.getString("runId",UUID.randomUUID().toString().substring(0,8));
        Assert.assertTrue("runId must be short and safe",runId.matches("[0-9a-zA-Z_.-]{1,40}"));
        JSONObject status=get("/experiment/status");
        Assert.assertEquals("Fresh facade required: one facade process per run",0,status.optInt("registered",-1));
        AuthTokenStore tokens=new AuthTokenStore(app);String previous=tokens.accessToken();
        Assert.assertTrue("Refuse to overwrite an existing real login",previous==null||"local-meta-experiment".equals(previous));
        tokens.save(new JSONObject().put("user_id","local-meta-experiment").put("access_token","local-meta-experiment")
            .put("refresh_token","local-meta-experiment-unused").put("expires_in",36000).put("refresh_expires_in",36000));
        SharedPreferences prefs=app.getSharedPreferences("memory-experiment-"+runId,Context.MODE_PRIVATE);prefs.edit().clear().commit();
        Map<String,AppTaskStore> stores=new HashMap<>();
        for(String arm:arms)if(!"A".equals(arm))stores.put(arm,store(prefs,"memexp-"+arm+"-"+runId));
        JSONArray manifest=new JSONArray(),orders=new JSONArray();for(Task task:tasks)manifest.put(task.describe());
        int index=0;
        for(Task task:tasks)for(int s=0;s<sessions;s++){
            orders.put(new JSONObject().put("task_id",task.id).put("session",schedule[s]).put("order",new JSONArray(order(arms,index++))));
        }
        write("manifest.json",new JSONObject().put("schema_version",VERSION).put("run_id",runId).put("tasks",manifest)
            .put("arms",new JSONArray(arms)).put("sessions",new JSONArray(Arrays.copyOf(schedule,sessions))).put("budget_ms",budget)
            .put("meta_arm",args.getString("metaArm","CURRENT")).put("meta_memory_integration",integrated).put("first_step_cost_experiment",firstStepCost).put("scheduled_arm_orders",orders).put("arm_order_scheme","deterministic_rotation_and_reversal_not_random")
            .put("memory_isolation","per_arm_device_store_and_facade_store").put("simulated_user","confirms_only_after_independent_oracle_700ms")
            .put("task_selection_frozen_before_run",true).toString(2),false);
        if(Boolean.parseBoolean(args.getString("clearOutput","true")))write("episodes.jsonl","",false);
        int count=0;index=0;
        try(DeviceSession session=new DeviceSession()){
            for(Task task:tasks)for(int s=0;s<sessions;s++){
                for(String arm:order(arms,index++)){
                    JSONObject episode=episode(session,task,schedule[s],arm,stores.get(arm),runId,budget);
                    write("episodes.jsonl",episode.toString()+"\n",true);
                    System.out.println("MEMORY_EPISODE "+episode.optString("trial_id")+" "+episode.optString("outcome"));count++;
                }
            }
        }
        Assert.assertEquals(tasks.size()*sessions*arms.size(),count);
    }

    private static List<String> order(List<String> arms,int index){
        List<String> order=new ArrayList<>(arms);Collections.rotate(order,-(index%order.size()));
        if((index/order.size())%2!=0)Collections.reverse(order);return order;
    }
    private AppTaskStore store(SharedPreferences prefs,String user)throws Exception {
        java.lang.reflect.Constructor<AppTaskStore> c=AppTaskStore.class.getDeclaredConstructor(SharedPreferences.class,String.class,Supplier.class);
        c.setAccessible(true);Supplier<String> owner=()->user;return c.newInstance(prefs,user,owner);
    }

    private JSONObject episode(DeviceSession session,Task task,String sessionName,String arm,AppTaskStore store,String runId,long budget)throws Exception {
        boolean mirror=sessionName.startsWith("mirror");String goal=mirror?task.mirror:task.goal;
        String trialId=runId+":"+task.id+":"+sessionName+":"+arm;
        JSONObject result=new JSONObject().put("schema_version",VERSION).put("trial_id",trialId).put("run_id",runId)
            .put("task_id",task.id).put("app_id",task.pkg).put("session",sessionName).put("goal_variant",mirror?"mirror":"original")
            .put("arm",arm).put("memory_enabled",!"A".equals(arm)).put("source","real_device_scripted_driver")
            .put("driver_mode","scripted_approvals_oracle_gated_user_confirmation").put("budget_ms",budget)
            .put("oracle_source","ui_automation_goal_check").put("oracle_success",false).put("elapsed_ms",0)
            .put("goal_observed_ms",JSONObject.NULL).put("user_confirmation","not_attempted").put("experience_ingested",JSONObject.NULL)
            .put("runtime_verified_offer",false).put("false_verified_offer",JSONObject.NULL);
        long setupAt=SystemClock.elapsedRealtime();Driver driver=null;SetupTrace trace=null;
        try{
            long drainAt=SystemClock.elapsedRealtime();awaitProviderDrain();result.put("provider_drain_ms",SystemClock.elapsedRealtime()-drainAt);
            trace=new SetupTrace();ScreenSnapshot initial=prepareStart(session,task,trace);
            result.put("setup_status","ready").put("setup_elapsed_ms",SystemClock.elapsedRealtime()-setupAt).put("initial_state_hash",initialPublicHash(initial));
            int confirmedBefore=confirmedTasks(arm);
            driver=new Driver(task,goal,arm,store,runId,sessionName);final Driver current=driver;
            instrumentation.runOnMainSync(current::create);
            if(current.error!=null)throw new IllegalStateException(current.error);
            post("/experiment/register",new JSONObject().put("runtime_task_id",current.runtimeTaskId).put("arm",arm)
                .put("task_id",task.id).put("goal_variant",mirror?"mirror":"original").put("trial_id",trialId));
            long started=SystemClock.elapsedRealtime(),goalSince=-1,offerWithoutGoalSince=-1;boolean stoppedForOffer=false;
            current.startedAt=started;instrumentation.runOnMainSync(current::start);
            while(SystemClock.elapsedRealtime()-started<budget){
                instrumentation.runOnMainSync(current::poll);
                int matches=goalCount(session.automation,task);long now=SystemClock.elapsedRealtime();
                if(matches==1){if(goalSince<0)goalSince=now;}else goalSince=-1;
                if(goalSince>=0&&now-goalSince>=700){result.put("oracle_success",true).put("goal_observed_ms",goalSince-started);break;}
                if(current.error!=null)break;
                if(current.runtimeVerifiedOffer&&matches==0){if(offerWithoutGoalSince<0)offerWithoutGoalSince=now;if(now-offerWithoutGoalSince>=700){stoppedForOffer=true;break;}}
                else offerWithoutGoalSince=-1;
                SystemClock.sleep(100);
            }
            long elapsed=SystemClock.elapsedRealtime()-started;int finalMatches=goalCount(session.automation,task);
            if(finalMatches<0&&!result.optBoolean("oracle_success"))result.put("oracle_success",JSONObject.NULL);
            if(result.optBoolean("oracle_success")){
                // A real user reads the panel before answering: give the runtime up to verifyWaitMs
                // on the reached goal to offer its own verified result before confirming.
                long verifyWait=Long.parseLong(args.getString("verifyWaitMs","0")),goalAt=SystemClock.elapsedRealtime();
                result.put("verify_wait_ms",verifyWait);
                while(!current.runtimeVerifiedOffer&&SystemClock.elapsedRealtime()-goalAt<verifyWait&&current.error==null){
                    instrumentation.runOnMainSync(current::poll);SystemClock.sleep(100);
                }
                result.put("runtime_verified_after_goal_ms",current.runtimeVerifiedOffer?SystemClock.elapsedRealtime()-goalAt:JSONObject.NULL);
                // Truthful simulated user: confirm only what the independent oracle observed.
                long until=SystemClock.elapsedRealtime()+5000;
                while(!current.confirmed&&current.confirmFailure==null&&SystemClock.elapsedRealtime()<until){
                    instrumentation.runOnMainSync(current::confirm);SystemClock.sleep(250);
                }
                result.put("user_confirmation",current.confirmed?"confirmed":current.confirmFailure!=null?"failed":"controller_busy");
                if(current.confirmed&&!"A".equals(arm)){
                    long ingestUntil=SystemClock.elapsedRealtime()+6000;boolean ingested=false;
                    while(!ingested&&SystemClock.elapsedRealtime()<ingestUntil){ingested=confirmedTasks(arm)>confirmedBefore;if(!ingested)SystemClock.sleep(200);}
                    result.put("experience_ingested",ingested);
                }
            }
            result.put("elapsed_ms",elapsed).put("outcome",result.optBoolean("oracle_success")?"goal_observed":current.error!=null?"controller_error"
                    :stoppedForOffer?"runtime_offer_without_goal":finalMatches<0?"oracle_unobservable":"budget_exhausted")
                .put("runtime_verified_offer",current.runtimeVerifiedOffer)
                .put("false_verified_offer",finalMatches<0||finalMatches>1?JSONObject.NULL:current.runtimeVerifiedOffer&&finalMatches==0&&!result.optBoolean("oracle_success"))
                .put("meta_arm",args.getString("metaArm","CURRENT")).put("condition",current.condition).put("first_step_memory_enabled",current.firstStep).put("fault_injected",current.injected).put("delayed_dispatch_accepted",current.delayedAccepted).put("recovery_count",current.controller.metaController().diagnostic().optInt("recovery_count")).put("observed_model_calls",current.modelCalls).put("observed_model_wait_ms",current.modelWait)
                .put("proposals_by_source",current.proposalSources).put("approvals_by_source",current.approvalSources)
                .put("native_acceptances",current.nativeAcceptances).put("native_rejections",current.nativeRejections)
                .put("first_proposal_ms",current.firstProposalAt<0?JSONObject.NULL:current.firstProposalAt-started)
                .put("terminal_runtime_status",current.latest==null?"none":current.latest.optString("status"))
                .put("runtime_failure",current.error==null?JSONObject.NULL:current.error);
            synchronized(current.acceptedPublicTargets){result.put("accepted_native_public_targets",new JSONArray(current.acceptedPublicTargets));}
            String experience=current.memory.experienceId();
            post("/experiment/register",new JSONObject().put("runtime_task_id",current.runtimeTaskId).put("arm",arm)
                .put("task_id",task.id).put("goal_variant",mirror?"mirror":"original").put("trial_id",trialId)
                .put("experience_task_id",experience.equals(current.runtimeTaskId)?JSONObject.NULL:experience));
        }catch(SetupFailure failed){
            result.put("setup_status","failed").put("failure_reason",failed.getMessage()).put("setup_elapsed_ms",SystemClock.elapsedRealtime()-setupAt).put("outcome","setup_failure");
        }catch(Exception failed){
            result.put("setup_status","error").put("failure_reason",failed.getClass().getSimpleName()+":"+failed.getMessage()).put("setup_elapsed_ms",SystemClock.elapsedRealtime()-setupAt).put("outcome","harness_error");
        }finally{
            if(driver!=null){final Driver current=driver;instrumentation.runOnMainSync(current::close);}
            if("chrome_new_tab".equals(task.id)&&driver!=null&&driver.nativeAcceptances>0&&result.optBoolean("oracle_success")&&service()!=null){
                service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);SystemClock.sleep(250);
            }
            // Harness-only fixture restore: the clock app reopens on its last tab, and the
            // timer tab's number pickers are not a public start window for the next trial.
            if(CLOCK.equals(task.pkg)){result.put("fixture_restore_alarm_tab",clickPublic(session.automation,CLOCK,"알람"));SystemClock.sleep(400);}
            if(service()!=null)service().performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            SystemClock.sleep(200);
        }
        result.put("setup_trace",trace==null?JSONObject.NULL:trace.json());
        return result;
    }

    private int confirmedTasks(String arm)throws Exception {
        if("A".equals(arm))return 0;JSONObject arms=get("/experiment/status").optJSONObject("arms");
        return arms==null||arms.optJSONObject(arm)==null?0:arms.getJSONObject(arm).optInt("confirmed_tasks");
    }
    private JSONObject get(String path)throws Exception {return http("GET",path,null);}
    private JSONObject post(String path,JSONObject body)throws Exception {return http("POST",path,body);}
    private JSONObject http(String method,String path,JSONObject body)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(BASE+path).openConnection();
        try{
            c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setUseCaches(false);
            c.setRequestProperty("Authorization",AUTH);
            if(body!=null){byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(data.length);
                try(OutputStream out=c.getOutputStream()){out.write(data);}}
            int code=c.getResponseCode();if(code!=200)throw new IOException("facade_http_"+code);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){byte[] b=new byte[2048];int n;while((n=in.read(b))>=0){bytes.write(b,0,n);if(bytes.size()>65536)throw new IOException("facade_response_too_large");}}
            return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
        }finally{c.disconnect();}
    }

    private final class Driver implements AppTaskController.Listener {
        final Task task;final String goal,arm,runId;final AppTaskStore store;
        final List<String> acceptedPublicTargets=Collections.synchronizedList(new ArrayList<>());
        final Set<String> approvalsSeen=new HashSet<>();final AtomicBoolean closed=new AtomicBoolean();
        final Set<JSONObject> seenDiagnostics=Collections.newSetFromMap(new IdentityHashMap<>());
        final JSONObject proposalSources=new JSONObject(),approvalSources=new JSONObject();
        AppTaskController controller;AppTaskMemory memory;JSONObject latest;String latestSource="none";
        String fingerprint,error,runtimeTaskId;volatile String confirmFailure;volatile boolean confirmed;boolean runtimeVerifiedOffer;
        int modelCalls,nativeAcceptances,nativeRejections;long modelWait,startedAt,firstProposalAt=-1;
        final String condition;final boolean firstStep;boolean injected,delayedAccepted;final Handler delayed=new Handler(Looper.getMainLooper());
        Driver(Task task,String goal,String arm,AppTaskStore store,String runId,String sessionName){this.task=task;this.goal=goal;this.arm=arm;this.store=store;this.runId=runId;
            firstStep=Boolean.parseBoolean(args.getString("firstStepCostExperiment","false"))&&"C".equals(arm)&&sessionName.startsWith("eval");
            condition=sessionName.equals("eval_no_effect")?"accepted_no_effect":sessionName.equals("eval_delayed")?"delayed_transition":"normal";}
        void create(){try{
            memory=new AppTaskMemory(goal);runtimeTaskId=memory.id();
            boolean learning=!"A".equals(arm);
            controller=new AppTaskController(new AppGuideGateway(app),memory,learning?store:null,this,true,learning,true,AppTaskMetaController.Arm.valueOf(args.getString("metaArm","CURRENT")),true,firstStep);
        }catch(Exception failed){error=failed.getClass().getSimpleName();}}
        void start(){try{
            controller.targetApp(task.pkg,task.pkg);fingerprint=AppTaskObservation.capture(fresh()).optString("fingerprint");controller.refresh();
        }catch(Exception failed){error=failed.getClass().getSimpleName();}}
        void poll(){try{
            if(controller==null||closed.get())return;
            ScreenSnapshot screen=fresh();String next=AppTaskObservation.capture(screen).optString("fingerprint");
            if(!next.equals(fingerprint)){fingerprint=next;controller.screenChanged(screen);}
            JSONObject response=latest;if(response==null)return;JSONObject action=response.optJSONObject("action");
            if("proposed".equals(response.optString("status"))&&action!=null&&approvalsSeen.add(action.optString("id"))){
                increment(approvalSources,latestSource);controller.execute(action.optString("id"),null);
            }
        }catch(Exception failed){error=failed.getClass().getSimpleName();}}
        void confirm(){
            if(confirmed||confirmFailure!=null||controller==null||closed.get())return;
            controller.confirmResultOnServer(()->confirmed=true,failed->confirmFailure=failed.getClass().getSimpleName());
        }
        void close(){closed.set(true);delayed.removeCallbacksAndMessages(null);if(controller!=null)controller.close();}
        private void increment(JSONObject counts,String key){try{counts.put(key,counts.optInt(key)+1);}catch(JSONException ignored){}}
        private String source(JSONObject diagnostic){
            if(diagnostic==null)return "none";
            if(diagnostic.optBoolean("called"))return "planner_model";
            String reason=diagnostic.optString("reason"),model=diagnostic.optString("model");
            if("ai2_remembered".equals(reason))return "memory_server_ai2";
            if("retained_experience".equals(reason))return "memory_device_ai2_recall";
            if("verified_route".equals(reason))return "memory_device_route_cache";
            if("public_control_match".equals(reason))return "local_label_match";
            if("requested_app_entry".equals(reason))return "requested_app_entry";
            return "other_"+(reason.isEmpty()?model:reason);
        }
        public ScreenSnapshot fresh(){return ClickAccessibilityService.freshSnapshot();}
        public boolean canObserve(){return !closed.get();}
        public void busy(boolean value){}
        public void present(JSONObject response,ScreenSnapshot submitted){
            latest=response;
            JSONObject diagnostic=response.optJSONObject("diagnostic");
            if("proposed".equals(response.optString("status"))){
                latestSource=source(diagnostic);increment(proposalSources,latestSource);
                if(firstProposalAt<0)firstProposalAt=SystemClock.elapsedRealtime();
            }
            JSONObject assessment=response.optJSONObject("result_assessment");
            if("confirm_result".equals(response.optString("status"))&&assessment!=null&&"verified".equals(assessment.optString("status")))runtimeVerifiedOffer=true;
            if(diagnostic!=null&&diagnostic.optBoolean("called")&&seenDiagnostics.add(diagnostic)){modelCalls++;modelWait+=diagnostic.optLong("latency_ms");}
        }
        public void failure(Throwable failed){error=failed.getClass().getSimpleName();}
        public boolean inputMatches(String target,String expectedHash){return false;}
        public String sessionId(){return "A".equals(arm)?null:"memexp-"+arm+"-"+runId;}
        public String scope(String pkg){
            try{android.content.pm.PackageInfo info=app.getPackageManager().getPackageInfo(pkg,0);
                return pkg+"|"+info.getLongVersionCode()+"|"+Build.VERSION.SDK_INT+"|app-task.v2";}
            catch(Exception e){return pkg+"|app-task.v2";}
        }
        public boolean execute(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String input,String selectedPackage){
            String tool=action.optString("tool");JSONObject target=AppTaskObservation.find(observation,action.optString("target_id"));
            String publicTarget=target==null?"":publicLabel(target.optString("label"));
            boolean samePublic=task.pkg.equals(observation.optString("package"))&&"public".equals(observation.optString("status"));
            boolean safeTap=samePublic&&"tap".equals(tool)&&NAVIGATION_LABELS.contains(publicTarget);
            boolean safeOpen="open_app".equals(tool)&&task.pkg.equals(selectedPackage)&&task.pkg.equals(action.optString("package"));
            boolean safeNavigation=samePublic&&("back".equals(tool)||("scroll_forward".equals(tool)||"scroll_backward".equals(tool))&&target!=null&&target.optBoolean("scrollable"));
            if(!safeTap&&!safeOpen&&!safeNavigation){nativeRejections++;return false;}
            if(safeTap&&!injected&&!condition.equals("normal")){
                injected=true;
                if(condition.equals("delayed_transition"))delayed.postDelayed(()->{
                    if(closed.get())return;
                    delayedAccepted=AppTaskExecutor.execute(service(),baseline,fresh(),observation,action,null,selectedPackage);
                    if(delayedAccepted){nativeAcceptances++;acceptedPublicTargets.add(publicTarget);}
                },5500);
                return true; // controlled executor acknowledgement, never reported as a native dispatch
            }
            boolean accepted=AppTaskExecutor.execute(service(),baseline,fresh(),observation,action,null,selectedPackage);
            if(!accepted)nativeRejections++;else{nativeAcceptances++;if("tap".equals(tool)&&!publicTarget.isEmpty())acceptedPublicTargets.add(publicTarget);}
            return accepted;
        }
    }
    private void write(String name,String text,boolean append)throws IOException {
        File directory=new File(app.getFilesDir(),"memory-reuse");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Cannot create experiment directory");
        try(Writer out=new OutputStreamWriter(new FileOutputStream(new File(directory,name),append),StandardCharsets.UTF_8)){out.write(text);}
    }

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
    private static final class SetupFailure extends Exception {SetupFailure(String reason){super(reason);}}
}
