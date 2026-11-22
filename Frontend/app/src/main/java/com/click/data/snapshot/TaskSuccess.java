package com.click.data.snapshot;

import org.json.JSONObject;

/** Evaluates device evidence against the shared per-goal success contract. */
public final class TaskSuccess {
    private TaskSuccess() {}
    private static boolean number(Object value){return value instanceof Number && Double.isFinite(((Number)value).doubleValue());}
    private static boolean equalsNumber(Object value,int expected){return (value instanceof Integer || value instanceof Long) && ((Number)value).longValue()==expected;}
    private static boolean correlatedChange(JSONObject proof) {
        for(String key:new String[]{"attempt_id","guide_command_id","target_key","event_key","baseline_observation_id","observation_id"})
            if(!(proof.opt(key) instanceof String) || proof.optString(key).isEmpty())return false;
        Object before=proof.opt("baseline_captured_at_ms"),action=proof.opt("action_at_ms"),after=proof.opt("verified_at_ms");
        return number(before) && number(action) && number(after)
            && ((Number)before).doubleValue()<((Number)action).doubleValue() && ((Number)action).doubleValue()<=((Number)after).doubleValue()
            && !proof.optString("baseline_observation_id").equals(proof.optString("observation_id"));
    }
    private static boolean manualNativeChange(JSONObject proof,JSONObject spec) {
        if (!"persisted_value".equals(spec.optString("kind"))
                || !java.util.Set.of("font_size","brightness","volume").contains(spec.optString("setting"))
                || !"manual_native_observation".equals(proof.optString("evidence_source"))
                || !"observed_only".equals(proof.optString("execution_attribution"))) return false;
        for (String key:new String[]{"guide_command_id","target_key","episode_id","baseline_observation_id","observation_id","settled_observation_id"})
            if (!(proof.opt(key) instanceof String) || proof.optString(key).isEmpty()) return false;
        Object before=proof.opt("baseline_captured_at_ms"), observed=proof.opt("observed_at_ms"), settled=proof.opt("verified_at_ms");
        if (!number(before) || !number(observed) || !number(settled)
                || ((Number)before).doubleValue()>=((Number)observed).doubleValue()
                || ((Number)settled).doubleValue()-((Number)observed).doubleValue()<250
                || ((Number)settled).doubleValue()-((Number)observed).doubleValue()>10000
                || new java.util.HashSet<>(java.util.List.of(proof.optString("baseline_observation_id"),
                    proof.optString("observation_id"),proof.optString("settled_observation_id"))).size()!=3) return false;
        Object a=proof.opt("baseline_control_value"), b=proof.opt("control_value");
        Object low=proof.opt("range_min"), high=proof.opt("range_max");
        return number(a) && number(b) && number(low) && number(high)
                && ((Number)low).doubleValue()<((Number)high).doubleValue()
                && ((Number)a).doubleValue()>=((Number)low).doubleValue() && ((Number)a).doubleValue()<=((Number)high).doubleValue()
                && ((Number)b).doubleValue()>=((Number)low).doubleValue() && ((Number)b).doubleValue()<=((Number)high).doubleValue()
                && ("increase".equals(spec.optString("direction"))
                ? ((Number)b).doubleValue()>((Number)a).doubleValue() : ((Number)b).doubleValue()<((Number)a).doubleValue());
    }
    public static boolean verified(JSONObject proof,String goal) {
        JSONObject spec=SettingsCapabilities.successContract(goal);
        if(proof==null || spec==null || !spec.optString("setting").equals(proof.optString("setting"))
                || proof.has("goal_type") && !proof.isNull("goal_type") && !goal.equals(proof.optString("goal_type")))return false;
        String kind=spec.optString("kind"),status=proof.optString("status"),reason=proof.optString("reason");
        if("page_opened".equals(kind))return "OPENED".equals(status) && "settings_page_visible".equals(reason) && proof.opt("observation_id") instanceof String && !proof.optString("observation_id").isEmpty();
        if("user_report".equals(kind) || !("CHANGED".equals(status) || "ALREADY_SET".equals(status)))return false;
        if("CHANGED".equals(status) && !(correlatedChange(proof) || manualNativeChange(proof,spec)))return false;
        if("persisted_value".equals(kind)) {
            Object before=proof.opt("baseline_setting_value"),after=proof.opt("setting_value");
            JSONObject domain=spec.optJSONObject("value_domain");
            if(domain!=null)for(Object value:new Object[]{before,after}) {
                if(!number(value) || domain.optBoolean("integer") && !(value instanceof Integer || value instanceof Long)
                        || ((Number)value).doubleValue()<domain.optDouble("minimum")
                        || ((Number)value).doubleValue()>domain.optDouble("maximum"))return false;
            }
            boolean increase="increase".equals(spec.optString("direction"));
            return "CHANGED".equals(status) && (increase?"target_value_increased":"target_value_decreased").equals(reason)
                && number(before) && number(after) && (increase?((Number)after).doubleValue()>((Number)before).doubleValue():((Number)after).doubleValue()<((Number)before).doubleValue());
        }
        String state=spec.optString("requested_state");boolean expected="on".equals(state);
        if(!state.equals(proof.optString("requested_state")))return false;
        JSONObject choice=spec.optJSONObject("system_choice");
        if(choice!=null && (choice.optString("reason").equals(reason) || "system_mode_already_set".equals(reason))) {
            Object baseline=proof.opt("baseline_setting_value");
            boolean validBaseline=false;
            org.json.JSONArray values=choice.optJSONArray("valid_values");
            if(values!=null)for(int i=0;i<values.length();i++)
                if(equalsNumber(baseline,values.optInt(i)))validBaseline=true;
            int expectedValue=choice.optInt("requested_value",Integer.MIN_VALUE);
            return equalsNumber(proof.opt("setting_value"),expectedValue)
                && ("ALREADY_SET".equals(status) && "system_mode_already_set".equals(reason)
                    || "CHANGED".equals(status) && choice.optString("reason").equals(reason)
                        && validBaseline && !equalsNumber(baseline,expectedValue));
        }
        if("system_choice".equals(kind))return false;
        String wanted="CHANGED".equals(status)?(expected?"target_checked_on":"target_checked_off")
            :("adapter_power".equals(kind)?"wifi_already_set":"native_switch_already_set");
        if(!wanted.equals(reason) || !(proof.opt("after_checked") instanceof Boolean)
                || (Boolean)proof.opt("after_checked")!=expected)return false;
        if("CHANGED".equals(status) && (!(proof.opt("baseline_checked") instanceof Boolean)
                || (Boolean)proof.opt("baseline_checked")==expected))return false;
        return !"adapter_power".equals(kind) || equalsNumber(proof.opt("setting_value"),expected?3:1);
    }
}
