package com.click.service.overlay;

import com.click.data.snapshot.*;
import org.json.*;
import java.util.Set;
import java.util.regex.Pattern;

/** Per-step approval never authorizes a stale or sensitive target. */
public final class AppTaskPolicy {
    private static final Pattern BOUNDARY=Pattern.compile("결제|송금|이체|구매|주문확정|제출|탈퇴|삭제|로그인|인증|비밀번호|보내기|동의|허용|승인|password|payment|checkout|purchase|transfer|delete|submit|send|login|sign.?in|accept|allow",Pattern.CASE_INSENSITIVE);
    public static boolean manualTapAllowed(JSONObject before,JSONObject action){
        JSONObject n=before==null?null:AppTaskObservation.find(before,action.optString("target_id"));
        return n!=null&&"public".equals(before.optString("status"))&&n.optBoolean("clickable")&&!n.optBoolean("editable")
            &&!n.optString("label").isEmpty()&&!BOUNDARY.matcher(n.optString("label")+" "+n.optString("resource")).find();
    }
    public static boolean allowed(JSONObject before,JSONObject current,JSONObject action,String selectedPackage){
        if(before==null||current==null||action==null||!validExpectation(action))return false;
        String tool=action.optString("tool");
        if("open_app".equals(tool))return selectedPackage!=null&&!selectedPackage.isEmpty()&&selectedPackage.equals(action.optString("package"));
        if(!before.optString("fingerprint").equals(current.optString("fingerprint")))return false;
        if(!"public".equals(before.optString("status")))return false;
        if(!before.optString("package").equals(action.optString("package",before.optString("package"))))return false;
        if("back".equals(tool))return true;
        JSONObject node=AppTaskObservation.find(before,action.optString("target_id"));
        if(node==null||"ocr".equals(node.optString("source"))||BOUNDARY.matcher(node.optString("label")+" "+node.optString("resource")).find())return false;
        if(node.optJSONArray("bounds")==null||!node.optJSONArray("bounds").toString().equals(String.valueOf(action.optJSONArray("bounds"))))return false;
        return "tap".equals(tool)&&node.optBoolean("clickable")&&!node.optBoolean("editable")&&!node.optString("label").isEmpty()||"set_text".equals(tool)&&node.optBoolean("editable")
            ||("scroll_forward".equals(tool)||"scroll_backward".equals(tool))&&node.optBoolean("scrollable");
    }
    public static boolean validExpectation(JSONObject action){
        if(action==null)return false;
        JSONObject e=action.optJSONObject("expectation");if(e==null)return false;String tool=action.optString("tool"),kind=e.optString("kind");
        if(!"selection_changed".equals(kind)&&(!e.isNull("checked")||!e.isNull("selected")))return false;
        if("open_app".equals(tool))return "package_opened".equals(kind)&&!action.optString("package").isEmpty()&&action.optString("package").equals(e.optString("package"));
        if("set_text".equals(tool))return "input_present".equals(kind);
        if("selection_changed".equals(kind))return "tap".equals(tool)
            &&(e.isNull("checked")||e.opt("checked") instanceof Boolean)&&(e.isNull("selected")||e.opt("selected") instanceof Boolean);
        return "screen_changed".equals(kind)||"target_visible".equals(kind)&&!e.optString("label").isEmpty();
    }
    /** A generic transition or list movement is progress, not a subgoal's requested result. */
    public static boolean provesSubgoal(JSONObject action){
        if(!validExpectation(action)||!action.optBoolean("advances_subgoal")||action.optString("tool").startsWith("scroll_"))return false;
        JSONObject e=action.optJSONObject("expectation");String kind=e.optString("kind");
        return Set.of("target_visible","input_present","package_opened").contains(kind)
            ||"selection_changed".equals(kind)&&(!e.isNull("checked")||!e.isNull("selected"));
    }
}
