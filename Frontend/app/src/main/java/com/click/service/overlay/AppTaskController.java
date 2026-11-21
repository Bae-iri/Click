package com.click.service.overlay;

import android.os.*;
import com.click.data.remote.AppGuideGateway;
import com.click.data.remote.AppTaskCompletionDiagnostics;
import com.click.data.remote.AppTaskDiagnostics;
import com.click.data.local.AppTaskStore;
import com.click.data.snapshot.*;
import org.json.*;
import java.util.*;

/** Observe -> propose -> approve -> execute -> verify -> advance. No app-specific branches. */
public final class AppTaskController {
    public interface Listener {
        ScreenSnapshot fresh();boolean canObserve();void busy(boolean busy);
        void present(JSONObject response,ScreenSnapshot submitted);void failure(Throwable error);
        boolean execute(ScreenSnapshot baseline,JSONObject observation,JSONObject action,String input,String selectedPackage);
        boolean inputMatches(String target,String expectedHash);
        default String scope(String packageName){return packageName;}
        default String sessionId(){return null;}
    }
    private final AppGuideGateway gateway;private final Listener listener;private final AppTaskMemory memory;
    private final AppTaskStore store;private final boolean remote;
    private final boolean learning;
    private final boolean completionSupported;
    /**
     * Paths that avoid a paid planner round trip. Production uses {@link SpeedOptions#PRODUCTION};
     * experiments switch single paths on or off to measure them. None of them skips per-action user approval,
     * fresh-frame policy, native verification or the result check.
     */
    public static final class SpeedOptions {
        /** Use a remembered action/verified route before the planner has bound a result. */
        public final boolean memoryBeforeCompletion;
        /** Check a result condition that natively matched a previously confirmed result first. */
        public final boolean rememberedCompletion;
        /**
         * Keep a prediction discarded as unsafe on the current screen (its label is still a menu
         * entry here) and re-offer it only where it natively matches as proof, usually on arrival.
         */
        public final boolean reofferPrediction;
        /** Re-find a proposal's target when the screen redraws while the planner answers. */
        public final boolean rebindChangedFrame;
        /** Ground the planner's later-step labels locally instead of asking again. */
        public final boolean plannedRoute;
        /** Re-ask once, on the same screen, after a provider timeout or transport failure. */
        public final boolean retryProviderFailure;
        /** Derive the result condition from the goal's destination when no planner supplied one. */
        public final boolean goalDerivedCompletion;
        public SpeedOptions(boolean memoryBeforeCompletion,boolean rememberedCompletion,boolean reofferPrediction,boolean rebindChangedFrame,boolean plannedRoute){
            this(memoryBeforeCompletion,rememberedCompletion,reofferPrediction,rebindChangedFrame,plannedRoute,false);}
        public SpeedOptions(boolean memoryBeforeCompletion,boolean rememberedCompletion,boolean reofferPrediction,boolean rebindChangedFrame,boolean plannedRoute,boolean retryProviderFailure){
            this(memoryBeforeCompletion,rememberedCompletion,reofferPrediction,rebindChangedFrame,plannedRoute,retryProviderFailure,false);}
        public SpeedOptions(boolean memoryBeforeCompletion,boolean rememberedCompletion,boolean reofferPrediction,boolean rebindChangedFrame,boolean plannedRoute,boolean retryProviderFailure,boolean goalDerivedCompletion){
            this.goalDerivedCompletion=goalDerivedCompletion;this.memoryBeforeCompletion=memoryBeforeCompletion;this.rememberedCompletion=rememberedCompletion;this.reofferPrediction=reofferPrediction;this.rebindChangedFrame=rebindChangedFrame;this.plannedRoute=plannedRoute;this.retryProviderFailure=retryProviderFailure;}
        public static final SpeedOptions ALL=new SpeedOptions(true,true,true,true,true,true,true),NONE=new SpeedOptions(false,false,false,false,false,false,false);
        /**
         * Production default. Memory-first (removes the deliberate result-binding gate) and planned
         * routes (taps proposed from labels the model has not seen) stay off until a device A/B
         * (SpeedOptions.NONE vs ALL) shows a benefit; the result-proof paths only accept native proof.
         */
        public static final SpeedOptions PRODUCTION=new SpeedOptions(false,true,true,true,false,true,true);
        /** Same options with goal-derived completion switched; tests of the planner-delivered result path pin it off. */
        public SpeedOptions withGoalDerived(boolean on){return new SpeedOptions(memoryBeforeCompletion,rememberedCompletion,reofferPrediction,rebindChangedFrame,plannedRoute,retryProviderFailure,on);}
    }
    private SpeedOptions speed=SpeedOptions.PRODUCTION;
    private String recallWaitFingerprint;private long recallWaitDeadline;
    private JSONArray planned;private boolean plannedReady;
    private JSONObject parkedCompletion;private boolean parkedRemembered;
    private String providerRetryFingerprint;
    private final boolean goalFirstSupported;
    private final AppTaskMetaController meta;
    private JSONObject recalled;
    private String recallFingerprint,pendingRecall,consentId;
    private long recalledAt;
    private long consentGrantedAt,actionAt;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private ScreenSnapshot baseline;private JSONObject observation,proposal;
    private String submitted,selectedPackage,selectedLabel,inputHash,scope;
    private boolean protectedShown;
    private RequestedApp selectedApp;
    private boolean awaitingAppEntry, automaticReturnUsed;
    RequestedApp selectedApp() { return selectedApp; }
    boolean takeAutomaticReturn() {
        if (automaticReturnUsed || baseline==null || observation==null || "protected".equals(observation.optString("status"))
                || baseline.windowContext.keyboardVisible()
                || Set.of("DIALOG","SYSTEM","INPUT_METHOD").contains(baseline.windowContext.windowKind())) return false;
        automaticReturnUsed = true;
        return true;
    }
    private boolean needsAppEntry(ScreenSnapshot screen) {
        return selectedApp != null && (screen == null || !selectedPackage.equals(screen.packageName)
            || awaitingAppEntry && !selectedApp.reached(screen));
    }
    private String searchQuery;private boolean searchPrepared;
    private boolean busy,closed,verifying,actionObserved,replaying,finishing;private long actionStarted,proposedAt;
    private JSONObject diagnostic;
    private JSONObject lastUnverified;
    private int turns;private JSONObject cached;private JSONObject ocr;
    private String ocrNative;private long ocrAt;
    private String resultObservation;private long resultCapturedAt,resultStarted;
    private Integer resultWindowId;private String resultPackage;
    private int resultEvidenceVersion=-1;private String resultEvidenceKey,resultDiscoveryScreen;
    private boolean checkingResult,resultOffered;
    private JSONObject resultAssessment;
    private String ignoredResultFingerprint;
    private String completionRequestRef;
    private long selectionGeneration;
    private boolean plannerGateArmed,plannerWaiting,plannerCheckScheduled,plannerFreshPair;
    private String plannerFrameKey,plannerObservation;private long plannerPendingAt,plannerStableAt;
    private int plannerStableFrames;
    private final Runnable plannerRefresh=()->{plannerCheckScheduled=false;observe(false);};
    private final GuidedActionTracker manualActions=new GuidedActionTracker();
    private final Runnable refresh=()->observe(false);
    private final Runnable verify=()->verifyNow();
    public AppTaskController(AppGuideGateway gateway,AppTaskMemory memory,AppTaskStore store,Listener listener,boolean remote){this(gateway,memory,store,listener,remote,false);}
    public AppTaskController(AppGuideGateway gateway,AppTaskMemory memory,AppTaskStore store,Listener listener,boolean remote,boolean learning){this(gateway,memory,store,listener,remote,learning,false);}
    public AppTaskController(AppGuideGateway gateway,AppTaskMemory memory,AppTaskStore store,Listener listener,boolean remote,boolean learning,boolean completionSupported){this(gateway,memory,store,listener,remote,learning,completionSupported,AppTaskMetaController.Arm.CURRENT);}
    public AppTaskController(AppGuideGateway gateway,AppTaskMemory memory,AppTaskStore store,Listener listener,boolean remote,boolean learning,boolean completionSupported,AppTaskMetaController.Arm arm){this(gateway,memory,store,listener,remote,learning,completionSupported,arm,completionSupported);}
    public AppTaskController(AppGuideGateway gateway,AppTaskMemory memory,AppTaskStore store,Listener listener,boolean remote,boolean learning,boolean completionSupported,AppTaskMetaController.Arm arm,boolean goalFirstSupported){this(gateway,memory,store,listener,remote,learning,completionSupported,arm,goalFirstSupported,SpeedOptions.PRODUCTION);}
    /** Reproduces the 2026-11-21 first-step memory audit: only the memory gate follows the flag. */
    public AppTaskController(AppGuideGateway gateway,AppTaskMemory memory,AppTaskStore store,Listener listener,boolean remote,boolean learning,boolean completionSupported,AppTaskMetaController.Arm arm,boolean goalFirstSupported,boolean firstStepMemoryExperiment){this(gateway,memory,store,listener,remote,learning,completionSupported,arm,goalFirstSupported,new SpeedOptions(firstStepMemoryExperiment,false,false,false,false));}
    public AppTaskController(AppGuideGateway gateway,AppTaskMemory memory,AppTaskStore store,Listener listener,boolean remote,boolean learning,boolean completionSupported,AppTaskMetaController.Arm arm,boolean goalFirstSupported,SpeedOptions speed){this.gateway=gateway;this.memory=memory;this.store=store;this.listener=listener;this.remote=remote;this.learning=learning;this.completionSupported=completionSupported;this.goalFirstSupported=goalFirstSupported;this.meta=new AppTaskMetaController(arm);this.speed=speed==null?SpeedOptions.NONE:speed;}
    public SpeedOptions speedOptions(){return speed;}
    public AppTaskMetaController metaController(){return meta;}
    public AppTaskMemory memory(){return memory;}
    public void targetApp(String packageName,String label){targetApp(new RequestedApp(packageName,label));}
    void targetApp(RequestedApp app){
        selectedApp=app;awaitingAppEntry=true;automaticReturnUsed=false;
        String packageName=app.packageName,label=app.label;
        selectionGeneration++;recallWaitFingerprint=null;recallWaitDeadline=0;planned=null;plannedReady=false;parkedCompletion=null;parkedRemembered=false;providerRetryFingerprint=null;cancelPlannerWait();resetResultEvidence();resultDiscoveryScreen=null;meta.resetTargetContext();selectedPackage=packageName;selectedLabel=RequestedApp.shortName(label);searchQuery=AppTaskSearchIntent.query(memory.goal(),label);scope=null;cached=null;submitted=null;proposal=null;searchPrepared=false;recalled=null;recallFingerprint=null;pendingRecall=null;}
    public boolean matches(ScreenSnapshot screen){try{return screen!=null&&submitted!=null&&submitted.equals(capture(screen).optString("fingerprint"));}catch(Exception e){return false;}}
    private JSONObject capture(ScreenSnapshot screen)throws JSONException {
        Set<String> labels=AppTaskCompletion.labels(memory.completion());
        JSONObject expected=proposal==null?null:proposal.optJSONObject("expectation");
        if(expected!=null&&!expected.optString("label").isEmpty())labels.add(expected.optString("label"));
        JSONObject out=AppTaskObservation.capture(screen,labels);
        if(ocr!=null&&screen!=null&&out.optString("fingerprint").equals(ocrNative)&&SystemClock.elapsedRealtime()-ocrAt<15000){
            JSONArray nodes=out.getJSONArray("nodes");for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i),extra=AppTaskObservation.find(ocr,n.optString("id"));if(n.optString("label").isEmpty()&&extra!=null){n.put("label",extra.optString("label")).put("source","ocr");}}
            AppTaskObservation.finish(out);
        }
        return out;
    }
    public void visualObservation(ScreenSnapshot nativeScreen,JSONObject enriched){
        if(closed)return;try{ocrNative=AppTaskObservation.capture(nativeScreen).getString("fingerprint");ocr=enriched;ocrAt=SystemClock.elapsedRealtime();busy=false;listener.busy(false);refresh();}catch(Exception e){visualFailure(e);}
    }
    public boolean beginVisual(){if(closed||busy||verifying||!listener.canObserve())return false;busy=true;listener.busy(true);return true;}
    public void visualFailure(Throwable error){if(!closed){busy=false;listener.busy(false);listener.failure(error);}}
    public void screenChanged(ScreenSnapshot screen){if(!closed){plannerGateArmed=true;if(meta.enabled())try{meta.observeEvent(capture(screen),SystemClock.elapsedRealtime());}catch(JSONException ignored){}
        if(busy&&goalFirstSupported&&completionSupported&&memory.completion()!=null){handler.removeCallbacks(refresh);handler.post(refresh);return;}
        schedulePlannerCheck();}}
    public void refresh(){resultObservation=null;resultAssessment=null;checkingResult=resultOffered=false;observe(true);}
    /** Explicit panel/voice refresh; automatic observation keeps the scoped recovery budget. */
    public void userRefresh(){if(closed||finishing||!listener.canObserve())return;meta.resetUserContext();refresh();}
    /** Only offer tools which can act on the current public screen. */
    public JSONArray availableTools(){
        JSONArray tools=new JSONArray();
        if(closed||busy||verifying||checkingResult||!listener.canObserve())return tools;
        try{
            JSONObject screen=capture(listener.fresh());String status=screen.optString("status");
            if("protected".equals(status))return tools;
            if("public".equals(status)){
                int inputs=0,lists=0;JSONArray nodes=screen.getJSONArray("nodes");
                for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(n.optBoolean("editable"))inputs++;if(n.optBoolean("scrollable"))lists++;}
                if(inputs==1)tools.put("set_text");
                if(lists==1){tools.put("scroll_forward");tools.put("scroll_backward");}
                tools.put("back");
            }
            tools.put("open_app");
        }catch(JSONException ignored){ }
        return tools;
    }
    private boolean canReadLabels(){
        if(Build.VERSION.SDK_INT<30||closed||busy||verifying||checkingResult||!listener.canObserve())return false;
        ScreenSnapshot screen=listener.fresh();
        if(screen==null||!VisionSafetyPolicy.assess(screen).allowed)return false;
        try{
            JSONObject current=capture(screen);if(!"public".equals(current.optString("status")))return false;
            JSONArray nodes=current.getJSONArray("nodes");
            for(int i=0;i<nodes.length();i++){
                JSONObject n=nodes.getJSONObject(i);JSONArray b=n.getJSONArray("bounds");
                if(n.optBoolean("clickable")&&!n.optBoolean("editable")&&!n.optBoolean("scrollable")&&n.optString("label").isEmpty()
                    &&(long)(b.getInt(2)-b.getInt(0))*(b.getInt(3)-b.getInt(1))<=(long)screen.screenWidth*screen.screenHeight/8)return true;
            }
        }catch(JSONException ignored){ }
        return false;
    }
    private void save(){if(store!=null)store.save(memory);}
    private void completionTrace(String stage,String reason,JSONObject candidate,JSONObject current,ScreenSnapshot nativeScreen,
            String requestRef,String verdict,String freshness,Long ageMs,long elapsedMs,int frames){
        if(!completionSupported||!com.click.data.local.CandidateDiagnosticLog.enabled())return;
        try{
            String key=AppTaskCompletion.valid(candidate)?AppTaskCompletion.key(candidate):null;
            int checks=candidate==null||candidate.optJSONArray("checks")==null?0:candidate.optJSONArray("checks").length();
            gateway.recordCompletion(AppTaskCompletionDiagnostics.event(memory.id(),requestRef,key,current,nativeScreen,
                memory.evidenceVersion(),checks,stage,reason,verdict,freshness,ageMs,elapsedMs,frames));
        }catch(Exception ignored){ /* Completion diagnostics must never change the task. */ }
    }
    private void show(String status,String message)throws JSONException {
        JSONObject target=proposal==null||observation==null?null:AppTaskObservation.find(observation,proposal.optString("target_id"));
        JSONObject result=new JSONObject().put("status",status).put("message",message).put("subgoals",memory.state().getJSONArray("subgoals"))
            .put("active_subgoal",memory.active()==null?JSONObject.NULL:memory.active()).put("action",proposal==null?JSONObject.NULL:proposal)
            .put("verifying",verifying||checkingResult).put("execute_allowed",proposal!=null&&AppTaskPolicy.allowed(observation,observation,proposal,selectedPackage))
            .put("target_label",target==null?selectedLabel:target.optString("label"));
        result.put("available_tools",availableTools()).put("can_read_labels",canReadLabels());
        if(resultAssessment!=null)result.put("result_assessment",resultAssessment);
        if(proposal!=null&&"set_text".equals(proposal.optString("tool"))&&searchQuery!=null)result.put("suggested_query",searchQuery);
        if(diagnostic!=null)result.put("diagnostic",diagnostic);
        listener.present(result,baseline);
    }
    /** A result can be reached manually, by a restored task, or already be present. */
    private void resetResultEvidence(){resultObservation=null;resultWindowId=null;resultPackage=null;resultAssessment=null;checkingResult=resultOffered=false;resultEvidenceVersion=-1;resultEvidenceKey=null;}
    private void showUnverifiedResult()throws JSONException{
        if(AppTaskSwitchGoal.parse(memory.goal())!=null){
            show("needs_input","요청하신 스위치 상태를 확인하지 못했어요. 현재 설정을 확인해 주세요.");
        }else if(completionSupported){
            resultAssessment=new JSONObject().put("status","unknown").put("reason","goal_postconditions_unconfirmed");
            show("needs_input","안내한 단계는 마쳤지만 요청하신 결과를 화면에서 확인하지 못했어요. 현재 화면을 다시 확인해 주세요.");
        }else{
            show("confirm_result","요청하신 결과가 나왔나요? 확인한 뒤 안내를 끝내 주세요.");
        }
    }
    private boolean discardUnsafeCompletion(JSONObject current,ScreenSnapshot screen)throws JSONException {
        if(!completionSupported||memory.finished()||memory.completionSupported()||memory.completion()==null
                ||!AppTaskCompletion.unsafeForGoal(memory.goal(),memory.completion(),current,screen))return false;
        completionTrace("discarded","unsafe_prediction_discarded",memory.completion(),current,screen,completionRequestRef,"unknown","not_checked",null,0,0);
        park(memory.completion());memory.discardCompletion();save();
        completionRequestRef=null;
        resetResultEvidence();
        return true;
    }
    private boolean observeResult(JSONObject current,ScreenSnapshot screen)throws JSONException {
        if(!completionSupported)return false;
        // A selected target change cannot let the previously visible app prove this context.
        if(needsAppEntry(screen)){
            checkingResult=resultOffered=false;resultObservation=null;resultAssessment=null;return false;
        }
        if(memory.completion()==null){
            completionTrace("goal_check","candidate_missing",null,current,screen,completionRequestRef,"not_checked","not_checked",null,0,0);return false;
        }
        if(discardUnsafeCompletion(current,screen))return false;
        int version=memory.evidenceVersion();String candidateKey=AppTaskCompletion.key(memory.completion());
        if(version!=resultEvidenceVersion||!candidateKey.equals(resultEvidenceKey)){
            resetResultEvidence();resultEvidenceVersion=version;resultEvidenceKey=candidateKey;
        }
        if(Objects.equals(ignoredResultFingerprint,current.optString("fingerprint"))){
            completionTrace("goal_check","ignored_frame",memory.completion(),current,screen,completionRequestRef,"not_checked","not_checked",null,0,0);return false;
        }
        ignoredResultFingerprint=null;
        AppTaskCompletion.Verdict verdict=AppTaskCompletion.checkForGoal(memory.goal(),memory.completion(),current,screen);
        long captured=current.optLong("captured_at_ms"),now=System.currentTimeMillis();
        String freshness=captured>now+2000?"future":now-captured>15000?"stale":"fresh";
        completionTrace("goal_check","checked",memory.completion(),current,screen,completionRequestRef,verdict.name().toLowerCase(Locale.ROOT),freshness,
            now-captured,resultObservation==null?0:SystemClock.elapsedRealtime()-resultStarted,resultObservation==null?0:1);
        if(verdict==AppTaskCompletion.Verdict.UNKNOWN&&"public".equals(current.optString("status"))&&!memory.completionSupported()&&!memory.finished()){
            // A predicted check that is ambiguous or unusable on a readable screen can never
            // become evidence here; release it so the destination's own title can be bound.
            completionTrace("discarded","ambiguous_prediction_discarded",memory.completion(),current,screen,completionRequestRef,"unknown",freshness,now-captured,0,0);
            park(memory.completion());memory.discardCompletion();save();completionRequestRef=null;resetResultEvidence();return false;
        }
        if(verdict!=AppTaskCompletion.Verdict.MATCHED||captured>now+2000||now-captured>15000
                ||screen==null||screen.activeWindowId==null||screen.activeWindowId<0){
            checkingResult=resultOffered=false;resultObservation=null;resultAssessment=null;return false;
        }
        if(resultObservation!=null&&(!Objects.equals(resultWindowId,screen.activeWindowId)||!Objects.equals(resultPackage,screen.packageName))){
            resultObservation=null;resultAssessment=null;checkingResult=resultOffered=false;
        }
        if(resultOffered)return true;
        if(resultObservation==null){
            resultWindowId=screen.activeWindowId;resultPackage=screen.packageName;
            resultObservation=current.optString("observation_id");resultCapturedAt=captured;resultStarted=SystemClock.elapsedRealtime();checkingResult=true;
            completionTrace("evidence","first_frame",memory.completion(),current,screen,completionRequestRef,"matched","fresh",now-captured,0,1);
            show("checking_result","요청하신 결과가 반영됐는지 확인하고 있어요.");
        }
        if(!resultObservation.equals(current.optString("observation_id"))&&captured-resultCapturedAt>=350&&SystemClock.elapsedRealtime()-resultStarted>=350){
            completionTrace("evidence","second_frame",memory.completion(),current,screen,completionRequestRef,"matched","fresh",now-captured,SystemClock.elapsedRealtime()-resultStarted,2);
            if(!memory.supportCompletion(resultEvidenceVersion)){
                completionTrace("unavailable","support_rejected",memory.completion(),current,screen,completionRequestRef,"matched","fresh",now-captured,SystemClock.elapsedRealtime()-resultStarted,2);
                resetResultEvidence();return false;
            }
            // The end state can be proven while the last action's prediction is still pending.
            if(verifying)finishVerification(current,AppTaskVerifier.Verdict.WAITING);
            lastUnverified=null;
            save();
            checkingResult=false;resultOffered=true;proposal=null;submitted=current.optString("fingerprint");
            resultAssessment=new JSONObject().put("status","verified").put("reason","goal_postconditions_observed")
                .put("source","device_observation").put("observation_id",current.optString("observation_id"))
                .put("check_count",memory.completion().getJSONArray("checks").length()).put("evidence_version",memory.evidenceVersion());
            meta.clearRecoveryContext();
            completionTrace("verified","fresh_native_observations",memory.completion(),current,screen,completionRequestRef,"matched","fresh",now-captured,SystemClock.elapsedRealtime()-resultStarted,2);
            show("confirm_result","요청하신 결과가 화면에 반영된 것을 확인했어요. 원하신 결과가 맞나요?");return true;
        }
        if(SystemClock.elapsedRealtime()-resultStarted>=5000){
            if(verifying)finishVerification(current,AppTaskVerifier.Verdict.UNKNOWN);
            checkingResult=false;resultObservation=null;
            resultAssessment=new JSONObject().put("status","unknown").put("reason","fresh_result_observation_unavailable");
            completionTrace("unavailable","fresh_frame_unavailable",memory.completion(),current,screen,completionRequestRef,"matched","fresh",now-captured,SystemClock.elapsedRealtime()-resultStarted,1);
            show("needs_input","결과를 확인할 새 화면을 읽지 못했어요. 현재 화면을 다시 확인해 주세요.");return true;
        }
        completionTrace("evidence","waiting_second_frame",memory.completion(),current,screen,completionRequestRef,"matched","fresh",now-captured,SystemClock.elapsedRealtime()-resultStarted,1);
        Runnable next=verifying?verify:refresh;
        handler.removeCallbacks(next);handler.postDelayed(next,400);return true;
    }
    /** A known local postcondition can finish proof while a paid planner response is still pending. */
    private void observeKnownResultWhileBusy(){
        if(!goalFirstSupported||!completionSupported||memory.completion()==null||memory.finished())return;
        try{
            ScreenSnapshot screen=listener.fresh();if(screen==null)return;JSONObject current=capture(screen);
            boolean observed=observeResult(current,screen);
            if(memory.completionSupported()){
                listener.busy(false);
                if(!observed){cancelPlannerWait();proposal=null;manualActions.clear();resultAssessment=null;
                    show("confirm_result","확인한 결과가 요청하신 내용과 맞나요?");}
            }
        }catch(Exception error){listener.failure(error);}
    }
    private void observe(boolean force){
        if(closed||finishing||!listener.canObserve())return;
        if(busy){observeKnownResultWhileBusy();return;}
        if(verifying){verifyNow();return;}
        ScreenSnapshot screen=listener.fresh();if(screen==null&&selectedPackage==null)return;
        try{
            JSONObject current=capture(screen);
            if (!needsAppEntry(screen)) {
                if(discardUnsafeCompletion(current,screen))current=capture(screen);
                if(reofferParkedCompletion(screen)||seedGoalDerivedCompletion(current,screen))current=capture(screen);
            }
            String fingerprint=current.getString("fingerprint");
            if(memory.finished()){proposal=null;show("completed","요청한 결과를 확인했어요.");return;}
            if(observeResult(current,screen)){cancelPlannerWait();return;}
            // Once native goal evidence is supported, await the user's result decision even
            // if a later redraw no longer matches it. Pending plan bits cannot restart actions.
            if(goalFirstSupported&&memory.completionSupported()){
                cancelPlannerWait();proposal=null;manualActions.clear();submitted=fingerprint;
                resultAssessment=null;show("confirm_result","확인한 결과가 요청하신 내용과 맞나요?");return;
            }
            if(!force&&fingerprint.equals(submitted)&&!plannerWaiting)return;
            if(!force&&rebindInput(current,screen))return;
            if(proposal!=null&&!fingerprint.equals(submitted))proposal=null;
            baseline=screen;observation=current;diagnostic=null;
            if(selectedApp==null && screen!=null && NavigationRecovery.isHome(screen.packageName)){
                cancelPlannerWait();submitted=fingerprint;proposal=null;
                show("needs_input","어떤 앱에서 진행할까요? 앱을 선택하거나 직접 열어주세요.");return;
            }
            if(needsAppEntry(screen)){
                awaitingAppEntry=true;
                if(learning && (memory.state().getJSONArray("subgoals").length()==0 || memory.active()!=null)){
                    // Consent can be established at entry without disclosing controls from another app.
                    maybeRecall(new JSONObject(current.toString()).put("status","outside").put("nodes",new JSONArray()));
                }
                localDiagnostic("requested_app_entry");
                propose(action("open_app",null,current,new JSONObject().put("kind","package_opened").put("package",selectedPackage),false).put("package",selectedPackage));return;
            }
            awaitingAppEntry=false;
            if(memory.state().getJSONArray("subgoals").length()==0||memory.active()!=null)maybeRecall(current);
            if(!"public".equals(current.optString("status"))){
                cancelPlannerWait();submitted=fingerprint;
                boolean guarded="protected".equals(current.optString("status"));
                // Private input stays unread. Asked to continue while it is still on screen, let the
                // user say they finished it themselves instead of looping on the same notice.
                if(guarded&&force&&protectedShown){show("confirm_result","입력 화면은 클릭이 읽지 않아요. 직접 입력과 전송을 마치셨다면 결과가 맞다고 눌러 끝내 주세요. 아직이면 입력을 마친 뒤 다시 눌러 주세요.");return;}
                // Typing re-captures the same guarded screen many times a second; re-rendering it
                // would invalidate the panel's buttons before they can be pressed.
                if(guarded&&protectedShown)return;
                protectedShown=guarded;
                show(guarded?"protected":"needs_input",guarded?"입력·인증은 직접 진행한 뒤 이어서 확인해 주세요.":selectedPackage!=null?"화면을 불러오고 있어요.":"도움이 필요한 앱을 열어주세요.");return;
            }
            protectedShown=false;
            JSONObject publicLogin=AppTaskObservation.publicHomeLogin(current);
            if(publicLogin!=null&&memory.goal().matches("(?is).*(?:로그인|login|sign\\s*in).*")) {
                localDiagnostic("gov24_public_home_login");
                propose(action("tap",publicLogin,current,new JSONObject().put("kind","screen_changed"),false));return;
            }
            if(scope==null){scope=listener.scope(current.getString("package"));memory.scope(scope);if(store!=null&&memory.actions()==0)cached=store.reusable(memory.goal(),scope);if(cached!=null&&memory.state().getJSONArray("subgoals").length()==0)memory.plan(cached.optJSONArray("subgoals"));
                if(seedRememberedCompletion()&&reofferParkedCompletion(screen)){handler.removeCallbacks(refresh);handler.post(refresh);return;}}
            if(memory.actions()>=64){show("needs_input","진행 단계가 많아졌어요. 현재 결과를 확인하고 새 작업으로 이어가 주세요.");return;}
            boolean allStepsVerified=memory.state().getJSONArray("subgoals").length()>0&&memory.active()==null;
            final boolean resultOnly=allStepsVerified&&remote&&completionSupported&&!memory.completionSupported();
            String resultDiscoveryKey=null;
            if(allStepsVerified){
                // A guessed selector cannot lock out evidence discovery after navigation succeeds.
                // Canonical native controls, rather than candidate-dependent projection, debounce it.
                String canonical=AppTaskObservation.capture(screen).optString("fingerprint");
                if(!resultOnly||canonical.equals(resultDiscoveryScreen)){
                    showUnverifiedResult();return;
                }
                resultDiscoveryKey=canonical;
            }
            if(!remote){memory.localPlan();save();}
            // Memory may act before the planner binds a result; the result is then checked locally
            // or by one goal-first request after arrival, instead of a planner request per step.
            boolean memoryFirst=speed.memoryBeforeCompletion&&remote&&!resultOnly;
            if(memoryFirst&&learning&&fingerprint.equals(pendingRecall)){
                // Recall is a short read; wait briefly instead of racing it with a paid planner call.
                if(!fingerprint.equals(recallWaitFingerprint)){recallWaitFingerprint=fingerprint;recallWaitDeadline=SystemClock.elapsedRealtime()+RECALL_WAIT_MS;}
                if(SystemClock.elapsedRealtime()<recallWaitDeadline){
                    handler.removeCallbacks(refresh);handler.postDelayed(refresh,100);
                    show("planning","이전에 확인한 실행 경험을 확인하고 있어요.");return;
                }
            }
            if(!resultOnly&&(!completionSupported||memory.completion()!=null||memoryFirst&&learning)&&fingerprint.equals(recallFingerprint)&&recalled!=null&&recalled.optJSONObject("action")!=null){
                JSONObject learned=recalled.getJSONObject("action");if(!memory.repeated(current,learned)&&!previouslyRejected(current,learned)){
                    memory.localPlan();planned=null;
                    diagnostic=new JSONObject().put("model","agent2-experience").put("called",false).put("reason","retained_experience");propose(learned);return;
                }
            }
            replaying=false;JSONObject cachedAction=resultOnly||remote&&completionSupported&&memory.completion()==null&&!memoryFirst?null:reuse(current);if(cachedAction!=null){replaying=true;planned=null;localDiagnostic("verified_route");propose(cachedAction);return;}
            JSONObject plannedAction=resultOnly?null:plannedStep(current);if(plannedAction!=null){localDiagnostic("planned_route");propose(plannedAction);return;}
            JSONObject local=resultOnly||remote&&completionSupported&&memory.completion()==null?null:local(current);if(local!=null){memory.localPlan();save();localDiagnostic("public_control_match");propose(local);return;}
            if(!remote){cancelPlannerWait();submitted=fingerprint;memory.localPlan();save();show("needs_input",searchQuery!=null?(searchPrepared?searchMessage(current):"검색창을 눌러주세요. 화면이 바뀌면 이어서 안내할게요."):"원하는 항목을 눌러주세요. 화면이 바뀌면 이어서 안내할게요.");return;}
            if(turns>=32){show("needs_input","안내 횟수에 도달했어요. 진행 내용을 확인하고 새 요청으로 시작해 주세요.");return;}
            boolean wasWaiting=plannerWaiting;PlannerFrameState frameState=plannerFrameState(screen,current);
            if(frameState!=PlannerFrameState.READY){
                if(frameState==PlannerFrameState.UNAVAILABLE){cancelPlannerWait();submitted=fingerprint;show("needs_input","현재 화면을 새로 확인하지 못했어요. 화면이 열린 뒤 다시 진행해 주세요.");}
                else{schedulePlannerCheck();if(!wasWaiting)show("planning","화면을 확인하고 있어요.");}
                return;
            }
            meta.subgoal(memory.active());
            if(!resultOnly&&!meta.claimPlannerRequest(screen)){cancelPlannerWait();submitted=fingerprint;meta.clearRecoveryContext();proposal=null;manualActions.clear();show("needs_input","이 화면에서 더 진행할 단계를 확인하지 못했어요. 다른 방법을 선택하거나 직접 진행해 주세요.");return;}
            JSONObject request=request(current);
            final boolean goalFirst=goalFirstSupported&&!resultOnly&&completionSupported&&!memory.completionSupported();
            if(goalFirstSupported&&(resultOnly||goalFirst))request.put("planning_mode",resultOnly?"result_only":"goal_first");
            cancelPlannerWait();submitted=fingerprint;if(resultOnly)resultDiscoveryScreen=resultDiscoveryKey;
            long requestSelectionGeneration=selectionGeneration;String requestSelectedPackage=selectedPackage;
            proposal=null;manualActions.clear();busy=true;turns++;listener.busy(true);
            show("planning",resultOnly?"요청하신 결과가 나왔는지 확인하고 있어요.":"현재 화면에서 다음 단계를 찾고 있어요.");
            gateway.send(request,new AppGuideGateway.Callback(){
                public void success(JSONObject response){
                    String requestRef=AppTaskDiagnostics.ref(request.optString("request_id"));
                    JSONObject receivedCandidate=response.optJSONObject("completion");
                    completionTrace("response","received",receivedCandidate,request.optJSONObject("screen"),screen,requestRef,"not_checked","not_checked",null,0,0);
                    if(closed||finishing){
                        completionTrace("discarded",closed?"closed":"finishing",receivedCandidate,request.optJSONObject("screen"),screen,requestRef,"not_checked","not_checked",null,0,0);return;
                    }
                    busy=false;listener.busy(false);if(!listener.canObserve()){
                        completionTrace("discarded","not_observable",receivedCandidate,request.optJSONObject("screen"),screen,requestRef,"not_checked","not_checked",null,0,0);return;
                    }
                    if(requestSelectionGeneration!=selectionGeneration||!Objects.equals(requestSelectedPackage,selectedPackage)){
                        completionTrace("discarded","changed_context",receivedCandidate,request.optJSONObject("screen"),screen,requestRef,"not_checked","not_checked",null,0,0);handler.post(refresh);return;
                    }
                    if(goalFirstSupported&&memory.completionSupported()){
                        completionTrace("discarded","immutable_supported",receivedCandidate,request.optJSONObject("screen"),screen,requestRef,"not_checked","not_checked",null,0,0);
                        proposal=null;manualActions.clear();handler.post(refresh);return;
                    }
                    try{
                        ScreenSnapshot fresh=listener.fresh();JSONObject freshObservation=fresh==null?null:capture(fresh);
                        boolean goalSatisfied=goalFirst&&satisfiedGoalResponse(response);
                        boolean readOnlyResult=resultOnly||goalSatisfied;
                        boolean changedFrame=fresh==null||!fingerprint.equals(freshObservation.optString("fingerprint"));
                        JSONObject rebound=changedFrame&&!readOnlyResult?rebindChangedFrame(request,response,screen,fresh,freshObservation):null;
                        if(rebound!=null){
                            // Same window, same unique control: keep the paid answer instead of re-asking.
                            baseline=fresh;observation=freshObservation;submitted=freshObservation.optString("fingerprint");changedFrame=false;
                        }
                        if(changedFrame||readOnlyResult&&!sameNativeWindow(screen,fresh)){
                            if(readOnlyResult&&revalidateResultCandidate(request,response,screen,fresh,requestSelectionGeneration,requestSelectedPackage,requestRef))return;
                            completionTrace("discarded",fresh==null?"no_frame":"changed_frame",receivedCandidate,freshObservation,fresh,requestRef,"not_checked","not_checked",null,0,0);
                            handler.post(refresh);return;
                        }
                        diagnostic=response.optJSONObject("diagnostic");
                        if(rebound!=null)diagnostic=new JSONObject(diagnostic==null?"{}":diagnostic.toString()).put("frame_rebound",true);
                        // A final-state assessment never edits the task plan or grants an action.
                        if(readOnlyResult&&!response.isNull("action"))throw new JSONException("Action in result evidence response");
                        if(goalFirst&&"uncertain".equals(goalAssessment(response))){
                            if(!response.isNull("action"))throw new JSONException("Action with uncertain goal");
                            // Ignore new predictions/progress from an uncertain assessment. A known
                            // local condition may still be independently proved on fresh native frames.
                            proposal=null;manualActions.clear();
                            if(observeResult(capture(fresh),fresh))return;
                            show("needs_input",response.optString("message","현재 화면에서 요청하신 결과를 확실히 확인하지 못했어요."));return;
                        }
                        if(!readOnlyResult)memory.plan(response.optJSONArray("subgoals"));
                        JSONObject candidate=response.optJSONObject("completion");
                        if(completionSupported&&candidate!=null){
                            Set<String> labels=AppTaskCompletion.labels(memory.completion());labels.addAll(AppTaskCompletion.labels(candidate));
                            JSONObject candidateScreen=AppTaskObservation.capture(fresh,labels);int oldVersion=memory.evidenceVersion();
                            boolean accepted=memory.offerCompletion(candidate,candidateScreen,fresh);
                            completionTrace(accepted?"candidate_accepted":"candidate_rejected",memory.lastCompletionOfferReason(),candidate,candidateScreen,fresh,requestRef,"not_checked","not_checked",null,0,0);
                            // Typical one-step goal: the destination title is still this screen's menu entry.
                            if(!accepted&&"unsafe_for_goal".equals(memory.lastCompletionOfferReason()))park(candidate);
                            if(oldVersion!=memory.evidenceVersion())resetResultEvidence();
                            if(accepted)completionRequestRef=requestRef;
                        }
                        // A destination title that is still this screen's menu entry: future-only, never proof here.
                        JSONObject deferred=response.optJSONObject("deferred_completion");
                        if(completionSupported&&deferred!=null&&memory.completion()==null&&!memory.finished())park(deferred);
                        save();
                        if(observeResult(capture(fresh),fresh))return;
                        if(resultOnly){showUnverifiedResult();return;}
                        if(goalSatisfied){proposal=null;manualActions.clear();show("needs_input","요청하신 결과를 확인할 화면 증거가 부족해요. 현재 화면을 확인해 주세요.");return;}
                        JSONObject action=rebound!=null?rebound:response.optJSONObject("action");
                        planned=action!=null&&speed.plannedRoute?plannedSteps(response,request.getJSONObject("screen")):null;plannedReady=false;
                        if(action!=null){if(memory.repeated(observation,action)){planned=null;show("needs_input","같은 동작이 반복해서 실패했어요. 다른 동작을 선택하거나 직접 화면을 이동해 주세요.");return;}propose(action);}
                        else if(retryableProviderFailure(response)&&!fingerprint.equals(providerRetryFingerprint)){
                            // One bounded retry: a single slow provider answer otherwise ends the task.
                            providerRetryFingerprint=fingerprint;submitted=null;
                            show("planning","응답이 늦어 한 번 더 확인하고 있어요.");handler.post(()->observe(true));
                        }
                        else show(response.optString("status"),response.optString("message"));
                    }catch(Exception error){listener.failure(error);}
                }
                public void failure(Throwable error){if(!closed&&!finishing){busy=false;listener.busy(false);planned=null;
                    if(goalFirstSupported&&memory.completionSupported()){handler.post(refresh);return;}
                    if(listener.canObserve())listener.failure(error);}}
            });
        }catch(Exception error){listener.failure(error);}
    }
    private static String goalAssessment(JSONObject response){
        JSONObject diagnostic=response.optJSONObject("diagnostic");return diagnostic==null?"":diagnostic.optString("goal_assessment");
    }
    private boolean retryableProviderFailure(JSONObject response){
        if(!speed.retryProviderFailure||!"unavailable".equals(response.optString("status"))||!response.isNull("action"))return false;
        JSONObject d=response.optJSONObject("diagnostic");
        return d!=null&&"provider".equals(d.optString("failure_kind"))
            &&Set.of("provider_timeout","provider_deadline_exceeded","provider_transport_error").contains(d.optString("reason_code"));
    }
    private static boolean satisfiedGoalResponse(JSONObject response){return "satisfied".equals(goalAssessment(response));}
    private enum PlannerFrameState {READY,WAIT,UNAVAILABLE}
    /** Planning debounce only. Native action approval still uses the complete observation. */
    private PlannerFrameState plannerFrameState(ScreenSnapshot screen,JSONObject current)throws JSONException {
        if(!plannerGateArmed)return PlannerFrameState.READY;
        long now=SystemClock.elapsedRealtime(),captured=current.optLong("captured_at_ms"),wall=System.currentTimeMillis();
        if(screen==null||screen.activeWindowId==null||screen.activeWindowId<0||captured>wall+2000||wall-captured>15000)return PlannerFrameState.UNAVAILABLE;
        JSONObject canonical=AppTaskObservation.capture(screen);List<String> controls=new ArrayList<>();JSONArray nodes=canonical.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);String role=n.optString("role");
            if(!n.optBoolean("clickable")&&!n.optBoolean("editable")&&!n.optBoolean("scrollable")&&!Set.of("toggle","tab").contains(role))continue;
            boolean passiveList=n.optBoolean("scrollable")&&!n.optBoolean("clickable")&&!n.optBoolean("editable");
            controls.add(role+"|"+n.optString("resource")+"|"+(passiveList?"":n.optString("label"))+"|"+n.optJSONArray("bounds")
                +"|"+n.optBoolean("clickable")+n.optBoolean("editable")+n.optBoolean("scrollable")+n.optBoolean("selected")
                +"|"+n.opt("checked")+"|"+n.optBoolean("has_value"));
        }
        Collections.sort(controls);String key=AppTaskObservation.hash(screen.packageName+"|"+screen.activeWindowId+"|"+String.join("\n",controls));
        if(!plannerWaiting){plannerWaiting=true;plannerPendingAt=now;plannerStableAt=now;plannerFrameKey=key;plannerStableFrames=1;plannerObservation=screen.observationId;return PlannerFrameState.WAIT;}
        boolean distinct=!screen.observationId.equals(plannerObservation);plannerFreshPair|=distinct;plannerObservation=screen.observationId;
        if(!key.equals(plannerFrameKey)){plannerFrameKey=key;plannerStableAt=now;plannerStableFrames=1;}else if(distinct)plannerStableFrames++;
        if(plannerStableFrames>=2&&now-plannerStableAt>=250)return PlannerFrameState.READY;
        if(now-plannerPendingAt>=1000)return plannerFreshPair?PlannerFrameState.READY:PlannerFrameState.UNAVAILABLE;
        return PlannerFrameState.WAIT;
    }
    private void schedulePlannerCheck(){if(plannerCheckScheduled||!plannerGateArmed)return;plannerCheckScheduled=true;
        handler.postDelayed(plannerRefresh,plannerWaiting?Math.max(1,Math.min(250,1000-(SystemClock.elapsedRealtime()-plannerPendingAt))):250);}
    private void cancelPlannerWait(){handler.removeCallbacks(plannerRefresh);plannerCheckScheduled=plannerWaiting=plannerGateArmed=plannerFreshPair=false;
        plannerFrameKey=plannerObservation=null;plannerPendingAt=plannerStableAt=0;plannerStableFrames=0;}
    private static boolean sameNativeWindow(ScreenSnapshot before,ScreenSnapshot after){
        return before!=null&&after!=null&&before.activeWindowId!=null&&before.activeWindowId>=0
            &&Objects.equals(before.activeWindowId,after.activeWindowId)&&Objects.equals(before.packageName,after.packageName);
    }
    /** A read-only result can be proved again; an action from an obsolete frame cannot. */
    private boolean revalidateResultCandidate(JSONObject request,JSONObject response,ScreenSnapshot sent,ScreenSnapshot fresh,
            long requestGeneration,String requestPackage,String requestRef)throws JSONException {
        if(!completionSupported||memory.finished()||memory.completionSupported()||fresh==null||sent==null
                ||requestGeneration!=selectionGeneration||!Objects.equals(requestPackage,selectedPackage)
                ||!request.optString("goal").equals(memory.goal())||request.optJSONObject("state")==null
                ||!memory.id().equals(request.getJSONObject("state").optString("task_id"))
                ||!AppTaskDiagnostics.identityBound(request,response)||!response.isNull("action")
                ||!sameNativeWindow(sent,fresh)||requestPackage!=null&&!requestPackage.equals(fresh.packageName))return false;
        JSONObject candidate=response.optJSONObject("completion");if(!AppTaskCompletion.valid(candidate))return false;
        Set<String> labels=AppTaskCompletion.labels(memory.completion());labels.addAll(AppTaskCompletion.labels(candidate));
        JSONObject observed=AppTaskObservation.capture(fresh,labels);long captured=observed.optLong("captured_at_ms"),now=System.currentTimeMillis();
        if(!"public".equals(observed.optString("status"))||captured>now||now-captured>15000
                ||captured<request.getJSONObject("screen").optLong("captured_at_ms")||sent.observationId.equals(fresh.observationId)
                ||AppTaskCompletion.checkForGoal(memory.goal(),candidate,observed,fresh)!=AppTaskCompletion.Verdict.MATCHED
                ||!AppTaskCompletion.distinctProofTargets(candidate,observed)||AppTaskCompletion.unsafeForGoal(memory.goal(),candidate,observed,fresh))return false;
        JSONObject proof=AppTaskObservation.capture(fresh,AppTaskCompletion.labels(candidate));
        if(Objects.equals(ignoredResultFingerprint,proof.optString("fingerprint")))return false;
        boolean accepted=memory.offerCompletion(candidate,observed,fresh);
        completionTrace(accepted?"candidate_accepted":"candidate_rejected",memory.lastCompletionOfferReason(),candidate,observed,fresh,requestRef,"matched","fresh",now-captured,0,0);
        if(!accepted)return false;
        resetResultEvidence();completionRequestRef=requestRef;proposal=null;manualActions.clear();baseline=fresh;observation=capture(fresh);submitted=observation.optString("fingerprint");
        diagnostic=response.optJSONObject("diagnostic");save();return observeResult(observation,fresh);
    }
    private static boolean simple(String title){return !title.matches(".*(?:해서|찾아|결과|비교|중에서|다음|그리고|하고).*");}
    private void localDiagnostic(String reason)throws JSONException{diagnostic=new JSONObject().put("model","on-device-tools").put("called",false).put("reason",reason).put("latency_ms",0);}
    private JSONObject environment(JSONObject current)throws JSONException {
        String[] scope=listener.scope(current.optString("package")).split("\\|");
        return new JSONObject().put("package",current.optString("package"))
            .put("app_version",scope.length>1&&scope[1].matches("[0-9]{1,20}")?scope[1]:"unknown").put("android_api",Build.VERSION.SDK_INT);
    }
    private JSONObject request(JSONObject current)throws JSONException {
        JSONObject request=new JSONObject().put("protocol_version","app-task.v2").put("request_id",UUID.randomUUID().toString())
            .put("goal",memory.goal()).put("screen",current).put("state",new JSONObject(memory.state().toString()))
            .put("apps",new JSONArray());
        if(!completionSupported)request.getJSONObject("state").remove("completion");
        JSONObject recovery=meta.recoveryContext(current);if(recovery!=null)request.put("recovery",recovery);
        if(learning){JSONObject env=environment(current);env.remove("package");request.put("environment",env).put("path_prefix",new JSONArray(memory.pathPrefix().toString()));}
        if(selectedPackage!=null)request.getJSONArray("apps").put(new JSONObject().put("package",selectedPackage).put("label",selectedLabel));return request;
    }
    private void maybeRecall(JSONObject current)throws JSONException {
        if(!learning||store==null||listener.sessionId()==null)return;
        if(refreshLearningConsent())return;
        String fingerprint=current.getString("fingerprint");
        if(fingerprint.equals(pendingRecall)||fingerprint.equals(recallFingerprint)&&SystemClock.elapsedRealtime()-recalledAt<60000)return;
        JSONObject request=request(current);pendingRecall=fingerprint;
        gateway.recall(request,new AppGuideGateway.Callback(){
            public void success(JSONObject result){
                if(closed||!fingerprint.equals(pendingRecall))return;pendingRecall=null;
                try{AppGuideGateway.validateMemory(request,result);if(refreshLearningConsent())return;consentId=result.optBoolean("collection_enabled")?result.optString("consent_id",null):null;consentGrantedAt=result.optLong("consent_recorded_at_ms");
                    store.retainExperienceConsent(consentId);if(consentId!=null){memory.experienceConsent(consentId);save();gateway.flush(store);}
                    recalled=result;recallFingerprint=fingerprint;recalledAt=SystemClock.elapsedRealtime();
                    if(!busy&&!verifying&&proposal==null&&listener.canObserve()&&matches(listener.fresh()))handler.post(refresh);
                }catch(Exception invalid){consentId=null;}
            }
            public void failure(Throwable error){if(!closed&&fingerprint.equals(pendingRecall)){pendingRecall=null;recallFingerprint=fingerprint;recalledAt=SystemClock.elapsedRealtime();recalled=null;}}
        });
    }
    private boolean refreshLearningConsent(){
        if(learning&&store!=null&&store.learningDisabled()){
            consentId=null;recalled=null;recallFingerprint=null;pendingRecall=null;store.retainExperienceConsent(null);
            if(!verifying&&diagnostic!=null&&"agent2-experience".equals(diagnostic.optString("model"))){proposal=null;handler.post(refresh);}
            return true;
        }return false;
    }
    private boolean previouslyRejected(JSONObject current,JSONObject action){
        if(meta.blocked(current,action))return true;
        if(recalled==null||!current.optString("fingerprint").equals(recallFingerprint))return false;
        JSONArray evidence=recalled.optJSONArray("evidence");for(int i=0;evidence!=null&&i<evidence.length();i++){
            JSONObject e=evidence.optJSONObject(i);if(e!=null&&action.optString("tool").equals(e.optString("tool"))
                &&action.optString("target_id").equals(e.optString("target_id"))&&e.optInt("rejections")>=2&&e.optInt("supporting_tasks_after_adverse")<2)return true;
        }return false;
    }
    private void record(JSONObject before,JSONObject after,JSONObject action,String outcome,boolean observed,boolean verified,boolean advances)throws JSONException {
        action=new JSONObject(action.toString()).put("advances_subgoal",advances&&AppTaskPolicy.provesSubgoal(action));
        refreshLearningConsent();
        JSONObject event=null;
        if(learning&&store!=null&&consentId!=null&&actionAt>=consentGrantedAt&&listener.sessionId()!=null&&memory.experienceSequence()<65&&!before.optString("package").isEmpty()
                &&("public".equals(before.optString("status"))||"open_app".equals(action.optString("tool")))){
            String reported=verified?"verified":"waiting".equals(outcome)?"unconfirmed":"failed".equals(outcome)?observed?"unconfirmed":"rejected":outcome;
            event=AppTaskExperience.capture(memory,memory.experienceSequence()+1,before,after,environment(before),action,reported,observed,verified);
            event.put("observed_at_ms",actionAt);
            memory.experienceSequence(memory.experienceSequence()+1);
        }
        if(verified){JSONObject target=AppTaskObservation.find(before,action.optString("target_id"));String key=target==null?"0000000000000000000000000000000000000000000000000000000000000000":AppTaskObservation.targetKey(target);
            memory.appendPath(AppTaskObservation.hash(AppTaskObservation.routeKey(before)+"\n"+action.getString("tool")+"\n"+key+"\n"+AppTaskObservation.routeKey(after)));}
        // An observed action whose effect could not be verified may still be the step that produced
        // the result the user confirms next; keep it only until the next recorded attempt.
        lastUnverified=!verified&&observed&&("failed".equals(outcome)||"waiting".equals(outcome))
            ?new JSONObject().put("before",before).put("action",action).put("after_key",AppTaskObservation.routeKey(after)):null;
        memory.record(before,action,outcome,advances);
        if(event!=null){store.saveExperience(memory,listener.sessionId(),consentId,event);gateway.flush(store);}else save();
    }
    private JSONObject local(JSONObject current)throws Exception {
        if(searchQuery!=null)return searchAction(current);
        if(remote&&memory.state().getJSONArray("subgoals").length()==0&&!simple(memory.goal()))return null;
        String goal=memory.activeTitle();String n=goal.replaceAll("\\s+","");
        if(n.contains("뒤로"))return action("back",null,current,new JSONObject().put("kind","screen_changed"),simple(goal));
        String tool=n.matches(".*(?:위로|올려|이전목록).*")?"scroll_backward":n.matches(".*(?:아래로|내려|스크롤).*")?"scroll_forward":n.matches(".*(?:입력|적어|써줘).*")?"set_text":null;
        JSONArray nodes=current.getJSONArray("nodes");JSONObject found=null;
        if(tool!=null){for(int i=0;i<nodes.length();i++){JSONObject node=nodes.getJSONObject(i);if((tool.equals("set_text")?node.optBoolean("editable"):node.optBoolean("scrollable"))){if(found!=null)return null;found=node;}}}
        else{
            JSONObject old=new JSONObject().put("status","public").put("observation_id",current.getString("observation_id")).put("nodes",new JSONArray());
            for(int i=0;i<nodes.length();i++){JSONObject node=nodes.getJSONObject(i);
                if("tab".equals(node.optString("role"))&&node.optBoolean("selected"))continue;
                old.getJSONArray("nodes").put(new JSONObject().put("node_id",node.getString("id")).put("label",node.getString("label")).put("resource_id",node.optString("resource")).put("clickable",node.optBoolean("clickable")).put("bounds",node.getJSONArray("bounds")));}
            JSONObject matched=AppGuideLocalMatch.select(goal,old);if(matched==null)return null;found=AppTaskObservation.find(current,matched.getString("target_node_id"));tool="tap";
        }
        if(found==null)return null;
        String kind=tool.equals("set_text")?"input_present":found.optString("role").matches("toggle|tab")?"selection_changed":"screen_changed";
        JSONObject result=action(tool,found,current,new JSONObject().put("kind",kind),simple(goal));
        return memory.repeated(current,result)||previouslyRejected(current,result)?null:result;
    }
    private String searchMessage(JSONObject current)throws JSONException {
        JSONArray nodes=current.getJSONArray("nodes");for(int i=0;i<nodes.length();i++)if(nodes.getJSONObject(i).optBoolean("editable"))return "키보드의 검색을 눌러주세요.";
        return "검색 결과에서 원하는 상품을 골라주세요.";
    }
    private JSONObject searchAction(JSONObject current)throws Exception {
        JSONArray nodes=current.getJSONArray("nodes");JSONObject field=null,entry=null;boolean ambiguousField=false,ambiguousEntry=false;
        for(int i=0;i<nodes.length();i++){
            JSONObject n=nodes.getJSONObject(i);
            if(n.optBoolean("editable")){if(field!=null)ambiguousField=true;field=n;}
            else if(n.optBoolean("clickable")&&(n.optString("label")+" "+n.optString("resource")).matches("(?i).*(?:검색|search).*" )
                &&!(n.optString("label")+" "+n.optString("resource")).matches("(?i).*(?:음성|카메라|이미지|삭제|취소|지우기|추천|기록|최근|voice|camera|clear|history).*")){
                if(entry!=null)ambiguousEntry=true;entry=n;
            }
        }
        if(ambiguousField)return null;
        if(field!=null){
            if(listener.inputMatches(field.optString("id"),AppTaskObservation.hash(searchQuery))){searchPrepared=true;return null;}
            JSONObject result=action("set_text",field,current,new JSONObject().put("kind","input_present"),false);
            return memory.repeated(current,result)?null:result;
        }
        if(searchPrepared||ambiguousEntry||entry==null)return null;
        JSONObject result=action("tap",entry,current,new JSONObject().put("kind","screen_changed"),false);
        return memory.repeated(current,result)?null:result;
    }
    private JSONObject action(String tool,JSONObject node,JSONObject current,JSONObject expectation,boolean advances)throws JSONException {
        return new JSONObject().put("id",UUID.randomUUID().toString()).put("tool",tool)
            .put("target_id",node==null?JSONObject.NULL:node.getString("id")).put("bounds",node==null?JSONObject.NULL:node.getJSONArray("bounds"))
            .put("package",current.optString("package")).put("expectation",expectation).put("advances_subgoal",advances);
    }
    private JSONObject reuse(JSONObject current)throws Exception {
        if(cached==null)return null;JSONArray route=cached.optJSONArray("route");int index=memory.route().length();if(route==null||index>=route.length())return null;
        JSONObject step=route.getJSONObject(index);if(!step.optString("package").equals(current.optString("package")))return null;
        String tool=step.optString("tool");if("open_app".equals(tool))return null;
        JSONObject match=null;JSONArray nodes=current.getJSONArray("nodes");
        if(!"back".equals(tool)){for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(step.optString("resource").equals(n.optString("resource"))&&step.optString("label").equals(n.optString("label"))){if(match!=null)return null;match=n;}}if(match==null)return null;}
        JSONObject result=action(tool,match,current,step.getJSONObject("expectation"),step.optBoolean("advances_subgoal"));
        return AppTaskPolicy.allowed(current,current,result,selectedPackage)&&!memory.repeated(current,result)&&!previouslyRejected(current,result)?result:null;
    }
    static final long RECALL_WAIT_MS=1500;
    /**
     * A result condition that natively matched a screen the user confirmed (same goal, same app
     * version). It is parked, not installed: like a parked prediction it can only become this
     * task's candidate where it natively matches, so a same-label menu entry never "proves" it.
     */
    private boolean seedRememberedCompletion(){
        if(!speed.rememberedCompletion||!completionSupported||store==null||scope==null||memory.actions()>0||memory.completion()!=null||memory.finished())return false;
        JSONObject remembered=store.rememberedCompletion(memory.goal(),scope);
        if(!AppTaskCompletion.valid(remembered))return false;
        parkedCompletion=remembered;parkedRemembered=true;return true;
    }
    /** No planner condition after a step: check the goal's own destination title/tab, locally. */
    private boolean seedGoalDerivedCompletion(JSONObject current,ScreenSnapshot screen)throws JSONException {
        if(!speed.goalDerivedCompletion||!completionSupported||memory.finished()||memory.completionSupported()||memory.completion()!=null
                ||parkedCompletion!=null||memory.actions()==0||screen==null||selectedPackage!=null&&!selectedPackage.equals(screen.packageName))return false;
        JSONObject derived=AppTaskCompletion.goalDerivedCompletion(memory.goal(),current,selectedLabel);
        if(derived==null)return false;
        parkedCompletion=derived;parkedRemembered=false;
        completionTrace("candidate_parked","goal_derived",derived,current,screen,null,"not_checked","not_checked",null,0,0);
        return reofferParkedCompletion(screen);
    }
    /** A remembered, once-proved condition outranks a discarded model prediction. */
    private void park(JSONObject candidate){if(speed.reofferPrediction&&!parkedRemembered&&AppTaskCompletion.valid(candidate))parkedCompletion=candidate;}
    /** Re-offer a parked condition only on a screen where it is native, unambiguous, safe proof. */
    private boolean reofferParkedCompletion(ScreenSnapshot screen)throws JSONException {
        JSONObject parked=parkedCompletion;
        if(parked==null||screen==null||!completionSupported||memory.finished()||memory.completionSupported())return false;
        // A bound candidate that differs may still be replaced, but only by memory's own refinement rule.
        if(memory.completion()!=null&&AppTaskCompletion.key(memory.completion()).equals(AppTaskCompletion.key(parked))){parkedCompletion=null;parkedRemembered=false;return false;}
        if(selectedPackage!=null&&!selectedPackage.equals(screen.packageName))return false;
        JSONObject observed=AppTaskObservation.capture(screen,AppTaskCompletion.labels(parked));
        if(AppTaskCompletion.checkForGoal(memory.goal(),parked,observed,screen)!=AppTaskCompletion.Verdict.MATCHED)return false;
        int version=memory.evidenceVersion();
        boolean accepted=memory.offerCompletion(parked,observed,screen);
        completionTrace(accepted?"candidate_accepted":"candidate_rejected","parked_result_matched",parked,observed,screen,completionRequestRef,"matched","fresh",null,0,0);
        if(!accepted)return false;
        parkedCompletion=null;parkedRemembered=false;if(version!=memory.evidenceVersion())resetResultEvidence();save();return true;
    }
    private void rememberConfirmedCompletion(){
        if(!speed.rememberedCompletion||!completionSupported||store==null||scope==null||memory.completion()==null||!listener.canObserve())return;
        try{
            ScreenSnapshot fresh=listener.fresh();if(fresh==null||selectedPackage!=null&&!selectedPackage.equals(fresh.packageName))return;
            JSONObject candidate=memory.completion();
            JSONObject observed=AppTaskObservation.capture(fresh,AppTaskCompletion.labels(candidate));
            // Only a condition proved on the screen the user is confirming is worth checking first next time.
            if(AppTaskCompletion.checkForGoal(memory.goal(),candidate,observed,fresh)==AppTaskCompletion.Verdict.MATCHED
                    &&AppTaskCompletion.distinctProofTargets(candidate,observed)&&!AppTaskCompletion.unsafeForGoal(memory.goal(),candidate,observed,fresh))
                store.recordCompletion(memory.goal(),scope,candidate);
        }catch(Exception ignored){ /* Remembering is an optimization; it never changes this result. */ }
    }
    /** Planner hints for later steps: exact public tap labels, re-grounded on the observed screen. */
    static JSONArray plannedSteps(JSONObject response,JSONObject planningScreen){
        JSONArray raw=response.optJSONArray("planned_steps"),out=new JSONArray();
        // A label already visible where the plan was made names that control, not a later screen's.
        Set<String> visible=new HashSet<>();JSONArray nodes=planningScreen==null?null:planningScreen.optJSONArray("nodes");
        for(int i=0;nodes!=null&&i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n!=null&&!n.optString("label").isEmpty())visible.add(n.optString("label"));}
        for(int i=0;raw!=null&&i<raw.length()&&out.length()<3;i++){JSONObject step=raw.optJSONObject(i);if(step==null)continue;
            String label=step.optString("label","").trim();
            if(!"tap".equals(step.optString("tool"))||label.isEmpty()||label.length()>80||visible.contains(label))break;
            try{out.put(new JSONObject().put("tool","tap").put("label",label));}catch(JSONException ignored){}}
        return out.length()==0?null:out;
    }
    private JSONObject plannedStep(JSONObject current)throws JSONException {
        if(!speed.plannedRoute||!plannedReady||planned==null||planned.length()==0)return null;
        plannedReady=false;String label=planned.getJSONObject(0).getString("label");planned.remove(0);
        JSONObject match=null;JSONArray nodes=current.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);
            if(label.equals(n.optString("label"))&&n.optBoolean("clickable")&&!n.optBoolean("editable")){if(match!=null){planned=null;return null;}match=n;}}
        if(match==null){planned=null;return null;}
        JSONObject result=action("tap",match,current,new JSONObject().put("kind","screen_changed"),false);
        if(!AppTaskPolicy.allowed(current,current,result,selectedPackage)||!AppTaskPolicy.manualTapAllowed(current,result)
                ||memory.repeated(current,result)||previouslyRejected(current,result)||meta.blocked(current,result)){planned=null;return null;}
        memory.localPlan();
        return result;
    }
    /**
     * The screen redrew while the planner answered (banner, clock, reflow). Rebind the action to the
     * same control only when it is unique on the same native window by an identity the user can see.
     */
    private JSONObject rebindChangedFrame(JSONObject request,JSONObject response,ScreenSnapshot sent,ScreenSnapshot fresh,JSONObject freshObservation)throws JSONException {
        JSONObject action=response.optJSONObject("action");
        if(!speed.rebindChangedFrame||action==null||fresh==null||freshObservation==null||!sameNativeWindow(sent,fresh)
                ||!"public".equals(freshObservation.optString("status"))||!request.getJSONObject("screen").optString("package").equals(freshObservation.optString("package")))return null;
        String tool=action.optString("tool");if(!Set.of("tap","set_text","scroll_forward","scroll_backward").contains(tool))return null;
        JSONObject old=AppTaskObservation.find(request.getJSONObject("screen"),action.optString("target_id"));if(old==null)return null;
        String label=old.optString("label"),resource=old.optString("resource"),role=old.optString("role");
        if(label.isEmpty()&&resource.isEmpty())return null;
        JSONArray nodes=freshObservation.getJSONArray("nodes");
        JSONObject match=uniqueMatch(nodes,tool,n->AppTaskObservation.targetKey(n).equals(AppTaskObservation.targetKey(old)));
        if(match==null&&!resource.isEmpty())match=uniqueMatch(nodes,tool,n->resource.equals(n.optString("resource"))&&role.equals(n.optString("role"))&&(label.isEmpty()||n.optString("label").isEmpty()));
        if(match==null)return null;
        JSONObject result=new JSONObject(action.toString()).put("target_id",match.getString("id")).put("bounds",match.getJSONArray("bounds"));
        return AppTaskPolicy.allowed(freshObservation,freshObservation,result,selectedPackage)?result:null;
    }
    private static JSONObject uniqueMatch(JSONArray nodes,String tool,java.util.function.Predicate<JSONObject> same){
        JSONObject match=null;
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n==null||!same.test(n))continue;
            boolean capable="set_text".equals(tool)?n.optBoolean("editable"):tool.startsWith("scroll_")?n.optBoolean("scrollable"):n.optBoolean("clickable")&&!n.optBoolean("editable");
            if(!capable)continue;if(match!=null)return null;match=n;}
        return match;
    }
    private void propose(JSONObject action)throws JSONException {
        cancelPlannerWait();
        if(meta.blocked(observation,action)||memory.repeated(observation,action)){meta.clearRecoveryContext();proposal=null;show("needs_input","이 화면에서 반영되지 않은 동작이에요. 다른 방법을 선택하거나 직접 진행해 주세요.");return;}
        if(!AppTaskPolicy.allowed(observation,observation,action,selectedPackage)&&!("tap".equals(action.optString("tool"))&&AppTaskPolicy.manualTapAllowed(observation,action))){proposal=null;show("needs_input","이 동작은 직접 진행해 주세요. 이후 현재 화면을 다시 확인할 수 있어요.");return;}
        proposal=new JSONObject(action.toString()).put("id",UUID.randomUUID().toString());proposedAt=System.currentTimeMillis();
        observation=capture(baseline);submitted=observation.getString("fingerprint");
        if("tap".equals(proposal.optString("tool"))){
            JSONObject target=AppTaskObservation.find(observation,proposal.optString("target_id"));
            if(target!=null){JSONArray b=target.getJSONArray("bounds");manualActions.bind(listener.sessionId(),memory.id(),proposal.getString("id"),"tap",target.getString("id"),new int[]{b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3)},baseline,proposedAt);}
        }else manualActions.clear();
        String tool=action.optString("tool");
        JSONObject target=AppTaskObservation.find(observation,proposal.optString("target_id"));
        String label=target==null?"":target.optString("label");
        String message=switch(tool){
            case "open_app" -> "‘"+RequestedApp.shortName(selectedLabel)+" 열기’를 눌러 앱을 열어주세요.";
            case "set_text" -> "검색창에 넣을 검색어를 확인해 주세요.";
            case "scroll_forward" -> "‘클릭이 아래로 이동’을 누르면 목록을 내려요.";
            case "scroll_backward" -> "‘클릭이 위로 이동’을 누르면 목록을 올려요.";
            case "back" -> "‘클릭이 뒤로 이동’을 누르면 이전 화면으로 돌아가요.";
            default -> "‘"+label+"’ 항목을 직접 눌러주세요.";
        };
        show("proposed",message);
    }
    public void chooseApp(String packageName,String label){ chooseApp(new RequestedApp(packageName,label)); }
    void chooseApp(RequestedApp app){
        String packageName=app.packageName;
        if(closed||busy||verifying||!listener.canObserve())return;
        try{leaveObservedResult();localDiagnostic("selected_app");targetApp(app);baseline=listener.fresh();observation=capture(baseline);submitted=observation.getString("fingerprint");memory.localPlan();
            proposal=action("open_app",null,observation,new JSONObject().put("kind","package_opened").put("package",packageName),false).put("package",packageName);
            propose(proposal);
        }catch(Exception e){listener.failure(e);}
    }
    public void chooseTool(String tool){
        if(closed||busy||verifying||!listener.canObserve())return;
        try{planned=null;leaveObservedResult();localDiagnostic("selected_tool");baseline=listener.fresh();observation=capture(baseline);submitted=observation.getString("fingerprint");JSONObject target=null;
            if(!"back".equals(tool)){JSONArray nodes=observation.getJSONArray("nodes");for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(tool.equals("set_text")?n.optBoolean("editable"):n.optBoolean("scrollable")){if(target!=null){show("needs_input","항목이 여러 개예요. 원하는 항목이 보이도록 화면을 좁혀 주세요.");return;}target=n;}}if(target==null){show("needs_input","이 화면에서 해당 동작을 할 항목을 찾지 못했어요.");return;}}
            memory.localPlan();propose(action(tool,target,observation,new JSONObject().put("kind",tool.equals("set_text")?"input_present":"screen_changed"),false));save();
        }catch(Exception e){listener.failure(e);}
    }
    private void leaveObservedResult()throws JSONException {
        cancelPlannerWait();
        if(resultOffered||checkingResult)ignoredResultFingerprint=capture(listener.fresh()).optString("fingerprint");
        resultObservation=null;resultAssessment=null;resultOffered=checkingResult=false;handler.removeCallbacks(refresh);
    }
    public void execute(String actionId,String input){if(proposal!=null&&proposal.optString("id").equals(actionId))execute(input);}
    public void execute(String input){
        refreshLearningConsent();
        if(closed||busy||verifying||checkingResult||resultOffered||proposal==null||memory.actions()>=64||!listener.canObserve())return;
        try{
            ScreenSnapshot fresh=listener.fresh();JSONObject current=capture(fresh);
            rebindInput(current,fresh);
            if(previouslyRejected(current,proposal)){proposal=null;show("needs_input","이전에 이 화면에서 동작을 확인하지 못했어요. 현재 화면을 다시 확인해 다른 방법을 준비해 주세요.");return;}
            if(!AppTaskPolicy.allowed(observation,current,proposal,selectedPackage)){proposal=null;submitted=null;show("needs_input","화면이나 대상이 바뀌었어요. 현재 화면을 다시 확인해 주세요.");return;}
            if("set_text".equals(proposal.optString("tool"))&&(input==null||input.isEmpty()||input.length()>200)){show("needs_input","입력할 검색어를 확인해 주세요.");return;}
            inputHash=input==null?null:AppTaskObservation.hash(input);
            if("set_text".equals(proposal.optString("tool"))&&searchQuery!=null)searchQuery=input;
            actionAt=System.currentTimeMillis();
            meta.clearRecoveryContext();
            boolean accepted=listener.execute(baseline,observation,proposal,input,selectedPackage);
            if(!accepted){
                record(observation,current,proposal,"failed",false,false,false);invalidateReplay();proposal=null;
                if(goalFirstSupported&&completionSupported){
                    ScreenSnapshot after=listener.fresh();
                    if(after!=null){reofferParkedCompletion(after);if(observeResult(capture(after),after))return;}
                }
                show("needs_input","동작을 실행하지 못했어요. 화면을 다시 확인하거나 직접 진행해 주세요.");return;
            }
            beginVerification();
        }catch(Exception e){listener.failure(e);}
    }
    /**
     * Typing into the panel raises the keyboard, which resizes the app and reshuffles its
     * suggestions. That moves the search field without changing it, so keep a set_text proposal
     * bound to the same unique field instead of discarding it and re-planning mid-typing.
     */
    private boolean rebindInput(JSONObject current,ScreenSnapshot screen)throws JSONException {
        if(proposal==null||!"set_text".equals(proposal.optString("tool"))||observation==null||screen==null)return false;
        if(current.optString("fingerprint").equals(observation.optString("fingerprint")))return false;
        if(!"public".equals(current.optString("status"))||!current.optString("package").equals(observation.optString("package")))return false;
        JSONObject old=AppTaskObservation.find(observation,proposal.optString("target_id"));if(old==null)return false;
        String key=AppTaskObservation.targetKey(old);JSONObject match=null;JSONArray nodes=current.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(n.optBoolean("editable")&&key.equals(AppTaskObservation.targetKey(n))){if(match!=null)return false;match=n;}}
        if(match==null)return false;
        proposal.put("target_id",match.getString("id")).put("bounds",match.getJSONArray("bounds"));
        observation=current;baseline=screen;submitted=current.getString("fingerprint");
        return true;
    }
    public void manualTap(String pkg,int window,String id,long occurredAt){
        if(baseline==null)return;
        for(var source:baseline.nodes)if(Objects.equals(source.nodeId,id)){manualTap(pkg,window,id,source.resourceId,source.bounds,occurredAt);return;}
    }
    public void manualTap(String pkg,int window,String id,String resource,int[] bounds,long occurredAt){
        if(closed||busy||verifying||checkingResult||resultOffered||proposal==null||!listener.canObserve()||memory.actions()>=64||!AppTaskPolicy.manualTapAllowed(observation,proposal))return;
        if(manualActions.record(com.click.data.remote.AgentActionEvents.TAP_COMPLETED,pkg,window,id,resource,bounds,occurredAt)!=null){actionAt=occurredAt;beginVerification();}
    }
    private void beginVerification(){plannerGateArmed=true;meta.clearRecoveryContext();manualActions.clear();actionObserved=true;verifying=true;actionStarted=SystemClock.elapsedRealtime();try{show("verifying","동작이 반영됐는지 확인하고 있어요.");}catch(Exception e){listener.failure(e);}handler.postDelayed(verify,250);}
    private void verifyNow(){
        if(closed||!verifying||!listener.canObserve())return;
        try{
            ScreenSnapshot fresh=listener.fresh();
            if(goalFirstSupported)reofferParkedCompletion(fresh);
            JSONObject after=capture(fresh);
            if (selectedApp != null && fresh != null && fresh.activeWindowId != null
                    && !selectedPackage.equals(fresh.packageName) && !"open_app".equals(proposal.optString("tool"))) {
                finishVerification(after, AppTaskVerifier.Verdict.STALE);
                awaitingAppEntry=true;
                handler.post(refresh);
                return;
            }
            JSONObject target=AppTaskVerifier.resolveTarget(observation,after,proposal.optString("target_id"));
            boolean text="set_text".equals(proposal.optString("tool"))&&target!=null&&listener.inputMatches(target.optString("id"),inputHash);
            AppTaskVerifier.Verdict verdict=AppTaskVerifier.check(observation,after,proposal,actionObserved,text,actionAt);
            if ("open_app".equals(proposal.optString("tool")) && selectedApp != null && !selectedApp.reached(fresh)
                    && verdict==AppTaskVerifier.Verdict.VERIFIED) verdict=AppTaskVerifier.Verdict.WAITING;
            long now=SystemClock.elapsedRealtime();
            meta.subgoal(memory.active());AppTaskMetaController.Decision decision=meta.after(observation,after,proposal,verdict,now-actionStarted,now,baseline,fresh);
            if(decision==AppTaskMetaController.Decision.WAIT){
                if(goalFirstSupported&&observeResult(after,fresh))return;
                handler.removeCallbacks(verify);handler.postDelayed(verify,400);return;
            }
            boolean verified=verdict==AppTaskVerifier.Verdict.VERIFIED;
            finishVerification(after,verdict);
            if(observeResult(after,fresh))return;
            if(verified||decision==AppTaskMetaController.Decision.REOBSERVE||decision==AppTaskMetaController.Decision.REOBSERVE_PROGRESS){handler.post(refresh);}else{baseline=fresh;observation=after;show("needs_input",verdict==AppTaskVerifier.Verdict.PROTECTED?"입력·인증은 직접 진행해 주세요. 진행 상태는 보관했어요.":verdict==AppTaskVerifier.Verdict.UNKNOWN?"결과를 확인할 화면이나 대상을 읽지 못했어요. 현재 화면을 다시 확인해 주세요.":"아직 변화를 확인하지 못했어요. 같은 동작을 반복하지 않고 다른 방법을 확인해 주세요.");}
        }catch(Exception e){verifying=false;listener.failure(e);}
    }
    private void finishVerification(JSONObject after,AppTaskVerifier.Verdict verdict)throws JSONException {
        boolean verified=verdict==AppTaskVerifier.Verdict.VERIFIED;
        if(!verified){invalidateReplay();planned=null;}
        plannedReady=verified&&planned!=null&&planned.length()>0;
        String outcome=switch(verdict){
            case VERIFIED -> "verified";
            case PROTECTED -> "protected";
            case STALE -> "stale";
            case WAITING, UNKNOWN -> "waiting";
        };
        String previousActive=memory.active();
        record(observation,after,proposal,outcome,actionObserved,verified,verified&&AppTaskPolicy.provesSubgoal(proposal));
        meta.nativeSubgoalAdvanced(previousActive,memory.active(),proposal,verdict,observation,after);
        handler.removeCallbacks(verify);
        proposal=null;verifying=false;inputHash=null;submitted=null;
    }
    private void invalidateReplay(){if(replaying){if(store!=null)store.invalidateRoute(memory.goal(),scope);cached=null;replaying=false;}}
    public void confirmResultOnServer(Runnable confirmed,java.util.function.Consumer<Throwable> failed){
        if(closed||verifying||checkingResult||finishing)return;
        if(!learning||listener.sessionId()==null){confirmResult();confirmed.run();return;}
        try{finishing=true;listener.busy(true);JSONObject request=new JSONObject().put("request_id",UUID.randomUUID().toString())
            .put("session_id",listener.sessionId()).put("task_id",memory.id()).put("outcome","user_confirmed");
            gateway.finishTask(request,new AppGuideGateway.Callback(){
                public void success(JSONObject result){if(closed)return;finishing=false;busy=false;listener.busy(false);confirmResult();confirmed.run();}
                public void failure(Throwable error){if(closed)return;finishing=false;busy=false;listener.busy(false);failed.accept(error);}
            });
        }catch(Exception error){finishing=false;listener.busy(false);failed.accept(error);}
    }
    public void confirmResult(){if(closed||verifying||memory.finished())return;try{
        refreshLearningConsent();
        promoteConfirmedEffect();
        rememberConfirmedCompletion();
        if(learning&&store!=null&&consentId!=null&&listener.sessionId()!=null&&observation!=null&&memory.experienceSequence()<66){
            JSONObject current=capture(listener.fresh());JSONObject event=AppTaskExperience.capture(memory,memory.experienceSequence()+1,current,current,environment(current),null,"user_confirmed",false,false);
            memory.experienceSequence(memory.experienceSequence()+1);memory.confirmResult();store.saveExperience(memory,listener.sessionId(),consentId,event);gateway.flush(store);
        }else{memory.confirmResult();save();}
        if(store!=null&&scope!=null)store.recordRoute(memory,scope);show("completed","요청한 결과를 확인했어요.");}catch(Exception e){listener.failure(e);}}
    /** The user confirmed the screen this observed action produced: its effect is the confirmed result. */
    private void promoteConfirmedEffect()throws JSONException {
        JSONObject last=lastUnverified;lastUnverified=null;if(last==null)return;
        ScreenSnapshot fresh=listener.fresh();if(fresh==null)return;
        if(!last.getString("after_key").equals(AppTaskObservation.routeKey(last.getJSONObject("before")))
                &&last.getString("after_key").equals(AppTaskObservation.routeKey(capture(fresh))))
            memory.confirmLastEffect(last.getJSONObject("before"),last.getJSONObject("action"),last.getString("after_key"));
    }
    public void resume(){meta.clearRecoveryContext();resultObservation=null;checkingResult=resultOffered=false;if(verifying){handler.post(verify);}else refresh();}
    public void close(){closed=true;cancelPlannerWait();manualActions.clear();handler.removeCallbacks(refresh);handler.removeCallbacks(verify);gateway.close();save();}
}
