package com.click.service.overlay;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.click.data.local.AppTaskStore;
import com.click.data.remote.AppGuideGateway;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystem;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Model-free closed loop for the planner-avoiding speed paths. The production controller,
 * policy, verifier and result proof run against a synthetic multi-step app; only the planner
 * and memory recall are controlled fixtures with modeled latency. Not a model or device result.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class, instrumentedPackages={"com.click"})
@LooperMode(LooperMode.Mode.PAUSED)
public class AppTaskSpeedExperimentTest {
    static final String PKG="org.click.speed.fixture",RES=PKG+":id/",VERSION="click.speed-closed-loop.v1";
    /** Median live first-planner wait on 2026-11-21 (SM-N981N, Gemini 3.8 Flash): 4.6-5.2 s. */
    static final long PLANNER_MS=4600,RECALL_MS=150,BUDGET_MS=45000,CHURN_AFTER_MS=1200,TIMEOUT_MS=8630;
    static final String[][] FLOWS={
        {"depth1","디스플레이 화면을 열어줘","디스플레이"},
        {"depth2","글자 크기 화면을 열어줘","디스플레이","글자 크기와 스타일"},
        {"depth3","앱 알림 고급 화면을 열어줘","알림","앱 알림","고급 설정"},
        {"depth4","배터리 최적화 화면을 열어줘","배터리","배터리 사용량","고급","배터리 최적화"}};
    enum Condition{normal,churn,prediction_miss,provider_timeout,no_prediction}
    enum Lookahead{accurate,none,absent_label,visible_distractor}

    static final Map<String,AppTaskController.SpeedOptions> ARMS=new LinkedHashMap<>();
    static{
        ARMS.put("base",AppTaskController.SpeedOptions.NONE);
        ARMS.put("reoffer_prediction",new AppTaskController.SpeedOptions(false,false,true,false,false));
        ARMS.put("memory_first",new AppTaskController.SpeedOptions(true,false,false,false,false));
        ARMS.put("memory_first+remembered_result",new AppTaskController.SpeedOptions(true,true,false,false,false));
        ARMS.put("rebind",new AppTaskController.SpeedOptions(false,false,false,true,false));
        ARMS.put("planned_route",new AppTaskController.SpeedOptions(false,false,false,false,true));
        ARMS.put("retry_provider",new AppTaskController.SpeedOptions(false,false,false,false,false,true));
        ARMS.put("production",AppTaskController.SpeedOptions.PRODUCTION);
        ARMS.put("all",AppTaskController.SpeedOptions.ALL);
    }

    // ---- Always-on conformance: the claims the report relies on. ----
    @Test public void plannedRouteReplacesPerStepPlannerCalls()throws Exception{
        JSONObject base=episode(3,Condition.normal,Lookahead.accurate,false,"base"),all=episode(3,Condition.normal,Lookahead.accurate,false,"all");
        assertGoal(base);assertGoal(all);
        assertEquals(3,base.getInt("planner_requests"));assertEquals(1,all.getInt("planner_requests"));
        assertEquals(2,all.getJSONObject("proposals_by_source").optInt("planned_route"));
        assertTrue(all.getLong("ms_to_verified_offer")<base.getLong("ms_to_verified_offer"));
    }
    @Test public void changedFrameRebindKeepsThePaidAnswer()throws Exception{
        JSONObject base=episode(1,Condition.churn,Lookahead.none,false,"base"),rebind=episode(1,Condition.churn,Lookahead.none,false,"rebind");
        assertGoal(base);assertGoal(rebind);
        // Base re-asks for the redrawn frame; both still pay the arrival check (no parking here).
        assertEquals(base.getInt("planner_requests")-1,rebind.getInt("planner_requests"));
        assertEquals(1,rebind.getInt("frame_rebound_proposals"));assertEquals(0,base.getInt("frame_rebound_proposals"));
    }
    @Test public void memoryAndRememberedResultSkipThePlannerOnARepeat()throws Exception{
        JSONObject base=episode(2,Condition.prediction_miss,Lookahead.none,true,"base");
        JSONObject memory=episode(2,Condition.prediction_miss,Lookahead.none,true,"memory_first");
        JSONObject all=episode(2,Condition.prediction_miss,Lookahead.none,true,"all");
        assertGoal(base);assertGoal(memory);assertGoal(all);
        assertEquals(2,base.getInt("planner_requests"));   // gated first step + goal-first check after arrival
        assertEquals(1,memory.getInt("planner_requests")); // arrival still needs the planner to find the result
        assertEquals(0,all.getInt("planner_requests"));    // remembered result proved natively
        assertEquals(2,all.getJSONObject("proposals_by_source").optInt("retained_experience"));
    }
    @Test public void untrustedLookaheadNeverTapsAnotherControl()throws Exception{
        for(Lookahead bad:new Lookahead[]{Lookahead.absent_label,Lookahead.visible_distractor}){
            JSONObject all=episode(3,Condition.normal,bad,false,"all");assertGoal(all);
            assertEquals(bad.name(),0,all.getInt("wrong_taps"));
            assertEquals(bad.name(),0,all.getJSONObject("proposals_by_source").optInt("planned_route"));
            JSONObject base=episode(3,Condition.normal,bad,false,"base");
            assertTrue(bad.name(),all.getInt("planner_requests")<=base.getInt("planner_requests"));
        }
    }
    @Test public void rememberedResultIsOnlyKeptWhenItMatchedTheConfirmedScreen()throws Exception{
        World w=new World(FLOWS[0],Condition.prediction_miss,Lookahead.none,false);
        JSONObject row=run(w,ARMS.get("all"));assertGoal(row);
        // The planner's wrong prediction was replaced by observed evidence before confirmation.
        assertNotNull(w.recorded);assertEquals("디스플레이",w.recorded.getJSONArray("checks").getJSONObject(0).getString("label"));
    }
    @Test public void oneStepDestinationTitleIsProvedLocallyOnArrival()throws Exception{
        // The real Settings case: the destination title equals the menu entry tapped to reach it.
        JSONObject base=episode(1,Condition.normal,Lookahead.none,false,"base"),park=episode(1,Condition.normal,Lookahead.none,false,"reoffer_prediction");
        assertGoal(base);assertGoal(park);
        assertEquals(2,base.getInt("planner_requests"));assertEquals(1,park.getInt("planner_requests"));
    }
    @Test public void serverDeferredTitleIsProvedLocallyOnArrival()throws Exception{
        // Device logs 2026-11-21: 8/18 episodes paid a result_only request because of this deferral.
        JSONObject base=episode(1,Condition.normal,Lookahead.none,false,"base"),production=episode(1,Condition.normal,Lookahead.none,false,"production");
        assertGoal(base);assertGoal(production);
        assertEquals(2,base.getInt("planner_requests"));assertEquals(1,production.getInt("planner_requests"));
        assertEquals(1,base.getInt("result_only_requests"));assertEquals(0,production.getInt("result_only_requests"));
    }
    @Test public void oneProviderTimeoutNoLongerEndsTheTask()throws Exception{
        JSONObject base=episode(1,Condition.provider_timeout,Lookahead.none,false,"base"),retry=episode(1,Condition.provider_timeout,Lookahead.none,false,"retry_provider");
        assertFalse(base.getBoolean("verified_offer"));assertEquals("unavailable",base.getString("final_status"));
        assertGoal(retry);assertEquals(base.getInt("planner_requests")+2,retry.getInt("planner_requests")); // retry + arrival check
    }
    @Test public void goalDerivedConditionProvesArrivalWhenNoPlannerGaveOne()throws Exception{
        for(int depth:new int[]{1,4}){ // goals whose wording names the destination title
            JSONObject base=episode(depth,Condition.no_prediction,Lookahead.none,false,"base");
            JSONObject production=episode(depth,Condition.no_prediction,Lookahead.none,false,"production");
            assertGoal(base);assertGoal(production);
            assertTrue(base.getInt("result_only_requests")+"",base.getInt("planner_requests")==depth+1);
            assertEquals(depth,production.getInt("planner_requests"));assertEquals(0,production.getInt("result_only_requests"));
        }
        // A paraphrased goal ("글자 크기" vs title "글자 크기와 스타일") derives nothing and keeps the planner check.
        JSONObject paraphrase=episode(2,Condition.no_prediction,Lookahead.none,false,"production");
        assertGoal(paraphrase);assertEquals(3,paraphrase.getInt("planner_requests"));
    }
    @Test public void productionKeepsUnvalidatedPathsOff()throws Exception{
        AppTaskController c=new AppTaskController(mock(AppGuideGateway.class),new AppTaskMemory("디스플레이 화면을 열어줘"),null,new World(FLOWS[0],Condition.normal,Lookahead.none,false),true,true,true,AppTaskMetaController.Arm.CURRENT,true);
        try{assertSame(AppTaskController.SpeedOptions.PRODUCTION,c.speedOptions());
            assertFalse(c.speedOptions().memoryBeforeCompletion);assertFalse(c.speedOptions().plannedRoute);
            assertTrue(c.speedOptions().reofferPrediction&&c.speedOptions().rememberedCompletion&&c.speedOptions().rebindChangedFrame&&c.speedOptions().retryProviderFailure&&c.speedOptions().goalDerivedCompletion);
        }finally{c.close();}
    }
    private static void assertGoal(JSONObject row)throws Exception{
        assertTrue(row.toString(),row.getBoolean("goal_reached"));assertTrue(row.toString(),row.getBoolean("verified_offer"));
        assertEquals(0,row.getInt("false_verified"));assertEquals(0,row.getInt("guard_rejections"));assertEquals(0,row.getInt("listener_errors"));
    }

    // ---- Opt-in matrix written for the report. ----
    @Test public void runSpeedMatrix()throws Exception{
        String output=System.getenv("CLICK_SPEED_EXPERIMENT_OUTPUT");
        Assume.assumeTrue("Opt in with CLICK_SPEED_EXPERIMENT_OUTPUT",output!=null&&!output.isBlank());
        Path dir=Path.of(output);Files.createDirectories(dir);Path rows=dir.resolve("episodes.jsonl");
        assertFalse("Refuse to overwrite a previous run",Files.exists(rows));
        int count=0;
        for(int depth=1;depth<=4;depth++)for(Condition c:Condition.values())for(boolean repeat:new boolean[]{false,true})
            for(Lookahead l:Lookahead.values()){
                if(l!=Lookahead.accurate&&(depth!=3||c!=Condition.normal||repeat))continue; // robustness slice only
                for(String arm:ARMS.keySet()){
                    JSONObject row=episode(depth,c,l,repeat,arm);
                    Files.write(rows,(row+"\n").getBytes(StandardCharsets.UTF_8),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
                    assertEquals(row.toString(),0,row.getInt("false_verified"));assertEquals(row.toString(),0,row.getInt("guard_rejections"));count++;
                }
            }
        Files.write(dir.resolve("run.json"),new JSONObject().put("schema_version",VERSION).put("episodes",count)
            .put("planner_latency_ms",PLANNER_MS).put("recall_latency_ms",RECALL_MS).put("churn_after_ms",CHURN_AFTER_MS)
            .put("real_device",false).put("real_model_calls",0).put("deterministic",true)
            .put("arms",new JSONArray(ARMS.keySet())).put("design","4 depths x 3 conditions x first/repeat x "+ARMS.size()+" arms + depth3 lookahead robustness (3 x "+ARMS.size()+")")
            .toString(2).getBytes(StandardCharsets.UTF_8));
    }

    static JSONObject episode(int depth,Condition c,Lookahead l,boolean repeat,String arm)throws Exception{
        World w=new World(FLOWS[depth-1],c,l,repeat);JSONObject row=run(w,ARMS.get(arm));
        return row.put("depth",depth).put("condition",c.name()).put("lookahead",l.name()).put("memory_available",repeat).put("arm",arm);
    }

    static JSONObject run(World w,AppTaskController.SpeedOptions options)throws Exception{
        long started=SystemClock.uptimeMillis();
        AppGuideGateway gateway=mock(AppGuideGateway.class);
        doAnswer(call->{JSONObject req=call.getArgument(0);AppGuideGateway.Callback cb=call.getArgument(1);w.plannerRequests++;if("result_only".equals(req.optString("planning_mode")))w.resultOnlyRequests++;w.trace.put("REQ:"+req.optString("planning_mode","next_action")+"@"+w.depth);
            if(w.condition==Condition.churn)w.after(CHURN_AFTER_MS,()->w.banner=true);
            boolean timeout=w.condition==Condition.provider_timeout&&w.plannerRequests==1;
            w.inFlight++;w.handler.postDelayed(()->{w.inFlight--;if(w.closed)return;try{JSONObject r=timeout?w.timeout(req):w.plan(req);AppGuideGateway.validateTask(req,r);cb.success(r);}catch(Exception e){w.errors++;cb.failure(e);}},timeout?TIMEOUT_MS:PLANNER_MS);
            return null;}).when(gateway).send(any(),any());
        doAnswer(call->{JSONObject req=call.getArgument(0);AppGuideGateway.Callback cb=call.getArgument(1);w.recallRequests++;
            w.inFlight++;w.handler.postDelayed(()->{w.inFlight--;if(w.closed)return;try{cb.success(w.recall(req));}catch(Exception e){cb.failure(e);}},RECALL_MS);
            return null;}).when(gateway).recall(any(),any());
        AppTaskStore store=mock(AppTaskStore.class);
        when(store.learningDisabled()).thenReturn(false);
        when(store.rememberedCompletion(anyString(),anyString())).thenAnswer(call->w.repeat?w.correctCompletion():null);
        doAnswer(call->{w.recorded=call.getArgument(2);return null;}).when(store).recordCompletion(anyString(),anyString(),any());
        AppTaskController controller=new AppTaskController(gateway,new AppTaskMemory(w.goal),store,w,true,true,true,AppTaskMetaController.Arm.CURRENT,true,options);
        w.controller=controller;controller.targetApp(PKG,"설정");
        try{
            controller.refresh();Set<String> approved=new HashSet<>();
            while(SystemClock.uptimeMillis()-started<BUDGET_MS){
                JSONObject latest=w.latest;
                if(latest!=null&&"proposed".equals(latest.optString("status"))){JSONObject a=latest.optJSONObject("action");
                    if(a!=null&&approved.add(a.optString("id")))controller.execute(a.getString("id"),null);}
                if(w.verifiedAt>=0){controller.confirmResult();break;}
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
                if(w.pending==0&&latest!=null&&Set.of("needs_input","unavailable","protected").contains(latest.optString("status"))&&!w.inFlight())break;
            }
            return new JSONObject().put("schema_version",VERSION).put("goal_reached",w.goal()).put("verified_offer",w.verifiedAt>=0)
                .put("ms_to_verified_offer",w.verifiedAt<0?-1:w.verifiedAt-started).put("false_verified",w.falseVerified)
                .put("planner_requests",w.plannerRequests).put("result_only_requests",w.resultOnlyRequests).put("recall_requests",w.recallRequests)
                .put("proposals_by_source",w.sources).put("frame_rebound_proposals",w.rebound).put("wrong_taps",w.wrongTaps)
                .put("accepted_actions",w.accepted).put("guard_rejections",w.guardRejections).put("needs_input",w.handoffs)
                .put("listener_errors",w.errors).put("error_types",w.errorTypes).put("trace",w.trace).put("final_status",w.latest==null?"none":w.latest.optString("status"));
        }finally{controller.close();w.closed=true;w.handler.removeCallbacksAndMessages(null);}
    }

    static long clock(){return ShadowSystem.currentTimeMillis();}

    static final class World implements AppTaskController.Listener{
        final String id,goal,destination;final String[] steps;final Condition condition;final Lookahead lookahead;final boolean repeat;
        final Handler handler=new Handler(Looper.getMainLooper());AppTaskController controller;
        int depth,pending,plannerRequests,resultOnlyRequests,recallRequests,accepted,guardRejections,wrongTaps,handoffs,errors,falseVerified,rebound;
        boolean wrong,banner,closed;long verifiedAt=-1;JSONArray errorTypes=new JSONArray();JSONArray trace=new JSONArray();JSONObject latest,recorded,sources=new JSONObject();
        World(String[] flow,Condition condition,Lookahead lookahead,boolean repeat){
            id=flow[0];goal=flow[1];steps=Arrays.copyOfRange(flow,2,flow.length);destination=steps[steps.length-1];
            this.condition=condition;this.lookahead=lookahead;this.repeat=repeat;}
        boolean goal(){return !wrong&&depth==steps.length;}
        int inFlight;boolean inFlight(){return inFlight>0;}
        void after(long ms,Runnable effect){pending++;handler.postDelayed(()->{pending--;if(closed)return;effect.run();controller.screenChanged(fresh());},ms);}
        public ScreenSnapshot fresh(){
            List<AccessibilityNodeSnapshot> nodes=new ArrayList<>();int y=banner?180:100;
            if(banner)nodes.add(node("banner","새 알림이 있어요","text",false,false,20));
            if(wrong)nodes.add(node("wrong_title","다른 화면","text",false,true,y));
            else if(goal())nodes.add(node("goal_title",destination,"text",false,true,y));
            else nodes.add(node("step"+depth,steps[depth],"button",true,false,y));
            if(!goal()&&!wrong)nodes.add(node("alternate","다른 경로","button",true,false,y+200));
            nodes.add(node("summary",goal()?"화면을 확인하세요":"원하는 항목을 선택하세요","text",false,false,y+400));
            return new ScreenSnapshot(PKG,Instant.ofEpochMilli(clock()).toString(),1080,2400,nodes,42);
        }
        public boolean canObserve(){return !closed;}public void busy(boolean value){}public boolean inputMatches(String t,String h){return false;}
        public String sessionId(){return "speed-session";}
        public void present(JSONObject r,ScreenSnapshot submitted){
            latest=r;String status=r.optString("status");
            {JSONObject d=r.optJSONObject("diagnostic");trace.put(status+"@"+depth+(d==null?"":"/"+d.optString("reason")+d.optString("goal_assessment")));}
            if("needs_input".equals(status))handoffs++;
            if("proposed".equals(status)){JSONObject d=r.optJSONObject("diagnostic");String src=d==null?"unknown":d.optString("reason","unknown");
                try{sources.put(src,sources.optInt(src)+1);if(d!=null&&d.optBoolean("frame_rebound"))rebound++;}catch(Exception ignored){}}
            JSONObject a=r.optJSONObject("result_assessment");
            if(a!=null&&"verified".equals(a.optString("status"))){if(!goal())falseVerified++;if(verifiedAt<0)verifiedAt=SystemClock.uptimeMillis();}
        }
        public void failure(Throwable e){errors++;errorTypes.put(e.getClass().getSimpleName()+":"+e.getMessage());try{latest=new JSONObject().put("status","needs_input");}catch(Exception ignored){}}
        public boolean execute(ScreenSnapshot baseline,JSONObject observed,JSONObject action,String input,String selected){
            try{
                if(!AppTaskPolicy.allowed(observed,AppTaskObservation.recaptureLike(fresh(),observed),action,selected)){guardRejections++;return false;}
                accepted++;String tool=action.optString("tool");
                if("back".equals(tool)){after(150,()->wrong=false);return true;}
                JSONObject target=AppTaskObservation.find(observed,action.optString("target_id"));if(target==null)return false;
                String resource=target.optString("resource");
                if(resource.equals(RES+"alternate")){wrongTaps++;after(150,()->wrong=true);return true;}
                if(resource.equals(RES+"step"+depth)){after(150,()->{depth++;banner=false;});return true;}
                return false;
            }catch(Exception e){errors++;return false;}
        }
        JSONObject correctCompletion()throws Exception{return completion(destination);}
        JSONObject timeout(JSONObject req)throws Exception{
            JSONObject screen=req.getJSONObject("screen"),state=req.getJSONObject("state");
            return new JSONObject().put("protocol_version","app-task.v2").put("request_id",req.getString("request_id"))
                .put("task_id",state.getString("task_id")).put("observation_id",screen.getString("observation_id"))
                .put("fingerprint",screen.getString("fingerprint")).put("requires_confirmation",true).put("status","unavailable")
                .put("message","다음 단계를 준비하지 못했어요.").put("subgoals",state.getJSONArray("subgoals")).put("active_subgoal",JSONObject.NULL)
                .put("diagnostic",new JSONObject().put("called",true).put("reason","provider_or_plan_error").put("failure_kind","provider").put("reason_code","provider_timeout"));
        }
        static JSONObject completion(String label)throws Exception{
            return new JSONObject().put("checks",new JSONArray().put(new JSONObject().put("package",PKG).put("label",label)));}
        /** Topology-aware planner double with the live contract's shape. */
        JSONObject plan(JSONObject req)throws Exception{
            JSONObject screen=req.getJSONObject("screen"),state=req.getJSONObject("state");JSONArray nodes=screen.getJSONArray("nodes");
            boolean goalFirst="goal_first".equals(req.optString("planning_mode"));
            JSONArray subgoals=state.getJSONArray("subgoals").length()>0?state.getJSONArray("subgoals")
                :new JSONArray().put(new JSONObject().put("id","g0").put("title",goal).put("status","pending"));
            JSONObject r=new JSONObject().put("protocol_version","app-task.v2").put("request_id",req.getString("request_id"))
                .put("task_id",state.getString("task_id")).put("observation_id",screen.getString("observation_id"))
                .put("fingerprint",screen.getString("fingerprint")).put("requires_confirmation",true).put("subgoals",subgoals)
                .put("message","실험용 단계").put("diagnostic",new JSONObject().put("called",false).put("reason","controlled_planner"));
            String active=null;for(int i=0;i<subgoals.length()&&active==null;i++)if("pending".equals(subgoals.getJSONObject(i).optString("status")))active=subgoals.getJSONObject(i).getString("id");
            r.put("active_subgoal",active==null?JSONObject.NULL:active);
            JSONObject title=null,step=null;
            for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);String res=n.optString("resource");
                if(res.equals(RES+"goal_title"))title=n;if(res.startsWith(RES+"step"))step=n;}
            if(title!=null){ // Final result observable: a goal-first check reports it as current evidence.
                r.put("status","confirm_result").put("completion",state.optJSONObject("completion")==null?completion(title.getString("label")):state.getJSONObject("completion"));
                if(goalFirst)r.getJSONObject("diagnostic").put("goal_assessment","satisfied");return r;}
            if(goalFirst)r.getJSONObject("diagnostic").put("goal_assessment","not_satisfied");
            JSONObject bound=state.optJSONObject("completion");
            String predicted=condition==Condition.prediction_miss?destination+" 설정":destination;boolean menu=false;
            for(int i=0;i<nodes.length();i++)if(predicted.equals(nodes.getJSONObject(i).optString("label"))&&nodes.getJSONObject(i).optBoolean("clickable"))menu=true;
            // Like the server: a destination title still visible as this screen's menu is future-only.
            // no_prediction: like 57/60 production Gemini replies on the Galaxy, no result condition at all.
            if(bound!=null)r.put("completion",bound);else if(condition==Condition.no_prediction){}
            else if(menu)r.put("deferred_completion",completion(predicted));else r.put("completion",completion(predicted));
            if(wrong||step==null){r.put("status","proposed").put("action",new JSONObject().put("id",UUID.randomUUID().toString()).put("tool","back")
                .put("package",PKG).put("expectation",new JSONObject().put("kind","screen_changed")).put("advances_subgoal",false));return r;}
            int k=Integer.parseInt(step.getString("resource").substring((RES+"step").length()));boolean last=k==steps.length-1;
            r.put("status","proposed").put("action",new JSONObject().put("id",UUID.randomUUID().toString()).put("tool","tap")
                .put("target_id",step.getString("id")).put("bounds",step.getJSONArray("bounds")).put("package",PKG)
                .put("expectation",new JSONObject().put("kind","target_visible").put("label",last?destination:steps[k+1])).put("advances_subgoal",last));
            JSONArray later=new JSONArray();
            if(lookahead==Lookahead.accurate)for(int i=k+1;i<steps.length&&later.length()<3;i++)later.put(new JSONObject().put("tool","tap").put("label",steps[i]));
            if(!last&&lookahead==Lookahead.absent_label)later.put(new JSONObject().put("tool","tap").put("label","존재하지 않는 메뉴"));
            if(!last&&lookahead==Lookahead.visible_distractor)later.put(new JSONObject().put("tool","tap").put("label","다른 경로"));
            r.put("planned_steps",later);return r;
        }
        /** Learned memory double: the correct next step on each non-final screen, only on a repeat. */
        JSONObject recall(JSONObject req)throws Exception{
            JSONObject screen=req.getJSONObject("screen");JSONObject action=null;
            if(repeat){JSONArray nodes=screen.getJSONArray("nodes");
                for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(n.optString("resource").startsWith(RES+"step"))
                    action=new JSONObject().put("id",UUID.randomUUID().toString()).put("tool","tap").put("target_id",n.getString("id"))
                        .put("bounds",n.getJSONArray("bounds")).put("package",PKG).put("expectation",new JSONObject().put("kind","screen_changed")).put("advances_subgoal",false);}}
            return new JSONObject().put("protocol_version","app-task-memory.v1").put("request_id",req.getString("request_id"))
                .put("observation_id",screen.getString("observation_id")).put("fingerprint",screen.getString("fingerprint"))
                .put("status",action==null?"needs_data":"remembered").put("collection_enabled",true)
                .put("consent_id","12345678-1234-1234-1234-123456789abc").put("consent_recorded_at_ms",clock()-1000)
                .put("requires_confirmation",true).put("action",action==null?JSONObject.NULL:action).put("evidence",new JSONArray());
        }
    }
    static AccessibilityNodeSnapshot node(String id,String label,String role,boolean clickable,boolean heading,int y){
        return new AccessibilityNodeSnapshot(id,null,true,PKG,label,null,RES+id,clickable?"android.widget.Button":"android.widget.TextView",role,
            new int[]{20,y,1000,y+100},clickable,true,false,false,false,null,42,false,null,null,false,null,heading,false);
    }
}
