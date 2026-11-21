package com.click.service.overlay;

import org.json.*;
import java.util.*;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;

/** Bounded task state; only the verifier or explicit user result can advance it. */
public final class AppTaskMemory {
    private final JSONObject data;
    private String lastCompletionOfferReason="missing_candidate";
    public String lastCompletionOfferReason(){return lastCompletionOfferReason;}
    private boolean completionOffer(boolean accepted,String reason){lastCompletionOfferReason=reason;return accepted;}
    public AppTaskMemory(String goal)throws JSONException {
        data=new JSONObject().put("task_id",UUID.randomUUID().toString()).put("goal",goal)
            .put("revision",0).put("subgoals",new JSONArray()).put("attempts",new JSONArray()).put("route",new JSONArray()).put("completed",false);
        initializeEvidence();
    }
    public AppTaskMemory(JSONObject stored)throws JSONException {
        data=new JSONObject(stored.toString());
        data.getString("task_id");data.getJSONArray("subgoals");data.getJSONArray("attempts");
        initializeEvidence();
        if(data.has("outcome_goal")&&!AppTaskOutcomes.valid(outcomeGoal()))throw new JSONException("Invalid stored outcome");
        JSONObject candidate=completion();
        if(!finished()&&candidate!=null){
            AppTaskSwitchGoal requested=AppTaskSwitchGoal.parse(goal());
            if(requested!=null&&!requested.possible(candidate.getJSONArray("checks"))){
                // Older builds could mark a page title as a supported switch result.
                JSONObject evidence=data.getJSONObject("completion_evidence");
                evidence.put("status","provisional");
                JSONObject requirements=evidence.optJSONObject("requirements");
                if(requirements!=null&&!requested.possible(requirements.getJSONArray("checks")))evidence.remove("requirements");
                discardCompletion();
            }
        }
    }
    public String goal(){return data.optString("goal");}
    public String id(){return data.optString("task_id");}
    public JSONObject stored()throws JSONException{return new JSONObject(data.toString());}
    public JSONObject state()throws JSONException{JSONObject state=new JSONObject().put("task_id",id()).put("revision",data.optInt("revision")).put("subgoals",data.getJSONArray("subgoals")).put("attempts",data.getJSONArray("attempts"));if(outcomeGoal()!=null)state.put("outcome_goal",new JSONObject(outcomeGoal().toString()));else if(completionSupported()&&completion()!=null)state.put("completion",completion());return state;}
    /** The local candidate is separate from the wire's supported completion contract. */
    JSONObject outcomeGoal(){return data.optJSONObject("outcome_goal");}
    boolean installOutcome(JSONObject goal)throws JSONException {
        if(!AppTaskOutcomes.valid(goal)||finished())return false;
        if(outcomeGoal()!=null)return outcomeGoal().getString("seal").equals(goal.getString("seal"));
        data.put("outcome_goal",new JSONObject(goal.toString())).put("numeric_baselines",new JSONObject());
        JSONObject evidence=data.getJSONObject("completion_evidence");
        evidence.put("version",evidenceVersion()+1).put("status","provisional");
        evidence.remove("requirements");evidence.remove("candidate_key");data.remove("completion");
        return true;
    }
    JSONObject bindings(){return data.optJSONObject("outcome_bindings");}
    void bindObserved(JSONObject mapping)throws JSONException {
        if(outcomeGoal()==null||mapping==null||mapping.length()==0)return;
        JSONObject known=bindings()==null?new JSONObject():bindings();
        boolean changed=false;
        for(Iterator<String> it=mapping.keys();it.hasNext();){
            String key=it.next();JSONObject check=mapping.optJSONObject(key);
            if(key.matches("r[0-7]")&&Integer.parseInt(key.substring(1))<outcomeGoal().getJSONArray("clauses").length()
                    &&check!=null&&AppTaskCompletion.valid(new JSONObject().put("checks",new JSONArray().put(check)))
                    &&!AppTaskCompletion.sameCheck(check,known.optJSONObject(key))){
                known.put(key,new JSONObject(check.toString()));changed=true;
            }
        }
        if(changed){
            JSONObject evidence=data.getJSONObject("completion_evidence");
            evidence.put("version",evidenceVersion()+1);
            data.put("outcome_bindings",known);
        }
    }
    JSONObject arrivals()throws JSONException {
        if(!data.has("arrival_receipts"))data.put("arrival_receipts",new JSONObject());
        return data.getJSONObject("arrival_receipts");
    }
    JSONObject baselines()throws JSONException {
        if(!data.has("numeric_baselines"))data.put("numeric_baselines",new JSONObject());
        return data.getJSONObject("numeric_baselines");
    }
    private String resultScreenKey(JSONObject current)throws JSONException {
        Set<String> names=AppTaskCompletion.labels(completion());
        names.addAll(AppTaskOutcomes.labels(outcomeGoal()));
        JSONObject bound=bindings();
        if(bound!=null)for(Iterator<String> it=bound.keys();it.hasNext();)names.add(bound.getJSONObject(it.next()).optString("label"));
        Set<String> normalized=new HashSet<>();
        for(String name:names)normalized.add(name.replaceAll("\\s+","").toLowerCase(Locale.ROOT));
        if(normalized.isEmpty())return AppTaskObservation.routeKey(current);
        List<String> facts=new ArrayList<>();
        JSONArray nodes=current.getJSONArray("nodes");
        for(int i=0;i<nodes.length();i++){
            JSONObject n=nodes.getJSONObject(i);
            if("accessibility".equals(n.optString("source"))&&(normalized.contains(n.optString("label").replaceAll("\\s+","").toLowerCase(Locale.ROOT))
                    ||"toggle".equals(n.optString("role"))&&AppTaskSwitchGoal.genericLabel(n.optString("label"))))
                facts.add(AppTaskObservation.targetKey(n)+"|"+n.optString("checked")+"|"+n.optString("selected"));
        }
        Collections.sort(facts);
        return AppTaskObservation.hash(current.optString("package")+"|"+current.optString("status")+"|"+String.join("\n",facts));
    }
    boolean declinedOn(JSONObject current)throws JSONException {
        return current!=null&&data.has("declined_screen")&&data.optString("declined_screen").equals(resultScreenKey(current));
    }
    boolean notifyRejection(){return data.optBoolean("notify_rejection");}
    void acknowledgedRejection(){data.remove("notify_rejection");}
    void clearDecline(){data.remove("declined_screen");}
    void reopenResult(String reason,JSONObject current,int clause)throws JSONException {
        if(finished())return;
        JSONObject evidence=data.getJSONObject("completion_evidence");
        evidence.put("version",evidenceVersion()+1).put("status","provisional");
        evidenceEvent(reason,current.optString("observation_id"),null,null);
        if("user_rejected_result".equals(reason)){
            data.put("notify_rejection",true);
            data.put("declined_screen",resultScreenKey(current));
            if(clause<0)data.remove("arrival_receipts");else arrivals().remove(Integer.toString(clause));
        }
        data.put("route_disputed",true);
        JSONArray stages=data.getJSONArray("subgoals");
        int index=stages.length()-1;
        if(clause>=0&&outcomeGoal()!=null){
            JSONArray labels=outcomeGoal().getJSONArray("clauses").getJSONObject(clause).getJSONArray("labels");
            int best=0;
            for(int i=0;i<stages.length();i++){
                String title=AppTaskOutcomes.normalize(stages.getJSONObject(i).getString("title"));
                for(int j=0;j<labels.length();j++){
                    String name=AppTaskOutcomes.normalize(labels.getString(j));
                    if(name.length()>best&&title.contains(name)){index=i;best=name.length();}
                }
            }
        }
        if(index>=0&&(active()==null||clause>=0))stages.getJSONObject(index).put("status","pending");
        data.put("revision",Math.min(100,data.optInt("revision")+1));
    }
    void verificationReason(String reason)throws JSONException {data.put("last_verification_reason",reason);}
    public JSONObject completion(){JSONObject result=data.optJSONObject("completion");try{return result==null?null:new JSONObject(result.toString());}catch(JSONException invalid){throw new IllegalStateException(invalid);}}
    public void completion(JSONObject condition)throws JSONException {
        if(condition==null)return;
        if(!AppTaskCompletion.valid(condition))throw new JSONException("Invalid result condition");
        if(finished()&&completion()==null)throw new JSONException("Completed result is immutable");
        if(completion()!=null&&!AppTaskCompletion.key(completion()).equals(AppTaskCompletion.key(condition)))throw new JSONException("Changed result condition");
        if(completion()==null)setEvidence(condition,"initial_prediction",null);
    }
    private void initializeEvidence()throws JSONException {
        String goalKey=AppTaskObservation.hash(goal());JSONObject contract=data.optJSONObject("goal_contract");
        if(contract==null)data.put("goal_contract",new JSONObject().put("version",1).put("task_id",id()).put("goal_key",goalKey));
        else if(contract.optInt("version")!=1||!id().equals(contract.optString("task_id"))||!goalKey.equals(contract.optString("goal_key")))throw new JSONException("Changed goal contract");
        JSONObject evidence=data.optJSONObject("completion_evidence");
        if(evidence==null){
            evidence=new JSONObject().put("version",completion()==null?0:1).put("status",completion()==null?"missing":finished()?"user_confirmed":"provisional")
                .put("goal_key",goalKey).put("history",new JSONArray());data.put("completion_evidence",evidence);
            if(completion()!=null){evidence.put("requirements",completion());evidenceEvent("legacy_result_migrated",null,null,AppTaskCompletion.key(completion()));}
            if(completion()!=null)evidence.put("candidate_key",AppTaskCompletion.key(completion()));
        }else if(!goalKey.equals(evidence.optString("goal_key"))
                ||completion()!=null&&!AppTaskCompletion.key(completion()).equals(evidence.optString("candidate_key")))throw new JSONException("Changed evidence goal or candidate");
    }
    public int evidenceVersion(){return data.optJSONObject("completion_evidence").optInt("version");}
    public boolean completionSupported(){String status=data.optJSONObject("completion_evidence").optString("status");return (completion()!=null||outcomeGoal()!=null)&&(finished()||"supported".equals(status)||"user_confirmed".equals(status));}
    public JSONObject evidenceState()throws JSONException{return new JSONObject(data.getJSONObject("completion_evidence").toString());}
    private void evidenceEvent(String reason,String observation,String from,String to)throws JSONException {
        JSONObject evidence=data.getJSONObject("completion_evidence");JSONArray history=evidence.getJSONArray("history");
        JSONObject event=new JSONObject().put("version",evidenceVersion()).put("status",evidence.getString("status")).put("reason",reason);
        if(observation!=null)event.put("observation_ref",AppTaskObservation.hash(observation));
        if(from!=null)event.put("from_key",from);if(to!=null)event.put("to_key",to);
        history.put(event);while(history.length()>8)history.remove(0);
    }
    private void setEvidence(JSONObject condition,String reason,String observation)throws JSONException {
        JSONObject old=completion(),evidence=data.getJSONObject("completion_evidence");
        data.put("completion",new JSONObject(condition.toString()));
        if(evidence.optJSONObject("requirements")==null)evidence.put("requirements",new JSONObject(condition.toString()));
        evidence.put("version",evidenceVersion()+1).put("status","provisional").put("candidate_key",AppTaskCompletion.key(condition));
        evidenceEvent(reason,observation,old==null?null:AppTaskCompletion.key(old),AppTaskCompletion.key(condition));
    }
    /** A model can refine only an unsupported prediction using public evidence present now. */
    public boolean offerCompletion(JSONObject condition,JSONObject observed,ScreenSnapshot nativeScreen)throws JSONException {
        if(condition==null)return completionOffer(false,"missing_candidate");
        if(!AppTaskCompletion.valid(condition))return completionOffer(false,"invalid_contract");
        if(AppTaskCompletion.unsafeForGoal(goal(),condition,observed,nativeScreen))return completionOffer(false,"unsafe_for_goal");
        JSONObject old=completion();String key=AppTaskCompletion.key(condition);
        if(old!=null&&AppTaskCompletion.key(old).equals(key))return completionOffer(true,"same_candidate");
        if(finished())return completionOffer(false,"immutable_finished");
        if(completionSupported())return completionOffer(false,"immutable_supported");
        if(evidenceVersion()>=32)return completionOffer(false,"version_limit");
        JSONObject requirements=data.getJSONObject("completion_evidence").optJSONObject("requirements");
        boolean matched=AppTaskCompletion.checkForGoal(goal(),condition,observed,nativeScreen)==AppTaskCompletion.Verdict.MATCHED
            &&AppTaskCompletion.distinctProofTargets(condition,observed);
        if(old!=null||requirements!=null){
            if(!matched||requirements!=null&&!AppTaskCompletion.preservesRequirements(requirements,condition)){
                evidenceEvent("replacement_rejected",observed==null?null:observed.optString("observation_id"),old==null?null:AppTaskCompletion.key(old),key);
                return completionOffer(false,!matched?"replacement_unmatched":"requirements_not_preserved");
            }
        }
        setEvidence(condition,old==null&&requirements==null?"initial_prediction":"observed_prediction_refined",observed==null?null:observed.optString("observation_id"));
        return completionOffer(true,old==null&&requirements==null?"initial_prediction":"observed_prediction_refined");
    }
    /** Called only after the controller checks the same candidate on two fresh native frames. */
    public boolean supportCompletion(int expectedVersion)throws JSONException {
        if(finished()||completion()==null&&outcomeGoal()==null||expectedVersion!=evidenceVersion())return false;
        if(!completionSupported()){
            data.getJSONObject("completion_evidence").put("status","supported");
            evidenceEvent("fresh_native_observations",null,null,outcomeGoal()!=null?outcomeGoal().getString("seal"):AppTaskCompletion.key(completion()));
        }
        return true;
    }
    /** Unsafe predictions may be discarded; supported and user-confirmed results stay immutable. */
    public void discardCompletion()throws JSONException {
        if(finished()||completionSupported()||completion()==null)return;
        String previous=AppTaskCompletion.key(completion());data.remove("completion");
        data.getJSONObject("completion_evidence").put("version",evidenceVersion()+1).put("status","missing").remove("candidate_key");
        JSONObject requirements=data.getJSONObject("completion_evidence").optJSONObject("requirements");
        if(requirements!=null){
            JSONObject corrected=AppTaskCompletion.releaseInvalidNativeDirections(goal(),requirements);
            if(!AppTaskCompletion.key(requirements).equals(AppTaskCompletion.key(corrected))){
                data.getJSONObject("completion_evidence").put("requirements",corrected);
                evidenceEvent("invalid_native_direction_released",null,AppTaskCompletion.key(requirements),AppTaskCompletion.key(corrected));
            }
        }
        evidenceEvent("unsafe_prediction_discarded",null,previous,null);
    }
    public String active(){JSONArray goals=data.optJSONArray("subgoals");for(int i=0;goals!=null&&i<goals.length();i++)if("pending".equals(goals.optJSONObject(i).optString("status")))return goals.optJSONObject(i).optString("id");return null;}
    public void plan(JSONArray incoming)throws JSONException {
        if(incoming==null||incoming.length()==0||incoming.length()>8)return;
        JSONArray existing=data.getJSONArray("subgoals");
        if(existing.length()>0){
            if(existing.length()!=incoming.length())throw new JSONException("Changed task plan");
            for(int i=0;i<existing.length();i++)if(!existing.getJSONObject(i).getString("id").equals(incoming.getJSONObject(i).getString("id"))
                    ||!existing.getJSONObject(i).getString("title").equals(incoming.getJSONObject(i).getString("title")))throw new JSONException("Changed subgoal");
            return; // A model cannot mark any subgoal verified.
        }
        JSONArray clean=new JSONArray();for(int i=0;i<incoming.length();i++)clean.put(new JSONObject().put("id","g"+i).put("title",incoming.getJSONObject(i).getString("title")).put("status","pending"));
        data.put("subgoals",clean);
    }
    public void localPlan()throws JSONException {
        if(data.getJSONArray("subgoals").length()>0)return;
        String[] clauses=goal().split("\\s*(?:그리고|그다음|한 뒤|→|하고)\\s*");JSONArray plan=new JSONArray();
        for(String clause:clauses){String title=clause.trim();if(title.isEmpty())continue;plan.put(new JSONObject().put("title",title.substring(0,Math.min(80,title.length()))));if(plan.length()==8)break;}
        if(plan.length()==0)plan.put(new JSONObject().put("title",goal().substring(0,Math.min(80,goal().length()))));plan(plan);
    }
    public String activeTitle(){String id=active();JSONArray goals=data.optJSONArray("subgoals");for(int i=0;goals!=null&&i<goals.length();i++)if(goals.optJSONObject(i).optString("id").equals(id))return goals.optJSONObject(i).optString("title");return goal();}
    public void record(JSONObject before,JSONObject action,String outcome,boolean completesSubgoal)throws JSONException {
        data.put("action_count",actions()+1);
        JSONObject row=new JSONObject().put("fingerprint",before.getString("fingerprint")).put("tool",action.getString("tool"))
            .put("target",target(action)).put("outcome",outcome);
        JSONObject node=AppTaskObservation.find(before,action.optString("target_id"));
        if("verified".equals(outcome)&&"tap".equals(action.optString("tool"))&&node!=null
                &&"accessibility".equals(node.optString("source"))&&"toggle".equals(node.optString("role"))
                &&node.opt("checked") instanceof Boolean){
            String key=AppTaskObservation.hash(before.getString("package")+"\n"+AppTaskObservation.targetKey(node));
            row.put("switch_before",new JSONObject().put("key",key).put("checked",node.getBoolean("checked")));
        }
        JSONArray attempts=data.getJSONArray("attempts");attempts.put(row);while(attempts.length()>8)attempts.remove(0);
        if("failed".equals(outcome))data.put("revision",data.optInt("revision")+1);
        if("verified".equals(outcome)) {
            if(data.getJSONArray("route").length()<32)data.getJSONArray("route").put(new JSONObject().put("package",before.optString("package")).put("tool",action.getString("tool"))
                .put("resource",node==null?"":node.optString("resource")).put("label",node==null?"":node.optString("label"))
                .put("open_package",action.optString("package")).put("expectation",action.getJSONObject("expectation"))
                .put("advances_subgoal",completesSubgoal&&AppTaskPolicy.provesSubgoal(action)));
            if(completesSubgoal&&AppTaskPolicy.provesSubgoal(action)){String id=active();JSONArray goals=data.getJSONArray("subgoals");for(int i=0;i<goals.length();i++)if(goals.getJSONObject(i).getString("id").equals(id))goals.getJSONObject(i).put("status","verified");}
        }
    }
    /** Append the last observed-but-unverified action to the route once the user confirmed its result screen. */
    public void confirmLastEffect(JSONObject before,JSONObject action,String afterKey)throws JSONException {
        JSONArray route=data.getJSONArray("route");if(route.length()!=actions()-1||route.length()>=32)return;
        JSONObject node=AppTaskObservation.find(before,action.optString("target_id"));
        route.put(new JSONObject().put("package",before.optString("package")).put("tool",action.getString("tool"))
            .put("resource",node==null?"":node.optString("resource")).put("label",node==null?"":node.optString("label"))
            .put("open_package",action.optString("package"))
            .put("expectation",new JSONObject().put("kind","screen_changed").put("screen_key",afterKey))
            .put("advances_subgoal",false).put("effect_evidence","user_confirmed_result"));
    }
    private static String target(JSONObject action){return "open_app".equals(action.optString("tool"))?action.optString("package",""):action.isNull("target_id")?"":action.optString("target_id","");}
    public boolean repeated(JSONObject before,JSONObject action){
        int count=0;
        JSONArray attempts=data.optJSONArray("attempts");
        for(int i=0;attempts!=null&&i<attempts.length();i++){
            JSONObject attempt=attempts.optJSONObject(i);
            if(attempt==null)continue;
            String outcome=attempt.optString("outcome");
            if(("failed".equals(outcome)||"waiting".equals(outcome))
                    &&before.optString("fingerprint").equals(attempt.optString("fingerprint"))
                    &&action.optString("tool").equals(attempt.optString("tool"))
                    &&target(action).equals(attempt.optString("target")))count++;
        }
        return count>=2;
    }
    public boolean finished(){return data.optBoolean("completed");}
    public int actions(){return data.optInt("action_count");}
    public int experienceSequence(){return data.optInt("experience_sequence");}
    public void experienceSequence(int sequence)throws JSONException{data.put("experience_sequence",sequence);}
    public String experienceId(){return data.optString("experience_task_id",id());}
    public JSONArray pathPrefix(){JSONArray prefix=data.optJSONArray("experience_path");return prefix==null?new JSONArray():prefix;}
    public void appendPath(String step)throws JSONException{JSONArray prefix=pathPrefix();if(prefix.length()<64)prefix.put(step);data.put("experience_path",prefix);}
    public void experienceConsent(String consent)throws JSONException {
        if(consent!=null&&!consent.equals(data.optString("experience_consent"))){data.put("experience_consent",consent).put("experience_sequence",0).put("experience_task_id",UUID.randomUUID().toString());}
    }
    public String scope(){return data.optString("route_scope",null);}
    boolean enteredApp(String pkg) {
        if (data.has("entered_app")) return pkg.equals(data.optString("entered_app"));
        String previous = scope();
        return pkg.equals(previous) || previous != null && previous.startsWith(pkg + "|");
    }
    void markAppEntered(String pkg) throws JSONException { data.put("entered_app",pkg); }
    public void scope(String scope)throws JSONException{data.put("route_scope",scope);}
    public void confirmResult()throws JSONException{data.put("completed",true);data.getJSONObject("completion_evidence").put("status","user_confirmed");evidenceEvent("user_confirmed",null,null,completion()==null?null:AppTaskCompletion.key(completion()));}
    public boolean routeDisputed(){return data.optBoolean("route_disputed");}
    public JSONArray route(){return data.optJSONArray("route");}
}
