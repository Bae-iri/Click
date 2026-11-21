package com.click.service.overlay;

import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.ScreenSnapshot;
import com.click.service.accessibility.SettingsSliderTarget;
import org.json.*;
import java.util.*;

/** Requested effects are task-scoped. Only the observation used to prove each effect may change. */
final class AppTaskOutcomes {
    private AppTaskOutcomes() {}
    static boolean valid(JSONObject goal) {
        if(goal==null || !goal.optString("seal").matches("[0-9a-f]{64}")) return false;
        JSONArray clauses=goal.optJSONArray("clauses");
        if(clauses==null || clauses.length()==0 || clauses.length()>8) return false;
        for(int i=0;i<clauses.length();i++){
            JSONObject c=clauses.optJSONObject(i);
            if(c==null || !Set.of("page","selected","on","off","increase","decrease","manual").contains(c.optString("kind"))) return false;
            JSONArray names=c.optJSONArray("labels");
            if(names==null||names.length()==0||names.length()>5) return false;
            for(int j=0;j<names.length();j++){
                String name=names.optString(j);
                if(name.isBlank()||!name.equals(AppTaskObservation.label(name))) return false;
            }
            boolean numeric=Set.of("increase","decrease").contains(c.optString("kind"));
            String metric=c.optString("metric");
            boolean validMetric=numeric?Set.of("control","font_size","brightness","volume").contains(metric):"none".equals(metric);
            if(!validMetric||c.optInt("steps",-1)<0
                    || c.optInt("steps")>100 || !numeric&&c.optInt("steps")!=0) return false;
        }
        return true;
    }
    static Set<String> labels(JSONObject goal) {
        Set<String> names=new HashSet<>();
        if(goal!=null) for(int i=0;i<goal.optJSONArray("clauses").length();i++){
            JSONArray values=goal.optJSONArray("clauses").optJSONObject(i).optJSONArray("labels");
            for(int j=0;j<values.length();j++) names.add(values.optString(j));
        }
        return names;
    }
    record Finding(AppTaskCompletion.Verdict verdict, int clause) {}
    /** A requested visit is an event. Preserve it across subpages only after two native observations. */
    static final class Arrivals {
        private record Frame(String id,long time,String pkg,int window) {}
        private final Map<Integer,Frame> first=new HashMap<>();
        boolean pending(){return !first.isEmpty();}
        void reset(){first.clear();}
        boolean observed(int index,AppTaskCompletion.Verdict verdict,JSONObject current,ScreenSnapshot screen,JSONObject receipts)throws JSONException {
            String key=Integer.toString(index);
            if(verdict==AppTaskCompletion.Verdict.MATCHED&&!screen.packageName.equals(receipts.optString(key))){
                Frame prior=first.get(index);
                Frame now=new Frame(current.getString("observation_id"),current.getLong("captured_at_ms"),screen.packageName,screen.activeWindowId);
                if(prior==null||!now.pkg.equals(prior.pkg)||now.window!=prior.window||now.time<prior.time){
                    first.put(index,now);
                }else if(!now.id.equals(prior.id)&&now.time-prior.time>=350){
                    receipts.put(key,now.pkg);first.remove(index);
                }
            }else first.remove(index);
            return verdict==AppTaskCompletion.Verdict.NOT_OBSERVED&&screen.packageName.equals(receipts.optString(key));
        }
    }
    static Finding check(JSONObject goal, JSONObject observed, ScreenSnapshot screen, JSONObject baselines,JSONObject bindings) throws JSONException {
        return check(goal,observed,screen,baselines,bindings,null,null);
    }
    static Finding check(JSONObject goal,JSONObject observed,ScreenSnapshot screen,JSONObject baselines,JSONObject bindings,
                         Arrivals arrivals,JSONObject receipts)throws JSONException {
        if(screen==null||screen.activeWindowId==null||screen.activeWindowId<0||!"public".equals(observed.optString("status"))){
            if(arrivals!=null)arrivals.reset();
            return new Finding("protected".equals(observed.optString("status"))?AppTaskCompletion.Verdict.PROTECTED:AppTaskCompletion.Verdict.UNKNOWN,-1);
        }
        JSONArray clauses=goal.getJSONArray("clauses");
        Finding missing=null,contrary=null;
        boolean liveMatch=false;
        Set<String> used=new HashSet<>();
        for(int i=0;i<clauses.length();i++){
            JSONObject c=clauses.getJSONObject(i);
            String kind=c.getString("kind");
            AppTaskCompletion.Verdict verdict=switch(kind){
                case "increase","decrease" -> numeric(c,screen,baselines,Integer.toString(i));
                case "manual" -> AppTaskCompletion.Verdict.UNKNOWN;
                default -> visible(c,observed,screen,used,bindings==null?null:bindings.optJSONObject("r"+i));
            };
            if(verdict==AppTaskCompletion.Verdict.MATCHED)liveMatch=true;
            if(arrivals!=null&&clauses.length()>1&&kind.equals("page")&&arrivals.observed(i,verdict,observed,screen,receipts))continue;
            if(verdict==AppTaskCompletion.Verdict.NOT_MET&&contrary==null)contrary=new Finding(verdict,i);
            if(verdict!=AppTaskCompletion.Verdict.MATCHED&&missing==null)missing=new Finding(verdict,i);
        }
        if(contrary!=null)return contrary;
        if(missing!=null)return missing;
        return new Finding(liveMatch?AppTaskCompletion.Verdict.MATCHED:AppTaskCompletion.Verdict.NOT_OBSERVED,-1);
    }
    static String normalize(String s){return s==null?"":s.replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
    private static boolean named(JSONObject c,String label){
        String name=normalize(label); JSONArray labels=c.optJSONArray("labels");
        for(int i=0;i<labels.length();i++) if(name.equals(normalize(labels.optString(i)))) return true;
        return false;
    }
    private static AppTaskCompletion.Verdict visible(JSONObject c,JSONObject observed,ScreenSnapshot screen,Set<String> used,JSONObject binding) throws JSONException {
        String kind=c.getString("kind"); JSONArray nodes=observed.getJSONArray("nodes"); JSONObject target=null,generic=null;
        int switches=0,headings=0; boolean contextual=false;
        for(int i=0;i<nodes.length();i++){
            JSONObject n=nodes.getJSONObject(i);
            if(!"accessibility".equals(n.optString("source"))) continue;
            String role=n.optString("role"),label=n.optString("label");
            if("toggle".equals(role)){switches++; if(AppTaskSwitchGoal.genericLabel(label)) generic=n;}
            if(named(c,label)&&"text".equals(role)&&n.optBoolean("native_heading"))headings++;
            boolean bound=binding!=null&&screen.packageName.equals(binding.optString("package"))
                &&label.equals(binding.optString("label"))&&role.equals(binding.optString("role"))
                &&(binding.optString("resource").isEmpty()||n.optString("resource").equals(binding.optString("resource")));
            if(!named(c,label)&&!bound)continue;
            boolean suitable=switch(kind){
                case "page" -> "text".equals(role)&&!n.optBoolean("clickable")&&!n.optBoolean("editable")&&!n.optBoolean("scrollable");
                case "selected" -> "tab".equals(role);
                default -> "toggle".equals(role);
            };
            if(suitable){if(target!=null)return AppTaskCompletion.Verdict.UNKNOWN;target=n;}
        }
        if(target==null&&Set.of("on","off").contains(kind)&&switches==1&&headings==1){target=generic;contextual=true;}
        if(target==null)return AppTaskCompletion.Verdict.NOT_OBSERVED;
        String label=target.optString("label");
        if(!used.add(target.getString("id")))return AppTaskCompletion.Verdict.UNKNOWN;
        JSONObject check=new JSONObject().put("package",screen.packageName).put("label",label).put("resource",target.optString("resource")).put("role",target.getString("role"));
        if("selected".equals(kind))check.put("selected",true);
        if(Set.of("on","off").contains(kind))check.put("checked","on".equals(kind));
        JSONObject proof=new JSONObject().put("checks",new JSONArray().put(check));
        // Keep navigation rows, including passive text inside clickable rows, out of destination proof.
        String requested="page".equals(kind)?label+" 화면을 열어줘":"";
        if(contextual&&!target.has("checked"))return AppTaskCompletion.Verdict.UNKNOWN;
        return AppTaskCompletion.checkForGoal(requested,proof,observed,screen);
    }
    private static AppTaskCompletion.Verdict numeric(JSONObject c,ScreenSnapshot s,JSONObject baselines,String id) throws JSONException {
        if(s.activeWindowId==null||s.activeWindowId<0||!s.privacyMaskingReady)return AppTaskCompletion.Verdict.UNKNOWN;
        String metric=c.getString("metric");
        SettingsAdjustment setting=Arrays.stream(SettingsAdjustment.values()).filter(v->v.setting.equals(metric)).findFirst().orElse(null);
        AccessibilityNodeSnapshot slider=null;
        for(var n:s.nodes){
            if(!n.visibleToUser||n.sensitiveInput||!n.enabled||n.windowId!=s.activeWindowId||!s.packageName.equals(n.packageName)||n.progress==null)continue;
            boolean match=setting!=null?SettingsSliderTarget.matches(s,n,metric):
                "slider".equals(n.role)&&(named(c,n.text)||named(c,n.contentDescription));
            if(match){if(slider!=null)return AppTaskCompletion.Verdict.UNKNOWN;slider=n;}
        }
        if(slider==null)return AppTaskCompletion.Verdict.NOT_OBSERVED;
        Float value=setting==null?slider.progress:setting.value(s);
        if(value==null||!Float.isFinite(value)||slider.rangeMin==null||slider.rangeMax==null
                ||!Float.isFinite(slider.progress)||!Float.isFinite(slider.rangeMin)||!Float.isFinite(slider.rangeMax)
                ||slider.rangeMin>=slider.rangeMax||slider.progress<slider.rangeMin||slider.progress>slider.rangeMax)return AppTaskCompletion.Verdict.UNKNOWN;
        int steps=c.optInt("steps");
        if(steps>0&&!slider.discreteRange)return AppTaskCompletion.Verdict.UNKNOWN;
        String identity=s.packageName+"|"+metric+"|"+(slider.resourceId==null||slider.resourceId.isBlank()?slider.nodeId:slider.resourceId);
        JSONObject b=baselines.optJSONObject(id);
        long time=java.time.Instant.parse(s.capturedAt).toEpochMilli();
        if(b==null){
            baselines.put(id,new JSONObject().put("target",identity).put("min",slider.rangeMin).put("max",slider.rangeMax)
                .put("position",slider.progress).put("value",value).put("at",time));
            return AppTaskCompletion.Verdict.NOT_MET;
        }
        if(!identity.equals(b.optString("target"))||time<=b.getLong("at")||b.getDouble("min")!=slider.rangeMin||b.getDouble("max")!=slider.rangeMax)
            return AppTaskCompletion.Verdict.UNKNOWN;
        int sign="increase".equals(c.getString("kind"))?1:-1;
        double delta=sign*(slider.progress-b.getDouble("position"));
        return (steps==0?delta>0:Math.abs(delta-steps)<0.00001)&&sign*(value-b.getDouble("value"))>0?
            AppTaskCompletion.Verdict.MATCHED:AppTaskCompletion.Verdict.NOT_MET;
    }
}
