package com.click.service.overlay;

import android.os.*;
import com.click.data.remote.AppGuideGateway;
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
    }
    private final AppGuideGateway gateway;private final Listener listener;private final AppTaskMemory memory;
    private final AppTaskStore store;private final boolean remote;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private ScreenSnapshot baseline;private JSONObject observation,proposal;
    private String submitted,selectedPackage,selectedLabel,inputHash,scope;
    private String searchQuery;private boolean searchPrepared;
    private boolean busy,closed,verifying,actionObserved,replaying;private long actionStarted,proposedAt;
    private JSONObject diagnostic;
    private int turns;private JSONObject cached;private JSONObject ocr;
    private String ocrNative;private long ocrAt;
    private final Runnable refresh=()->observe(false);
    private final Runnable verify=()->verifyNow();
    public AppTaskController(AppGuideGateway gateway,AppTaskMemory memory,AppTaskStore store,Listener listener,boolean remote){this.gateway=gateway;this.memory=memory;this.store=store;this.listener=listener;this.remote=remote;}
    public AppTaskMemory memory(){return memory;}
    public void targetApp(String packageName,String label){selectedPackage=packageName;selectedLabel=RequestedApp.shortName(label);searchQuery=AppTaskSearchIntent.query(memory.goal(),label);scope=null;cached=null;submitted=null;proposal=null;searchPrepared=false;}
    public boolean matches(ScreenSnapshot screen){try{return screen!=null&&submitted!=null&&submitted.equals(capture(screen).optString("fingerprint"));}catch(Exception e){return false;}}
    private JSONObject capture(ScreenSnapshot screen)throws Exception {
        JSONObject out=AppTaskObservation.capture(screen);
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
    public void screenChanged(ScreenSnapshot screen){if(!closed){handler.removeCallbacks(refresh);handler.postDelayed(refresh,250);}}
    public void refresh(){observe(true);}
    private void save(){if(store!=null)store.save(memory);}
    private void show(String status,String message)throws JSONException {
        JSONObject target=proposal==null||observation==null?null:AppTaskObservation.find(observation,proposal.optString("target_id"));
        JSONObject result=new JSONObject().put("status",status).put("message",message).put("subgoals",memory.state().getJSONArray("subgoals"))
            .put("active_subgoal",memory.active()==null?JSONObject.NULL:memory.active()).put("action",proposal==null?JSONObject.NULL:proposal)
            .put("verifying",verifying).put("execute_allowed",proposal!=null&&AppTaskPolicy.allowed(observation,observation,proposal,selectedPackage))
            .put("target_label",target==null?selectedLabel:target.optString("label"));
        if(proposal!=null&&"set_text".equals(proposal.optString("tool"))&&searchQuery!=null)result.put("suggested_query",searchQuery);
        if(diagnostic!=null)result.put("diagnostic",diagnostic);
        listener.present(result,baseline);
    }
    private void observe(boolean force){
        if(closed||busy||!listener.canObserve())return;
        if(verifying){verifyNow();return;}
        ScreenSnapshot screen=listener.fresh();if(screen==null&&selectedPackage==null)return;
        try{
            JSONObject current=capture(screen);String fingerprint=current.getString("fingerprint");
            if(!force&&fingerprint.equals(submitted))return;
            if(proposal!=null&&!fingerprint.equals(submitted))proposal=null;
            baseline=screen;observation=current;submitted=fingerprint;diagnostic=null;
            if(selectedPackage!=null&&!selectedPackage.equals(current.optString("package"))){
                localDiagnostic("requested_app_entry");
                propose(action("open_app",null,current,new JSONObject().put("kind","package_opened").put("package",selectedPackage),false).put("package",selectedPackage));return;
            }
            if(!"public".equals(current.optString("status"))){show("protected".equals(current.optString("status"))?"protected":"needs_input","protected".equals(current.optString("status"))?"입력·인증은 직접 진행한 뒤 이어서 확인해 주세요.":selectedPackage!=null?"화면을 불러오고 있어요.":"도움이 필요한 앱을 열어주세요.");return;}
            if(scope==null){scope=listener.scope(current.getString("package"));memory.scope(scope);if(store!=null&&memory.actions()==0)cached=store.reusable(memory.goal(),scope);if(cached!=null&&memory.state().getJSONArray("subgoals").length()==0)memory.plan(cached.optJSONArray("subgoals"));}
            if(memory.actions()>=64){show("needs_input","진행 단계가 많아졌어요. 현재 결과를 확인하고 새 작업으로 이어가 주세요.");return;}
            if(memory.finished()){show("completed","요청한 결과를 확인했어요.");return;}
            if(memory.state().getJSONArray("subgoals").length()>0&&memory.active()==null){show("confirm_result","요청하신 결과가 나왔나요? 확인한 뒤 안내를 끝내 주세요.");return;}
            if(!remote){memory.localPlan();save();}
            replaying=false;JSONObject cachedAction=reuse(current);if(cachedAction!=null){replaying=true;localDiagnostic("verified_route");propose(cachedAction);return;}
            JSONObject local=local(current);if(local!=null){memory.localPlan();save();localDiagnostic("public_control_match");propose(local);return;}
            if(!remote){memory.localPlan();save();show("needs_input",searchQuery!=null?(searchPrepared?searchMessage(current):"검색창을 눌러주세요. 화면이 바뀌면 이어서 안내할게요."):"원하는 항목을 눌러주세요. 화면이 바뀌면 이어서 안내할게요.");return;}
            if(turns>=32){show("needs_input","안내 횟수에 도달했어요. 진행 내용을 확인하고 새 요청으로 시작해 주세요.");return;}
            JSONObject request=new JSONObject().put("protocol_version","app-task.v2").put("request_id",UUID.randomUUID().toString())
                .put("goal",memory.goal()).put("screen",current).put("state",memory.state()).put("apps",new JSONArray());
            if(selectedPackage!=null)request.getJSONArray("apps").put(new JSONObject().put("package",selectedPackage).put("label",selectedLabel));
            busy=true;turns++;listener.busy(true);
            gateway.send(request,new AppGuideGateway.Callback(){
                public void success(JSONObject response){
                    if(closed)return;busy=false;listener.busy(false);if(!listener.canObserve())return;
                    try{
                        ScreenSnapshot fresh=listener.fresh();if(fresh==null||!fingerprint.equals(capture(fresh).optString("fingerprint"))){handler.post(refresh);return;}
                        diagnostic=response.optJSONObject("diagnostic");memory.plan(response.optJSONArray("subgoals"));save();
                        JSONObject action=response.optJSONObject("action");
                        if(action!=null){if(memory.repeated(observation,action)){show("needs_input","같은 동작이 반복해서 실패했어요. 다른 동작을 선택하거나 직접 화면을 이동해 주세요.");return;}propose(action);}
                        else show(response.optString("status"),response.optString("message"));
                    }catch(Exception error){listener.failure(error);}
                }
                public void failure(Throwable error){if(!closed){busy=false;listener.busy(false);listener.failure(error);}}
            });
        }catch(Exception error){listener.failure(error);}
    }
    private static boolean simple(String title){return !title.matches(".*(?:해서|찾아|결과|비교|중에서|다음|그리고|하고).*");}
    private void localDiagnostic(String reason)throws JSONException{diagnostic=new JSONObject().put("model","on-device-tools").put("called",false).put("reason",reason).put("latency_ms",0);}
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
            for(int i=0;i<nodes.length();i++){JSONObject node=nodes.getJSONObject(i);old.getJSONArray("nodes").put(new JSONObject().put("node_id",node.getString("id")).put("label",node.getString("label")).put("resource_id",node.optString("resource")).put("clickable",node.optBoolean("clickable")).put("bounds",node.getJSONArray("bounds")));}
            JSONObject matched=AppGuideLocalMatch.select(goal,old);if(matched==null)return null;found=AppTaskObservation.find(current,matched.getString("target_node_id"));tool="tap";
        }
        if(found==null)return null;
        String kind=tool.equals("set_text")?"input_present":found.optString("role").matches("toggle|tab")?"selection_changed":"screen_changed";
        JSONObject result=action(tool,found,current,new JSONObject().put("kind",kind),simple(goal));
        return memory.repeated(current,result)?null:result;
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
        return AppTaskPolicy.allowed(current,current,result,selectedPackage)&&!memory.repeated(current,result)?result:null;
    }
    private void propose(JSONObject action)throws JSONException {
        if(!AppTaskPolicy.allowed(observation,observation,action,selectedPackage)&&!("tap".equals(action.optString("tool"))&&AppTaskPolicy.manualTapAllowed(observation,action))){proposal=null;show("needs_input","이 동작은 직접 진행해 주세요. 이후 현재 화면을 다시 확인할 수 있어요.");return;}
        proposal=action;proposedAt=System.currentTimeMillis();
        String tool=action.optString("tool");
        show("proposed","open_app".equals(tool)?RequestedApp.shortName(selectedLabel)+"을 열어주세요.":"set_text".equals(tool)?"검색어를 확인해 주세요.":"다음 항목을 눌러주세요.");
    }
    public void chooseApp(String packageName,String label){
        if(closed||busy||verifying||!listener.canObserve())return;
        try{localDiagnostic("selected_app");targetApp(packageName,label);baseline=listener.fresh();observation=capture(baseline);submitted=observation.getString("fingerprint");memory.localPlan();
            proposal=action("open_app",null,observation,new JSONObject().put("kind","package_opened").put("package",packageName),false).put("package",packageName);
            propose(proposal);
        }catch(Exception e){listener.failure(e);}
    }
    public void chooseTool(String tool){
        if(closed||busy||verifying||!listener.canObserve())return;
        try{localDiagnostic("selected_tool");baseline=listener.fresh();observation=capture(baseline);submitted=observation.getString("fingerprint");JSONObject target=null;
            if(!"back".equals(tool)){JSONArray nodes=observation.getJSONArray("nodes");for(int i=0;i<nodes.length();i++){JSONObject n=nodes.getJSONObject(i);if(tool.equals("set_text")?n.optBoolean("editable"):n.optBoolean("scrollable")){if(target!=null){show("needs_input","항목이 여러 개예요. 원하는 항목이 보이도록 화면을 좁혀 주세요.");return;}target=n;}}if(target==null){show("needs_input","이 화면에서 해당 동작을 할 항목을 찾지 못했어요.");return;}}
            memory.localPlan();propose(action(tool,target,observation,new JSONObject().put("kind",tool.equals("set_text")?"input_present":"screen_changed"),false));save();
        }catch(Exception e){listener.failure(e);}
    }
    public void execute(String actionId,String input){if(proposal!=null&&proposal.optString("id").equals(actionId))execute(input);}
    public void execute(String input){
        if(closed||busy||verifying||proposal==null||memory.actions()>=64||!listener.canObserve())return;
        try{
            ScreenSnapshot fresh=listener.fresh();JSONObject current=capture(fresh);
            if(!AppTaskPolicy.allowed(observation,current,proposal,selectedPackage)){proposal=null;submitted=null;show("needs_input","화면이나 대상이 바뀌었어요. 현재 화면을 다시 확인해 주세요.");return;}
            if("set_text".equals(proposal.optString("tool"))&&(input==null||input.isEmpty()||input.length()>200)){show("needs_input","입력할 검색어를 확인해 주세요.");return;}
            inputHash=input==null?null:AppTaskObservation.hash(input);
            if("set_text".equals(proposal.optString("tool"))&&searchQuery!=null)searchQuery=input;
            boolean accepted=listener.execute(baseline,observation,proposal,input,selectedPackage);
            if(!accepted){memory.record(observation,proposal,"failed",false);invalidateReplay();save();proposal=null;show("needs_input","동작을 실행하지 못했어요. 화면을 다시 확인하거나 직접 진행해 주세요.");return;}
            beginVerification();
        }catch(Exception e){listener.failure(e);}
    }
    public void manualTap(String pkg,int window,String id,long occurredAt){
        if(closed||busy||verifying||proposal==null||!listener.canObserve()||memory.actions()>=64||!"tap".equals(proposal.optString("tool"))||occurredAt<proposedAt||!AppTaskPolicy.manualTapAllowed(observation,proposal))return;
        if(observation!=null&&observation.optString("package").equals(pkg)&&proposal.optString("target_id").equals(id)
                &&baseline!=null&&baseline.activeWindowId!=null&&baseline.activeWindowId==window)beginVerification();
    }
    private void beginVerification(){actionObserved=true;verifying=true;actionStarted=SystemClock.elapsedRealtime();try{show("verifying","동작이 반영됐는지 확인하고 있어요.");}catch(Exception e){listener.failure(e);}handler.postDelayed(verify,250);}
    private void verifyNow(){
        if(closed||!verifying||!listener.canObserve())return;
        try{
            ScreenSnapshot fresh=listener.fresh();JSONObject after=capture(fresh);
            boolean text="set_text".equals(proposal.optString("tool"))&&listener.inputMatches(proposal.optString("target_id"),inputHash);
            AppTaskVerifier.Verdict verdict=AppTaskVerifier.check(observation,after,proposal,actionObserved,text);
            if(verdict==AppTaskVerifier.Verdict.WAITING&&SystemClock.elapsedRealtime()-actionStarted<5000){handler.removeCallbacks(verify);handler.postDelayed(verify,400);return;}
            boolean verified=verdict==AppTaskVerifier.Verdict.VERIFIED;
            if(!verified)invalidateReplay();
            memory.record(observation,proposal,verified?"verified":verdict==AppTaskVerifier.Verdict.PROTECTED?"protected":verdict==AppTaskVerifier.Verdict.STALE?"stale":"failed",verified&&proposal.optBoolean("advances_subgoal"));
            save();proposal=null;verifying=false;inputHash=null;submitted=null;
            if(verified){handler.post(refresh);}else{baseline=fresh;observation=after;show("needs_input",verdict==AppTaskVerifier.Verdict.PROTECTED?"입력·인증은 직접 진행해 주세요. 진행 상태는 보관했어요.":"아직 변화를 확인하지 못했어요. 같은 동작을 반복하지 않고 다른 방법을 확인해 주세요.");}
        }catch(Exception e){verifying=false;listener.failure(e);}
    }
    private void invalidateReplay(){if(replaying){if(store!=null)store.invalidateRoute(memory.goal(),scope);cached=null;replaying=false;}}
    public void confirmResult(){if(closed||verifying)return;try{memory.confirmResult();save();if(store!=null&&scope!=null)store.recordRoute(memory,scope);show("completed","요청한 결과를 확인했어요.");}catch(Exception e){listener.failure(e);}}
    public void resume(){if(verifying){handler.post(verify);}else refresh();}
    public void close(){closed=true;handler.removeCallbacks(refresh);handler.removeCallbacks(verify);gateway.close();save();}
}
