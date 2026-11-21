package com.click.service.overlay;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.click.data.remote.AppGuideGateway;
import com.click.data.remote.AppTaskDiagnostics;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystem;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

/**
 * Opt-in closed loop: production controller/policy/verifier with a synthetic UI.
 * Controlled mode is a topology-aware test double, never an efficacy experiment.
 * Live mode sends every evolving observation to the actual local AppTaskService.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class, instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
public class MetaControllerClosedLoopExperimentTest {
    static final String PKG="org.click.experiment.fixture", RES=PKG+":id/";
    static final String VERSION="click.synthetic-closed-loop.v2";
    static final long EPISODE_LIMIT_MS=14000;
    private static final Flow[] FLOWS={
        new Flow("nav_settings","환경 화면을 열어줘","환경","환경 화면",false,false),
        new Flow("nav_display","디스플레이 화면을 열어줘","디스플레이","디스플레이 화면",false,false),
        new Flow("select_history","기록 탭을 선택해줘","기록","기록",true,false),
        new Flow("open_search","검색 화면을 열어줘","검색","검색 화면",false,false),
        new Flow("nested_advanced","고급 화면을 열어줘","메뉴","고급 화면",false,true),
        new Flow("nav_help","도움말 화면을 열어줘","도움말","도움말 화면",false,false)
    };
    private enum Condition { normal, accepted_no_effect, delayed_transition }

    @Test public void runFrozenMatchedMatrix() throws Exception {
        String output=System.getenv("CLICK_META_CLOSED_LOOP_OUTPUT");
        Assume.assumeTrue("Opt in with CLICK_META_CLOSED_LOOP_OUTPUT", output!=null&&!output.isBlank());
        String mode=System.getenv().getOrDefault("CLICK_META_CLOSED_LOOP_MODE","controlled");
        assertTrue("mode must be controlled or live",Set.of("controlled","live").contains(mode));
        String endpoint=System.getenv("CLICK_META_CLOSED_LOOP_ENDPOINT");
        if(mode.equals("live"))assertNotNull("Live mode requires explicit loopback endpoint",endpoint);
        Set<String> wantedTasks=selection("CLICK_META_CLOSED_LOOP_TASKS");
        Set<String> wantedConditions=selection("CLICK_META_CLOSED_LOOP_CONDITIONS");
        List<Flow> flows=new ArrayList<>();for(Flow f:FLOWS)if(wantedTasks.isEmpty()||wantedTasks.contains(f.id))flows.add(f);
        assertEquals("Unknown selected task",wantedTasks.size(),wantedTasks.isEmpty()?0:flows.size());
        List<Condition> conditions=new ArrayList<>();for(Condition c:Condition.values())if(wantedConditions.isEmpty()||wantedConditions.contains(c.name()))conditions.add(c);
        assertEquals("Unknown selected condition",wantedConditions.size(),wantedConditions.isEmpty()?0:conditions.size());
        int repeats=Integer.parseInt(System.getenv().getOrDefault("CLICK_META_CLOSED_LOOP_REPETITIONS","2"));
        assertTrue("Repetitions must be 1..10",repeats>=1&&repeats<=10);
        int expectedEpisodes=flows.size()*conditions.size()*repeats*2;
        Path dir=Path.of(output);Files.createDirectories(dir);
        Path episodes=dir.resolve("episodes.jsonl");
        assertFalse("Refuse to overwrite a previous run",Files.exists(episodes));
        JSONObject gates=readiness();
        Files.write(dir.resolve("readiness.json"),gates.toString(2).getBytes(StandardCharsets.UTF_8));
        List<JSONObject> rows=new ArrayList<>();
        int pair=0;
        for(Flow flow:flows)for(Condition condition:conditions)for(int rep=0;rep<repeats;rep++){
            String pairId=flow.id+"/"+condition+"/"+rep;
            AppTaskMetaController.Arm[] order=(pair++%2==0)
                ?new AppTaskMetaController.Arm[]{AppTaskMetaController.Arm.CURRENT,AppTaskMetaController.Arm.META}
                :new AppTaskMetaController.Arm[]{AppTaskMetaController.Arm.META,AppTaskMetaController.Arm.CURRENT};
            for(int position=0;position<order.length;position++){
                JSONObject row=episode(flow,condition,rep,pairId,position,order[position],mode,endpoint);
                rows.add(row);
                Files.write(episodes,(row+"\n").getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
            }
            assertEquals("A/B initial state must match",rows.get(rows.size()-2).getString("initial_state_key"),rows.get(rows.size()-1).getString("initial_state_key"));
        }
        JSONObject manifest=new JSONObject().put("schema_version",VERSION).put("mode",mode)
            .put("episode_count",rows.size()).put("matched_pairs",expectedEpisodes/2).put("readiness_passed",true)
            .put("run_scope",flows.size()==6&&conditions.size()==3&&repeats==2?"primary_full_matrix":"preflight_subset")
            .put("selected_tasks",new JSONArray(flows.stream().map(f->f.id).toArray()))
            .put("selected_conditions",new JSONArray(conditions.stream().map(c->c.name()).toArray())).put("repetitions",repeats)
            .put("real_apps_tested",false).put("learning_enabled",false).put("shared_common_fixes",true)
            .put("ui_clock","Robolectric paused; modeled UI waits")
            .put("model_clock","host monotonic wall time; not added to virtual UI clock")
            .put("controlled_model_calls",0).put("request_join","direct_request_and_task_sha256_prefix24")
            .put("model_call_count_rule","Observed calls are a lower bound whenever any request has unknown call state")
            .put("analysis_script","Backend/scripts/summarize_meta_controller_closed_loop.py");
        Files.write(dir.resolve("run.json"),manifest.toString(2).getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedEpisodes,rows.size());
        for(JSONObject row:rows){
            assertEquals("False goal verification",0,row.getInt("false_verified_offers"));
            assertEquals("Unsafe execution",0,row.getInt("guard_violations"));
        }
    }

    /** Interaction conformance audit; recall is a controlled fixture, not measured training. */
    @Test public void runMemoryInteractionMatrix() throws Exception {
        String output=System.getenv("CLICK_META_MEMORY_OUTPUT");
        Assume.assumeTrue("Opt in with CLICK_META_MEMORY_OUTPUT",output!=null&&!output.isBlank());
        Path dir=Path.of(output);Files.createDirectories(dir);Path rows=dir.resolve("episodes.jsonl");
        assertFalse("Refuse to overwrite a previous run",Files.exists(rows));
        Files.write(dir.resolve("readiness.json"),readiness().toString(2).getBytes(StandardCharsets.UTF_8));int count=0;
        for(Flow flow:FLOWS)for(Condition condition:Condition.values())for(int rep=0;rep<2;rep++)
            for(boolean remembered:new boolean[]{false,true})for(AppTaskMetaController.Arm arm:
                new AppTaskMetaController.Arm[]{AppTaskMetaController.Arm.CURRENT,AppTaskMetaController.Arm.META}){
                JSONObject row=episode(flow,condition,rep,flow.id+"/"+condition+"/"+rep,0,arm,"controlled",null,remembered);
                row.put("memory_enabled",remembered).put("memory_source","controlled_grounded_recall_fixture").put("training_measured",false);
                Files.write(rows,(row+"\n").getBytes(StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
                assertEquals(0,row.getInt("false_verified_offers"));assertEquals(0,row.getInt("guard_violations"));count++;
            }
        assertEquals(144,count);
        Files.write(dir.resolve("run.json"),new JSONObject().put("episodes",count)
            .put("real_device",false).put("real_model_calls",0).put("training_measured",false)
            .put("design","6 flows x 3 conditions x 2 repetitions x A/B x memory off/on")
            .put("scope","production Android controller, verifier and MCv2; controlled planner and recall fixture").toString(2).getBytes(StandardCharsets.UTF_8));
    }

    @Test public void blockedRememberedActionFallsThroughToRecoveryPlanner() throws Exception {
        JSONObject row=episode(FLOWS[1],Condition.accepted_no_effect,0,"remembered-recovery",0,
            AppTaskMetaController.Arm.META,"controlled",null,true);
        assertTrue("Remembered blocked action must not swallow replanning",row.getBoolean("independent_goal_reached"));
        assertTrue("Recovery must call planner after excluding old memory",row.getInt("planner_requests")>=2);
        assertEquals(1,row.getInt("recovery_count"));assertEquals(0,row.getInt("false_verified_offers"));
    }

    private static JSONObject readiness() throws Exception {
        int positive=0,negative=0,unsafeMenus=0;
        for(Flow flow:FLOWS){
            World w=new World(flow,Condition.normal);
            assertEquals("Initial menu must not satisfy the goal: "+flow.id,
                AppTaskCompletion.Verdict.NOT_MET,AppTaskCompletion.checkForGoal(flow.goal,flow.completion(),AppTaskObservation.capture(w.fresh()),w.fresh()));negative++;
            JSONObject unsafeMenu=new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("package",PKG).put("label",flow.primary)
                .put("resource",RES+(flow.selection?"history":"primary")).put("role",flow.selection?"tab":"button")));
            assertEquals("Visible navigation control must not count as arrival: "+flow.id,
                AppTaskCompletion.Verdict.UNKNOWN,AppTaskCompletion.checkForGoal(flow.goal,unsafeMenu,AppTaskObservation.capture(w.fresh()),w.fresh()));unsafeMenus++;
            w.state="goal";
            assertEquals("Destination must satisfy the goal: "+flow.id,
                AppTaskCompletion.Verdict.MATCHED,AppTaskCompletion.checkForGoal(flow.goal,flow.completion(),AppTaskObservation.capture(w.fresh()),w.fresh()));positive++;
        }
        World w=new World(FLOWS[1],Condition.normal);
        JSONObject before=AppTaskObservation.capture(w.fresh()), action=plan(request(w,FLOWS[1]),FLOWS[1]).getJSONObject("action");
        assertTrue("Same-frame canonical projection must pass",AppTaskPolicy.allowed(before,AppTaskObservation.capture(w.fresh()),action,PKG));
        w.state="goal";
        assertFalse("Changed frame must reject",AppTaskPolicy.allowed(before,AppTaskObservation.capture(w.fresh()),action,PKG));
        ScreenSnapshot protectedScreen=new ScreenSnapshot(PKG,Instant.ofEpochMilli(clock()).toString(),1080,2400,
            List.of(new AccessibilityNodeSnapshot("private",null,true,PKG,"secret",null,RES+"password","android.widget.EditText",null,new int[]{20,100,900,200},false,true,false,false,false,null,42,true)),42);
        assertFalse("Protected frame must reject",AppTaskPolicy.allowed(before,AppTaskObservation.capture(protectedScreen),action,PKG));
        // Exercise the actual controller's notion of wall time, not a forged future capture.
        World probe=new World(FLOWS[1],Condition.normal);AppGuideGateway gateway=mock(AppGuideGateway.class);
        doAnswer(call->{((AppGuideGateway.Callback)call.getArgument(1)).success(plan(call.getArgument(0),FLOWS[1]));return null;}).when(gateway).send(any(),any());
        AppTaskController controller=new AppTaskController(gateway,new AppTaskMemory(FLOWS[1].goal),null,probe,true,false,true,AppTaskMetaController.Arm.CURRENT);
        probe.controller=controller;controller.targetApp(PKG,"실험 화면");controller.refresh();
        java.lang.reflect.Field proposed=AppTaskController.class.getDeclaredField("proposedAt");proposed.setAccessible(true);
        assertTrue("Robolectric app/test clocks differ; do not run invalid timed episodes",Math.abs(proposed.getLong(controller)-clock())<100);
        controller.close();
        return new JSONObject().put("schema_version",VERSION).put("passed",true).put("positive_goal_controls",positive)
            .put("negative_goal_controls",negative).put("unsafe_menu_controls",unsafeMenus).put("same_frame",true).put("changed_frame",true).put("protected_frame",true).put("consistent_clock",true);
    }

    private static JSONObject episode(Flow flow,Condition condition,int repetition,String pairId,int order,
                                     AppTaskMetaController.Arm arm,String mode,String endpoint) throws Exception {
        return episode(flow,condition,repetition,pairId,order,arm,mode,endpoint,false);
    }
    private static JSONObject episode(Flow flow,Condition condition,int repetition,String pairId,int order,
            AppTaskMetaController.Arm arm,String mode,String endpoint,boolean remembered) throws Exception {
        long started=SystemClock.uptimeMillis(),hostStarted=System.nanoTime();
        World w=new World(flow,condition);String initial=AppTaskObservation.capture(w.fresh()).getString("fingerprint");
        AppGuideGateway gateway=mock(AppGuideGateway.class);Stats stats=new Stats();
        doAnswer(call->{
            JSONObject req=call.getArgument(0);AppGuideGateway.Callback callback=call.getArgument(1);stats.requests++;
            JSONObject trace=new JSONObject().put("request_ref",reference(req.getString("request_id")))
                .put("task_ref",reference(req.getJSONObject("state").getString("task_id")))
                .put("http_status",JSONObject.NULL).put("error_code",JSONObject.NULL)
                .put("outcome","started").put("model_call_state","unknown")
                .put("model_calls",JSONObject.NULL).put("model_wait_ms",JSONObject.NULL);
            stats.traces.put(trace);
            try{
                JSONObject response;
                if(mode.equals("live")){
                    long begin=System.nanoTime();
                    try{response=liveRequest(endpoint,req,trace);}finally{stats.transportWallMs+=(System.nanoTime()-begin)/1_000_000.0;}
                    boolean bound=applyResponseTrace(req,response,trace);
                    JSONObject diagnostic=bound?response.optJSONObject("diagnostic"):null;
                    if(diagnostic!=null&&"observed".equals(trace.optString("model_call_state"))){
                        stats.modelCalls++;double wait=trace.optDouble("model_wait_ms",Double.NaN);
                        if(Double.isFinite(wait)&&wait>=0&&wait<=120000)stats.modelWaitMs+=wait;
                        String name=diagnostic.optString("model","");if(name.matches("gemini-[a-zA-Z0-9.-]{1,100}"))stats.model=name;
                    }
                    if(diagnostic!=null&&(Set.of("configuration","provider","parse","plan").contains(diagnostic.optString("failure_kind"))||"unavailable".equals(response.optString("status"))))stats.providerOrPlanErrors++;
                }else{response=plan(req,flow);
                    // Controlled planner never claims the goal; goal-first requests require an explicit assessment.
                    if("goal_first".equals(req.optString("planning_mode"))){JSONObject d=response.optJSONObject("diagnostic");if(d==null)response.put("diagnostic",d=new JSONObject());if(!d.has("goal_assessment"))d.put("goal_assessment","not_satisfied");}
                    trace.put("outcome","controlled_response").put("model_call_state","not_started").put("model_calls",0).put("model_wait_ms",0);}
                AppGuideGateway.validateTask(req,response);callback.success(response);
            }catch(Exception error){
                if(Set.of("started","service_response","controlled_response").contains(trace.optString("outcome")))trace.put("outcome","response_validation_failed").put("error_code","response_validation_failed");
                stats.errors++;callback.failure(error);
            }
            return null;
        }).when(gateway).send(any(),any());
        com.click.data.local.AppTaskStore store=remembered?mock(com.click.data.local.AppTaskStore.class):null;
        if(remembered)doAnswer(call->{
            JSONObject req=call.getArgument(0),screen=req.getJSONObject("screen");
            JSONObject clean=new JSONObject(req.toString());clean.getJSONObject("state").put("attempts",new JSONArray());
            JSONObject action=plan(clean,flow).optJSONObject("action");
            JSONObject reply=new JSONObject().put("protocol_version","app-task-memory.v1")
                .put("request_id",req.getString("request_id")).put("observation_id",screen.getString("observation_id"))
                .put("fingerprint",screen.getString("fingerprint")).put("status",action==null?"needs_data":"remembered")
                .put("collection_enabled",true).put("consent_id","12345678-1234-1234-1234-123456789abc")
                .put("consent_recorded_at_ms",clock()-1000).put("requires_confirmation",true)
                .put("action",action==null?JSONObject.NULL:action).put("evidence",new JSONArray());
            ((AppGuideGateway.Callback)call.getArgument(1)).success(reply);return null;
        }).when(gateway).recall(any(),any());
        AppTaskController controller=new AppTaskController(gateway,new AppTaskMemory(flow.goal),store,w,true,remembered,true,arm);
        w.controller=controller;controller.targetApp(PKG,"실험 화면");
        try{
            controller.refresh();Set<String> approved=new HashSet<>();
            while(SystemClock.uptimeMillis()-started<EPISODE_LIMIT_MS){
                JSONObject response=w.latest;
                if(response!=null&&"proposed".equals(response.optString("status"))){
                    JSONObject a=response.optJSONObject("action");
                    if(a!=null&&approved.add(a.optString("id")))controller.execute(a.getString("id"),null);
                }
                if(w.verifiedAt>=0)break;
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
                // A needs_input may later receive a real synthetic screen event; keep observing
                // delayed effects in BOTH arms instead of declaring A a failure at five seconds.
                if(w.pendingEvents==0&&w.latest!=null&&Set.of("needs_input","unavailable","protected").contains(w.latest.optString("status")))break;
            }
            JSONObject meta=controller.metaController().diagnostic();
            return new JSONObject().put("schema_version",VERSION).put("evidence_mode",mode.equals("live")?"live_model_synthetic_closed_loop":"controlled_full_controller_conformance")
                .put("task",flow.id).put("condition",condition.name()).put("repetition",repetition).put("pair_id",pairId).put("arm_order",order)
                .put("task_ref",reference(controller.memory().id())).put("request_traces",stats.traces)
                .put("arm",arm==AppTaskMetaController.Arm.CURRENT?"A":"B").put("initial_state_key",initial)
                .put("setup_valid",true).put("independent_goal_reached",w.goal()).put("runtime_verified_offer",w.verifiedAt>=0)
                .put("false_verified_offers",w.falseVerified).put("guard_violations",w.guardViolations)
                .put("remembered_proposals",w.rememberedProposals).put("canonical_rejections",w.canonicalRejections).put("accepted_actions",w.accepted).put("attempted_actions",w.attempted)
                .put("unsupported_tools",w.unsupportedTools)
                .put("primary_action_seen",!w.firstPrimary).put("fault_injected",condition!=Condition.normal&&!w.firstPrimary)
                .put("delayed_effect_applied",w.delayedEffectApplied)
                .put("planner_requests",stats.requests).put("controlled_planner_requests",mode.equals("controlled")?stats.requests:0)
                .put("actual_model_calls",mode.equals("live")?stats.modelCalls:0).put("actual_model_wait_ms",stats.modelWaitMs)
                .put("recorded_model_calls",mode.equals("live")?stats.modelCalls:0)
                .put("unknown_model_call_requests",unknownCalls(stats.traces)).put("model_call_count_complete",unknownCalls(stats.traces)==0)
                .put("actual_model",stats.model.isEmpty()?JSONObject.NULL:stats.model).put("transport_wall_ms",stats.transportWallMs)
                .put("modeled_ui_elapsed_ms",SystemClock.uptimeMillis()-started)
                .put("modeled_ui_ms_to_verified_offer",w.verifiedAt<0?JSONObject.NULL:w.verifiedAt-started)
                .put("host_total_wall_ms",(System.nanoTime()-hostStarted)/1_000_000.0)
                .put("needs_input_presentations",w.handoffs).put("recovery_count",meta.optInt("recovery_count"))
                .put("gateway_errors",stats.errors).put("provider_or_plan_errors",stats.providerOrPlanErrors).put("listener_errors",w.errors)
                .put("final_status",w.latest==null?"none":w.latest.optString("status"))
                .put("final_world_state",w.state).put("memory_attempts",controller.memory().state().getJSONArray("attempts"));
        }finally{controller.close();w.closed=true;w.handler.removeCallbacksAndMessages(null);}
    }

    /** Deterministic topology-aware control. Never supplied to Gemini. */
    private static JSONObject plan(JSONObject request,Flow flow) throws Exception {
        JSONObject screen=request.getJSONObject("screen"),state=request.getJSONObject("state");
        JSONArray nodes=screen.getJSONArray("nodes"),attempts=state.getJSONArray("attempts");
        String resource=flow.selection?RES+"history":RES+"primary";boolean failed=false;
        for(int i=0;i<attempts.length();i++)if("failed".equals(attempts.getJSONObject(i).optString("outcome")))failed=true;
        if(failed)resource=RES+"alternate";
        else for(int i=0;i<nodes.length();i++)if((RES+"advanced").equals(nodes.getJSONObject(i).optString("resource")))resource=RES+"advanced";
        JSONObject target=null;for(int i=0;i<nodes.length();i++)if(resource.equals(nodes.getJSONObject(i).optString("resource")))target=nodes.getJSONObject(i);
        JSONObject result=new JSONObject().put("protocol_version","app-task.v2").put("request_id",request.getString("request_id"))
            .put("task_id",state.getString("task_id")).put("observation_id",screen.getString("observation_id"))
            .put("fingerprint",screen.getString("fingerprint")).put("requires_confirmation",true).put("status",target==null?"needs_input":"proposed")
            .put("message","실험용 공개 항목을 확인합니다.").put("subgoals",new JSONArray().put(new JSONObject().put("id","g0").put("title",flow.goal).put("status","pending")))
            .put("completion",state.optJSONObject("completion")==null?flow.completion():state.getJSONObject("completion"))
            .put("diagnostic",new JSONObject().put("called",false).put("reason","explicit_controlled_topology_test_double"));
        if(target!=null){
            boolean intermediate=flow.nested&&(RES+"primary").equals(resource);
            JSONObject expectation=new JSONObject().put("kind",flow.selection?"selection_changed":"target_visible");
            if(flow.selection)expectation.put("selected",true);else expectation.put("label",intermediate?"고급":flow.destination);
            result.put("action",new JSONObject().put("id",UUID.randomUUID().toString()).put("tool","tap").put("target_id",target.getString("id"))
                .put("bounds",target.getJSONArray("bounds")).put("package",PKG).put("expectation",expectation).put("advances_subgoal",!intermediate));
        }
        return result;
    }

    private static JSONObject request(World w,Flow flow)throws Exception{return new JSONObject().put("protocol_version","app-task.v2").put("request_id",UUID.randomUUID().toString())
        .put("goal",flow.goal).put("screen",AppTaskObservation.capture(w.fresh())).put("state",new AppTaskMemory(flow.goal).state()).put("apps",new JSONArray());}

    private static Set<String> selection(String name){
        String raw=System.getenv().getOrDefault(name,"");Set<String> values=new java.util.LinkedHashSet<>();
        for(String value:raw.split(","))if(!value.trim().isEmpty())values.add(value.trim());return values;
    }
    private static String reference(String id){return AppTaskObservation.hash(id).substring(0,24);}
    private static int unknownCalls(JSONArray traces){int count=0;for(int i=0;i<traces.length();i++)if("unknown".equals(traces.optJSONObject(i).optString("model_call_state")))count++;return count;}
    private static String safeCode(String code){return Set.of("not_found","test_authorization_required","bounded_content_length_required","body_too_large",
        "json_body_required","request_read_timeout","invalid_app_task_request","outside_frozen_public_fixture","reference_mismatch",
        "synthetic_experiment_service_error","synthetic_experiment_internal_error","service_identity_mismatch").contains(code)?code:"http_error_unclassified";}
    static boolean applyResponseTrace(JSONObject request,JSONObject response,JSONObject trace)throws Exception{
        boolean bound=AppTaskDiagnostics.identityBound(request,response);
        trace.put("response_identity_bound",bound);
        if(!bound){
            trace.put("model_call_state","unknown").put("model_calls",JSONObject.NULL).put("model_wait_ms",JSONObject.NULL);
            for(String key:List.of("planning_mode","failure_kind","failure_stage","reason_code","validation_errors",
                    "completion_grounding","completion_deferred_reason","provider_budget_ms","provider_attempt_limit"))trace.remove(key);
            return false;
        }
        JSONObject safe=AppTaskDiagnostics.receipt(request,response,200,null,0,0,0);
        for(String key:List.of("planning_mode","failure_kind","failure_stage","reason_code","validation_errors",
                "completion_grounding","completion_deferred_reason","provider_budget_ms","provider_attempt_limit"))
            if(safe.has(key))trace.put(key,safe.get(key));
        return true;
    }
    private static JSONObject liveRequest(String endpoint,JSONObject request,JSONObject trace)throws Exception{
        URI uri=URI.create(endpoint);
        if(!"http".equals(uri.getScheme())||!Set.of("127.0.0.1","localhost").contains(uri.getHost())||!AppGuideGateway.TASK_PATH.equals(uri.getPath()))throw new IllegalArgumentException("Only explicit local fixture facade permitted");
        HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();
        connection.setConnectTimeout(3000);connection.setReadTimeout(30000);connection.setRequestMethod("POST");connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Authorization","Bearer local-meta-synthetic-experiment");
        connection.setRequestProperty("X-Click-Request-Ref",trace.getString("request_ref"));
        connection.setRequestProperty("X-Click-Task-Ref",trace.getString("task_ref"));
        // The facade alone translates synthetic captured_at_ms to real provider freshness time.
        byte[] bytes=request.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
        return exchange(connection,bytes,trace);
    }
    /** Injectable connection boundary for deterministic, no-network receipt tests. */
    static JSONObject exchange(HttpURLConnection connection,byte[] bytes,JSONObject trace)throws Exception{
        try{connection.getOutputStream().write(bytes);int status=connection.getResponseCode();trace.put("http_status",status);
            String server=connection.getHeaderField("X-Click-Server-Ref");if(server!=null&&server.matches("[0-9a-f]{24}"))trace.put("server_ref",server);
            try(InputStream stream=status==200?connection.getInputStream():connection.getErrorStream()){
                if(stream==null)throw new java.io.IOException("MISSING_FIXTURE_RESPONSE");
                java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();byte[] chunk=new byte[4096];int count;
                while((count=stream.read(chunk))!=-1){buffer.write(chunk,0,count);if(buffer.size()>131072)throw new java.io.IOException("fixture_response_too_large");}
                JSONObject response=new JSONObject(new String(buffer.toByteArray(),StandardCharsets.UTF_8));
                if(status!=200){
                    String state=response.optString("model_call_state","unknown");if(!Set.of("not_started","observed","unknown").contains(state))state="unknown";
                    trace.put("outcome","http_rejected").put("error_code",safeCode(response.optString("error"))).put("model_call_state",state);
                    if(state.equals("not_started"))trace.put("model_calls",0).put("model_wait_ms",0);
                    throw new java.io.IOException("SYNTHETIC_HTTP_ERROR");
                }
                JSONObject diagnostic=response.optJSONObject("diagnostic");
                trace.put("outcome","service_response");
                if(diagnostic!=null&&diagnostic.opt("called") instanceof Boolean){
                    boolean called=diagnostic.optBoolean("called");trace.put("model_call_state",called?"observed":"not_started").put("model_calls",called?1:0);
                    double wait=diagnostic.optDouble("latency_ms",Double.NaN);
                    trace.put("model_wait_ms",!called?0:Double.isFinite(wait)&&wait>=0&&wait<=120000?wait:JSONObject.NULL);
                }
                return response;
            }
        }catch(java.net.SocketTimeoutException error){trace.put("outcome","transport_timeout").put("error_code","transport_timeout");throw error;
        }catch(java.io.IOException error){if(!"http_rejected".equals(trace.optString("outcome")))trace.put("outcome","transport_error").put("error_code","transport_io_error");throw error;
        }catch(JSONException error){trace.put("outcome","malformed_response").put("error_code","malformed_response");throw error;
        }finally{connection.disconnect();}
    }

    private static long clock(){return ShadowSystem.currentTimeMillis();}
    private static final class Stats{int requests,modelCalls,providerOrPlanErrors,errors;double modelWaitMs,transportWallMs;String model="";final JSONArray traces=new JSONArray();}
    private static final class Flow{
        final String id,goal,primary,destination;final boolean selection,nested;
        Flow(String id,String goal,String primary,String destination,boolean selection,boolean nested){this.id=id;this.goal=goal;this.primary=primary;this.destination=destination;this.selection=selection;this.nested=nested;}
        JSONObject completion()throws Exception{
            JSONObject check=new JSONObject().put("package",PKG).put("label",destination).put("resource",RES+(selection?"history":"goal_title")).put("role",selection?"tab":"text");
            if(selection)check.put("selected",true);return new JSONObject().put("checks",new JSONArray().put(check));
        }
    }

    private static final class World implements AppTaskController.Listener{
        final Flow flow;final Condition condition;final Handler handler=new Handler(Looper.getMainLooper());
        String state="start";boolean pending,firstPrimary=true,closed,delayedEffectApplied;int pendingEvents,attempted,accepted,canonicalRejections,guardViolations,falseVerified,handoffs,errors,unsupportedTools;
        int rememberedProposals;long verifiedAt=-1;JSONObject latest;AppTaskController controller;
        World(Flow flow,Condition condition){this.flow=flow;this.condition=condition;}
        boolean goal(){return state.equals("goal");}
        public ScreenSnapshot fresh(){
            List<AccessibilityNodeSnapshot> nodes=new ArrayList<>();
            if(flow.selection){nodes.add(node("history","기록","tab",true,goal(),false,100));}
            else if(goal())nodes.add(node("goal_title",flow.destination,"text",false,false,true,100));
            else if(state.equals("menu"))nodes.add(node("advanced","고급","button",true,false,false,250));
            else nodes.add(node("primary",flow.primary,"button",true,false,false,250));
            if(!goal())nodes.add(node("alternate","다른 경로","button",true,false,false,450));
            if(pending)nodes.add(node("pending","화면 준비 중","text",false,false,true,650));
            if(goal()&&flow.id.equals("open_search"))nodes.add(new AccessibilityNodeSnapshot("search",null,true,PKG,"",null,RES+"search",
                "android.widget.EditText",null,new int[]{20,300,1000,400},true,true,false,false,false,null,42,true,null,null,false,null,false,true));
            // Static public text also checks default vs goal-label projection consistency.
            nodes.add(node("summary",goal()?"화면을 확인하세요":"원하는 화면을 선택하세요","text",false,false,false,850));
            return new ScreenSnapshot(PKG,Instant.ofEpochMilli(clock()).toString(),1080,2400,nodes,42);
        }
        public boolean canObserve(){return !closed;}public void busy(boolean value){}public boolean inputMatches(String target,String expected){return false;}
        public String sessionId(){return "controlled-memory-session";}
        public void present(JSONObject response,ScreenSnapshot submitted){
            if("proposed".equals(response.optString("status"))&&response.optJSONObject("diagnostic")!=null
                &&"retained_experience".equals(response.optJSONObject("diagnostic").optString("reason")))rememberedProposals++;
            latest=response;
            if("needs_input".equals(response.optString("status")))handoffs++;
            if(response.optJSONObject("result_assessment")!=null&&"verified".equals(response.optJSONObject("result_assessment").optString("status"))){
                if(!goal())falseVerified++;if(verifiedAt<0)verifiedAt=SystemClock.uptimeMillis();
            }
        }
        public void failure(Throwable error){errors++;try{latest=new JSONObject().put("status","needs_input").put("error_type",error.getClass().getSimpleName());}catch(Exception ignored){}}
        public boolean execute(ScreenSnapshot baseline,JSONObject observed,JSONObject action,String input,String selectedPackage){
            attempted++;
            try{
                JSONObject current=AppTaskObservation.recaptureLike(fresh(),observed);
                if(!AppTaskPolicy.allowed(observed,current,action,selectedPackage)){canonicalRejections++;return false;}
                String tool=action.optString("tool");
                if(tool.equals("back")||tool.equals("open_app")&&PKG.equals(action.optString("package"))){
                    accepted++;schedule(120,()->{state="start";pending=false;});return true;
                }
                JSONObject target=AppTaskObservation.find(observed,action.optString("target_id"));
                if(target==null||!"tap".equals(tool)){unsupportedTools++;return false;}
                String resource=target.optString("resource");boolean primary=resource.equals(RES+(flow.selection?"history":"primary"));
                accepted++;
                if(primary&&firstPrimary){firstPrimary=false;
                    if(condition==Condition.accepted_no_effect)return true;
                    if(condition==Condition.delayed_transition){schedule(4800,()->pending=true);schedule(5500,()->{delayedEffectApplied=true;transition(resource);});return true;}
                }
                schedule(120,()->transition(resource));return true;
            }catch(Exception error){failure(error);return false;}
        }
        private void transition(String resource){pending=false;state=flow.nested&&resource.equals(RES+"primary")?"menu":"goal";}
        private void schedule(long ms,Runnable effect){pendingEvents++;handler.postDelayed(()->{pendingEvents--;if(closed)return;effect.run();controller.screenChanged(fresh());},ms);}
    }

    private static AccessibilityNodeSnapshot node(String id,String label,String role,boolean clickable,boolean selected,boolean heading,int y){
        return new AccessibilityNodeSnapshot(id,null,true,PKG,label,null,RES+id,
            role.equals("tab")?"android.widget.TabWidget":clickable?"android.widget.Button":"android.widget.TextView",role,
            new int[]{20,y,1000,y+100},clickable,true,selected,false,false,null,42,false,null,null,false,null,heading,false);
    }
}
