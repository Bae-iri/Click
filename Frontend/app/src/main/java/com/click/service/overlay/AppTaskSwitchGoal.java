package com.click.service.overlay;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Independent native check, using the same single-switch request grammar as the server. */
final class AppTaskSwitchGoal {
    private record Form(Pattern pattern, int target, int verb) {}
    private static final List<Form> FORMS=new ArrayList<>();
    private static final Set<String> ON=new HashSet<>(), GENERIC=new HashSet<>();
    private static final Pattern EXCLUDED;
    static {
        try (InputStream stream=AppTaskSwitchGoal.class.getResourceAsStream("/switch_goal.json")) {
            if(stream==null)throw new IOException("Missing switch goal rules");
            String text=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).lines().collect(Collectors.joining("\n"));
            JSONObject rules=new JSONObject(text);
            JSONArray forms=rules.getJSONArray("forms");
            for(int i=0;i<forms.length();i++){
                JSONObject form=forms.getJSONObject(i);
                FORMS.add(new Form(Pattern.compile(form.getString("pattern")),form.getInt("target"),form.getInt("verb")));
            }
            for(String key:List.of("on","generic_switch_labels")){
                JSONArray values=rules.getJSONArray(key);
                for(int i=0;i<values.length();i++)("on".equals(key)?ON:GENERIC).add(name(values.getString(i)));
            }
            EXCLUDED=Pattern.compile(rules.getString("excluded_target"));
        }catch(IOException|JSONException e){throw new ExceptionInInitializerError(e);}
    }
    static boolean genericLabel(String label){return GENERIC.contains(name(label));}
    private final String name;
    private final boolean checked;
    private AppTaskSwitchGoal(String name,boolean checked){this.name=name;this.checked=checked;}
    private static String name(String value){return value.toLowerCase(Locale.ROOT).replaceAll("\\s+","");}

    static AppTaskSwitchGoal parse(String goal){
        String text=goal==null?"":java.text.Normalizer.normalize(goal,java.text.Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).trim().replaceFirst("[.!?]+$","").trim();
        for(Form form:FORMS){
            Matcher match=form.pattern().matcher(text);
            if(!match.matches())continue;
            String target=match.group(form.target()).trim();
            if(target.isEmpty()||EXCLUDED.matcher(target).find()||GENERIC.contains(name(target)))return null;
            return new AppTaskSwitchGoal(name(target),ON.contains(match.group(form.verb())));
        }
        return null;
    }

    /** The requested state can be bound before arriving at the switch. */
    boolean possible(JSONArray checks){
        boolean genericSwitch=false, heading=false;
        for(int i=0;i<checks.length();i++){
            JSONObject check=checks.optJSONObject(i);
            if(check==null)continue;
            String label=name(check.optString("label")),role=check.optString("role");
            if("toggle".equals(role)&&Boolean.valueOf(checked).equals(check.opt("checked"))){
                if(name.equals(label))return true;
                if(GENERIC.contains(label))genericSwitch=true;
            }else if((role.isEmpty()||"text".equals(role))&&name.equals(label)){
                heading=true;
            }
        }
        return genericSwitch&&heading;
    }

    boolean accepts(JSONArray checks,JSONArray nodes){
        JSONObject direct=null,generic=null,title=null;
        int named=0,switches=0,titles=0;
        for(int i=0;i<nodes.length();i++){
            JSONObject node=nodes.optJSONObject(i);
            if(node==null||!"accessibility".equals(node.optString("source")))continue;
            String label=name(node.optString("label"));
            if("toggle".equals(node.optString("role"))){
                switches++;
                if(name.equals(label)){direct=node;named++;}
                else if(GENERIC.contains(label))generic=node;
            }else if(name.equals(label)&&node.optBoolean("native_heading")&&"text".equals(node.optString("role"))
                    &&!node.optBoolean("clickable")&&!node.optBoolean("editable")&&!node.optBoolean("scrollable")){
                title=node;titles++;
            }
        }
        if(named==1)return covered(checks,direct,checked);
        return named==0&&switches==1&&generic!=null&&titles==1&&covered(checks,title,null)&&covered(checks,generic,checked);
    }

    private static boolean covered(JSONArray checks,JSONObject node,Boolean desired){
        for(int i=0;i<checks.length();i++){
            JSONObject check=checks.optJSONObject(i);
            if(check==null||!node.optString("label").equals(check.optString("label")))continue;
            if(!check.optString("role").isEmpty()&&!node.optString("role").equals(check.optString("role")))continue;
            if(!check.optString("resource").isEmpty()&&!node.optString("resource").equals(check.optString("resource")))continue;
            if(desired==null?check.isNull("checked"):node.opt("checked") instanceof Boolean&&desired.equals(check.opt("checked")))return true;
        }
        return false;
    }
}
