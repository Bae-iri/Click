package com.click.data.snapshot;

import org.json.*;
import java.text.Normalizer;
import java.util.*;

/** Exports only public semantic login labels. Credentials and identities stay local. */
public final class Gov24LoginPrivacy {
    private final boolean civil;
    private final boolean diagnostics;
    public Gov24LoginPrivacy(){this(false);}
    public Gov24LoginPrivacy(boolean civil){this(civil,false);}
    public Gov24LoginPrivacy(boolean civil,boolean diagnostics){this.civil=civil;this.diagnostics=diagnostics;}
    public static final String PACKAGE="kr.go.minwon.m";
    private static final JSONObject POLICY;
    static {try {POLICY=new JSONObject(com.click.generated.Gov24LoginPolicyData.json());}
        catch(JSONException error){throw new ExceptionInInitializerError(error);}}
    public static String normalize(String value) {
        return Normalizer.normalize(value==null?"":value,Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT).replaceAll("[^가-힣a-z0-9]","");
    }
    private static boolean exact(JSONArray list,String label) {
        for(int i=0;list!=null&&i<list.length();i++)if(normalize(list.optString(i)).equals(normalize(label)))return true;
        return false;
    }
    public static String option(String label) {
        JSONObject specs=POLICY.optJSONObject("options");
        for(var keys=specs.keys();keys.hasNext();) {
            String key=keys.next();if(exact(specs.optJSONObject(key).optJSONArray("aliases"),label))return key;
        }
        return null;
    }
    public static boolean supportsCivilGoal(String goalType) {
        return goalType!=null && POLICY.optJSONObject("civil_navigation").optJSONObject("services").has(goalType);
    }
    public static boolean isPublicSearchHint(String hint) {
        return exact(POLICY.optJSONObject("civil_navigation").optJSONArray("search_hints"),hint);
    }
    public static boolean knownBaselineGraph(String value) {
        JSONObject services=POLICY.optJSONObject("civil_navigation").optJSONObject("services");
        for(var keys=services.keys();keys.hasNext();)if(services.optJSONObject(keys.next()).optString("graph_id").equals(value))return true;
        return false;
    }
    public static String goalForBaseline(String value) {
        JSONObject services=POLICY.optJSONObject("civil_navigation").optJSONObject("services");
        for(var keys=services.keys();keys.hasNext();) {
            String goal=keys.next();if(services.optJSONObject(goal).optString("graph_id").equals(value))return goal;
        }
        return null;
    }
    private boolean publicHome(ScreenSnapshot screen) {
        boolean brand=false,services=false;
        for(var node:screen.nodes) {
            if(!node.visibleToUser||!PACKAGE.equals(node.packageName))continue;
            for(String value:new String[]{node.text,node.contentDescription}) {
                if("정부24".equals(normalize(value)))brand=true;
                if("자주찾는서비스".equals(normalize(value))||"민원서비스".equals(normalize(value)))services=true;
            }
        }
        return brand&&services;
    }
    private static String label(AccessibilityNodeSnapshot node) {
        return node.text==null||node.text.isBlank()?node.contentDescription:node.text;
    }
    private boolean publicSearchContext(ScreenSnapshot screen) {
        boolean anchor=publicHome(screen);int inputs=0;
        for(var node:screen.nodes) {
            if(!node.visibleToUser)continue;
            if(node.sensitiveInput) {
                if(!node.publicSearchInput()||++inputs>1)return false;
                continue;
            }
            String login=option(label(node));
            if(login!=null&&(login.startsWith("method_")||login.startsWith("provider_")))return false;
            String navigation=civilOption(label(node),true),marker=civilOption(label(node),false);
            anchor|=navigation!=null||marker!=null;
        }
        return anchor;
    }
    private JSONObject specs() throws JSONException {
        JSONObject result=new JSONObject(POLICY.getJSONObject("options").toString());
        if(civil) {
            JSONObject navigation=POLICY.getJSONObject("civil_navigation").getJSONObject("options");
            for(var keys=navigation.keys();keys.hasNext();) {String key=keys.next();result.put(key,navigation.get(key));}
        }
        return result;
    }
    private JSONObject specification(String key) {
        JSONObject spec=POLICY.optJSONObject("options").optJSONObject(key);
        return spec!=null?spec:POLICY.optJSONObject("civil_navigation").optJSONObject("options").optJSONObject(key);
    }
    private String civilOption(String label,boolean clickable) {
        JSONObject specs=POLICY.optJSONObject("civil_navigation").optJSONObject("options");
        for(var keys=specs.keys();keys.hasNext();) {
            String key=keys.next();JSONObject spec=specs.optJSONObject(key);
            if(clickable==!"marker".equals(spec.optString("group"))&&exact(spec.optJSONArray("aliases"),label))return key;
        }
        return null;
    }
    private JSONObject reason(JSONObject base,String code) throws JSONException {
        if(diagnostics)base.put("privacy_reason",code);
        return base;
    }
    private JSONObject blocked(JSONObject base,String status,String reason,String detail) throws JSONException {
        base.put("status",status).put("nodes",new JSONArray());
        if(reason!=null)base.put("handoff_reason",reason);
        return reason(base,detail);
    }
    public JSONObject filter(ScreenSnapshot screen) throws Exception {
        JSONObject base=new JSONObject().put("status","unrecognized").put("nodes",new JSONArray())
            .put("observation_id",screen==null?UUID.randomUUID().toString():screen.observationId)
            .put("captured_at_ms",screen==null?System.currentTimeMillis():java.time.Instant.parse(screen.capturedAt).toEpochMilli())
            .put("width",screen==null?1:screen.screenWidth).put("height",screen==null?1:screen.screenHeight);
        if(screen==null)return reason(base,"snapshot_missing");
        if(!PACKAGE.equals(screen.packageName))return exact(POLICY.optJSONArray("auth_packages"),screen.packageName)
            ?blocked(base,"handoff","external_auth","external_auth_app"):blocked(base,"outside_app",null,"outside_gov24_app");
        base.put("package_name",PACKAGE);
        boolean publicHome=publicHome(screen);
        boolean publicSearch=publicSearchContext(screen);
        Map<String,AccessibilityNodeSnapshot> byId=new HashMap<>();
        for(var node:screen.nodes)byId.put(node.nodeId,node);
        Map<String,Map<String,JSONObject>> options=new LinkedHashMap<>();
        JSONObject specs=specs();
        Map<String,AccessibilityNodeSnapshot> logouts=new LinkedHashMap<>();
        int visible=0;
        for(AccessibilityNodeSnapshot n:screen.nodes) {
            if(!n.visibleToUser)continue;
            visible++;
            if(!PACKAGE.equals(n.packageName))return blocked(base,"sensitive",null,"foreign_package_node");
            if(screen.activeWindowId!=null&&n.windowId!=screen.activeWindowId)return blocked(base,"sensitive",null,"inactive_window_node");
            if(n.sensitiveInput||n.passwordInput()) {
                if(publicSearch&&n.publicSearchInput())continue; // Query values stay local even when filled.
                return blocked(base,"handoff","credential_input",n.passwordInput()?"password_field":n.emptySearchInput()?"empty_search_field":"sensitive_input_field");
            }
            String label=label(n);
            for(String value:new String[]{n.text,n.contentDescription}) {
                if(value!=null&&exact(POLICY.optJSONArray("consent_labels"),value))return blocked(base,"handoff","consent_required","consent_prompt");
                if(value!=null&&exact(POLICY.optJSONArray("handoff_labels"),value))return blocked(base,"handoff","certificate_selection",
                    Set.of("이름","성명","생년월일","주민등록번호","휴대폰번호","인증번호","본인정보입력","개인정보입력").contains(normalize(value))?"private_identity_label":"certificate_prompt");
            }
            if(publicHome&&exact(POLICY.getJSONObject("home_authentication").optJSONArray("logout_aliases"),label)) {
                var logout=clickOwner(n,"auth_logout",screen,byId);
                if(logout!=null&&onScreen(logout,screen))logouts.put(logout.nodeId,logout);
            }
            String key=civil?civilOption(label,true):option(label);
            if(key==null&&civil)key=option(label);
            if(key==null&&civil&&(!n.clickable||publicHome&&"정부24".equals(normalize(label))))key=civilOption(label,false);
            if(key==null||!n.enabled||n.checkable||n.scrollable)continue;
            boolean marker=civil&&"marker".equals(specs.getJSONObject(key).optString("group"));
            AccessibilityNodeSnapshot target=marker?n:clickOwner(n,key,screen,byId);
            if(civil&&!marker&&target==null&&!n.clickable) {
                key=civilOption(label,false);
                marker=key!=null;
                target=marker?n:null;
            }
            if(target==null)continue;
            int[] b=target.bounds;
            if(b==null||b.length!=4||b[0]<0||b[1]<0||b[2]>screen.screenWidth||b[3]>screen.screenHeight||b[0]>=b[2]||b[1]>=b[3])continue;
            JSONObject node=new JSONObject().put("node_id",target.nodeId).put("option_id",key)
                .put("resource_id","").put("label",specs.getJSONObject(key).getString("label"))
                .put("bounds",new JSONArray(b)).put("clickable",!marker).put("scrollable",false);
            options.computeIfAbsent(key,k->new LinkedHashMap<>()).put(target.nodeId,node);
        }
        // A modal provider grid takes precedence over the underlying login page.
        boolean provider=options.keySet().stream().anyMatch(k->k.startsWith("provider_"));
        boolean choices=options.keySet().stream().anyMatch(k->k.startsWith("provider_")||k.startsWith("method_"));
        Map<String,JSONObject> logins=options.get("entry_login");
        String auth="unknown";
        if(publicHome&&!choices) {
            if(!logouts.isEmpty()&&logins!=null&&!logins.isEmpty())return reason(base,"ambiguous_public_options");
            if(logouts.size()==1&&(logins==null||logins.isEmpty()))auth="signed_in";
            else if(logouts.isEmpty()&&logins!=null&&logins.size()==1)auth="signed_out";
        }
        JSONArray exported=new JSONArray();
        for(var entry:options.entrySet())if(entry.getValue().size()==1&&(!provider||entry.getKey().startsWith("provider_")))exported.put(entry.getValue().values().iterator().next());
        if(exported.length()>POLICY.getInt("max_nodes"))return reason(base,"public_options_limit");
        if(exported.length()==0&&"unknown".equals(auth))return reason(base,screen.nodes.isEmpty()?"empty_accessibility_tree":visible==0?"no_visible_nodes":
            options.isEmpty()?"no_public_options":"ambiguous_public_options");
        return base.put("status","public").put("nodes",exported).put("auth_state",auth);
    }

    private boolean onScreen(AccessibilityNodeSnapshot node,ScreenSnapshot screen) {
        int[] b=node.bounds;
        return node.visibleToUser&&node.enabled&&b!=null&&b.length==4&&b[0]>=0&&b[1]>=0&&b[2]<=screen.screenWidth
            &&b[3]<=screen.screenHeight&&b[0]<b[2]&&b[1]<b[3];
    }

    /** A virtual text child may label a native button. Never promote a whole list or mixed row. */
    private AccessibilityNodeSnapshot clickOwner(AccessibilityNodeSnapshot label,String key,ScreenSnapshot screen,
            Map<String,AccessibilityNodeSnapshot> byId) {
        AccessibilityNodeSnapshot target=label;
        Set<String> seen=new HashSet<>();
        while(target!=null && seen.add(target.nodeId)) {
            if(!target.visibleToUser||!target.enabled||target.sensitiveInput||target.checkable||target.scrollable
                    ||!PACKAGE.equals(target.packageName)||target.windowId!=label.windowId)return null;
            if(target.clickable)break;
            target=byId.get(target.parentId);
        }
        if(target==null||!target.clickable)return null;
        if(target==label)return target;
        int[] outer=target.bounds,inner=label.bounds;
        if(outer==null||inner==null||outer.length!=4||inner.length!=4
                ||inner[0]<outer[0]||inner[1]<outer[1]||inner[2]>outer[2]||inner[3]>outer[3])return null;
        for(var child:screen.nodes) {
            if(!child.visibleToUser)continue;
            seen.clear();AccessibilityNodeSnapshot ancestor=child;
            while(ancestor!=null&&seen.add(ancestor.nodeId)&&!target.nodeId.equals(ancestor.nodeId))ancestor=byId.get(ancestor.parentId);
            if(ancestor==null||!target.nodeId.equals(ancestor.nodeId))continue;
            if(child.sensitiveInput||child.checkable||child.scrollable)return null;
            for(String text:new String[]{child.text,child.contentDescription}) {
                if(text==null||text.isBlank())continue;
                JSONArray aliases="auth_logout".equals(key)?POLICY.optJSONObject("home_authentication").optJSONArray("logout_aliases")
                    :specification(key).optJSONArray("aliases");
                if(!exact(aliases,text))return null;
            }
        }
        return target;
    }
}
