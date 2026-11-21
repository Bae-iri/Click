package com.click.service.overlay;

import com.click.data.snapshot.AppTaskObservation;
import com.click.data.snapshot.AccessibilityNodeSnapshot;
import com.click.data.snapshot.ScreenSnapshot;
import org.json.*;
import java.util.*;

/** Goal postconditions are rechecked on the current screen, never inferred from a click or plan bits. */
public final class AppTaskCompletion {
    public enum Verdict { MATCHED, NOT_MET, NOT_OBSERVED, UNKNOWN, PROTECTED }
    private static final Set<String> FIELDS=Set.of("package","label","resource","role","checked","selected");
    private AppTaskCompletion() {}

    public static boolean valid(JSONObject completion) {
        if(completion==null||completion.length()!=1)return false;
        JSONArray checks=completion.optJSONArray("checks");
        if(checks==null||checks.length()<1||checks.length()>8)return false;
        String packageName=null;Set<String> unique=new HashSet<>();
        for(int i=0;i<checks.length();i++){
            JSONObject c=checks.optJSONObject(i);if(c==null)return false;
            for(Iterator<String> k=c.keys();k.hasNext();)if(!FIELDS.contains(k.next()))return false;
            for(String f:List.of("package","label","resource","role"))if(c.has(f)&&!(c.opt(f) instanceof String))return false;
            if(!c.optString("package").matches("[a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+")
                    ||c.optString("package").length()>200||c.optString("resource").length()>300)return false;
            if(packageName!=null&&!packageName.equals(c.optString("package")))return false;
            packageName=c.optString("package");
            String label=c.optString("label"),role=c.optString("role");
            if(label.isEmpty()&&c.optString("resource").isEmpty()||!label.equals(AppTaskObservation.label(label))
                    ||!Set.of("","button","text_field","list","tab","toggle","text","image","unknown").contains(role))return false;
            if(!c.isNull("checked")&&(!(c.opt("checked") instanceof Boolean)||!"toggle".equals(role)))return false;
            if(!c.isNull("selected")&&(!(c.opt("selected") instanceof Boolean)||!Set.of("tab","toggle","button").contains(role)))return false;
            if(!unique.add(checkKey(c)))return false;
        }
        return true;
    }

    public static Set<String> labels(JSONObject completion) {
        Set<String> labels=new HashSet<>();
        if(valid(completion)){JSONArray checks=completion.optJSONArray("checks");for(int i=0;i<checks.length();i++){
            String label=checks.optJSONObject(i).optString("label");if(!label.isEmpty())labels.add(label);
        }}
        return labels;
    }

    public static String key(JSONObject completion) {
        List<String> checks=new ArrayList<>();JSONArray all=completion.optJSONArray("checks");
        for(int i=0;all!=null&&i<all.length();i++){
            JSONObject c=all.optJSONObject(i);StringBuilder key=new StringBuilder();
            for(String f:List.of("package","label","resource","role","checked","selected"))key.append(c.isNull(f)?"":c.optString(f)).append('\n');
            checks.add(key.toString());
        }
        Collections.sort(checks);return AppTaskObservation.hash(String.join("\n",checks));
    }

    static boolean sameCheck(JSONObject first,JSONObject second){return second!=null&&checkKey(first).equals(checkKey(second));}

    private static String checkKey(JSONObject check){
        StringBuilder key=new StringBuilder();
        for(String field:List.of("package","label","resource","role","checked","selected"))
            key.append(check.isNull(field)?"":check.optString(field)).append('\n');
        return key.toString();
    }

    /** Refining an unseen selector cannot drop clauses or reverse a desired native state. */
    public static boolean preservesRequirements(JSONObject previous,JSONObject candidate){
        if(!valid(previous)||!valid(candidate))return false;
        JSONArray old=previous.optJSONArray("checks"),next=candidate.optJSONArray("checks");
        if(next.length()<old.length()||!old.optJSONObject(0).optString("package").equals(next.optJSONObject(0).optString("package")))return false;
        Set<Integer> used=new HashSet<>();
        for(int i=0;i<old.length();i++){
            JSONObject requirement=old.optJSONObject(i);
            if(requirement.isNull("checked")&&requirement.isNull("selected"))continue;
            boolean retained=false;
            for(int j=0;j<next.length();j++){
                if(used.contains(j))continue;
                JSONObject check=next.optJSONObject(j);
                if(!requirement.optString("role").equals(check.optString("role")))continue;
                if(!requirement.optString("resource").isEmpty()){
                    if(!requirement.optString("resource").equals(check.optString("resource")))continue;
                }else if(!requirement.optString("label").equals(check.optString("label")))continue;
                boolean same=true;
                for(String state:List.of("checked","selected"))if(!requirement.isNull(state)&&!requirement.opt(state).equals(check.opt(state)))same=false;
                if(same){used.add(j);retained=true;break;}
            }
            if(!retained)return false;
        }
        return true;
    }

    /** A prediction's known wrong direction is not part of the immutable user goal. */
    public static JSONObject releaseInvalidNativeDirections(String goal,JSONObject requirements)throws JSONException {
        JSONObject copy=new JSONObject(requirements.toString());JSONArray checks=copy.getJSONArray("checks");
        if(!deselectionGoal(goal))for(int i=0;i<checks.length();i++){
            JSONObject check=checks.getJSONObject(i);
            if(Boolean.FALSE.equals(check.opt("selected")))check.remove("selected");
        }
        return copy;
    }

    /** Different selector spellings for the same native fact cannot manufacture extra clauses. */
    public static boolean distinctProofTargets(JSONObject completion,JSONObject screen){
        if(!valid(completion)||screen==null)return false;
        Set<String> used=new HashSet<>();JSONArray checks=completion.optJSONArray("checks"),nodes=screen.optJSONArray("nodes");
        for(int i=0;i<checks.length();i++){
            JSONObject check=checks.optJSONObject(i),found=null;
            for(int j=0;nodes!=null&&j<nodes.length();j++){
                JSONObject node=nodes.optJSONObject(j);if(node==null||!"accessibility".equals(node.optString("source")))continue;
                boolean matches=true;
                for(String field:List.of("label","resource","role"))if(!check.optString(field).isEmpty()&&!check.optString(field).equals(node.optString(field)))matches=false;
                if(matches){if(found!=null)return false;found=node;}
            }
            if(found==null||!used.add(found.optString("id")))return false;
        }
        return true;
    }

    public static Verdict check(JSONObject completion,JSONObject screen) {
        if(!valid(completion)||screen==null)return Verdict.UNKNOWN;
        if("protected".equals(screen.optString("status")))return Verdict.PROTECTED;
        if(!"public".equals(screen.optString("status")))return Verdict.UNKNOWN;
        JSONArray checks=completion.optJSONArray("checks"),nodes=screen.optJSONArray("nodes");
        if(nodes==null)return Verdict.UNKNOWN;
        Verdict pending=Verdict.MATCHED;
        for(int i=0;i<checks.length();i++){
            JSONObject c=checks.optJSONObject(i),found=null;boolean ambiguous=false;
            if(!c.optString("package").equals(screen.optString("package")))return Verdict.NOT_OBSERVED;
            for(int j=0;j<nodes.length();j++){
                JSONObject n=nodes.optJSONObject(j);
                if(n==null||!"accessibility".equals(n.optString("source")))continue;
                boolean matches=true;for(String key:List.of("label","resource","role"))
                    if(!c.optString(key).isEmpty()&&!c.optString(key).equals(n.optString(key)))matches=false;
                if(!matches)continue;
                if(found!=null){ambiguous=true;break;}
                found=n;
            }
            if(ambiguous){pending=Verdict.UNKNOWN;continue;}
            if(found==null){if(pending==Verdict.MATCHED)pending=Verdict.NOT_OBSERVED;continue;}
            for(String state:List.of("checked","selected"))if(!c.isNull(state)){
                if(!(found.opt(state) instanceof Boolean)){pending=Verdict.UNKNOWN;continue;}
                if(!c.opt(state).equals(found.opt(state)))return Verdict.NOT_MET;
            }
        }
        return pending;
    }

    /**
     * Action expectations can describe a button becoming visible. A goal result
     * needs destination evidence or a desired native state, not that same button.
     * This gate is local: a model response cannot opt out of it.
     */
    public static Verdict checkForGoal(String goal,JSONObject completion,JSONObject screen,ScreenSnapshot nativeScreen) {
        Verdict verdict=check(completion,screen);
        return verdict==Verdict.MATCHED&&(unsafeForGoal(goal,completion,screen,nativeScreen)||!distinctProofTargets(completion,screen))?Verdict.UNKNOWN:verdict;
    }

    /** Only reject known unsafe evidence; an unseen future destination can bind. */
    private static final java.util.regex.Pattern GOAL_CONTEXT=java.util.regex.Pattern.compile("^(?:\\S+\\s+)?\\S+에서\\s+");
    private static final java.util.regex.Pattern GOAL_REQUEST=java.util.regex.Pattern.compile(
        "\\s*(?:을|를|으로|로|에|이|가)?\\s*(?:열어|보여|이동|들어가|가\\s*줘|띄워|켜|선택|확인|찾아|진입|접속)\\S*\\s*$");
    private static final java.util.regex.Pattern GOAL_SUFFIX=java.util.regex.Pattern.compile(
        "\\s*(?:화면|메뉴|탭|페이지|설정|목록|창|screen|page|tab|menu|settings|section)$",java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern GOAL_ENGLISH=java.util.regex.Pattern.compile(
        "^(?:please\\s+)?(?:open|go\\s+to|show(?:\\s+me)?|navigate\\s+to|switch\\s+to|bring\\s+up|take\\s+me\\s+to)\\s+(?:the\\s+)?(.+?)"
        +"(?:\\s+(?:in|on|of)\\s+(?:the\\s+)?[\\w&.\\- ]{1,40}?(?:\\s+app)?)?[.!?]*$",java.util.regex.Pattern.CASE_INSENSITIVE);
    private static String compact(String text){return text==null?"":text.replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
    /** The goal's destination phrase and its suffix-stripped forms; same rule as the server's triage_targets. */
    public static Set<String> destinationForms(String goal){
        String text=goal==null?"":goal.trim().replaceAll("\\s+"," "),stripped;
        java.util.regex.Matcher english=GOAL_ENGLISH.matcher(text);
        if(english.matches())stripped=english.group(1).trim();
        else{text=GOAL_CONTEXT.matcher(text).replaceFirst("");stripped=GOAL_REQUEST.matcher(text).replaceFirst("");if(stripped.equals(text))return Collections.emptySet();}
        Set<String> forms=new LinkedHashSet<>();String form=stripped;
        while(!form.isEmpty()){forms.add(compact(form));String shorter=GOAL_SUFFIX.matcher(form).replaceFirst("").trim();if(shorter.equals(form))break;form=shorter;}
        return forms;
    }
    /**
     * An actionable control named exactly like the goal's destination is the way there, so this
     * screen is before the destination ("앱 알림" row on the "알림" page). Toggles and the selected
     * tab act in place and are not entries elsewhere.
     */
    private static final Set<String> GOAL_GENERIC=new HashSet<>(Arrays.asList("설정","화면","메뉴","앱","홈","탭","더보기","검색","목록","settings","menu","home","search"));
    /**
     * A result condition derived from the goal itself when no planner supplied one: exactly one
     * exported page title (app-bar text) or selected native tab whose label IS the goal's destination
     * phrase. It is only a candidate; the controller still needs two fresh native matches and the
     * user's confirmation. Device logs (galaxy-completion): 309/343 goal checks had no candidate.
     */
    public static JSONObject goalDerivedCompletion(String goal,JSONObject screen,String appLabel){
        try{
            if(screen==null||!"public".equals(screen.optString("status"))||goalEntryVisible(goal,screen))return null;
            Set<String> forms=destinationForms(goal);JSONArray nodes=screen.optJSONArray("nodes");
            if(forms.isEmpty()||nodes==null)return null;
            JSONObject found=null;
            for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n==null||!"accessibility".equals(n.optString("source","accessibility")))continue;
                String label=compact(n.optString("label"));
                if(label.length()<2||GOAL_GENERIC.contains(label)||label.equals(compact(appLabel))||!forms.contains(label))continue;
                boolean title="text".equals(n.optString("role"))&&!n.optBoolean("clickable")&&!n.optBoolean("editable")&&!n.optBoolean("scrollable");
                boolean tab="tab".equals(n.optString("role"))&&n.optBoolean("selected");
                if(!title&&!tab)continue;
                JSONObject check=new JSONObject().put("package",screen.optString("package")).put("label",n.optString("label")).put("role",tab?"tab":"text");
                if(tab)check.put("selected",true);
                if(found!=null&&!found.toString().equals(check.toString()))return null; // Two destinations: leave it to the planner.
                found=check;
            }
            JSONObject completion=found==null?null:new JSONObject().put("checks",new JSONArray().put(found));
            return completion!=null&&valid(completion)?completion:null;
        }catch(JSONException invalid){return null;}
    }
    public static boolean goalEntryVisible(String goal,JSONObject screen){
        Set<String> forms=destinationForms(goal);JSONArray nodes=screen==null?null:screen.optJSONArray("nodes");
        if(forms.isEmpty()||nodes==null)return false;
        for(int i=0;i<nodes.length();i++){JSONObject n=nodes.optJSONObject(i);if(n==null||!"accessibility".equals(n.optString("source","accessibility")))continue;
            String label=compact(n.optString("label"));
            if(n.optBoolean("clickable")&&!n.optBoolean("selected")&&!"toggle".equals(n.optString("role"))&&label.length()>=2&&forms.contains(label))return true;}
        return false;
    }
    public static boolean unsafeForGoal(String goal,JSONObject completion,JSONObject screen,ScreenSnapshot nativeScreen) {
        if(!valid(completion)||screen==null||!"public".equals(screen.optString("status")))return false;
        JSONArray checks=completion.optJSONArray("checks"),nodes=screen.optJSONArray("nodes");
        if(nodes==null)return false;
        AppTaskSwitchGoal requested=AppTaskSwitchGoal.parse(goal);
        if(requested!=null&&(!requested.possible(checks)
                ||check(completion,screen)==Verdict.MATCHED&&!requested.accepts(checks,nodes)))return true;
        boolean beforeDestination=goalEntryVisible(goal,screen);
        for(int i=0;i<checks.length();i++){
            JSONObject c=checks.optJSONObject(i);
            if(!c.optString("package").equals(screen.optString("package")))continue;
            // Plain page evidence cannot prove the destination while its own entry is still on screen.
            if(beforeDestination&&c.isNull("checked")&&c.isNull("selected"))return true;
            // A false selected state needs an explicit request to deselect/close.
            // Otherwise an untouched tab could "prove" even a paraphrased open goal.
            if(Boolean.FALSE.equals(c.opt("selected"))&&!deselectionGoal(goal))return true;
            if(!c.isNull("checked")||!c.isNull("selected"))continue;
            String role=c.optString("role");
            if(Set.of("button","tab","toggle","list").contains(role))return true;
            if("text_field".equals(role)&&!searchOpenGoal(goal))return true;
            boolean found=false,safe=false;
            for(int j=0;j<nodes.length();j++){
                JSONObject n=nodes.optJSONObject(j);
                if(n==null||!"accessibility".equals(n.optString("source")))continue;
                boolean matches=true;for(String key:List.of("label","resource","role"))
                    if(!c.optString(key).isEmpty()&&!c.optString(key).equals(n.optString(key)))matches=false;
                if(!matches)continue;
                found=true;
                if("text_field".equals(n.optString("role"))||n.optBoolean("editable")){
                    if(searchOpenGoal(goal))safe=true;
                }else if("text".equals(n.optString("role"))&&!n.optBoolean("clickable")&&!n.optBoolean("scrollable")
                        &&!actionableAncestor(n.optString("id"),nativeScreen))safe=true;
            }
            if(found&&!safe)return true;
        }
        return false;
    }

    private static boolean navigationGoal(String goal){return normalizedGoal(goal).matches(".*(?:열|이동|진입|들어|접속|화면|보여|보기|띄워|켜|확인|open|show|view|navigate|go to|switch to|bring up|launch).*");}
    private static boolean deselectionGoal(String goal){return normalizedGoal(goal).matches(".*(?:해제|취소|끄|꺼|닫|접어|접기|unselect|deselect|disable|turn off|close|collapse).*");}
    private static boolean searchOpenGoal(String goal){
        String value=normalizedGoal(goal);
        return navigationGoal(value)&&value.matches(".*(?:검색|search).*")
            &&!value.matches(".*(?:입력|검색어|결과|찾아|그리고|다음|하고|열고|열어서|한 뒤|type|enter|query|results|then|and ).*");
    }
    private static String normalizedGoal(String goal){return goal==null?"":goal.toLowerCase(Locale.ROOT);}
    private static boolean actionableAncestor(String id,ScreenSnapshot screen){
        if(screen==null)return false;
        Map<String,AccessibilityNodeSnapshot> byId=new HashMap<>();for(var n:screen.nodes)byId.put(n.nodeId,n);
        AccessibilityNodeSnapshot current=byId.get(id);Set<String> seen=new HashSet<>();
        // A native page heading may be under a broad clickable layout, such as
        // a background listener that dismisses the keyboard. It is still a heading.
        if(current!=null&&current.heading)return false;
        while(current!=null&&current.parentId!=null&&seen.add(current.nodeId)){
            current=byId.get(current.parentId);
            if(current!=null&&(current.clickable||AppTaskObservation.searchField(current)))return true;
        }
        return false;
    }
}
